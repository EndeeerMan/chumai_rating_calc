from __future__ import annotations

import asyncio
from dataclasses import dataclass
import json
import re
from types import SimpleNamespace
import unittest
from urllib.parse import urlencode

from mitmproxy import http

from wechat_helper.adapter import AdapterError, CallbackParameters, SyncProgress
from wechat_helper.addon import WechatHelperAddon
from wechat_helper.backend import BackendError
from wechat_helper.config import Config
from wechat_helper.security import BackendTarget, CALLBACK_HOST, CALLBACK_PATHS
from wechat_helper.session_store import OAuthBinding


SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
TOKEN = "A" * 43
OAUTH = OAuthBinding(
    "https://open.weixin.qq.com/connect/oauth2/authorize?example=1",
    "oauth-state",
    "r-secret",
    "t-secret",
    "maimai",
)


def config() -> Config:
    return Config(
        listen_host="0.0.0.0",
        listen_port=8081,
        backend=BackendTarget("http", "127.0.0.1", 8080),
        session_ttl_seconds=900,
        max_pending_sessions=32,
        upstream_timeout_seconds=300.0,
        backend_timeout_seconds=30.0,
        play_utc_offset_minutes=480,
    )


@dataclass
class FakeResponse:
    status: int
    body: bytes
    headers: dict[str, str]


def response_factory(status: int, body: bytes, headers: dict[str, str]) -> FakeResponse:
    return FakeResponse(status, body, headers)


def flow(url: str, method: str = "GET", fallback_port: int | None = None) -> object:
    return SimpleNamespace(
        request=SimpleNamespace(
            pretty_url=url,
            scheme="",
            host="",
            port=fallback_port,
            method=method,
        ),
        response=None,
    )


def start_url(token: str = TOKEN) -> str:
    return "http://127.0.0.1:8081/start?" + urlencode(
        {"sessionId": SESSION_ID, "token": token}
    )


def callback_url(game: str = "maimai") -> str:
    return f"http://{CALLBACK_HOST}{CALLBACK_PATHS[game]}?" + urlencode(
        {
            "r": "r-secret",
            "t": "t-secret",
            "code": "wechat-code",
            "state": "oauth-state",
        }
    )


class FakeAdapter:
    def __init__(self, log: list[object]) -> None:
        self.log = log
        self.begin_calls = 0
        self.fetch_error: Exception | None = None
        self.fetch_delay = 0.0
        self.emit_progress = False
        self.callback_paths: list[str | None] = []

    async def begin_oauth(self, game: str) -> OAuthBinding:
        self.begin_calls += 1
        self.log.append(("begin_oauth", game))
        return OAuthBinding(
            OAUTH.url, OAUTH.state, OAUTH.expected_r, OAUTH.expected_t, game
        )

    async def fetch_import_payload(
        self,
        callback: CallbackParameters,
        session_id: str,
        game: str,
        progress_callback: object | None = None,
        callback_path: str | None = None,
    ) -> dict[str, object]:
        self.log.append(("fetch", game))
        self.callback_paths.append(callback_path)
        if self.emit_progress:
            self.assert_progress_callback = progress_callback
            if not callable(progress_callback):
                raise AssertionError("progress callback was not provided")
            await progress_callback(
                SyncProgress(game, "play_details", 0, 2, 0)
            )
            await progress_callback(
                SyncProgress(game, "play_details", 1, 2, 1)
            )
            await progress_callback(
                SyncProgress(game, "play_details", 2, 2, 1)
            )
        if self.fetch_delay:
            await asyncio.sleep(self.fetch_delay)
        if self.fetch_error is not None:
            raise self.fetch_error
        return {
            "game": game,
            "sessionId": session_id,
            "charts": [{"songId": "1"}],
            "records": [],
        }


