"""mitmproxy addon that handles only local start and the audited callback."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
import html
import json
import logging
import re
import secrets
import time
from typing import Any, Callable
from urllib.parse import parse_qs, urlsplit

from .adapter import (
    MAX_SYNC_DEADLINE_SECONDS,
    SYNC_DEADLINE_MARGIN_SECONDS,
    AdapterError,
    CallbackParameters,
    MaimaiPyAdapter,
    SyncProgress,
)
from .backend import BackendClient, BackendError
from .config import Config
from .security import (
    CALLBACK_HOST,
    ValidationError,
    callback_game_for_path,
    is_loopback_or_private_host,
    validate_helper_token,
    validate_game,
    validate_session_id,
    validate_short_secret,
)
from .session_store import AuthorizationLease, PendingSessionStore, SessionError


LOGGER = logging.getLogger("maimai_wechat_helper")
ResponseFactory = Callable[[int, bytes, dict[str, str]], Any]
PROGRESS_PATH_PREFIX = "/progress/"
PROGRESS_TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}")
PROGRESS_TERMINAL_TTL_SECONDS = 300.0


@dataclass(slots=True)
class _ProgressState:
    status: str
    message: str
    expires_at: float
    game: str | None = None
    stage: str | None = None
    completed: int | None = None
    total: int | None = None
    succeeded: int | None = None
    skipped: int | None = None
    failure_reasons: dict[str, int] | None = None


class WechatHelperAddon:
    def __init__(
        self,
        config: Config,
        adapter: MaimaiPyAdapter,
        backend: BackendClient,
        *,
        response_factory: ResponseFactory | None = None,
        sessions: PendingSessionStore | None = None,
    ) -> None:
        self._config = config
        self._adapter = adapter
        self._backend = backend
        self._response_factory = response_factory or _mitm_response
        self._sessions = sessions or PendingSessionStore(
            config.session_ttl_seconds,
            config.max_pending_sessions,
        )
        self._tasks: set[asyncio.Task[None]] = set()
        self._progress: dict[str, _ProgressState] = {}
        self._progress_capacity = max(8, config.max_pending_sessions * 4)
        self._sync_deadline_seconds = min(
            MAX_SYNC_DEADLINE_SECONDS,
            max(1.0, float(config.session_ttl_seconds) - SYNC_DEADLINE_MARGIN_SECONDS),
        )

    async def request(self, flow: Any) -> None:
        """Handle a mitmproxy HTTPFlow without ever forwarding unknown traffic."""
        try:
            request = flow.request
            parsed = urlsplit(request.pretty_url)
            scheme = (parsed.scheme or getattr(request, "scheme", "")).lower()
            host = (parsed.hostname or getattr(request, "host", "")).lower()
            port = (
                parsed.port
                if parsed.port is not None
                else getattr(request, "port", None)
            )
            method = str(getattr(request, "method", "")).upper()
        except (TypeError, ValueError, AttributeError):
            self._set_html(flow, 400, "请求格式无效")
            return

        if self._is_local_health(scheme, host, port, parsed.path):
            self._handle_health(flow, method)
            return

        if self._is_local_start(scheme, host, port, parsed.path):
            LOGGER.info("local start route hit")
            await self._handle_start(flow, method, parsed.query)
            return

        if (
            scheme == "http"
            and host == CALLBACK_HOST
            and port == 80
            and parsed.path.startswith(PROGRESS_PATH_PREFIX)
        ):
            self._handle_progress(flow, method, parsed.path, parsed.query)
            return

        callback_game = callback_game_for_path(parsed.path)
        if (
            scheme == "http"
            and host == CALLBACK_HOST
            and port == 80
            and callback_game is not None
        ):
            LOGGER.info("audited %s callback route hit", callback_game)
            await self._handle_callback(
                flow,
                method,
                parsed.query,
                callback_game,
                parsed.path,
            )
            return

        # Fail closed even when Clash is accidentally configured too broadly.
        self._set_html(flow, 403, "此辅助程序不代理该请求")

    async def shutdown(self) -> None:
        self._sessions.clear()
        self._progress.clear()
        tasks = tuple(self._tasks)
        for task in tasks:
            task.cancel()
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    async def wait_for_tasks(self) -> None:
        """Wait for current tasks; useful for orderly shutdown and unit tests."""
        tasks = tuple(self._tasks)
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    def _is_local_start(
        self,
        scheme: str,
        host: str,
        port: int | None,
        path: str,
    ) -> bool:
        return (
            scheme == "http"
            and port == self._config.listen_port
            and path == "/start"
            and is_loopback_or_private_host(host)
        )

    def _is_local_health(
        self,
        scheme: str,
        host: str,
        port: int | None,
        path: str,
    ) -> bool:
        return (
            scheme == "http"
            and port == self._config.listen_port
            and path == "/health"
            and is_loopback_or_private_host(host)
        )

    def _handle_health(self, flow: Any, method: str) -> None:
        if method == "GET":
            self._set_json(
                flow,
                200,
                {"status": "ok", "service": "wahlap-wechat-helper"},
            )
        else:
            self._set_json(
                flow,
                405,
                {
                    "status": "method_not_allowed",
                    "service": "wahlap-wechat-helper",
                },
            )
            flow.response.headers["Allow"] = "GET"
        # The health response contains only a fixed service marker and is safe
        # for a browser page on the same private network to probe directly.
        flow.response.headers["Access-Control-Allow-Origin"] = "*"

    async def _handle_start(self, flow: Any, method: str, query: str) -> None:
        if method != "GET":
            self._set_html(flow, 405, "启动入口只接受 GET")
            return
        try:
            values = _exact_query(query, {"sessionId", "token"})
            session_id = validate_session_id(values["sessionId"])
            token = validate_helper_token(values["token"])
        except ValidationError:
            self._set_html(flow, 400, "同步会话格式无效")
            return
        replay = self._sessions.replay(session_id, token)
        if replay is not None:
            headers = _security_headers()
            headers["Location"] = replay.url
            flow.response = self._response_factory(302, b"", headers)
            LOGGER.info("local start route accepted")
            return

        try:
            # This is deliberately first: a random UUID/token must never be
            # able to allocate an OAuth flow or occupy the local session cap.
            waiting_response = await self._backend.report_event(
                session_id,
                token,
                "waiting_auth",
            )
            game = _waiting_auth_game(waiting_response, session_id)
        except BackendError as error:
            status = 403 if error.status is not None and 400 <= error.status < 500 else 502
            self._set_html(flow, status, "无法验证网页签发的同步会话，请返回成绩页重试")
            return

        try:
            oauth = await self._adapter.begin_oauth(game)
            if oauth.game != game:
                raise AdapterError("authorization game binding is invalid")
            self._sessions.register(session_id, token, oauth)
        except SessionError:
            # Two quick opens can both pass the initial replay check before
            # the first OAuth binding is registered. Reuse that audited
            # binding instead of turning the second navigation into a 409.
            replay = self._sessions.replay(session_id, token)
            if replay is not None:
                headers = _security_headers()
                headers["Location"] = replay.url
                flow.response = self._response_factory(302, b"", headers)
                LOGGER.info("local start route accepted")
                return
            # A concurrent duplicate may already own the valid flow. Do not
            # consume its backend token by publishing a false failure.
            if not self._sessions.has_session(session_id):
                await self._report_failed(
                    session_id,
                    token,
                    "本地同步会话容量已满，请稍后重试",
                    game,
                )
            self._set_html(flow, 409, "同步会话重复或本地会话容量已满")
            return
        except AdapterError as error:
            await self._report_failed(
                session_id,
                token,
                "暂时无法取得微信授权地址",
                game,
            )
            LOGGER.error(
                "one-time %s synchronization could not start at %s",
                game,
                _adapter_failure_stage(error),
            )
            self._set_html(flow, 502, "暂时无法取得微信授权地址")
            return
        headers = _security_headers()
        headers["Location"] = oauth.url
        flow.response = self._response_factory(302, b"", headers)
        LOGGER.info("local start route accepted")

    async def _handle_callback(
        self,
        flow: Any,
        method: str,
        query: str,
        game: str,
        callback_path: str,
    ) -> None:
        if method != "GET":
            self._set_html(flow, 405, "OAuth 回调只接受 GET")
            return
        try:
            values = _exact_query(query, {"r", "t", "code", "state"})
            callback = CallbackParameters(
                r=validate_short_secret(values["r"], "r", 2048),
                t=validate_short_secret(values["t"], "t", 2048),
                code=validate_short_secret(values["code"], "code", 1024),
                state=validate_short_secret(values["state"], "state", 512),
            )
            lease = self._sessions.claim(
                callback.state,
                callback.r,
                callback.t,
                game,
                callback_path,
            )
        except (ValidationError, SessionError):
            self._set_html(flow, 409, "授权回调无效、已过期或已经使用")
            return

        try:
            await self._backend.report_event(
                lease.session_id,
                lease.token,
                "callback_received",
                game=lease.game,
            )
        except BackendError:
            await self._report_failed(
                lease.session_id,
                lease.token,
                "后台未能确认微信授权回调，请重新创建同步会话",
                lease.game,
            )
            self._set_html(flow, 502, "后台未能确认授权回调，请返回成绩页重试")
            return

        progress_token = self._create_progress()
        self._set_progress_page(flow, progress_token)
        task = asyncio.create_task(
            self._synchronize(lease, callback, progress_token),
            name=f"{lease.game}-wechat-one-shot-sync",
        )
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        LOGGER.info("audited %s callback route accepted", lease.game)

    async def _synchronize(
        self,
        lease: AuthorizationLease,
        callback: CallbackParameters,
        progress_token: str,
    ) -> None:
        try:
            async with asyncio.timeout(self._sync_deadline_seconds):
                await self._backend.report_event(
                    lease.session_id,
                    lease.token,
                    "fetching",
                    game=lease.game,
                )

                async def publish_progress(progress: SyncProgress) -> None:
                    if progress.game != lease.game:
                        raise AdapterError(
                            "synchronization progress game binding is invalid"
                        )
                    self._update_progress(progress_token, progress)
                    try:
                        await self._backend.report_event(
                            lease.session_id,
                            lease.token,
                            "fetching",
                            game=lease.game,
                            stage=progress.stage,
                            completed=progress.completed,
                            total=progress.total,
                            succeeded=progress.succeeded,
                            skipped=progress.skipped,
                            failure_reasons=dict(progress.failure_reasons),
                        )
                    except asyncio.CancelledError:
                        raise
                    except BackendError:
                        # A transient progress-event failure must not discard
                        # already fetched official play data. The authenticated
                        # import remains the terminal source of truth.
                        LOGGER.warning(
                            "unable to publish a sanitized %s detail progress event",
                            lease.game,
                        )

                payload = await self._adapter.fetch_import_payload(
                    callback,
                    lease.session_id,
                    lease.game,
                    progress_callback=publish_progress,
                    callback_path=lease.callback_path,
                )
                await self._backend.import_sync(payload, lease.token)
            self._finish_progress(
                progress_token,
                "complete",
                "游戏数据已全部上传完成，你可以安全退出此页面。",
            )
            LOGGER.info("one-time %s synchronization completed", lease.game)
        except asyncio.CancelledError:
            raise
        except TimeoutError:
            message = "读取游戏成绩超时，请重新创建同步会话后再试"
            await self._report_failed(
                lease.session_id,
                lease.token,
                message,
                lease.game,
            )
            self._finish_progress(progress_token, "failed", message)
            LOGGER.error(
                "one-time %s synchronization exceeded its deadline", lease.game
            )
        except AdapterError as error:
            message = "读取最佳成绩或游玩记录失败，请稍后重试"
            await self._report_failed(
                lease.session_id,
                lease.token,
                message,
                lease.game,
            )
            self._finish_progress(progress_token, "failed", message)
            LOGGER.error(
                "one-time %s synchronization failed at %s",
                lease.game,
                _adapter_failure_stage(error),
            )
        except BackendError:
            message = "后台导入成绩失败，请检查主程序后重试"
            await self._report_failed(
                lease.session_id,
                lease.token,
                message,
                lease.game,
            )
            self._finish_progress(progress_token, "failed", message)
            LOGGER.error("one-time %s synchronization failed at backend import", lease.game)
        except Exception:
            message = "同步辅助程序发生异常，请重新创建同步会话"
            await self._report_failed(
                lease.session_id,
                lease.token,
                message,
                lease.game,
            )
            self._finish_progress(progress_token, "failed", message)
            LOGGER.error("one-time %s synchronization failed unexpectedly", lease.game)

    async def _report_failed(
        self,
        session_id: str,
        token: str,
        message: str,
        game: str,
    ) -> None:
        """Best-effort terminal event so the web page never polls forever."""
        try:
            await self._backend.report_event(
                session_id,
                token,
                "failed",
                message,
                game=game,
            )
        except asyncio.CancelledError:
            raise
        except Exception:
            # This path must not obscure the original failure or leak secrets.
            LOGGER.error("unable to publish the sanitized synchronization failure")

    def _create_progress(self) -> str:
        now = time.monotonic()
        self._cleanup_progress(now)
        while len(self._progress) >= self._progress_capacity:
            oldest = min(
                self._progress,
                key=lambda token: self._progress[token].expires_at,
            )
            self._progress.pop(oldest, None)
        while True:
            token = secrets.token_urlsafe(32)
            if PROGRESS_TOKEN_PATTERN.fullmatch(token) and token not in self._progress:
                break
        self._progress[token] = _ProgressState(
            status="working",
            message="正在获取玩家信息与游玩详情，请稍候。",
            expires_at=now + float(self._config.session_ttl_seconds),
        )
        return token

    def _update_progress(self, token: str, progress: SyncProgress) -> None:
        state = self._progress.get(token)
        if state is None or state.status != "working":
            return
        if state.stage is not None:
            if (
                state.game != progress.game
                or state.stage != progress.stage
                or state.total != progress.total
                or state.completed is None
                or state.succeeded is None
                or state.skipped is None
                or progress.completed < state.completed
                or progress.succeeded < state.succeeded
                or progress.skipped is None
                or progress.skipped < state.skipped
                or any(
                    dict(progress.failure_reasons).get(reason, 0) < count
                    for reason, count in (state.failure_reasons or {}).items()
                )
            ):
                raise AdapterError("synchronization progress sequence is invalid")
        state.game = progress.game
        state.stage = progress.stage
        state.completed = progress.completed
        state.total = progress.total
        state.succeeded = progress.succeeded
        state.skipped = progress.skipped
        state.failure_reasons = dict(progress.failure_reasons)
        label = "舞萌 DX" if progress.game == "maimai" else "中二节奏"
        state.message = (
            f"正在抓取{label}游玩详情：{progress.completed} / {progress.total}"
            f"（详情成功 {progress.succeeded}，跳过 {progress.skipped}）"
        )

    def _finish_progress(self, token: str, status: str, message: str) -> None:
        if status not in {"complete", "failed"}:
            return
        state = self._progress.get(token)
        if state is None:
            return
        state.status = status
        state.message = message
        state.expires_at = time.monotonic() + PROGRESS_TERMINAL_TTL_SECONDS

    def _cleanup_progress(self, now: float | None = None) -> None:
        current = time.monotonic() if now is None else now
        expired = [
            token
            for token, state in self._progress.items()
            if state.expires_at <= current
        ]
        for token in expired:
            self._progress.pop(token, None)

    def _handle_progress(
        self,
        flow: Any,
        method: str,
        path: str,
        query: str,
    ) -> None:
        if method != "GET":
            self._set_json(
                flow,
                405,
                {"status": "failed", "message": "同步状态只接受 GET"},
            )
            return
        token = path.removeprefix(PROGRESS_PATH_PREFIX)
        if query or not PROGRESS_TOKEN_PATTERN.fullmatch(token):
            self._set_json(
                flow,
                404,
                {"status": "failed", "message": "同步状态不存在或已过期"},
            )
            return
        self._cleanup_progress()
        state = self._progress.get(token)
        if state is None:
            self._set_json(
                flow,
                410,
                {"status": "failed", "message": "同步状态不存在或已过期"},
            )
            return
        value: dict[str, Any] = {
            "status": state.status,
            "message": state.message,
        }
        if (
            state.game is not None
            and state.stage is not None
            and state.completed is not None
            and state.total is not None
            and state.succeeded is not None
            and state.skipped is not None
            and state.failure_reasons is not None
        ):
            value.update(
                {
                    "game": state.game,
                    "stage": state.stage,
                    "completed": state.completed,
                    "total": state.total,
                    "succeeded": state.succeeded,
                    "skipped": state.skipped,
                    "failureReasons": state.failure_reasons,
                }
            )
        self._set_json(flow, 200, value)

    def _set_progress_page(self, flow: Any, progress_token: str) -> None:
        nonce = secrets.token_urlsafe(18)
        endpoint = json.dumps(
            f"{PROGRESS_PATH_PREFIX}{progress_token}", ensure_ascii=True
        )
        body = f"""<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>音游同步助手</title>