class FakeBackend:
    def __init__(self, log: list[object]) -> None:
        self.log = log
        self.fail_status: str | None = None
        self.fail_http_status: int | None = None
        self.import_error: Exception | None = None
        self.session_game = "maimai"

    async def report_event(
        self,
        session_id: str,
        token: str,
        status: str,
        message: str | None = None,
        game: str | None = None,
        *,
        stage: str | None = None,
        completed: int | None = None,
        total: int | None = None,
        succeeded: int | None = None,
        skipped: int | None = None,
        failure_reasons: dict[str, int] | None = None,
    ) -> dict[str, object]:
        if stage is None:
            self.log.append(("event", status, message, game))
        else:
            self.log.append(
                (
                    "progress",
                    status,
                    game,
                    stage,
                    completed,
                    total,
                    succeeded,
                    skipped,
                    failure_reasons,
                )
            )
        if status == self.fail_status:
            raise BackendError("simulated safe failure", status=self.fail_http_status)
        return {
            "session": {
                "id": session_id,
                "game": self.session_game,
                "status": status,
            }
        }

    async def import_sync(
        self,
        payload: dict[str, object],
        token: str,
    ) -> dict[str, object]:
        self.log.append(("import", payload["sessionId"]))
        if self.import_error is not None:
            raise self.import_error
        return {"ok": True}