<style nonce="{html.escape(nonce, quote=True)}">
:root{{color-scheme:light;font-family:system-ui,-apple-system,"Segoe UI",sans-serif}}
*{{box-sizing:border-box}}
body{{min-height:100vh;margin:0;display:grid;place-items:center;padding:24px;background:linear-gradient(145deg,#e7f9ff,#f7f5ff);color:#18304a}}
.card{{width:min(430px,100%);padding:38px 28px;text-align:center;background:rgba(255,255,255,.94);border:1px solid #d9edf7;border-radius:24px;box-shadow:0 20px 60px rgba(38,111,151,.16)}}
.spinner{{width:58px;height:58px;margin:0 auto 24px;border:6px solid #dceefa;border-top-color:#38aeea;border-radius:50%;animation:spin .85s linear infinite}}
@keyframes spin{{to{{transform:rotate(360deg)}}}}
.icon{{display:none;width:64px;height:64px;margin:0 auto 22px;border-radius:50%;place-items:center;font-size:36px;font-weight:800;color:#fff}}
h1{{margin:0 0 12px;font-size:28px}}p{{margin:0;line-height:1.75;color:#557086}}
.progress-box{{margin:22px 0 0}}progress{{display:block;width:100%;height:14px;border:0;border-radius:999px;overflow:hidden;background:#dceefa}}
progress::-webkit-progress-bar{{background:#dceefa;border-radius:999px}}progress::-webkit-progress-value{{background:linear-gradient(90deg,#38aeea,#7b6cf6);border-radius:999px}}
progress::-moz-progress-bar{{background:linear-gradient(90deg,#38aeea,#7b6cf6);border-radius:999px}}
.progress-count{{margin-top:8px;font-size:14px;font-weight:700;color:#3c6d8d}}
.progress-reasons{{margin-top:5px;font-size:13px;color:#745b48}}
button{{margin-top:26px;padding:12px 28px;border:0;border-radius:999px;background:#28b66f;color:#fff;font-size:16px;font-weight:700;box-shadow:0 8px 20px rgba(40,182,111,.25)}}
.hint{{margin-top:13px;font-size:13px;color:#7890a3}}
.card[data-state="complete"]{{border-color:#bcead1}}.card[data-state="complete"] .icon{{display:grid;background:#28b66f}}
.card[data-state="failed"]{{border-color:#f1c6c6}}.card[data-state="failed"] .icon{{display:grid;background:#df5961}}
.card[data-state="failed"] button{{background:#66798a;box-shadow:none}}
</style>
</head>
<body>
<main class="card" id="card" data-state="working" aria-live="polite">
  <div class="spinner" id="spinner" aria-hidden="true"></div>
  <div class="icon" id="icon" aria-hidden="true">✓</div>
  <h1 id="title">抓取中</h1>
  <p id="message">正在获取玩家信息与游玩详情，请稍候。</p>
  <div class="progress-box" id="progress-box" hidden>
    <progress id="progress" max="1" value="0" aria-label="游玩详情抓取进度"></progress>
    <p class="progress-count" id="progress-count">0 / 0（详情成功 0，跳过 0）</p>
    <p class="progress-reasons" id="progress-reasons" hidden></p>
  </div>
  <button id="close" type="button" hidden>关闭此页面</button>
  <p class="hint" id="hint" hidden>若页面没有自动关闭，你可以安全地手动关闭它。</p>
</main>
<script nonce="{html.escape(nonce, quote=True)}">
"use strict";
try{{window.history.replaceState(null,"",window.location.pathname);}}catch(_error){{}}
const endpoint={endpoint};
const card=document.getElementById("card");
const spinner=document.getElementById("spinner");
const icon=document.getElementById("icon");
const title=document.getElementById("title");
const message=document.getElementById("message");
const progressBox=document.getElementById("progress-box");
const progressBar=document.getElementById("progress");
const progressCount=document.getElementById("progress-count");
const progressReasons=document.getElementById("progress-reasons");
const closeButton=document.getElementById("close");
const hint=document.getElementById("hint");
let stopped=false;
const reasonLabels=Object.freeze({{
  "missing-source-id":"官网未提供详情入口",
  "invalid-source-id":"详情入口格式无效",
  "request-timeout":"详情请求超时",
  "request-failed":"详情请求失败",
  "request-error":"详情请求失败",
  "transport-failure":"网络传输失败",
  "invalid-response":"官网响应无效",
  "invalid-detail-template":"官网返回的不是详情页",
  "detail-validation-failure":"详情页无法解析",
  "response-too-large":"官网响应过大",
  "non-retryable-status":"官网拒绝详情请求",
  "rate-limited":"官网请求限流",
  "upstream-error":"官网暂时异常",
  "row-timeout":"单条详情抓取超时",
  "unexpected-row-failure":"单条详情抓取异常",
  "unavailable":"详情暂不可用"
}});
function normalizeReasons(value,skipped){{
  if(value===undefined&&skipped>0){{return {{unavailable:skipped}};}}
  if(!value||typeof value!=="object"||Array.isArray(value)){{return skipped===0?{{}}:null;}}
  const result={{}};
  let sum=0;
  for(const [reason,rawCount] of Object.entries(value)){{
    const count=Number(rawCount);
    if(!Object.prototype.hasOwnProperty.call(reasonLabels,reason)||!Number.isInteger(count)||count<1){{return null;}}
    result[reason]=count;
    sum+=count;
  }}
  return sum===skipped?result:null;
}}
function finish(data){{
  stopped=true;
  const success=data.status==="complete";
  card.dataset.state=success?"complete":"failed";
  spinner.hidden=true;
  icon.textContent=success?"✓":"!";
  title.textContent=success?"上传完成":"同步失败";
  message.textContent=String(data.message||"同步未完成，请返回成绩页重试。");
  closeButton.hidden=false;
}}
function updateProgress(data){{
  const completed=Number(data.completed);
  const total=Number(data.total);
  const succeeded=Number(data.succeeded);
  const skipped=Number(data.skipped===undefined?completed-succeeded:data.skipped);
  const reasons=normalizeReasons(data.failureReasons,skipped);
  if((data.game!=="maimai"&&data.game!=="chunithm")||
     data.stage!=="play_details"||!Number.isInteger(completed)||
     !Number.isInteger(total)||!Number.isInteger(succeeded)||!Number.isInteger(skipped)||
     total<1||completed<0||completed>total||succeeded<0||succeeded>completed||
     skipped<0||succeeded+skipped!==completed||reasons===null){{return;}}
  progressBar.max=total;
  progressBar.value=completed;
  const game=data.game==="chunithm"?"中二节奏":"舞萌 DX";
  progressBar.setAttribute("aria-valuetext",`${{game}} ${{completed}} / ${{total}}，详情成功 ${{succeeded}}，跳过 ${{skipped}}`);
  progressCount.textContent=`${{completed}} / ${{total}}（详情成功 ${{succeeded}}，跳过 ${{skipped}}）`;
  const reasonText=Object.entries(reasons).map(([reason,count])=>`${{reasonLabels[reason]}} ${{count}}`).join("；");
  progressReasons.textContent=reasonText?`跳过原因：${{reasonText}}`:"";
  progressReasons.hidden=!reasonText;
  progressBox.hidden=false;
}}
async function poll(){{
  try{{
    const response=await fetch(endpoint,{{cache:"no-store",credentials:"omit",redirect:"error",referrerPolicy:"no-referrer"}});
    const data=await response.json();
    updateProgress(data);
    if(data.status==="complete"||data.status==="failed"){{finish(data);return;}}
    message.textContent=String(data.message||"正在获取玩家信息与游玩详情，请稍候。");
  }}catch(_error){{
    message.textContent="正在等待同步状态，请保持此页面打开。";
  }}
  if(!stopped){{window.setTimeout(poll,700);}}
}}
closeButton.addEventListener("click",()=>{{
  try{{
    const bridge=window.WeixinJSBridge;
    if(bridge&&typeof bridge.call==="function"){{bridge.call("closeWindow");}}
    else{{window.close();}}
  }}catch(_error){{window.close();}}
  window.setTimeout(()=>{{hint.hidden=false;}},350);
}});
window.setTimeout(poll,250);
</script>
</body>
</html>""".encode("utf-8")
        headers = _security_headers(
            content_type="text/html; charset=utf-8",
            content_security_policy=(
                "default-src 'none'; base-uri 'none'; form-action 'none'; "
                "frame-ancestors 'none'; connect-src 'self'; "
                f"style-src 'nonce-{nonce}'; script-src 'nonce-{nonce}'"
            ),
        )
        # Android WeChat WebView may replace a non-200 top-level navigation
        # with ERR_HTTP_RESPONSE_CODE_FAILURE even though 202 is a successful
        # HTTP status.  This page is already the accepted job monitor, so 200
        # is the interoperable response.
        flow.response = self._response_factory(200, body, headers)

    def _set_json(
        self,
        flow: Any,
        status: int,
        value: dict[str, Any],
    ) -> None:
        body = json.dumps(
            value,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
        flow.response = self._response_factory(
            status,
            body,
            _security_headers(
                content_type="application/json; charset=utf-8",
                content_security_policy="default-src 'none'; frame-ancestors 'none'",
            ),
        )

    def _set_html(self, flow: Any, status: int, message: str) -> None:
        body = (
            "<!doctype html><html lang='zh-CN'><meta charset='utf-8'>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<title>音游同步助手</title>"
            "<body style='font-family:sans-serif;max-width:38rem;margin:4rem auto;"
            "padding:0 1.25rem;line-height:1.7'>"
            f"<h1>音游同步助手</h1><p>{html.escape(message)}</p>"
            "<p>支持舞萌 DX 与中二节奏的一次性微信同步。</p></body></html>"
        ).encode("utf-8")
        flow.response = self._response_factory(status, body, _security_headers())


def _exact_query(query: str, expected: set[str]) -> dict[str, str]:
    try:
        parsed = parse_qs(query, keep_blank_values=True, strict_parsing=True)
    except ValueError as error:
        raise ValidationError("query string is invalid") from error
    if set(parsed) != expected:
        raise ValidationError("query string fields are invalid")
    result: dict[str, str] = {}
    for name in expected:
        values = parsed[name]
        if len(values) != 1 or not values[0]:
            raise ValidationError(f"{name} must occur exactly once")
        result[name] = values[0]
    return result


def _waiting_auth_game(value: dict[str, Any], session_id: str) -> str:
    if not isinstance(value, dict):
        raise BackendError("backend returned an invalid waiting session")
    session = value.get("session")
    if not isinstance(session, dict):
        raise BackendError("backend returned an invalid waiting session")
    if session.get("id") != session_id or session.get("status") != "waiting_auth":
        raise BackendError("backend returned a mismatched waiting session")
    try:
        return validate_game(session.get("game"))
    except ValueError as error:
        raise BackendError("backend returned an unsupported session game") from error


def _adapter_failure_stage(error: AdapterError) -> str:
    """Map a safe adapter message to a non-secret diagnostic stage."""
    message = str(error).lower()
    if "deadline" in message or "timeout" in message:
        return "upstream timeout"
    if "authorization callback" in message:
        return "authorization callback"
    if "authorized page" in message or "authorization returned no cookies" in message:
        return "official authorization redirect"
    if "catalog" in message or "songid" in message or "matched" in message:
        return "catalog matching"
    if "rating" in message:
        return "rating parsing"
    if "recent play" in message or "playlog" in message or "record" in message:
        return "playlog parsing"
    if "score page" in message or "score fetch" in message:
        return "score page fetch"
    return "adapter validation"


def _security_headers(
    *,
    content_type: str = "text/html; charset=utf-8",
    content_security_policy: str = (
        "default-src 'none'; frame-ancestors 'none'; style-src 'unsafe-inline'"
    ),
) -> dict[str, str]:
    return {
        "Content-Type": content_type,
        "Cache-Control": "no-store, max-age=0",
        "Pragma": "no-cache",
        "Referrer-Policy": "no-referrer",
        "X-Content-Type-Options": "nosniff",
        "X-Frame-Options": "DENY",
        "Content-Security-Policy": content_security_policy,
    }


def _mitm_response(status: int, body: bytes, headers: dict[str, str]) -> Any:
    from mitmproxy.http import Response

    return Response.make(status, body, headers)