class AddonFlowTests(unittest.IsolatedAsyncioTestCase):
    def make_addon(self) -> tuple[WechatHelperAddon, FakeAdapter, FakeBackend, list[object]]:
        log: list[object] = []
        adapter = FakeAdapter(log)
        backend = FakeBackend(log)
        addon = WechatHelperAddon(
            config(),
            adapter,  # type: ignore[arg-type]
            backend,  # type: ignore[arg-type]
            response_factory=response_factory,
        )
        return addon, adapter, backend, log

    async def authorize(
        self, addon: WechatHelperAddon, game: str = "maimai"
    ) -> None:
        backend = addon._backend
        backend.session_game = game
        start = flow(start_url())
        await addon.request(start)
        self.assertEqual(302, start.response.status)
        callback = flow(callback_url(game), fallback_port=80)
        await addon.request(callback)
        self.assertEqual(200, callback.response.status)
        await addon.wait_for_tasks()

    async def test_health_is_direct_json_for_loopback_and_private_hosts(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        for host in ("127.0.0.1", "192.168.1.12"):
            with self.subTest(host=host):
                request = flow(f"http://{host}:8081/health")
                await addon.request(request)
                self.assertEqual(200, request.response.status)
                self.assertNotEqual(504, request.response.status)
                self.assertEqual(
                    {"status": "ok", "service": "wahlap-wechat-helper"},
                    json.loads(request.response.body),
                )
                self.assertEqual(
                    "application/json; charset=utf-8",
                    request.response.headers["Content-Type"],
                )
                self.assertEqual(
                    "*", request.response.headers["Access-Control-Allow-Origin"]
                )
                body = request.response.body.decode("utf-8")
                self.assertNotIn(TOKEN, body)
                self.assertNotIn(SESSION_ID, body)
                self.assertNotIn("127.0.0.1:8080", body)
        self.assertEqual(0, adapter.begin_calls)
        self.assertEqual([], log)

    async def test_health_rejects_non_get_methods_without_forwarding(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        for method in ("POST", "HEAD", "OPTIONS"):
            with self.subTest(method=method):
                request = flow("http://127.0.0.1:8081/health", method=method)
                await addon.request(request)
                self.assertEqual(405, request.response.status)
                self.assertNotEqual(504, request.response.status)
                self.assertEqual("GET", request.response.headers["Allow"])
                self.assertEqual(
                    "*", request.response.headers["Access-Control-Allow-Origin"]
                )
                self.assertEqual(
                    "method_not_allowed",
                    json.loads(request.response.body)["status"],
                )
        self.assertEqual(0, adapter.begin_calls)
        self.assertEqual([], log)

    async def test_route_logs_show_hits_and_acceptance_without_secrets(self) -> None:
        addon, _adapter, _backend, _log = self.make_addon()
        with self.assertLogs("maimai_wechat_helper", level="INFO") as captured:
            start = flow(start_url())
            await addon.request(start)
            self.assertEqual(302, start.response.status)

            callback = flow(callback_url(), fallback_port=80)
            await addon.request(callback)
            self.assertEqual(200, callback.response.status)
            self.assertNotEqual(504, callback.response.status)
            await addon.wait_for_tasks()

        messages = "\n".join(captured.output)
        self.assertIn("local start route hit", messages)
        self.assertIn("local start route accepted", messages)
        self.assertIn("audited maimai callback route hit", messages)
        self.assertIn("audited maimai callback route accepted", messages)
        for secret in (
            TOKEN,
            SESSION_ID,
            "r-secret",
            "t-secret",
            "wechat-code",
            "oauth-state",
        ):
            self.assertNotIn(secret, messages)
        self.assertNotIn("?", messages)

    async def test_full_flow_obeys_backend_state_machine_order(self) -> None:
        addon, _adapter, _backend, log = self.make_addon()
        await self.authorize(addon)
        self.assertEqual(
            [
                ("event", "waiting_auth", None, None),
                ("begin_oauth", "maimai"),
                ("event", "callback_received", None, "maimai"),
                ("event", "fetching", None, "maimai"),
                ("fetch", "maimai"),
                ("import", SESSION_ID),
            ],
            log,
        )

    async def test_random_formatted_token_never_allocates_oauth(self) -> None:
        addon, adapter, backend, _log = self.make_addon()
        backend.fail_status = "waiting_auth"
        backend.fail_http_status = 401
        request = flow(start_url())
        await addon.request(request)
        self.assertEqual(403, request.response.status)
        self.assertEqual(0, adapter.begin_calls)
        self.assertEqual(0, addon._sessions.pending_count())

    async def test_same_session_and_token_replays_verified_oauth_redirect(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        first = flow(start_url())
        await addon.request(first)
        self.assertEqual(302, first.response.status)

        replay = flow(start_url())
        await addon.request(replay)
        self.assertEqual(302, replay.response.status)
        self.assertEqual(
            first.response.headers["Location"],
            replay.response.headers["Location"],
        )
        self.assertEqual(OAUTH.url, replay.response.headers["Location"])
        self.assertEqual(1, adapter.begin_calls)
        self.assertEqual(
            1,
            log.count(("event", "waiting_auth", None, None)),
            "a local replay must not consume the backend token again",
        )
        self.assertEqual(1, addon._sessions.pending_count())

    async def test_wrong_token_cannot_replay_or_leak_oauth_redirect(self) -> None:
        addon, adapter, _backend, _log = self.make_addon()
        first = flow(start_url())
        await addon.request(first)
        self.assertEqual(302, first.response.status)

        rejected = flow(start_url("B" * 43))
        await addon.request(rejected)
        self.assertNotEqual(302, rejected.response.status)
        self.assertNotIn("Location", rejected.response.headers)
        rejected_body = rejected.response.body.decode("utf-8")
        self.assertNotIn(OAUTH.url, rejected_body)
        self.assertNotIn("open.weixin.qq.com", rejected_body)
        self.assertEqual(1, addon._sessions.pending_count())

        begin_calls_after_rejection = adapter.begin_calls
        valid_replay = flow(start_url())
        await addon.request(valid_replay)
        self.assertEqual(302, valid_replay.response.status)
        self.assertEqual(OAUTH.url, valid_replay.response.headers["Location"])
        self.assertEqual(begin_calls_after_rejection, adapter.begin_calls)

    async def test_claimed_callback_cannot_replay_old_oauth_redirect(self) -> None:
        addon, adapter, backend, _log = self.make_addon()
        first = flow(start_url())
        await addon.request(first)
        self.assertEqual(302, first.response.status)

        callback = flow(callback_url(), fallback_port=80)
        await addon.request(callback)
        self.assertEqual(200, callback.response.status)
        await addon.wait_for_tasks()
        self.assertEqual(0, addon._sessions.pending_count())

        # The backend token is one-shot. Once the callback has claimed the
        # local binding, a repeated /start must consult the backend instead of
        # replaying the cached OAuth URL.
        backend.fail_status = "waiting_auth"
        backend.fail_http_status = 409
        rejected = flow(start_url())
        await addon.request(rejected)
        self.assertNotEqual(302, rejected.response.status)
        self.assertNotIn("Location", rejected.response.headers)
        self.assertNotIn(OAUTH.url, rejected.response.body.decode("utf-8"))
        self.assertEqual(1, adapter.begin_calls)

    async def test_fetch_failure_is_terminal_instead_of_leaving_page_waiting(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        adapter.fetch_error = AdapterError("upstream URL with secret must not leak")
        await self.authorize(addon)
        self.assertEqual("failed", log[-1][1])  # type: ignore[index]
        self.assertNotIn("secret", str(log[-1]))
        self.assertFalse(any(item[0] == "import" for item in log if isinstance(item, tuple)))

    async def test_callback_event_failure_is_shown_and_reported_terminally(self) -> None:
        addon, _adapter, backend, log = self.make_addon()
        start = flow(start_url())
        await addon.request(start)
        backend.fail_status = "callback_received"
        backend.fail_http_status = 503
        callback = flow(callback_url(), fallback_port=80)
        await addon.request(callback)
        self.assertEqual(502, callback.response.status)
        self.assertEqual("failed", log[-1][1])  # type: ignore[index]
        self.assertFalse(any(item[0] == "import" for item in log if isinstance(item, tuple)))

    async def test_whole_sync_has_a_deadline_and_reports_failure(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        adapter.fetch_delay = 0.05
        addon._sync_deadline_seconds = 0.005
        await self.authorize(addon)
        self.assertEqual("failed", log[-1][1])  # type: ignore[index]
        self.assertIn("超时", log[-1][2])  # type: ignore[index]

    async def test_callback_page_polls_random_progress_until_complete(self) -> None:
        addon, adapter, _backend, _log = self.make_addon()
        adapter.fetch_delay = 0.02
        start = flow(start_url())
        await addon.request(start)
        callback = flow(callback_url(), fallback_port=80)
        await addon.request(callback)

        self.assertEqual(200, callback.response.status)
        self.assertEqual(
            "text/html; charset=utf-8",
            callback.response.headers["Content-Type"],
        )
        page = callback.response.body.decode("utf-8")
        match = re.search(r"/progress/([A-Za-z0-9_-]{43})", page)
        self.assertIsNotNone(match)
        progress_token = match.group(1)
        self.assertNotIn(TOKEN, page)
        self.assertNotIn(SESSION_ID, page)
        self.assertNotIn("wechat-code", page)
        self.assertNotIn("r-secret", page)
        self.assertIn("history.replaceState", page)
        self.assertIn("抓取中", page)
        self.assertIn("WeixinJSBridge", page)
        self.assertIn("window.close", page)
        self.assertIn('aria-label="游玩详情抓取进度"', page)
        self.assertIn("aria-valuetext", page)
        self.assertIn("跳过原因", page)
        policy = callback.response.headers["Content-Security-Policy"]
        self.assertIn("connect-src 'self'", policy)
        self.assertIn("script-src 'nonce-", policy)
        self.assertNotIn("unsafe-inline", policy)

        status_url = f"http://{CALLBACK_HOST}/progress/{progress_token}"
        pending = flow(status_url, fallback_port=80)
        await addon.request(pending)
        self.assertEqual(
            "working", json.loads(pending.response.body)["status"]
        )

        await addon.wait_for_tasks()
        complete = flow(status_url, fallback_port=80)
        await addon.request(complete)
        value = json.loads(complete.response.body)
        self.assertEqual("complete", value["status"])
        self.assertIn("上传完成", value["message"])
        self.assertEqual(
            "application/json; charset=utf-8",
            complete.response.headers["Content-Type"],
        )

    async def test_both_games_publish_real_bounded_detail_progress(self) -> None:
        for game in ("maimai", "chunithm"):
            with self.subTest(game=game):
                addon, adapter, backend, log = self.make_addon()
                adapter.emit_progress = True
                backend.session_game = game
                start = flow(start_url())
                await addon.request(start)
                callback = flow(callback_url(game), fallback_port=80)
                await addon.request(callback)
                page = callback.response.body.decode("utf-8")
                progress_token = re.search(
                    r"/progress/([A-Za-z0-9_-]{43})", page
                ).group(1)
                await addon.wait_for_tasks()

                self.assertEqual(
                    [
                        (
                            "progress",
                            "fetching",
                            game,
                            "play_details",
                            0,
                            2,
                            0,
                            0,
                            {},
                        ),
                        (
                            "progress",
                            "fetching",
                            game,
                            "play_details",
                            1,
                            2,
                            1,
                            0,
                            {},
                        ),
                        (
                            "progress",
                            "fetching",
                            game,
                            "play_details",
                            2,
                            2,
                            1,
                            1,
                            {"unavailable": 1},
                        ),
                    ],
                    [item for item in log if item[0] == "progress"],
                )
                status = flow(
                    f"http://{CALLBACK_HOST}/progress/{progress_token}",
                    fallback_port=80,
                )
                await addon.request(status)
                value = json.loads(status.response.body)
                self.assertEqual("complete", value["status"])
                self.assertEqual("play_details", value["stage"])
                self.assertEqual(2, value["completed"])
                self.assertEqual(2, value["total"])
                self.assertEqual(1, value["succeeded"])
                self.assertEqual(1, value["skipped"])
                self.assertEqual({"unavailable": 1}, value["failureReasons"])
                self.assertNotIn(TOKEN, status.response.body.decode("utf-8"))
                self.assertNotIn("wechat-code", status.response.body.decode("utf-8"))

    async def test_progress_failure_is_sanitized_and_terminal(self) -> None:
        addon, adapter, _backend, _log = self.make_addon()
        adapter.fetch_error = AdapterError("secret OAuth URL must not reach page")
        start = flow(start_url())
        await addon.request(start)
        callback = flow(callback_url(), fallback_port=80)
        await addon.request(callback)
        page = callback.response.body.decode("utf-8")
        progress_token = re.search(
            r"/progress/([A-Za-z0-9_-]{43})", page
        ).group(1)

        await addon.wait_for_tasks()
        status = flow(
            f"http://{CALLBACK_HOST}/progress/{progress_token}",
            fallback_port=80,
        )
        await addon.request(status)
        value = json.loads(status.response.body)
        self.assertEqual("failed", value["status"])
        self.assertNotIn("OAuth", value["message"])
        self.assertNotIn("secret", value["message"])

    async def test_terminal_failure_retains_last_safe_detail_count(self) -> None:
        addon, _adapter, _backend, _log = self.make_addon()
        progress_token = addon._create_progress()
        addon._update_progress(
            progress_token,
            SyncProgress(
                "chunithm",
                "play_details",
                7,
                50,
                6,
                failure_reasons={"request-timeout": 1},
            ),
        )
        addon._finish_progress(progress_token, "failed", "同步失败，请重试。")
        request = flow(
            f"http://{CALLBACK_HOST}/progress/{progress_token}",
            fallback_port=80,
        )
        await addon.request(request)
        value = json.loads(request.response.body)
        self.assertEqual("failed", value["status"])
        self.assertEqual("chunithm", value["game"])
        self.assertEqual("play_details", value["stage"])
        self.assertEqual(7, value["completed"])
        self.assertEqual(50, value["total"])
        self.assertEqual(6, value["succeeded"])
        self.assertEqual(1, value["skipped"])
        self.assertEqual({"request-timeout": 1}, value["failureReasons"])

    async def test_progress_route_rejects_unknown_tokens_and_queries(self) -> None:
        addon, _adapter, _backend, _log = self.make_addon()
        unknown = flow(
            f"http://{CALLBACK_HOST}/progress/{'B' * 43}",
            fallback_port=80,
        )
        await addon.request(unknown)
        self.assertEqual(410, unknown.response.status)

        queried = flow(
            f"http://{CALLBACK_HOST}/progress/{'B' * 43}?sessionId={SESSION_ID}",
            fallback_port=80,
        )
        await addon.request(queried)
        self.assertEqual(404, queried.response.status)
        self.assertNotIn(
            SESSION_ID, queried.response.body.decode("utf-8")
        )

    async def test_expired_progress_state_is_removed_from_memory(self) -> None:
        addon, _adapter, _backend, _log = self.make_addon()
        progress_token = addon._create_progress()
        addon._progress[progress_token].expires_at = 0.0
        request = flow(
            f"http://{CALLBACK_HOST}/progress/{progress_token}",
            fallback_port=80,
        )
        await addon.request(request)
        self.assertEqual(410, request.response.status)
        self.assertNotIn(progress_token, addon._progress)

    async def test_chunithm_game_is_bound_to_state_and_callback_slug(self) -> None:
        addon, _adapter, backend, log = self.make_addon()
        backend.session_game = "chunithm"
        start = flow(start_url())
        await addon.request(start)
        self.assertEqual(302, start.response.status)

        wrong = flow(callback_url("maimai"), fallback_port=80)
        await addon.request(wrong)
        self.assertEqual(409, wrong.response.status)

        callback = flow(callback_url("chunithm"), fallback_port=80)
        await addon.request(callback)
        self.assertEqual(200, callback.response.status)
        await addon.wait_for_tasks()
        self.assertIn(("begin_oauth", "chunithm"), log)
        self.assertIn(("fetch", "chunithm"), log)
        self.assertIn(("event", "fetching", None, "chunithm"), log)

    async def test_maimai_current_and_legacy_callback_paths_are_accepted(self) -> None:
        for callback_path in (
            "/wc_auth/oauth/callback/maimai-dx",
            "/wc_auth/oauth/callback/maidx",
        ):
            with self.subTest(callback_path=callback_path):
                addon, adapter, _backend, log = self.make_addon()
                start = flow(start_url())
                await addon.request(start)
                self.assertEqual(302, start.response.status)

                callback = flow(
                    f"http://{CALLBACK_HOST}{callback_path}?"
                    + urlencode(
                        {
                            "r": "r-secret",
                            "t": "t-secret",
                            "code": "wechat-code",
                            "state": "oauth-state",
                        }
                    ),
                    fallback_port=80,
                )
                await addon.request(callback)
                self.assertEqual(200, callback.response.status)
                self.assertRegex(
                    callback.response.body.decode("utf-8"),
                    r"/progress/[A-Za-z0-9_-]{43}",
                )
                await addon.wait_for_tasks()
                self.assertEqual([callback_path], adapter.callback_paths)
                self.assertIn(
                    ("event", "callback_received", None, "maimai"),
                    log,
                )
                self.assertIn(("fetch", "maimai"), log)
                self.assertIn(("import", SESSION_ID), log)

    async def test_real_mitmproxy_callback_navigation_is_http_200(self) -> None:
        cases = (
            ("maimai", "/wc_auth/oauth/callback/maimai-dx"),
            ("maimai", "/wc_auth/oauth/callback/maidx"),
            ("chunithm", "/wc_auth/oauth/callback/chunithm"),
        )
        for game, callback_path in cases:
            with self.subTest(game=game, callback_path=callback_path):
                log: list[object] = []
                adapter = FakeAdapter(log)
                backend = FakeBackend(log)
                backend.session_game = game
                addon = WechatHelperAddon(
                    config(),
                    adapter,  # type: ignore[arg-type]
                    backend,  # type: ignore[arg-type]
                )

                start = SimpleNamespace(
                    request=http.Request.make("GET", start_url()),
                    response=None,
                )
                await addon.request(start)
                self.assertIsInstance(start.response, http.Response)
                self.assertEqual(302, start.response.status_code)

                callback_query = urlencode(
                    {
                        "r": "r-secret",
                        "t": "t-secret",
                        "code": "wechat-code",
                        "state": "oauth-state",
                    }
                )
                callback = SimpleNamespace(
                    request=http.Request.make(
                        "GET",
                        f"http://{CALLBACK_HOST}{callback_path}?{callback_query}",
                    ),
                    response=None,
                )
                await addon.request(callback)

                self.assertIsInstance(callback.response, http.Response)
                self.assertEqual(200, callback.response.status_code)
                self.assertEqual(
                    "text/html; charset=utf-8",
                    callback.response.headers.get("Content-Type"),
                )
                page = bytes(callback.response.content).decode("utf-8")
                self.assertRegex(page, r"/progress/[A-Za-z0-9_-]{43}")

                await addon.wait_for_tasks()
                self.assertIn(("event", "callback_received", None, game), log)
                self.assertIn(("fetch", game), log)
                self.assertIn(("import", SESSION_ID), log)

    async def test_unknown_callback_path_is_denied(self) -> None:
        addon, adapter, _backend, log = self.make_addon()
        request = flow(
            f"http://{CALLBACK_HOST}/wc_auth/oauth/callback/maidx/extra?"
            + urlencode(
                {
                    "r": "r-secret",
                    "t": "t-secret",
                    "code": "wechat-code",
                    "state": "oauth-state",
                }
            ),
            fallback_port=80,
        )
        await addon.request(request)
        self.assertEqual(403, request.response.status)
        self.assertEqual(0, adapter.begin_calls)
        self.assertEqual([], log)

    async def test_unknown_proxy_destination_is_denied(self) -> None:
        addon, _adapter, _backend, _log = self.make_addon()
        request = flow("https://example.com/private")
        await addon.request(request)
        self.assertEqual(403, request.response.status)

    async def test_explicit_zero_port_never_falls_back_to_request_port(self) -> None:
        addon, adapter, _backend, _log = self.make_addon()
        request = flow(
            start_url().replace("127.0.0.1:8081", "127.0.0.1:0"),
            fallback_port=8081,
        )
        await addon.request(request)
        self.assertEqual(403, request.response.status)
        self.assertEqual(0, adapter.begin_calls)


if __name__ == "__main__":
    unittest.main()
