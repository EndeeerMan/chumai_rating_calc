"""Narrow, capability-checked adapter for maimai-py 1.5.1."""

from __future__ import annotations

import asyncio
from collections.abc import Mapping
import dataclasses
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from importlib import metadata
import inspect
import logging
import math
from pathlib import Path
import posixpath
import re
from typing import Any, Awaitable, Callable
from urllib.parse import parse_qs, urljoin, urlsplit

from httpx import Cookies, RequestError

from .catalog import (
    CatalogError,
    ChunithmCatalog,
    MaimaiCatalog,
    normalize_chunithm_difficulty,
    normalize_difficulty,
)
from .config import Config
from .security import (
    CALLBACK_HOST,
    CALLBACK_PATHS,
    validate_callback_path,
    validate_game,
    validate_short_secret,
)
from .session_store import OAuthBinding


EXPECTED_MAIMAI_PY_VERSION = "1.5.1"
MAX_OAUTH_URL_LENGTH = 8192
MAX_CHARTS = 2_000
MAX_RECORDS = 2_000
EXPECTED_WECHAT_APP_ID = "wx1fcecfcbd16803b1"
EXPECTED_WECHAT_RESPONSE_TYPE = "code"
EXPECTED_WECHAT_SCOPE = "snsapi_base"
MAX_SYNC_DEADLINE_SECONDS = 600.0
SYNC_DEADLINE_MARGIN_SECONDS = 30.0
MAX_OFFICIAL_HTML_CHARS = 8 * 1024 * 1024
MAX_OFFICIAL_ASSET_URL_LENGTH = 2048
MAX_OFFICIAL_REDIRECTS = 5
MAX_DETAIL_PAGES = 100
DETAIL_REQUEST_ATTEMPTS = 3
DETAIL_REQUEST_INTERVAL_SECONDS = 0.15
MAIMAI_DETAIL_REQUEST_INTERVAL_SECONDS = 1.0
MAIMAI_DETAIL_UPSTREAM_COOLDOWNS_SECONDS = (3.0, 10.0, 30.0)
MAIMAI_DETAIL_STAGE_BUDGET_SECONDS = 240.0
# Detail pages are optional enrichment.  Never let one stalled Wahlap response
# monopolize the ten-minute synchronization deadline or discard base scores.
# The outer per-row budget also protects custom/test transports that ignore an
# HTTP client's timeout setting.
DETAIL_REQUEST_TIMEOUT_SECONDS = 4.0
DETAIL_ROW_TIMEOUT_SECONDS = 8.0
DETAIL_FAILURE_REASONS = frozenset({
    "missing-source-id",
    "invalid-source-id",
    "request-timeout",
    "request-failed",
    "request-error",
    "transport-failure",
    "invalid-response",
    "invalid-detail-template",
    "detail-validation-failure",
    "response-too-large",
    "non-retryable-status",
    "rate-limited",
    "upstream-error",
    "row-timeout",
    "unexpected-row-failure",
    "unavailable",
})
MAIMAI_UPSTREAM_FAILURE_REASONS = frozenset({"rate-limited", "upstream-error"})
MAX_JUDGMENT_COUNT = 10_000_000
MAX_SOURCE_RECORD_ID_LENGTH = 256
MAIMAI_DETAIL_PAGE_URL = (
    "https://maimai.wahlap.com/maimai-mobile/record/playlogDetail/"
)
CHUNITHM_DETAIL_SUBMIT_PATH = "/mobile/record/playlog/sendPlaylogDetail"
SCORE_PAGE_TEMPLATE = (
    "https://maimai.wahlap.com/maimai-mobile/record/"
    "musicGenre/search/?genre=99&diff={difficulty}"
)
RECORD_PAGE_URL = "https://maimai.wahlap.com/maimai-mobile/record/"
OAUTH_AUTHORIZE_URLS = {
    game: f"https://{CALLBACK_HOST}/wc_auth/oauth/authorize/{slug}"
    for game, slug in (("maimai", "maimai-dx"), ("chunithm", "chunithm"))
}
CHUNITHM_PAGE_URLS = (
    "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailBest/",
    "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailRecent/",
    "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailNext/",
    "https://chunithm.wahlap.com/mobile/record/playlog/",
)
OFFICIAL_PAGE_ROOTS = {
    "maimai": ("maimai.wahlap.com", "/maimai-mobile/"),
    "chunithm": ("chunithm.wahlap.com", "/mobile/"),
}
WECHAT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/81.0.4044.138 Safari/537.36 "
    "NetType/WIFI MicroMessenger/7.0.20.1781(0x6700143B) "
    "WindowsWechat(0x6307001e)"
)
CHUNITHM_RANKS = {
    0: "d", 1: "c", 2: "b", 3: "bb", 4: "bbb", 5: "a", 6: "aa",
    7: "aaa", 8: "s", 9: "sp", 10: "ss", 11: "ssp", 12: "sss",
    13: "sssp",
}
COMBO_STATUSES = frozenset({"fc", "fcp", "ap", "app"})
SYNC_STATUSES = frozenset({"sync", "fs", "fsp", "fsd", "fsdp"})
RANK_STATUSES = frozenset(
    {
        "d", "c", "b", "bb", "bbb", "a", "aa", "aaa",
        "s", "sp", "ss", "ssp", "sss", "sssp",
    }
)
MAIMAI_NOTE_TYPES = ("tap", "hold", "slide", "touch", "break")
MAIMAI_JUDGMENTS = (
    "criticalPerfect", "perfect", "great", "good", "miss",
)
MAIMAI_RATING_FRAMES = frozenset({
    "white", "blue", "green", "yellow", "red", "purple",
    "bronze", "silver", "gold", "platinum", "rainbow",
})
CHUNITHM_JUDGMENTS = (
    "justiceCritical", "justice", "attack", "miss",
)
CHUNITHM_NOTE_TYPES = ("tap", "hold", "slide", "air", "flick")
COOKIE_NAME_PATTERN = re.compile(r"[!#$%&'*+.^_`|~0-9A-Za-z-]{1,256}")
COOKIE_VALUE_PATTERN = re.compile(
    r"[\x21\x23-\x2B\x2D-\x3A\x3C-\x5B\x5D-\x7E]{1,8192}"
)


RawScoreFetcher = Callable[[Any], Awaitable[tuple[list[Any], list[Any]]]]
RawChunithmFetcher = Callable[
    [dict[str, str]], Awaitable[tuple[list[str], str, list[str | None]]]
]
ProgressCallback = Callable[["SyncProgress"], Awaitable[None]]

LOGGER = logging.getLogger("maimai_wechat_helper")


class CapabilityError(RuntimeError):
    """Installed maimai.py does not expose the audited 1.5.1 API."""


class AdapterError(RuntimeError):
    """A safe summary of an authorization or normalization failure."""


@dataclass(frozen=True, slots=True)
class SyncProgress:
    """Secret-free progress for the bounded official play-detail window."""

    game: str
    stage: str
    completed: int
    total: int
    succeeded: int
    skipped: int | None = None
    failure_reasons: tuple[tuple[str, int], ...] | Mapping[str, int] = ()

    def __post_init__(self) -> None:
        try:
            normalized_game = validate_game(self.game)
        except ValueError as error:
            raise ValueError("synchronization progress game is invalid") from error
        if normalized_game != self.game:
            raise ValueError("synchronization progress game is invalid")
        if self.stage != "play_details":
            raise ValueError("synchronization progress stage is invalid")
        for name in ("completed", "total", "succeeded"):
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int):
                raise ValueError(f"synchronization progress {name} is invalid")
        if self.total < 1 or self.total > MAX_DETAIL_PAGES:
            raise ValueError("synchronization progress total is invalid")
        if self.completed < 0 or self.completed > self.total:
            raise ValueError("synchronization progress completed is invalid")
        if self.succeeded < 0 or self.succeeded > self.completed:
            raise ValueError("synchronization progress succeeded is invalid")
        skipped = self.completed - self.succeeded if self.skipped is None else self.skipped
        if isinstance(skipped, bool) or not isinstance(skipped, int):
            raise ValueError("synchronization progress skipped is invalid")
        if skipped < 0 or skipped != self.completed - self.succeeded:
            raise ValueError("synchronization progress skipped is invalid")
        raw_reasons = (
            tuple(self.failure_reasons.items())
            if isinstance(self.failure_reasons, Mapping)
            else self.failure_reasons
        )
        if not isinstance(raw_reasons, tuple):
            raise ValueError("synchronization progress failure reasons are invalid")
        if skipped and not raw_reasons:
            raw_reasons = (("unavailable", skipped),)
        reasons: dict[str, int] = {}
        for item in raw_reasons:
            if not isinstance(item, tuple) or len(item) != 2:
                raise ValueError("synchronization progress failure reasons are invalid")
            reason, count = item
            if (
                not isinstance(reason, str)
                or reason not in DETAIL_FAILURE_REASONS
                or reason in reasons
            ):
                raise ValueError("synchronization progress failure reason is invalid")
            if isinstance(count, bool) or not isinstance(count, int) or count < 1:
                raise ValueError("synchronization progress failure count is invalid")
            reasons[reason] = count
        if sum(reasons.values()) != skipped:
            raise ValueError("synchronization progress failure counts are invalid")
        object.__setattr__(self, "skipped", skipped)
        object.__setattr__(self, "failure_reasons", tuple(sorted(reasons.items())))


@dataclass(frozen=True, slots=True)
class CallbackParameters:
    r: str = field(repr=False)
    t: str = field(repr=False)
    code: str = field(repr=False)
    state: str = field(repr=False)


@dataclass(frozen=True, slots=True)
class _Bindings:
    client_type: type
    provider_type: type
    song_type: type
    current_version: Any


@dataclass(frozen=True, slots=True)
class _OfficialMaimaiRecord:
    raw: Any
    title: str
    source_record_id: str | None
    judgment_details: dict[str, Any] | None = None
    play_details: dict[str, Any] | None = None

    def __getattr__(self, name: str) -> Any:
        return getattr(self.raw, name)


@dataclass(frozen=True, slots=True)
class _AuthorizedIdentifier:
    """Minimal maimai-py-compatible identifier with non-printable cookies."""

    credentials: dict[str, str] = field(repr=False)


def load_bindings() -> _Bindings:
    try:
        installed = metadata.version("maimai-py")
    except metadata.PackageNotFoundError as error:
        raise CapabilityError("maimai-py is not installed") from error
    if installed != EXPECTED_MAIMAI_PY_VERSION:
        raise CapabilityError(
            f"maimai-py {EXPECTED_MAIMAI_PY_VERSION} is required; found {installed}"
        )
    try:
        from maimai_py import MaimaiClient, WechatProvider
        from maimai_py.enums import SongType, current_version
        from maimai_py.utils.page_parser import get_data_from_record_div
        from lxml import html as lxml_html
    except (ImportError, AttributeError) as error:
        raise CapabilityError("maimai-py 1.5.1 imports are unavailable") from error

    _require_async_method(MaimaiClient, "wechat", {"r", "t", "code", "state"})
    _require_async_method(MaimaiClient, "scores", {"identifier", "provider"})
    _require_async_method(MaimaiClient, "records", {"identifier", "provider"})
    _require_async_method(MaimaiClient, "songs", {"alias_provider"})
    if not hasattr(current_version, "value") or not isinstance(current_version.value, int):
        raise CapabilityError("maimai-py current_version is incompatible")
    for member in ("STANDARD", "DX", "UTAGE"):
        if not hasattr(SongType, member):
            raise CapabilityError("maimai-py SongType is incompatible")
    if not callable(get_data_from_record_div) or not callable(lxml_html.fromstring):
        raise CapabilityError("required official HTML parsers are unavailable")
    return _Bindings(MaimaiClient, WechatProvider, SongType, current_version)


def _require_async_method(owner: type, name: str, parameters: set[str]) -> None:
    method = getattr(owner, name, None)
    if method is None or not inspect.iscoroutinefunction(method):
        raise CapabilityError(f"maimai-py MaimaiClient.{name} is unavailable")
    names = set(inspect.signature(method).parameters)
    if not parameters.issubset(names):
        raise CapabilityError(f"maimai-py MaimaiClient.{name} has an incompatible signature")


class MaimaiPyAdapter:
    def __init__(
        self,
        config: Config,
        bindings: _Bindings | None = None,
        client: Any | None = None,
        provider: Any | None = None,
        catalog_path: Path | None = None,
        raw_score_fetcher: RawScoreFetcher | None = None,
        chunithm_catalog_path: Path | None = None,
        raw_chunithm_fetcher: RawChunithmFetcher | None = None,
    ) -> None:
        self._config = config
        self._bindings = bindings or load_bindings()
        self._client = client or self._bindings.client_type(
            timeout=config.upstream_timeout_seconds,
            trust_env=False,
        )
        self._provider = provider or self._bindings.provider_type()
        self._catalog_path = catalog_path or (
            Path(__file__).resolve().parents[2] / "cache" / "maimai-catalog.json"
        )
        self._uses_default_raw_score_fetcher = raw_score_fetcher is None
        self._raw_score_fetcher = raw_score_fetcher or self._fetch_official_html
        self._chunithm_catalog_path = chunithm_catalog_path or (
            Path(__file__).resolve().parents[2]
            / "web"
            / "chunithm-catalog"
            / "diving-fish-music-data.json"
        )
        self._uses_default_raw_chunithm_fetcher = raw_chunithm_fetcher is None
        self._raw_chunithm_fetcher = (
            raw_chunithm_fetcher or self._fetch_chunithm_html
        )
        self._sync_deadline_seconds = min(
            MAX_SYNC_DEADLINE_SECONDS,
            max(1.0, float(config.session_ttl_seconds) - SYNC_DEADLINE_MARGIN_SECONDS),
        )

    async def begin_oauth(self, game: str = "maimai") -> OAuthBinding:
        try:
            game = validate_game(game)
        except ValueError as error:
            raise AdapterError("unsupported synchronization game") from error
        try:
            if game == "maimai":
                value = await self._client.wechat()
            else:
                value = await self._fetch_oauth_url(game)
        except AdapterError:
            raise
        except Exception:
            raise AdapterError(
                "unable to obtain a WeChat authorization URL"
            ) from None
        if not isinstance(value, str):
            raise AdapterError("authorization service returned an invalid URL")
        return _validate_oauth_url(value, game)

    async def fetch_import_payload(
        self,
        callback: CallbackParameters,
        session_id: str,
        game: str = "maimai",
        progress_callback: ProgressCallback | None = None,
        callback_path: str | None = None,
    ) -> dict[str, Any]:
        try:
            game = validate_game(game)
        except ValueError as error:
            raise AdapterError("unsupported synchronization game") from error
        if progress_callback is not None and not callable(progress_callback):
            raise AdapterError("synchronization progress callback is invalid")
        try:
            async with asyncio.timeout(self._sync_deadline_seconds):
                return await self._fetch_import_payload(
                    callback,
                    session_id,
                    game,
                    progress_callback,
                    callback_path,
                )
        except TimeoutError:
            raise AdapterError("WeChat score fetch exceeded the safe deadline") from None

    async def _fetch_import_payload(
        self,
        callback: CallbackParameters,
        session_id: str,
        game: str,
        progress_callback: ProgressCallback | None,
        callback_path: str | None,
    ) -> dict[str, Any]:
        if game == "chunithm":
            return await self._fetch_chunithm_payload(
                callback,
                session_id,
                progress_callback,
                callback_path,
            )
        identifier = None
        credentials = None
        try:
            credentials = (
                await self._exchange_maimai_callback(callback)
                if callback_path is None
                else await self._exchange_maimai_callback(
                    callback,
                    callback_path,
                )
            )
            identifier = _AuthorizedIdentifier(credentials)
            if self._catalog_path.is_file():
                catalogue = MaimaiCatalog.load(self._catalog_path)
                if self._uses_default_raw_score_fetcher:
                    raw_scores, raw_records = await self._fetch_official_html(
                        identifier,
                        progress_callback,
                    )
                else:
                    raw_scores, raw_records = await self._raw_score_fetcher(identifier)
                charts = self._normalize_html_charts(raw_scores, catalogue)
                records = self._normalize_html_records(raw_records, catalogue)
            else:
                # Compatibility fallback for installations that have not run
                # the new catalogue synchronizer yet.  Once the shared cache
                # exists, raw HTML parsing avoids maimai-py's title-drop bug.
                songs = await self._client.songs(alias_provider=None)
                score_collection = await self._client.scores(identifier, self._provider)
                recent_records = await self._client.records(identifier, self._provider)
                charts = self._normalize_charts(score_collection)
                records = await self._normalize_records(recent_records, songs)
        except AdapterError:
            raise
        except CatalogError:
            raise AdapterError(
                "the synchronized maimai SongID catalogue is unavailable; "
                "run the root catalogue sync script"
            ) from None
        except Exception as error:
            raise AdapterError("maimai WeChat authorization or score fetch failed") from None
        finally:
            identifier = None
            credentials = None
        if not charts and not records:
            raise AdapterError(
                "maimai-py returned no matched scores; authorization or song matching may have failed"
            )
        return {
            "game": game,
            "sessionId": session_id,
            "charts": charts,
            "records": records,
        }

    async def _fetch_oauth_url(self, game: str) -> str:
        request = self._http_get()
        try:
            response = await request(OAUTH_AUTHORIZE_URLS[game])
        except RequestError:
            raise AdapterError("WeChat authorization service could not be reached") from None
        status = getattr(response, "status_code", None)
        location = _header_value(response, "location")
        if not isinstance(status, int) or status < 300 or status >= 400 or not location:
            raise AdapterError("WeChat authorization service returned an error")
        value = urljoin(OAUTH_AUTHORIZE_URLS[game], location)
        # Wahlap publishes an HTTPS callback in the redirect parameter. Only
        # the one callback request routed through Clash must use plain HTTP.
        return value.replace("redirect_uri=https", "redirect_uri=http", 1)

    async def _fetch_chunithm_payload(
        self,
        callback: CallbackParameters,
        session_id: str,
        progress_callback: ProgressCallback | None,
        callback_path: str | None,
    ) -> dict[str, Any]:
        credentials: dict[str, str] | None = None
        try:
            credentials = (
                await self._exchange_chunithm_callback(callback)
                if callback_path is None
                else await self._exchange_chunithm_callback(
                    callback,
                    callback_path,
                )
            )
            catalogue = ChunithmCatalog.load(self._chunithm_catalog_path)
            if self._uses_default_raw_chunithm_fetcher:
                rating_pages, playlog_page, detail_pages = (
                    await self._fetch_chunithm_html(
                        credentials,
                        progress_callback,
                    )
                )
            else:
                rating_pages, playlog_page, detail_pages = (
                    await self._raw_chunithm_fetcher(credentials)
                )
            charts = self._normalize_chunithm_rating_pages(rating_pages, catalogue)
            records = self._normalize_chunithm_playlog(
                playlog_page, detail_pages, catalogue
            )
        except AdapterError:
            raise
        except CatalogError:
            raise AdapterError(
                "the synchronized CHUNITHM SongID catalogue is unavailable"
            ) from None
        except Exception:
            raise AdapterError(
                "CHUNITHM WeChat authorization or score fetch failed"
            ) from None
        finally:
            credentials = None
        if not charts and not records:
            raise AdapterError("CHUNITHM WeChat returned no matched score data")
        return {
            "game": "chunithm",
            "sessionId": session_id,
            "charts": charts,
            "records": records,
        }

    async def _exchange_chunithm_callback(
        self,
        callback: CallbackParameters,
        callback_path: str | None = None,
    ) -> dict[str, str]:
        return await self._exchange_official_callback(
            callback,
            "chunithm",
            callback_path,
        )

    async def _exchange_maimai_callback(
        self,
        callback: CallbackParameters,
        callback_path: str | None = None,
    ) -> dict[str, str]:
        return await self._exchange_official_callback(
            callback,
            "maimai",
            callback_path,
        )

    async def _exchange_official_callback(
        self,
        callback: CallbackParameters,
        game: str,
        callback_path: str | None = None,
    ) -> dict[str, str]:
        request = self._http_get()
        try:
            audited_path = validate_callback_path(
                callback_path or CALLBACK_PATHS[game],
                game,
            )
        except ValueError as error:
            raise AdapterError("authorization callback path is invalid") from error
        callback_url = f"https://{CALLBACK_HOST}{audited_path}"
        label = "CHUNITHM" if game == "chunithm" else "maimai"
        headers = {
            "User-Agent": WECHAT_USER_AGENT,
            "Host": CALLBACK_HOST,
            "Upgrade-Insecure-Requests": "1",
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": "zh-CN,zh;q=0.9,en-US;q=0.8",
        }
        try:
            response = await request(
                callback_url,
                headers=headers,
                cookies=None,
                params=dataclasses.asdict(callback),
            )
        except RequestError:
            raise AdapterError(
                f"{label} authorization callback could not be reached"
            ) from None
        if getattr(response, "status_code", None) != 302:
            raise AdapterError(f"{label} authorization callback was rejected")
        location = _header_value(response, "location")
        if not location:
            next_request = getattr(response, "next_request", None)
            next_url = getattr(next_request, "url", None)
            location = str(next_url) if next_url is not None else None
        if not location:
            raise AdapterError(
                f"{label} authorization callback returned no destination"
            )
        destination = urljoin(callback_url, location)
        _validate_official_destination(destination, game)
        # Both games may set the usable session cookie on an intermediate
        # official redirect. Keep callback bridge cookies only inside this
        # validated chain, and return cookies set by the official game origin.
        transfer_cookies = Cookies(getattr(response, "cookies", None))
        official_cookies = Cookies()
        visited: set[str] = set()
        for redirect_count in range(MAX_OFFICIAL_REDIRECTS + 1):
            if destination in visited:
                raise AdapterError(
                    f"{label} authorized page entered a redirect loop"
                )
            visited.add(destination)
            try:
                next_response = await request(
                    destination,
                    headers={"User-Agent": WECHAT_USER_AGENT},
                    cookies=transfer_cookies,
                )
            except RequestError:
                raise AdapterError(
                    f"{label} authorized page could not be reached"
                ) from None

            response_cookies = getattr(next_response, "cookies", None)
            if response_cookies is not None:
                transfer_cookies.update(response_cookies)
                official_cookies.update(response_cookies)

            next_status = getattr(next_response, "status_code", None)
            if isinstance(next_status, int) and 200 <= next_status < 300:
                break
            if not isinstance(next_status, int) or not 300 <= next_status < 400:
                raise AdapterError(
                    f"{label} authorized page returned an error"
                )
            if redirect_count >= MAX_OFFICIAL_REDIRECTS:
                raise AdapterError(
                    f"{label} authorized page redirected too many times"
                )
            next_location = _header_value(next_response, "location")
            if not next_location:
                next_request = getattr(next_response, "next_request", None)
                next_url = getattr(next_request, "url", None)
                next_location = str(next_url) if next_url is not None else None
            if not next_location:
                raise AdapterError(
                    f"{label} authorized page returned no redirect destination"
                )
            destination = urljoin(destination, next_location)
            _validate_official_destination(destination, game)

        credentials = _cookie_dict(official_cookies)
        if not credentials:
            raise AdapterError(f"{label} authorization returned no cookies")
        return credentials

    async def _fetch_chunithm_html(
        self,
        credentials: dict[str, str],
        progress_callback: ProgressCallback | None = None,
    ) -> tuple[list[str], str, list[str | None]]:
        request = self._http_get()

        async def fetch(url: str, ordinal: int) -> str:
            if ordinal:
                await asyncio.sleep(ordinal * 0.05)
            for attempt in range(3):
                try:
                    response = await request(url, cookies=credentials)
                except RequestError:
                    if attempt == 2:
                        raise AdapterError(
                            "CHUNITHM score page could not be reached"
                        ) from None
                    await asyncio.sleep(0.15 * (attempt + 1))
                    continue
                status = getattr(response, "status_code", None)
                if status == 302:
                    raise AdapterError(
                        "CHUNITHM authorization expired while fetching scores"
                    )
                if status == 429 or isinstance(status, int) and status >= 500:
                    if attempt < 2:
                        await asyncio.sleep(0.15 * (attempt + 1))
                        continue
                if not isinstance(status, int) or status < 200 or status >= 300:
                    raise AdapterError("CHUNITHM score page returned an error")
                text = getattr(response, "text", None)
                if not isinstance(text, str) or len(text) > MAX_OFFICIAL_HTML_CHARS:
                    raise AdapterError("CHUNITHM score page is invalid or too large")
                return text
            raise AdapterError("CHUNITHM score page could not be reached")

        pages = await asyncio.gather(
            *(fetch(url, index) for index, url in enumerate(CHUNITHM_PAGE_URLS))
        )
        playlog_page = pages[3]
        detail_requests = [
            row.get("detailRequest")
            for row in _parse_chunithm_playlog_html(playlog_page)
        ]
        detail_pages = await self._fetch_chunithm_detail_pages(
            credentials,
            detail_requests,
            progress_callback,
        )
        return list(pages[:3]), playlog_page, detail_pages

    async def _fetch_chunithm_detail_pages(
        self,
        credentials: dict[str, str],
        detail_requests: list[Any],
        progress_callback: ProgressCallback | None = None,
    ) -> list[str | None]:
        if len(detail_requests) > MAX_DETAIL_PAGES:
            raise AdapterError("CHUNITHM returned too many play detail links")
        post = self._http_post()
        get = self._http_get()
        # The submit endpoint selects a volatile playlog row for the common
        # detail URL. Keep each POST -> redirect chain serialized so one row
        # cannot overwrite another row's selection in the same game session.
        async def fetch_detail(value: Any) -> str | None:
            if value is None:
                return None
            if not isinstance(value, dict) or set(value) != {
                "url", "idx", "token"
            }:
                return None
            url = value.get("url")
            idx = value.get("idx")
            token = value.get("token")
            if (
                not isinstance(url, str)
                or not isinstance(idx, str)
                or not isinstance(token, str)
            ):
                return None
            try:
                _validate_chunithm_detail_submission(url, idx, token)
            except (AdapterError, ValueError):
                return None
            for attempt in range(DETAIL_REQUEST_ATTEMPTS):
                try:
                    detail_cookies = Cookies(credentials)
                    response = await post(
                        url,
                        data={"idx": idx, "token": token},
                        cookies=detail_cookies,
                        headers={"Referer": CHUNITHM_PAGE_URLS[3]},
                    )
                    destination = url
                    for redirect_count in range(MAX_OFFICIAL_REDIRECTS + 1):
                        response_cookies = getattr(response, "cookies", None)
                        if response_cookies is not None:
                            detail_cookies.update(response_cookies)
                        status = getattr(response, "status_code", None)
                        if isinstance(status, int) and 200 <= status < 300:
                            text = getattr(response, "text", None)
                            if (
                                not isinstance(text, str)
                                or len(text) > MAX_OFFICIAL_HTML_CHARS
                            ):
                                return None
                            try:
                                _parse_chunithm_judgment_detail_html(text)
                            except AdapterError:
                                # The official site can transiently return an
                                # HTTP-200 error/login template. Retry instead
                                # of treating that placeholder as play data.
                                break
                            return text
                        if (
                            status == 429
                            or isinstance(status, int) and status >= 500
                        ):
                            break
                        if (
                            not isinstance(status, int)
                            or not 300 <= status < 400
                            or redirect_count >= MAX_OFFICIAL_REDIRECTS
                        ):
                            return None
                        location = _header_value(response, "location")
                        if not location:
                            return None
                        destination = urljoin(destination, location)
                        _validate_official_destination(
                            destination, "chunithm"
                        )
                        response = await get(
                            destination,
                            cookies=detail_cookies,
                            headers={"Referer": url},
                        )
                except (AdapterError, RequestError):
                    if attempt + 1 >= DETAIL_REQUEST_ATTEMPTS:
                        return None
                if attempt + 1 < DETAIL_REQUEST_ATTEMPTS:
                    await asyncio.sleep(
                        DETAIL_REQUEST_INTERVAL_SECONDS * (attempt + 1)
                    )
            return None

        pages: list[str | None] = []
        total = len(detail_requests)
        succeeded = 0
        failure_counts: dict[str, int] = {}
        await _publish_detail_progress(
            progress_callback,
            "chunithm",
            completed=0,
            total=total,
            succeeded=0,
        )
        for ordinal, value in enumerate(detail_requests):
            if ordinal:
                await asyncio.sleep(DETAIL_REQUEST_INTERVAL_SECONDS)
            page = await fetch_detail(value)
            pages.append(page)
            if page is not None:
                succeeded += 1
            else:
                failure_counts["unavailable"] = (
                    failure_counts.get("unavailable", 0) + 1
                )
            await _publish_detail_progress(
                progress_callback,
                "chunithm",
                completed=ordinal + 1,
                total=total,
                succeeded=succeeded,
                failure_reasons=failure_counts,
            )
        return pages

    def _normalize_chunithm_rating_pages(
        self,
        pages: list[str],
        catalogue: ChunithmCatalog,
    ) -> list[dict[str, Any]]:
        if not isinstance(pages, list) or len(pages) != 3:
            raise AdapterError("CHUNITHM rating pages are incomplete")
        charts: dict[tuple[str, str], dict[str, Any]] = {}
        unmatched = 0
        for page in pages:
            for row in _parse_chunithm_rating_html(page):
                match = catalogue.resolve_by_id(
                    row["songId"], row["title"], row["difficulty"]
                )
                if match is None:
                    unmatched += 1
                    continue
                candidate = {
                    "songId": match.song.song_id,
                    "title": match.song.title,
                    "difficulty": match.chart.difficulty,
                    "constant": match.chart.constant,
                    "score": row["score"],
                    "version": match.chart.version,
                }
                key = (match.song.song_id, match.chart.difficulty)
                previous = charts.get(key)
                if previous is None or int(candidate["score"]) > int(previous["score"]):
                    charts[key] = candidate
        if unmatched:
            raise AdapterError(
                f"{unmatched} CHUNITHM rating entries failed SongID cross-validation"
            )
        return list(charts.values())

    def _normalize_chunithm_playlog(
        self,
        page: str,
        detail_pages: list[str | None],
        catalogue: ChunithmCatalog,
    ) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        unmatched = 0
        rows = _parse_chunithm_playlog_html(page)
        if len(detail_pages) != len(rows):
            raise AdapterError("CHUNITHM play detail pages are incomplete")
        detail_requested = sum(
            row.get("detailRequest") is not None for row in rows
        )
        detail_fetched = sum(page is not None for page in detail_pages)
        detail_parsed = 0
        detail_unavailable = 0
        for row, detail_page in zip(rows, detail_pages, strict=True):
            match = catalogue.resolve(row["title"], row["difficulty"])
            if match is None:
                unmatched += 1
                continue
            judgment_details = None
            if detail_page is not None:
                try:
                    judgment_details = _parse_chunithm_judgment_detail_html(
                        detail_page
                    )
                    detail_parsed += 1
                except AdapterError:
                    detail_unavailable += 1
            else:
                detail_unavailable += 1
            records.append(
                {
                    "source": "chunithm-wechat",
                    # The official playlog idx is only a volatile 0..49 row
                    # position. It must never become persistent identity.
                    "sourceRecordId": None,
                    "songId": match.song.song_id,
                    "title": match.song.title,
                    "difficulty": match.chart.difficulty,
                    "score": row["score"],
                    "rank": row["rank"],
                    "clearStatus": row["clearStatus"],
                    "comboStatus": row["comboStatus"],
                    "playedAt": self._instant(row["playTime"]),
                    "track": row["track"],
                    "judgmentDetails": judgment_details,
                }
            )
        if unmatched:
            raise AdapterError(
                f"{unmatched} CHUNITHM recent plays could not be matched to a SongID"
            )
        if detail_unavailable:
            LOGGER.warning(
                "%d CHUNITHM play detail pages had an unrecognized or unavailable shape",
                detail_unavailable,
            )
        LOGGER.info(
            "CHUNITHM play details synchronized: requested=%d fetched=%d "
            "parsed=%d unavailable=%d",
            detail_requested,
            detail_fetched,
            detail_parsed,
            detail_unavailable,
        )
        return records

    def _http_get(self) -> Callable[..., Awaitable[Any]]:
        request = getattr(getattr(self._client, "_client", None), "get", None)
        if request is None or not callable(request):
            raise AdapterError("maimai-py HTTP client is unavailable")
        return request

    def _http_post(self) -> Callable[..., Awaitable[Any]]:
        request = getattr(getattr(self._client, "_client", None), "post", None)
        if request is None or not callable(request):
            raise AdapterError("maimai-py HTTP client has no POST support")
        return request

    async def _fetch_official_html(
        self,
        identifier: Any,
        progress_callback: ProgressCallback | None = None,
    ) -> tuple[list[Any], list[Any]]:
        """Fetch and parse official pages before any title matching occurs."""
        try:
            from maimai_py.utils import wmdx_html2score
            from maimai_py.utils.page_parser import get_data_from_record_div
        except (ImportError, AttributeError) as error:
            raise CapabilityError("maimai-py HTML parsers are unavailable") from error

        credentials = getattr(identifier, "credentials", None)
        if credentials is None:
            raise AdapterError("maimai WeChat authorization returned no cookies")
        http_client = getattr(self._client, "_client", None)
        request = getattr(http_client, "get", None)
        if request is None or not callable(request):
            raise AdapterError("maimai-py HTTP client is unavailable")

        async def fetch(url: str, ordinal: int) -> str:
            # Match the audited provider's three attempts while staggering the
            # six page requests over 250ms to avoid a burst at Wahlap.
            if ordinal:
                await asyncio.sleep(ordinal * 0.05)
            for attempt in range(3):
                try:
                    response = await request(url, cookies=credentials)
                except RequestError:
                    if attempt == 2:
                        raise AdapterError(
                            "maimai WeChat score page could not be reached"
                        ) from None
                    await asyncio.sleep(0.15 * (attempt + 1))
                    continue
                status = getattr(response, "status_code", None)
                if status == 302:
                    raise AdapterError(
                        "maimai WeChat authorization expired while fetching scores"
                    )
                if status == 429 or isinstance(status, int) and status >= 500:
                    if attempt < 2:
                        await asyncio.sleep(0.15 * (attempt + 1))
                        continue
                if not isinstance(status, int) or status < 200 or status >= 300:
                    raise AdapterError("maimai WeChat score page returned an error")
                text = getattr(response, "text", None)
                if not isinstance(text, str) or len(text) > MAX_OFFICIAL_HTML_CHARS:
                    raise AdapterError(
                        "maimai WeChat score page is invalid or too large"
                    )
                return text
            raise AdapterError("maimai WeChat score page could not be reached")

        urls = [
            *(SCORE_PAGE_TEMPLATE.format(difficulty=index) for index in range(5)),
            RECORD_PAGE_URL,
        ]
        pages = await asyncio.gather(
            *(fetch(url, index) for index, url in enumerate(urls)),
        )
        try:
            scores: list[Any] = []
            for page in pages[:5]:
                scores.extend(wmdx_html2score(page))
            records = _parse_maimai_record_html(
                pages[5], get_data_from_record_div
            )
        except Exception:
            raise AdapterError("maimai WeChat returned an unrecognized score page") from None
        if len(scores) > MAX_CHARTS or len(records) > MAX_RECORDS:
            raise AdapterError("maimai WeChat returned too many score entries")
        def validate_detail_page(text: str, ordinal: int) -> bool:
            record = records[ordinal]
            _judgments, details = _parse_maimai_play_detail_html(text)
            detail_dx_score = (
                details.get("dxScore", {}).get("current")
                if isinstance(details, dict)
                and isinstance(details.get("dxScore"), dict)
                else None
            )
            if detail_dx_score is not None and detail_dx_score != _dx_score(record):
                raise AdapterError(
                    "maimai play detail does not match its recent record"
                )
            return True

        detail_pages = await self._fetch_maimai_detail_pages(
            credentials,
            [record.source_record_id for record in records],
            validate_detail_page,
            progress_callback,
        )
        detail_requested = sum(
            record.source_record_id is not None for record in records
        )
        detail_fetched = sum(page is not None for page in detail_pages)
        detail_parsed = 0
        detail_unavailable = 0
        enriched_records: list[_OfficialMaimaiRecord] = []
        for record, detail_page in zip(records, detail_pages, strict=True):
            if detail_page is None:
                detail_unavailable += 1
                enriched_records.append(record)
                continue
            try:
                judgment_details, play_details = _parse_maimai_play_detail_html(
                    detail_page
                )
                detail_dx_score = (
                    play_details.get("dxScore", {}).get("current")
                    if isinstance(play_details, dict)
                    and isinstance(play_details.get("dxScore"), dict)
                    else None
                )
                if (
                    detail_dx_score is not None
                    and detail_dx_score != _dx_score(record)
                ):
                    raise AdapterError(
                        "maimai play detail does not match its recent record"
                    )
            except AdapterError:
                detail_unavailable += 1
                enriched_records.append(record)
                continue
            enriched_records.append(
                dataclasses.replace(
                    record,
                    judgment_details=judgment_details,
                    play_details=play_details,
                )
            )
            detail_parsed += 1
        if detail_unavailable:
            LOGGER.warning(
                "%d maimai play detail pages had an unrecognized or unavailable shape; "
                "base records remain importable",
                detail_unavailable,
            )
        LOGGER.info(
            "maimai play details synchronized: requested=%d fetched=%d "
            "parsed=%d unavailable=%d",
            detail_requested,
            detail_fetched,
            detail_parsed,
            detail_unavailable,
        )
        return scores, enriched_records

    async def _fetch_maimai_detail_pages(
        self,
        credentials: Any,
        source_record_ids: list[str | None],
        validator: Callable[[str, int], bool] | None = None,
        progress_callback: ProgressCallback | None = None,
    ) -> list[str | None]:
        if len(source_record_ids) > MAX_DETAIL_PAGES:
            raise AdapterError("maimai returned too many play detail links")
        request = self._http_get()
        detail_cookies = Cookies(credentials)
        detail_loop = asyncio.get_running_loop()
        detail_deadline = (
            detail_loop.time() + MAIMAI_DETAIL_STAGE_BUDGET_SECONDS
        )

        def has_detail_budget(required_seconds: float) -> bool:
            return detail_loop.time() + required_seconds < detail_deadline

        def merge_response_cookies(response: Any) -> None:
            try:
                response_cookies = getattr(response, "cookies", None)
                if response_cookies is None:
                    return
                incoming = Cookies(response_cookies)
                incoming_names = {cookie.name for cookie in incoming.jar}
                for name in incoming_names:
                    # OAuth credentials arrive as a flat mapping, while an
                    # official response cookie has a domain and path. Remove
                    # every older scope first so httpx cannot send both the
                    # stale and refreshed values on the next detail request.
                    detail_cookies.delete(name)
                detail_cookies.update(incoming)
            except Exception:
                # Cookie refresh is opportunistic. A malformed optional cookie
                # must not discard an otherwise valid official detail page.
                return

        async def fetch_detail(
            value: str | None,
            ordinal: int,
        ) -> tuple[str | None, str | None]:
            if value is None:
                return None, "missing-source-id"
            try:
                idx = validate_short_secret(value, "idx", 512)
            except ValueError:
                return None, "invalid-source-id"
            failure_reason = "request-failed"
            for attempt in range(DETAIL_REQUEST_ATTEMPTS):
                try:
                    async with asyncio.timeout(DETAIL_REQUEST_TIMEOUT_SECONDS):
                        response = await request(
                            MAIMAI_DETAIL_PAGE_URL,
                            params={"idx": idx},
                            cookies=detail_cookies,
                            headers={"Referer": RECORD_PAGE_URL},
                        )
                except TimeoutError:
                    # asyncio.timeout raises the built-in TimeoutError without
                    # exposing the request capability or response body.
                    response = None
                    failure_reason = "request-timeout"
                except RequestError:
                    response = None
                    failure_reason = "request-error"
                except Exception:
                    # Detail enrichment must be failure-isolated even when a
                    # transport raises something outside httpx.RequestError.
                    # Do not log the exception: messages may contain a URL or
                    # request metadata.  The controlled reason is sufficient.
                    response = None
                    failure_reason = "transport-failure"
                try:
                    status = getattr(response, "status_code", None)
                except Exception:
                    status = None
                    failure_reason = "invalid-response"
                if response is None:
                    # Timeout/transport failures are retryable.  Keeping this
                    # branch separate also avoids rewriting the useful
                    # controlled reason as a generic status failure.
                    pass
                else:
                    merge_response_cookies(response)
                    if isinstance(status, int) and 200 <= status < 300:
                        try:
                            text = getattr(response, "text", None)
                        except Exception:
                            text = None
                            failure_reason = "invalid-response"
                        if (
                            isinstance(text, str)
                            and len(text) <= MAX_OFFICIAL_HTML_CHARS
                        ):
                            valid = validator is None
                            if validator is not None:
                                try:
                                    valid = validator(text, ordinal)
                                except AdapterError:
                                    valid = False
                                    failure_reason = "invalid-detail-template"
                                except Exception:
                                    # Parser/validator bugs affect only this
                                    # optional page. Their messages are neither
                                    # persisted nor logged.
                                    valid = False
                                    failure_reason = "detail-validation-failure"
                            if valid:
                                return text, None
                            if failure_reason == "request-failed":
                                failure_reason = "invalid-detail-template"
                        elif not isinstance(text, str):
                            failure_reason = "invalid-response"
                        else:
                            failure_reason = "response-too-large"
                        # Wahlap can answer HTTP 200 with a temporary/non-detail
                        # template. Treat that shape like a transient failure.
                    elif (
                        status != 429
                        and not (isinstance(status, int) and status >= 500)
                    ):
                        return None, "non-retryable-status"
                    else:
                        failure_reason = (
                            "rate-limited"
                            if status == 429
                            else "upstream-error"
                        )
                        # A shared recovery gate handles official throttling
                        # and 5xx responses. Per-row retries would otherwise
                        # multiply one failure across the remaining window.
                        return None, failure_reason
                if attempt + 1 < DETAIL_REQUEST_ATTEMPTS:
                    await asyncio.sleep(0.3 * (attempt + 1))
            return None, failure_reason

        async def refresh_detail_session() -> bool:
            try:
                async with asyncio.timeout(DETAIL_REQUEST_TIMEOUT_SECONDS):
                    response = await request(
                        RECORD_PAGE_URL,
                        cookies=detail_cookies,
                        headers={"Referer": RECORD_PAGE_URL},
                    )
            except (TimeoutError, RequestError):
                return False
            except Exception:
                return False
            merge_response_cookies(response)
            try:
                status = getattr(response, "status_code", None)
            except Exception:
                return False
            return isinstance(status, int) and 200 <= status < 300

        async def fetch_row(
            value: str | None,
            ordinal: int,
        ) -> tuple[str | None, str | None]:
            try:
                async with asyncio.timeout(DETAIL_ROW_TIMEOUT_SECONDS):
                    return await fetch_detail(value, ordinal)
            except TimeoutError:
                return None, "row-timeout"
            except Exception:
                # Last-resort row isolation. Cancellation is intentionally not
                # swallowed so the session-wide deadline remains authoritative.
                return None, "unexpected-row-failure"

        async def fetch_with_upstream_recovery(
            value: str | None,
            ordinal: int,
        ) -> tuple[str | None, str | None]:
            page, failure_reason = await fetch_row(value, ordinal)
            if failure_reason not in MAIMAI_UPSTREAM_FAILURE_REASONS:
                return page, failure_reason
            for cooldown in MAIMAI_DETAIL_UPSTREAM_COOLDOWNS_SECONDS:
                recovery_budget = (
                    cooldown
                    + DETAIL_REQUEST_TIMEOUT_SECONDS
                    + DETAIL_ROW_TIMEOUT_SECONDS
                )
                if not has_detail_budget(recovery_budget):
                    LOGGER.warning(
                        "maimai play detail recovery stopped at its soft deadline"
                    )
                    return None, failure_reason
                LOGGER.warning(
                    "maimai play detail upstream paused for %.0fs before retry",
                    cooldown,
                )
                await asyncio.sleep(cooldown)
                if not await refresh_detail_session():
                    continue
                page, failure_reason = await fetch_row(value, ordinal)
                if failure_reason not in MAIMAI_UPSTREAM_FAILURE_REASONS:
                    return page, failure_reason
            return None, failure_reason

        # The official detail endpoint starts returning 5xx after a short
        # burst. Keep requests strictly serial and paced; when the shared
        # recovery gate cannot restore the session, stop sending detail GETs
        # while retaining every base play record for import.
        total = len(source_record_ids)
        pages: list[str | None] = []
        succeeded = 0
        failure_counts: dict[str, int] = {}
        blocked_reason: str | None = None
        upstream_probe_required = False
        await _publish_detail_progress(
            progress_callback,
            "maimai",
            completed=0,
            total=total,
            succeeded=0,
        )
        for ordinal, value in enumerate(source_record_ids):
            if blocked_reason is None:
                interval = (
                    MAIMAI_DETAIL_REQUEST_INTERVAL_SECONDS if ordinal else 0.0
                )
                if not has_detail_budget(interval + DETAIL_ROW_TIMEOUT_SECONDS):
                    blocked_reason = "unavailable"
                    LOGGER.warning(
                        "maimai play detail soft deadline reached; "
                        "remaining detail requests were suppressed"
                    )
                    page, failure_reason = None, blocked_reason
                else:
                    if interval:
                        await asyncio.sleep(interval)
                    is_upstream_probe = upstream_probe_required
                    if is_upstream_probe:
                        page, failure_reason = await fetch_row(value, ordinal)
                    else:
                        page, failure_reason = await fetch_with_upstream_recovery(
                            value, ordinal
                        )
                    if failure_reason in MAIMAI_UPSTREAM_FAILURE_REASONS:
                        if is_upstream_probe:
                            blocked_reason = failure_reason
                            LOGGER.warning(
                                "maimai play detail upstream failed for two "
                                "distinct records; remaining requests were "
                                "suppressed"
                            )
                        else:
                            upstream_probe_required = True
                            LOGGER.warning(
                                "one maimai play detail remained unavailable; "
                                "the next record will be used as a probe"
                            )
                    else:
                        upstream_probe_required = False
            else:
                page, failure_reason = None, blocked_reason
            pages.append(page)
            if page is not None:
                succeeded += 1
            else:
                reason = failure_reason or "unavailable"
                failure_counts[reason] = failure_counts.get(reason, 0) + 1
                LOGGER.warning(
                    "maimai play detail %d/%d unavailable (reason=%s)",
                    ordinal + 1,
                    total,
                    reason,
                )
            await _publish_detail_progress(
                progress_callback,
                "maimai",
                completed=ordinal + 1,
                total=total,
                succeeded=succeeded,
                failure_reasons=failure_counts,
            )
        if failure_counts:
            summary = ",".join(
                f"{reason}={failure_counts[reason]}"
                for reason in sorted(failure_counts)
            )
            LOGGER.warning(
                "maimai play detail fetch completed with unavailable rows: "
                "requested=%d succeeded=%d unavailable=%d reasons=%s",
                total,
                succeeded,
                total - succeeded,
                summary,
            )
        return pages

    def _normalize_html_charts(
        self,
        raw_scores: list[Any],
        catalogue: MaimaiCatalog,
    ) -> list[dict[str, Any]]:
        if not isinstance(raw_scores, list) or len(raw_scores) > MAX_CHARTS:
            raise AdapterError("maimai WeChat returned invalid score entries")
        charts: dict[tuple[str, str, str], dict[str, Any]] = {}
        unmatched: list[str] = []
        for score in raw_scores:
            if getattr(score, "level_index", None) == -1:
                # Banquet/UTAGE charts are outside the B50 catalogue.
                continue
            title = _text_attribute(score, "title", 300)
            chart_type = _html_chart_type(score)
            difficulty = _html_difficulty(score)
            match = catalogue.resolve(title, chart_type, difficulty)
            if match is None:
                unmatched.append(title)
                continue
            candidate = {
                "songId": match.song.song_id,
                "title": match.song.title,
                "chartType": match.chart.chart_type,
                "difficulty": match.chart.difficulty,
                "level": match.chart.level_value,
                "achievement": _achievement(score),
                "version": match.chart.version,
                "comboStatus": _html_optional_status(score, "fc", COMBO_STATUSES),
                "syncStatus": _html_optional_status(score, "fs", SYNC_STATUSES),
            }
            key = (
                match.song.song_id,
                match.chart.chart_type,
                match.chart.difficulty,
            )
            previous = charts.get(key)
            charts[key] = _stronger_html_chart(previous, candidate)
        if unmatched:
            raise AdapterError(
                f"{len(unmatched)} official scores could not be matched to a SongID; "
                "synchronize the song catalogue and retry"
            )
        return list(charts.values())

    def _normalize_html_records(
        self,
        raw_records: list[Any],
        catalogue: MaimaiCatalog,
    ) -> list[dict[str, Any]]:
        if not isinstance(raw_records, list) or len(raw_records) > MAX_RECORDS:
            raise AdapterError("maimai WeChat returned invalid recent records")
        records: list[dict[str, Any]] = []
        unmatched: list[str] = []
        for score in raw_records:
            if getattr(score, "level_index", None) == -1:
                # The public B50 catalogue intentionally excludes UTAGE.
                continue
            title = _text_attribute(score, "title", 300)
            chart_type = _html_chart_type(score)
            difficulty = _html_difficulty(score)
            match = catalogue.resolve(title, chart_type, difficulty)
            if match is None:
                unmatched.append(title)
                continue
            play_time = getattr(score, "play_time", None)
            if not isinstance(play_time, datetime):
                raise AdapterError("a recent record has no reliable play time")
            records.append(
                {
                    "source": "maimai-wechat",
                    "sourceRecordId": _source_record_id(score),
                    "songId": match.song.song_id,
                    "title": match.song.title,
                    "chartType": match.chart.chart_type,
                    "difficulty": match.chart.difficulty,
                    "achievement": _achievement(score),
                    "dxScore": _dx_score(score),
                    "rank": _html_optional_status(score, "rate", RANK_STATUSES),
                    "comboStatus": _html_optional_status(
                        score, "fc", COMBO_STATUSES
                    ),
                    "syncStatus": _html_optional_status(
                        score, "fs", SYNC_STATUSES
                    ),
                    "playedAt": self._instant(play_time),
                    "judgmentDetails": getattr(
                        score, "judgment_details", None
                    ),
                    "playDetails": getattr(score, "play_details", None),
                }
            )
        if unmatched:
            raise AdapterError(
                f"{len(unmatched)} recent plays could not be matched to a SongID; "
                "synchronize the song catalogue and retry"
            )
        return records

    async def close(self) -> None:
        inner_client = getattr(self._client, "_client", None)
        close_method = getattr(inner_client, "aclose", None)
        if close_method is not None and inspect.iscoroutinefunction(close_method):
            await close_method()

    def _normalize_charts(self, score_collection: Any) -> list[dict[str, Any]]:
        raw_scores = getattr(score_collection, "scores", None)
        if not isinstance(raw_scores, list):
            raise AdapterError("maimai-py scores() returned an incompatible object")
        if len(raw_scores) > MAX_CHARTS:
            raise AdapterError("maimai.py returned too many score charts")
        charts: dict[tuple[str, str, str], dict[str, Any]] = {}
        supported = {
            self._bindings.song_type.STANDARD,
            self._bindings.song_type.DX,
        }
        for score in raw_scores:
            if getattr(score, "type", None) not in supported:
                continue
            achievement = _achievement(score)
            level_value = _finite_number(score, "level_value")
            version_value = _integer_attribute(score, "version")
            candidate = {
                "songId": str(_integer_attribute(score, "id") % 10_000),
                "title": _text_attribute(score, "title", 300),
                "chartType": _enum_value(score, "type"),
                "difficulty": _difficulty(score),
                "level": level_value,
                "achievement": achievement,
                "version": (
                    "current"
                    if version_value >= self._bindings.current_version.value
                    else "legacy"
                ),
                "comboStatus": _optional_enum_name(score, "fc"),
                "syncStatus": _optional_enum_name(score, "fs"),
            }
            key = (
                candidate["songId"],
                candidate["chartType"],
                candidate["difficulty"],
            )
            charts[key] = _stronger_html_chart(charts.get(key), candidate)
        return list(charts.values())

    async def _normalize_records(
        self,
        recent_records: Any,
        songs: Any,
    ) -> list[dict[str, Any]]:
        if not isinstance(recent_records, list):
            raise AdapterError("maimai-py records() returned an incompatible object")
        if len(recent_records) > MAX_RECORDS:
            raise AdapterError("maimai.py returned too many recent records")
        records: list[dict[str, Any]] = []
        for score in recent_records:
            song_id = _integer_attribute(score, "id") % 10_000
            song = await songs.by_id(song_id)
            if song is None:
                raise AdapterError("a recent record could not be matched to the maimai song catalog")
            play_time = getattr(score, "play_time", None)
            if not isinstance(play_time, datetime):
                raise AdapterError("a recent record has no reliable play time")
            records.append(
                {
                    "source": "maimai-wechat",
                    # maimai-py 1.5.1 discards the official hidden idx.
                    "sourceRecordId": None,
                    "songId": str(song_id),
                    "title": _text_attribute(song, "title", 300),
                    "chartType": _enum_value(score, "type"),
                    "difficulty": (
                        "UTAGE"
                        if getattr(score, "type", None) == self._bindings.song_type.UTAGE
                        else _difficulty(score)
                    ),
                    "achievement": _achievement(score),
                    "dxScore": _dx_score(score),
                    "rank": _required_enum_name(score, "rate"),
                    "comboStatus": _optional_enum_name(score, "fc"),
                    "syncStatus": _optional_enum_name(score, "fs"),
                    "playedAt": self._instant(play_time),
                }
            )
        return records

    def _instant(self, value: datetime) -> str:
        if value.tzinfo is None:
            value = value.replace(
                tzinfo=timezone(timedelta(minutes=self._config.play_utc_offset_minutes))
            )
        utc_value = value.astimezone(timezone.utc).replace(microsecond=0)
        return utc_value.isoformat().replace("+00:00", "Z")


async def _publish_detail_progress(
    callback: ProgressCallback | None,
    game: str,
    *,
    completed: int,
    total: int,
    succeeded: int,
    failure_reasons: Mapping[str, int] | None = None,
) -> None:
    if callback is None or total == 0:
        return
    progress = SyncProgress(
        game=game,
        stage="play_details",
        completed=completed,
        total=total,
        succeeded=succeeded,
        skipped=completed - succeeded,
        failure_reasons=failure_reasons or (),
    )
    result = callback(progress)
    if not inspect.isawaitable(result):
        raise AdapterError("synchronization progress callback is invalid")
    await result


def _parse_maimai_record_html(
    value: str,
    row_parser: Callable[[Any], Any],
) -> list[Any]:
    root = _html_root(value, "maimai recent record page")
    rows = root.xpath(
        "//div[contains(concat(' ', normalize-space(@class), ' '), ' t_l ') "
        "and contains(concat(' ', normalize-space(@class), ' '), ' v_b ') "
        "and contains(concat(' ', normalize-space(@class), ' '), ' p_10 ') "
        "and contains(concat(' ', normalize-space(@class), ' '), ' f_0 ')]"
    )
    if len(rows) > MAX_RECORDS:
        raise AdapterError("maimai WeChat returned too many recent records")
    records: list[Any] = []
    for row in rows:
        try:
            score = row_parser(row)
        except Exception:
            raise AdapterError("maimai WeChat returned an unrecognized record") from None
        if score is None:
            continue
        title_nodes = row.xpath(
            ".//div[contains(concat(' ', normalize-space(@class), ' '), "
            "' basic_block ') and contains(concat(' ', normalize-space(@class), "
            "' '), ' break ')]"
        )
        direct_parts = (
            [
                str(part).strip()
                for part in title_nodes[0].xpath("./text()")
                if str(part).strip()
            ]
            if len(title_nodes) == 1
            else []
        )
        if len(direct_parts) != 1:
            raise AdapterError("maimai WeChat recent record has an invalid title")
        direct_title = direct_parts[0]
        source_record_id = _maimai_record_source_id(row)
        records.append(
            _OfficialMaimaiRecord(score, direct_title, source_record_id)
        )
    return records


def _maimai_record_source_id(row: Any) -> str | None:
    ancestor_forms = list(row.xpath("ancestor::form"))
    descendant_forms = list(row.xpath(".//form[.//input[@name='idx']]"))
    if ancestor_forms:
        # Nested or overlapping form ownership is ambiguous. Do not let one
        # record borrow another form's detail-page capability.
        if len(ancestor_forms) != 1 or descendant_forms:
            return None
        form = ancestor_forms[0]
        if not _is_trusted_maimai_detail_form(form):
            return None
        inputs = list(form.xpath(".//input[@name='idx']"))
    elif descendant_forms:
        if len(descendant_forms) != 1:
            return None
        form = descendant_forms[0]
        if not _is_trusted_maimai_detail_form(form):
            return None
        # Include legacy row-local inputs outside the descendant form so a
        # conflicting pair is rejected rather than silently selecting one.
        inputs = list(row.xpath(".//input[@name='idx']"))
    else:
        # Older templates placed idx directly inside the record container and
        # did not expose a form. Preserve that audited shape.
        inputs = list(row.xpath(".//input[@name='idx']"))
    if len(inputs) != 1:
        return None
    candidate = str(inputs[0].get("value", "")).strip()
    try:
        return validate_short_secret(
            candidate, "idx", MAX_SOURCE_RECORD_ID_LENGTH
        )
    except ValueError:
        # The idx is optional metadata and a detail-page capability, not the
        # play's identity. Keep the base row importable when it is unsafe.
        return None


def _is_trusted_maimai_detail_form(form: Any) -> bool:
    action = str(form.get("action", "")).strip()
    if (
        not action
        or len(action) > MAX_OFFICIAL_ASSET_URL_LENGTH
        or any(
            ord(character) < 0x20 or ord(character) == 0x7F
            for character in action
        )
    ):
        return False
    try:
        parsed = urlsplit(urljoin(RECORD_PAGE_URL, action))
        port = parsed.port if parsed.port is not None else 443
    except (TypeError, ValueError):
        return False
    return (
        parsed.scheme == "https"
        and (parsed.hostname or "").lower() == "maimai.wahlap.com"
        and port == 443
        and parsed.path == urlsplit(MAIMAI_DETAIL_PAGE_URL).path
        and not parsed.query
        and not parsed.fragment
        and parsed.username is None
        and parsed.password is None
    )


def _parse_chunithm_rating_html(value: str) -> list[dict[str, Any]]:
    root = _html_root(value, "CHUNITHM rating page")
    forms = [
        form
        for form in root.xpath("//form[@action]")
        if urlsplit(str(form.get("action"))).path.rstrip("/").endswith(
            "/sendMusicDetail"
        )
    ]
    if len(forms) > MAX_CHARTS:
        raise AdapterError("CHUNITHM returned too many rating entries")
    result: list[dict[str, Any]] = []
    for form in forms:
        boxes = _class_nodes(form, "musiclist_box")
        if len(boxes) != 1:
            raise AdapterError("CHUNITHM rating entry has an invalid container")
        box = boxes[0]
        title = _one_node_text(box, "music_title", "CHUNITHM rating title")
        score_text = _one_node_text(
            box, "play_musicdata_highscore", "CHUNITHM rating score"
        )
        idx_values = form.xpath(".//input[@name='idx']/@value")
        if len(idx_values) != 1:
            raise AdapterError("CHUNITHM rating entry has an invalid SongID")
        song_id = str(idx_values[0]).strip()
        if re.fullmatch(r"[0-9]{1,12}", song_id) is None:
            raise AdapterError("CHUNITHM rating entry has an invalid SongID")
        difficulty = _difficulty_from_background(box)
        result.append(
            {
                "songId": song_id,
                "title": title,
                "difficulty": difficulty,
                "score": _chunithm_score(score_text),
            }
        )
    return result


def _parse_chunithm_playlog_html(value: str) -> list[dict[str, Any]]:
    root = _html_root(value, "CHUNITHM playlog page")
    rows = root.xpath(
        "//div[contains(concat(' ', normalize-space(@class), ' '), ' frame02 ') "
        "and contains(concat(' ', normalize-space(@class), ' '), ' w400 ')]"
    )
    if len(rows) > MAX_RECORDS:
        raise AdapterError("CHUNITHM returned too many recent plays")
    records: list[dict[str, Any]] = []
    for row in rows:
        title_nodes = _class_nodes(row, "play_musicdata_title")
        if not title_nodes:
            continue
        if len(title_nodes) != 1:
            raise AdapterError("CHUNITHM recent play has an invalid title")
        title = _node_text(title_nodes[0], "CHUNITHM recent play title")
        date_text = _one_node_text(
            row, "play_datalist_date", "CHUNITHM recent play time"
        )
        score_text = _one_node_text(
            row, "play_musicdata_score_text", "CHUNITHM recent play score"
        )
        result_nodes = _class_nodes(row, "play_track_result")
        if len(result_nodes) != 1:
            raise AdapterError("CHUNITHM recent play has no difficulty")
        difficulty_images = result_nodes[0].xpath(
            ".//img[contains(translate(@src, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ', "
            "'abcdefghijklmnopqrstuvwxyz'), 'musiclevel_')]/@src"
        )
        if len(difficulty_images) != 1:
            raise AdapterError("CHUNITHM recent play has an invalid difficulty")
        matched = re.search(
            r"musiclevel_(basic|advanced|expert|master|ultima)\.png(?:\?|$)",
            str(difficulty_images[0]).lower(),
        )
        if matched is None:
            raise AdapterError("CHUNITHM recent play has an invalid difficulty")
        difficulty = normalize_chunithm_difficulty(matched.group(1))

        rank: str | None = None
        clear_status: str | None = None
        combo_status: str | None = None
        icon_containers = _class_nodes(row, "play_musicdata_icon")
        icon_sources: list[str] = []
        for container in icon_containers:
            icon_sources.extend(str(src) for src in container.xpath(".//img/@src"))
        for source in icon_sources:
            basename = urlsplit(source).path.rsplit("/", 1)[-1].lower()
            rank_match = re.fullmatch(r"icon_rank_([0-9]{1,2})\.png", basename)
            if rank_match is not None:
                rank_value = CHUNITHM_RANKS.get(int(rank_match.group(1), 10))
                if rank_value is None or rank is not None and rank != rank_value:
                    raise AdapterError("CHUNITHM recent play has an invalid rank")
                rank = rank_value
            elif basename == "icon_clear.png":
                clear_status = "clear"
            elif basename == "icon_fullcombo.png":
                combo_status = "fc"
            elif basename == "icon_alljustice.png":
                combo_status = "aj"
            elif basename == "icon_alljusticecritical.png":
                combo_status = "ajc"

        track: int | None = None
        track_nodes = _class_nodes(row, "play_track_text")
        if len(track_nodes) == 1:
            candidates = {
                int(item, 10)
                for item in re.findall(r"(?<![0-9])([1-3])(?![0-9])", track_nodes[0].text_content())
            }
            if len(candidates) == 1:
                track = next(iter(candidates))

        detail_request: dict[str, str] | None = None
        detail_forms = []
        candidate_forms = [
            *row.xpath("ancestor::form[@action][1]"),
            *row.xpath(".//form[@action]"),
        ]
        seen_forms: set[int] = set()
        for form in candidate_forms:
            if id(form) in seen_forms:
                continue
            seen_forms.add(id(form))
            action = str(form.get("action", "")).strip()
            candidate_url = urljoin(CHUNITHM_PAGE_URLS[3], action)
            try:
                path = urlsplit(candidate_url).path.rstrip("/")
            except ValueError:
                continue
            if path == CHUNITHM_DETAIL_SUBMIT_PATH:
                detail_forms.append((form, candidate_url))
        if len(detail_forms) == 1:
            form, detail_url = detail_forms[0]
            idx_values = form.xpath(".//input[@name='idx']/@value")
            token_values = form.xpath(".//input[@name='token']/@value")
            if len(idx_values) == 1 and len(token_values) == 1:
                idx = str(idx_values[0]).strip()
                token = str(token_values[0]).strip()
                try:
                    _validate_chunithm_detail_submission(
                        detail_url, idx, token
                    )
                except (AdapterError, ValueError):
                    pass
                else:
                    detail_request = {
                        "url": detail_url,
                        "idx": idx,
                        "token": token,
                    }
        records.append(
            {
                "title": title,
                "difficulty": difficulty,
                "score": _chunithm_score(score_text),
                "rank": rank,
                "clearStatus": clear_status,
                "comboStatus": combo_status,
                "playTime": _play_time(date_text),
                "track": track,
                # Temporary request capability. The normalizer deliberately
                # consumes this value and never copies it into the payload.
                "detailRequest": detail_request,
            }
        )
    return records


def _parse_maimai_play_detail_html(
    value: str,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    root = _html_root(value, "maimai play detail page")
    tables = _class_nodes(root, "playlog_notes_detail")
    if len(tables) != 1:
        raise AdapterError("maimai play detail has an invalid judgment table")
    rows: list[list[int]] = []
    for table_row in tables[0].xpath(".//tr"):
        cells = table_row.xpath("./td")
        if not cells:
            continue
        expected_note_type = (
            MAIMAI_NOTE_TYPES[len(rows)]
            if len(rows) < len(MAIMAI_NOTE_TYPES)
            else None
        )
        if len(cells) == len(MAIMAI_JUDGMENTS) + 1:
            label = _maimai_note_type_label(cells[0])
            if label is None and not rows:
                # The official table's first row is a column header. Depending
                # on the deployed template its corner/header cells may also be
                # td elements rather than th elements.
                continue
            if (
                expected_note_type is None
                or label != expected_note_type
            ):
                raise AdapterError("maimai play detail has an invalid note label")
            cells = cells[1:]
        elif len(cells) == len(MAIMAI_JUDGMENTS):
            headers = table_row.xpath("./th")
            if len(headers) == 1:
                label = _maimai_note_type_label(headers[0])
                if label is None and not rows:
                    continue
                if label is None or label != expected_note_type:
                    raise AdapterError(
                        "maimai play detail has an invalid note label"
                    )
        if len(cells) != len(MAIMAI_JUDGMENTS):
            raise AdapterError("maimai play detail has an invalid judgment row")
        rows.append([_maimai_judgment_cell(cell) for cell in cells])
    if len(rows) != len(MAIMAI_NOTE_TYPES):
        raise AdapterError("maimai play detail has an incomplete judgment table")
    judgment_details = {
        "byNoteType": {
            note_type: dict(zip(MAIMAI_JUDGMENTS, counts, strict=True))
            for note_type, counts in zip(MAIMAI_NOTE_TYPES, rows, strict=True)
        }
    }

    play_details: dict[str, Any] = {}
    timing_blocks = _class_nodes(root, "playlog_fl_block")
    if len(timing_blocks) == 1:
        timing_nodes = timing_blocks[0].xpath(
            ".//div[contains(concat(' ', normalize-space(@class), ' '), ' w_96 ')]"
            "/div[contains(concat(' ', normalize-space(@class), ' '), ' p_t_5 ')]"
        )
        if len(timing_nodes) == 2:
            timing_values = [
                _optional_unique_nonnegative_integer(node.text_content())
                for node in timing_nodes
            ]
            timing_labels = [
                _maimai_play_value_label(node, {"fast", "late"})
                for node in timing_nodes
            ]
            if all(item is not None for item in timing_values):
                if set(timing_labels) == {"fast", "late"}:
                    for label, count in zip(
                        timing_labels, timing_values, strict=True
                    ):
                        play_details[label] = count
                elif timing_labels == [None, None]:
                    # Older official templates expose only fixed-position
                    # background labels. Preserve that audited order.
                    play_details["fast"] = timing_values[0]
                    play_details["late"] = timing_values[1]

    semantic_fractions: dict[str, set[tuple[int, int]]] = {}
    for node in root.iter():
        if not isinstance(getattr(node, "tag", None), str):
            continue
        text_content = getattr(node, "text_content", None)
        if not callable(text_content):
            continue
        fraction = _optional_fraction(str(text_content()))
        if fraction is None:
            continue
        label = _maimai_play_value_label(
            node, {"maxCombo", "maxSync", "dxScore"}
        )
        if label is not None:
            semantic_fractions.setdefault(label, set()).add(fraction)

    fraction_nodes = [
        node
        for node in _class_nodes(root, "white")
        if {"f_r", "f_14", "white"}.issubset(
            set(str(node.get("class", "")).split())
        )
        and _optional_fraction(node.text_content()) is not None
    ]
    labeled_fractions: dict[str, Any] = {}
    duplicate_fraction_label = False
    for node in fraction_nodes:
        label = _maimai_play_value_label(node, {"maxCombo", "maxSync"})
        if label is None:
            continue
        if label in labeled_fractions:
            duplicate_fraction_label = True
            break
        labeled_fractions[label] = node
    combo_node = None
    sync_node = None
    if not duplicate_fraction_label and set(labeled_fractions) == {
        "maxCombo", "maxSync"
    }:
        combo_node = labeled_fractions["maxCombo"]
        sync_node = labeled_fractions["maxSync"]
    else:
        combo_node = _one_exact_class_node(root, "f_r f_14 white")
        sync_node = _one_exact_class_node(root, " f_r f_14 white")
        if combo_node is None or sync_node is None:
            if len(fraction_nodes) == 2 and not labeled_fractions:
                combo_node, sync_node = fraction_nodes
    combo = (
        _optional_fraction(combo_node.text_content())
        if combo_node is not None
        else None
    )
    sync = (
        _optional_fraction(sync_node.text_content())
        if sync_node is not None
        else None
    )
    score_value_nodes = root.xpath(
        ".//div[contains(concat(' ', normalize-space(@class), ' '), "
        "' playlog_score_block ')]/div["
        "contains(concat(' ', normalize-space(@class), ' '), ' white ')]"
    )
    ordered_score_values = [
        _optional_fraction(node.text_content()) for node in score_value_nodes
    ]
    if any(item is None for item in ordered_score_values):
        ordered_score_values = []
    # The official result template orders these white values as DX SCORE,
    # MAX COMBO, MAX SYNC. Labels/assets remain the preferred binding above;
    # this audited order covers the deployed template whose label image is a
    # sibling rather than a descendant of the numeric div.
    if combo is None and len(ordered_score_values) >= 2:
        combo = ordered_score_values[1]
    if sync is None and len(ordered_score_values) >= 3:
        sync = ordered_score_values[2]
    if len(semantic_fractions.get("maxCombo", set())) == 1:
        combo = next(iter(semantic_fractions["maxCombo"]))
    if len(semantic_fractions.get("maxSync", set())) == 1:
        sync = next(iter(semantic_fractions["maxSync"]))
    if combo is not None:
        play_details["maxCombo"] = {
            "current": combo[0],
            "maximum": combo[1],
        }
    if sync is not None:
        play_details["maxSync"] = {
            "current": sync[0],
            "maximum": sync[1],
        }

    dx_score = None
    if len(semantic_fractions.get("dxScore", set())) == 1:
        dx_score = next(iter(semantic_fractions["dxScore"]))
    else:
        score_blocks = _class_nodes(root, "playlog_score_block")
        if len(score_blocks) == 1:
            dx_score = _optional_fraction(score_blocks[0].text_content())
    if dx_score is None and ordered_score_values:
        dx_score = ordered_score_values[0]
    if dx_score is not None:
        play_details["dxScore"] = {
            "current": dx_score[0],
            "maximum": dx_score[1],
        }

    rating: dict[str, Any] = {}
    rating_detail_blocks = _class_nodes(root, "playlog_rating_detail_block")
    rating_block = (
        rating_detail_blocks[0]
        if len(rating_detail_blocks) == 1
        else None
    )
    rating_value_nodes = _class_nodes(root, "playlog_rating_val_block")
    if not rating_value_nodes and rating_block is not None:
        rating_value_nodes = _class_nodes(rating_block, "rating_block")
    if len(rating_value_nodes) == 1:
        rating_value = _optional_unique_nonnegative_integer(
            rating_value_nodes[0].text_content()
        )
        if rating_value is not None:
            rating["value"] = rating_value
    player_rating_nodes = root.xpath(
        "//div[@class='basic_block m_t_5 p_3 t_r f_0']/span[@class='f_14']"
    )
    if len(player_rating_nodes) == 1:
        player_rating = _optional_unique_nonnegative_integer(
            player_rating_nodes[0].text_content()
        )
        if player_rating is not None:
            rating["playerTotal"] = player_rating
    if rating_block is not None:
        if "value" not in rating:
            block_text = re.sub(
                r"\(\s*[+-]\s*[0-9][0-9,\s]{0,20}\s*\)",
                " ",
                str(rating_block.text_content()),
            )
            rating_value = _optional_unique_nonnegative_integer(block_text)
            if rating_value is not None:
                rating["value"] = rating_value
        rating_delta = _optional_parenthesized_delta(
            rating_block.text_content()
        )
        if rating_delta is not None:
            rating["delta"] = rating_delta
        rating_frame = _maimai_rating_frame(rating_block)
        if rating_frame is not None:
            rating["frame"] = rating_frame[0]
            rating["frameImageUrl"] = rating_frame[1]
    if rating:
        play_details["rating"] = rating

    partners = _parse_maimai_partners(root)
    if partners is not None:
        play_details["partners"] = partners

    max_combo = play_details.get("maxCombo")
    if isinstance(max_combo, dict):
        judgment_total = sum(
            sum(counts.values())
            for counts in judgment_details["byNoteType"].values()
        )
        if max_combo.get("maximum") != judgment_total:
            raise AdapterError(
                "maimai play detail max combo does not match its judgments"
            )

    return judgment_details, play_details or None


def _parse_chunithm_judgment_detail_html(value: str) -> dict[str, Any]:
    root = _html_root(value, "CHUNITHM play detail page")
    judgment_nodes = _class_nodes(root, "play_data_detail_judge_text")
    note_nodes = _class_nodes(root, "play_data_detail_notes_text")
    judgment_nodes = _semantic_node_order(
        judgment_nodes,
        ("text_critical", "text_justice", "text_attack", "text_miss"),
    )
    note_nodes = _semantic_node_order(
        note_nodes,
        (
            "text_tap_red",
            "text_hold_yellow",
            "text_slide_blue",
            "text_air_green",
            "text_flick_skyblue",
        ),
    )
    combo_nodes = _class_nodes(root, "play_data_detail_maxcombo_block")
    if len(judgment_nodes) != len(CHUNITHM_JUDGMENTS):
        raise AdapterError("CHUNITHM play detail has invalid judgment counts")
    if len(note_nodes) != len(CHUNITHM_NOTE_TYPES):
        raise AdapterError("CHUNITHM play detail has invalid note achievements")
    if len(combo_nodes) != 1:
        raise AdapterError("CHUNITHM play detail has an invalid max combo")
    judgments = {
        name: _one_unique_nonnegative_integer(
            node.text_content(), "CHUNITHM judgment"
        )
        for name, node in zip(CHUNITHM_JUDGMENTS, judgment_nodes, strict=True)
    }
    achievement_values = [
        _optional_percentage(node.text_content()) for node in note_nodes
    ]
    count_values = [
        _optional_unique_nonnegative_integer(node.text_content())
        for node in note_nodes
    ]
    max_combo = _one_unique_nonnegative_integer(
        combo_nodes[0].text_content(), "CHUNITHM max combo"
    )
    judgment_total = sum(judgments.values())
    if max_combo > judgment_total:
        raise AdapterError(
            "CHUNITHM play detail counts are internally inconsistent"
        )
    result = {
        "judgments": judgments,
        "maxCombo": max_combo,
    }
    if all(item is not None for item in achievement_values):
        result["noteAchievements"] = dict(zip(
            CHUNITHM_NOTE_TYPES, achievement_values, strict=True
        ))
    elif all(item is not None for item in count_values):
        # Compatibility with the old/overseas template that emitted note
        # quantities instead of the current Chinese site's percentages.
        note_counts = dict(zip(
            CHUNITHM_NOTE_TYPES, count_values, strict=True
        ))
        if sum(note_counts.values()) != judgment_total:
            raise AdapterError(
                "CHUNITHM play detail counts are internally inconsistent"
            )
        result["noteCounts"] = note_counts
    else:
        raise AdapterError("CHUNITHM play detail has invalid note achievements")
    return result


def _semantic_node_order(
    nodes: list[Any],
    class_order: tuple[str, ...],
) -> list[Any]:
    found: dict[str, Any] = {}
    semantic_tokens_seen = False
    for node in nodes:
        classes = set(str(node.get("class", "")).split())
        matched = classes.intersection(class_order)
        if matched:
            semantic_tokens_seen = True
        if len(matched) != 1:
            continue
        token = next(iter(matched))
        if token in found:
            return []
        found[token] = node
    if not semantic_tokens_seen:
        # Compatibility for older fixture/templates whose fields were only
        # distinguishable by the documented fixed order.
        return nodes
    return [found[token] for token in class_order] if set(found) == set(class_order) else []


def _maimai_judgment_cell(node: Any) -> int:
    text = "".join(str(node.text_content()).split())
    if not text or text in {"-", "―", "—"}:
        return 0
    return _one_nonnegative_integer(text, "maimai judgment")


def _maimai_note_type_label(node: Any) -> str | None:
    candidates = [str(node.text_content()), *map(str, node.xpath(".//img/@src"))]
    found: set[str] = set()
    for candidate in candidates:
        normalized = candidate.lower()
        for note_type in MAIMAI_NOTE_TYPES:
            if re.search(rf"(?<![a-z]){re.escape(note_type)}(?![a-z])", normalized):
                found.add(note_type)
    return next(iter(found)) if len(found) == 1 else None


def _maimai_play_value_label(
    node: Any,
    allowed: set[str],
) -> str | None:
    candidates = [
        str(node.text_content()),
        *map(str, node.xpath(".//img/@alt")),
        *map(str, node.xpath(".//img/@src")),
    ]
    found: set[str] = set()
    tokens = {
        "fast": ("fast",),
        "late": ("late",),
        "maxCombo": ("maxcombo",),
        "maxSync": ("maxsync",),
        "dxScore": ("dxscore", "deluxscore"),
    }
    for candidate in candidates:
        compact = re.sub(r"[^a-z]", "", candidate.lower())
        for label in allowed:
            if any(token in compact for token in tokens[label]) or (
                label == "dxScore" and "dx分数" in candidate.lower()
            ):
                found.add(label)
    return next(iter(found)) if len(found) == 1 else None


def _parse_maimai_partners(root: Any) -> list[dict[str, Any]] | None:
    containers = _class_nodes(root, "playlog_chara_container")
    if not containers or len(containers) > 5:
        return None
    partners: list[dict[str, Any]] = []
    for container in containers:
        star_nodes = _class_nodes(container, "playlog_chara_star_block")
        level_nodes = _class_nodes(container, "playlog_chara_lv_block")
        image_nodes = [
            image
            for image in container.xpath(".//img[@src]")
            if "chara_cycle_img" in str(image.get("class", "")).split()
        ]
        if len(star_nodes) != 1 or len(level_nodes) != 1 or len(image_nodes) != 1:
            return None
        stars = _optional_unique_nonnegative_integer(star_nodes[0].text_content())
        level = _optional_unique_nonnegative_integer(level_nodes[0].text_content())
        image_url = _official_maimai_asset_url(str(image_nodes[0].get("src", "")))
        if stars is None or level is None or image_url is None:
            return None
        partners.append(
            {"stars": stars, "level": level, "imageUrl": image_url}
        )
    return partners


def _maimai_rating_frame(node: Any) -> tuple[str, str] | None:
    raw_candidates: list[str] = []
    for element in (node, *node.iterdescendants()):
        for attribute in ("src", "data-src"):
            value = element.get(attribute)
            if isinstance(value, str) and value:
                raw_candidates.append(value)
        style = element.get("style")
        if isinstance(style, str):
            raw_candidates.extend(
                match[1]
                for match in re.findall(
                    r"url\(\s*(['\"]?)([^)'\"]+)\1\s*\)",
                    style,
                    flags=re.IGNORECASE,
                )
            )

    normalized: set[tuple[str, str]] = set()
    for candidate in raw_candidates:
        try:
            parsed = urlsplit(urljoin(
                "https://maimai.wahlap.com/maimai-mobile/", candidate
            ))
        except ValueError:
            continue
        if parsed.query:
            query = parse_qs(parsed.query, keep_blank_values=True)
            if (
                set(query) != {"ver"}
                or len(query["ver"]) != 1
                # Wahlap has used both timestamp-like and dotted static-asset
                # versions.  The query is discarded before persistence, but
                # keep its accepted alphabet narrow so unrelated parameters
                # can never be smuggled into the stored image URL.
                or re.fullmatch(
                    r"[0-9A-Za-z._-]{1,32}", query["ver"][0]
                ) is None
            ):
                continue
            parsed = parsed._replace(query="")
        url = _official_maimai_asset_url(parsed.geturl())
        if url is None:
            continue
        matched = re.search(
            r"(?:^|/)rating_base_([a-z]+)\.(?:png|webp)$",
            urlsplit(url).path.lower(),
        )
        if matched is None or matched.group(1) not in MAIMAI_RATING_FRAMES:
            continue
        normalized.add((matched.group(1), url))
    return next(iter(normalized)) if len(normalized) == 1 else None


def _official_maimai_asset_url(value: str) -> str | None:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > MAX_OFFICIAL_ASSET_URL_LENGTH
        or any(
            ord(character) < 0x20 or ord(character) == 0x7F
            for character in value
        )
    ):
        return None
    try:
        parsed = urlsplit(
            urljoin("https://maimai.wahlap.com/maimai-mobile/", value)
        )
        port = parsed.port if parsed.port is not None else 443
    except ValueError:
        return None
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower() != "maimai.wahlap.com"
        or port != 443
        or not parsed.path.startswith("/maimai-mobile/img/")
        or "%" in parsed.path
        or parsed.username is not None
        or parsed.password is not None
        or parsed.query
        or parsed.fragment
        or not parsed.path
        or "\\" in parsed.path
        or posixpath.normpath(parsed.path) != parsed.path
        or parsed.path.lower().rsplit(".", 1)[-1]
        not in {"png", "jpg", "jpeg", "webp"}
    ):
        return None
    normalized = f"https://maimai.wahlap.com{parsed.path}"
    return (
        normalized
        if len(normalized) <= MAX_OFFICIAL_ASSET_URL_LENGTH
        else None
    )


def _one_nonnegative_integer(value: Any, label: str) -> int:
    result = _optional_nonnegative_integer(value)
    if result is None:
        raise AdapterError(f"{label} is invalid")
    return result


def _one_unique_nonnegative_integer(value: Any, label: str) -> int:
    result = _optional_unique_nonnegative_integer(value)
    if result is None:
        raise AdapterError(f"{label} is invalid")
    return result


def _optional_nonnegative_integer(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    compact = re.sub(r"[\s,]", "", value)
    if re.fullmatch(r"[0-9]{1,12}", compact) is None:
        return None
    result = int(compact, 10)
    if result > MAX_JUDGMENT_COUNT:
        return None
    return result


def _optional_unique_nonnegative_integer(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    matches = re.findall(r"(?<![0-9])([0-9][0-9,\s]{0,20})(?![0-9])", value)
    results = [
        parsed
        for match in matches
        if (parsed := _optional_nonnegative_integer(match)) is not None
    ]
    return next(iter(results)) if len(results) == 1 else None


def _optional_percentage(value: Any) -> float | None:
    if not isinstance(value, str):
        return None
    matches = re.findall(
        r"(?<![0-9.])([0-9]{1,3}(?:\.[0-9]{1,2})?)\s*%",
        value,
    )
    if len(matches) != 1:
        return None
    result = float(matches[0])
    return result if math.isfinite(result) and 0.0 <= result <= 101.0 else None


def _optional_parenthesized_delta(value: Any) -> int | None:
    if not isinstance(value, str):
        return None
    matches = re.findall(r"\(\s*([+-])\s*([0-9][0-9,\s]{0,20})\s*\)", value)
    values: list[int] = []
    for sign, raw in matches:
        parsed = _optional_nonnegative_integer(raw)
        if parsed is None or parsed > 100_000:
            continue
        values.append(-parsed if sign == "-" else parsed)
    return next(iter(values)) if len(values) == 1 else None


def _optional_fraction(value: Any) -> tuple[int, int] | None:
    if not isinstance(value, str):
        return None
    matches = re.findall(
        r"(?<![0-9])([0-9][0-9, \t\u00a0]{0,20})\s*/\s*"
        r"([0-9][0-9, \t\u00a0]{0,20})(?![0-9])",
        value,
    )
    if len(matches) != 1:
        return None
    current = _optional_nonnegative_integer(matches[0][0])
    maximum = _optional_nonnegative_integer(matches[0][1])
    if current is None or maximum is None or current > maximum:
        return None
    return current, maximum


def _one_exact_class_node(root: Any, class_value: str) -> Any | None:
    nodes = root.xpath("//div[@class=$class_value]", class_value=class_value)
    return nodes[0] if len(nodes) == 1 else None


def _validate_chunithm_detail_submission(
    url: str,
    idx: str,
    token: str,
) -> None:
    try:
        parsed = urlsplit(url)
        port = parsed.port if parsed.port is not None else 443
        ordinal = int(idx, 10)
        validate_short_secret(token, "token", 512)
    except (TypeError, ValueError) as error:
        raise AdapterError("CHUNITHM play detail request is invalid") from error
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower() != "chunithm.wahlap.com"
        or port != 443
        or parsed.path.rstrip("/") != CHUNITHM_DETAIL_SUBMIT_PATH
        or parsed.query
        or parsed.fragment
        or parsed.username is not None
        or parsed.password is not None
        or re.fullmatch(r"[0-9]{1,2}", idx) is None
        or ordinal < 0
        or ordinal >= 50
    ):
        raise AdapterError("CHUNITHM play detail request is invalid")


def _html_root(value: str, label: str) -> Any:
    if not isinstance(value, str) or len(value) > MAX_OFFICIAL_HTML_CHARS:
        raise AdapterError(f"{label} is invalid or too large")
    try:
        from lxml import html as lxml_html

        return lxml_html.fromstring(value)
    except Exception:
        raise AdapterError(f"{label} is not valid HTML") from None


def _class_nodes(root: Any, class_name: str) -> list[Any]:
    own = []
    classes = str(root.get("class", "")).split() if hasattr(root, "get") else []
    if class_name in classes:
        own.append(root)
    return own + list(
        root.xpath(
            ".//*[contains(concat(' ', normalize-space(@class), ' '), $class_token)]",
            class_token=f" {class_name} ",
        )
    )


def _one_node_text(root: Any, class_name: str, label: str) -> str:
    nodes = _class_nodes(root, class_name)
    if len(nodes) != 1:
        raise AdapterError(f"{label} is invalid")
    return _node_text(nodes[0], label)


def _node_text(node: Any, label: str) -> str:
    text = " ".join(str(node.text_content()).split())
    if not text or len(text) > 300:
        raise AdapterError(f"{label} is invalid")
    return text


def _difficulty_from_background(node: Any) -> str:
    found: set[str] = set()
    for candidate in (node, *node.iterdescendants()):
        for token in str(candidate.get("class", "")).lower().split():
            matched = re.fullmatch(
                r"bg_(basic|advanced|expert|master|ultima)", token
            )
            if matched is not None:
                found.add(matched.group(1))
    if len(found) != 1:
        raise AdapterError("CHUNITHM rating entry has an invalid difficulty")
    return normalize_chunithm_difficulty(next(iter(found)))


def _chunithm_score(value: str) -> int:
    matched = re.fullmatch(
        r"(?:分数\s*[:：]\s*)?([0-9][0-9,\s]{0,20})",
        value.strip(),
    )
    if matched is None:
        raise AdapterError("CHUNITHM score is invalid")
    compact = re.sub(r"[\s,]", "", matched.group(1))
    if re.fullmatch(r"[0-9]{1,9}", compact) is None:
        raise AdapterError("CHUNITHM score is invalid")
    result = int(compact, 10)
    if result < 0 or result > 1_010_000:
        raise AdapterError("CHUNITHM score is out of range")
    return result


def _play_time(value: str) -> datetime:
    matched = re.search(r"([0-9]{4}/[0-9]{1,2}/[0-9]{1,2}\s+[0-9]{1,2}:[0-9]{2})", value)
    if matched is None:
        raise AdapterError("CHUNITHM recent play time is invalid")
    try:
        return datetime.strptime(matched.group(1), "%Y/%m/%d %H:%M")
    except ValueError:
        raise AdapterError("CHUNITHM recent play time is invalid") from None


def _source_record_id(value: Any) -> str | None:
    result = getattr(value, "source_record_id", None)
    if result is None:
        return None
    if not isinstance(result, str):
        return None
    try:
        return validate_short_secret(
            result, "sourceRecordId", MAX_SOURCE_RECORD_ID_LENGTH
        )
    except ValueError:
        return None


def _header_value(response: Any, name: str) -> str | None:
    headers = getattr(response, "headers", None)
    if headers is None:
        return None
    value = headers.get(name)
    return value if isinstance(value, str) and value else None


def _cookie_dict(value: Any) -> dict[str, str]:
    if value is None:
        return {}
    jar = getattr(value, "jar", None)
    if jar is not None:
        pairs = ((cookie.name, cookie.value) for cookie in jar)
    else:
        try:
            pairs = value.items()
        except AttributeError:
            return {}
    result: dict[str, str] = {}
    for key, item in pairs:
        if (
            isinstance(key, str)
            and isinstance(item, str)
            and COOKIE_NAME_PATTERN.fullmatch(key) is not None
            and COOKIE_VALUE_PATTERN.fullmatch(item) is not None
        ):
            result[key] = item
    return result


def _validate_official_destination(value: str, game: str) -> None:
    expected_host, expected_prefix = OFFICIAL_PAGE_ROOTS[game]
    if (
        not isinstance(value, str)
        or not value
        or len(value) > MAX_OAUTH_URL_LENGTH
        or any(
            ord(character) < 0x20 or ord(character) == 0x7F
            for character in value
        )
    ):
        raise AdapterError("authorization destination is invalid")
    try:
        parsed = urlsplit(value)
        port = parsed.port if parsed.port is not None else 443
    except ValueError as error:
        raise AdapterError("authorization destination is invalid") from error
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower() != expected_host
        or port != 443
        or not parsed.path.startswith(expected_prefix)
        or "%" in parsed.path
        or "\\" in parsed.path
        or not _is_normalized_official_path(parsed.path)
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
    ):
        raise AdapterError("authorization destination is not an official game page")


def _is_normalized_official_path(value: str) -> bool:
    if not value.startswith("/"):
        return False
    normalized = posixpath.normpath(value)
    if value.endswith("/") and normalized != "/":
        normalized += "/"
    return normalized == value


def _validate_oauth_url(value: str, game: str = "maimai") -> OAuthBinding:
    try:
        game = validate_game(game)
    except ValueError as error:
        raise AdapterError("unsupported synchronization game") from error
    if len(value) > MAX_OAUTH_URL_LENGTH:
        raise AdapterError("maimai-py returned an oversized authorization URL")
    try:
        parsed = urlsplit(value)
        outer = parse_qs(parsed.query, keep_blank_values=True, strict_parsing=True)
        outer_port = parsed.port if parsed.port is not None else 443
    except ValueError as error:
        raise AdapterError("maimai-py returned an invalid authorization URL") from error
    if (
        parsed.scheme != "https"
        or (parsed.hostname or "").lower() != "open.weixin.qq.com"
        or outer_port != 443
        or parsed.path != "/connect/oauth2/authorize"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment != "wechat_redirect"
    ):
        raise AdapterError("maimai-py returned an unexpected authorization destination")
    if set(outer) != {"appid", "redirect_uri", "response_type", "scope", "state"}:
        raise AdapterError("authorization URL has unexpected parameters")
    if _one_query_value(outer, "appid", 64) != EXPECTED_WECHAT_APP_ID:
        raise AdapterError("authorization URL has an unexpected appid")
    if (
        _one_query_value(outer, "response_type", 32)
        != EXPECTED_WECHAT_RESPONSE_TYPE
    ):
        raise AdapterError("authorization URL has an unexpected response_type")
    if _one_query_value(outer, "scope", 64) != EXPECTED_WECHAT_SCOPE:
        raise AdapterError("authorization URL has an unexpected scope")
    state = _one_query_value(outer, "state", 512)
    redirect_value = _one_query_value(outer, "redirect_uri", MAX_OAUTH_URL_LENGTH)
    try:
        redirect = urlsplit(redirect_value)
        redirect_query = parse_qs(
            redirect.query,
            keep_blank_values=True,
            strict_parsing=True,
        )
        redirect_port = redirect.port if redirect.port is not None else 80
    except ValueError as error:
        raise AdapterError("authorization callback URL is invalid") from error
    if (
        redirect.scheme != "http"
        or (redirect.hostname or "").lower() != CALLBACK_HOST
        or redirect_port != 80
        or _callback_game_path_is_invalid(redirect.path, game)
        or redirect.username is not None
        or redirect.password is not None
        or redirect.fragment
    ):
        raise AdapterError("authorization callback does not target the audited endpoint")
    if set(redirect_query) != {"r", "t"}:
        raise AdapterError("authorization callback has unexpected parameters")
    expected_r = _one_query_value(redirect_query, "r", 2048)
    expected_t = _one_query_value(redirect_query, "t", 2048)
    return OAuthBinding(
        value,
        state,
        expected_r,
        expected_t,
        game,
        redirect.path,
    )


def _callback_game_path_is_invalid(path: str, game: str) -> bool:
    """Keep the OAuth condition readable while validating exact aliases."""
    try:
        validate_callback_path(path, game)
    except ValueError:
        return True
    return False


def _one_query_value(values: dict[str, list[str]], name: str, maximum: int) -> str:
    found = values.get(name)
    if found is None or len(found) != 1:
        raise AdapterError(f"authorization URL has an invalid {name} parameter")
    try:
        return validate_short_secret(found[0], name, maximum)
    except ValueError as error:
        raise AdapterError(f"authorization URL has an invalid {name} parameter") from error


def _text_attribute(value: Any, field_name: str, maximum: int) -> str:
    field_value = getattr(value, field_name, None)
    if not isinstance(field_value, str) or not field_value.strip():
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    normalized = field_value.strip()
    if len(normalized) > maximum:
        raise AdapterError(f"maimai.py returned an oversized {field_name}")
    return normalized


def _finite_number(value: Any, field_name: str) -> float:
    field_value = getattr(value, field_name, None)
    if isinstance(field_value, bool) or not isinstance(field_value, (int, float)):
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    result = float(field_value)
    if not math.isfinite(result):
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    return result


def _achievement(value: Any) -> float:
    result = _finite_number(value, "achievements")
    if result < 0.0 or result > 101.0:
        raise AdapterError("maimai.py returned an invalid achievements")
    if abs(result * 10_000 - round(result * 10_000)) > 1e-7:
        raise AdapterError("maimai.py returned an over-precise achievements")
    return result


def _integer_attribute(value: Any, field_name: str) -> int:
    field_value = getattr(value, field_name, None)
    if isinstance(field_value, bool) or not isinstance(field_value, int):
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    if field_value < 0:
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    return field_value


def _dx_score(value: Any) -> int:
    result = _integer_attribute(value, "dx_score")
    if result > 100_000_000:
        raise AdapterError("maimai.py returned an invalid dx_score")
    return result


def _enum_value(value: Any, field_name: str) -> str:
    enum_value = getattr(getattr(value, field_name, None), "value", None)
    if not isinstance(enum_value, str) or not enum_value:
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    return enum_value.lower()


def _required_enum_name(value: Any, field_name: str) -> str:
    result = _optional_enum_name(value, field_name)
    if result is None:
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    return result


def _optional_enum_name(value: Any, field_name: str) -> str | None:
    field_value = getattr(value, field_name, None)
    if field_value is None:
        return None
    name = getattr(field_value, "name", None)
    if not isinstance(name, str) or not name:
        raise AdapterError(f"maimai.py returned an invalid {field_name}")
    return name.lower()


def _difficulty(score: Any) -> str:
    name = getattr(getattr(score, "level_index", None), "name", None)
    if not isinstance(name, str) or not name:
        raise AdapterError("maimai.py returned an invalid level_index")
    normalized = name.upper()
    return "RE:MASTER" if normalized == "REMASTER" else normalized


def _html_chart_type(score: Any) -> str:
    value = getattr(score, "type", None)
    if not isinstance(value, str):
        raise AdapterError("maimai WeChat returned an invalid chart type")
    normalized = value.strip().upper()
    if normalized in {"SD", "STD", "STANDARD"}:
        return "standard"
    if normalized == "DX":
        return "dx"
    raise AdapterError("maimai WeChat returned an unsupported chart type")


def _html_difficulty(score: Any) -> str:
    try:
        return normalize_difficulty(getattr(score, "level_index", None))
    except (TypeError, ValueError):
        raise AdapterError("maimai WeChat returned an invalid difficulty") from None


def _html_optional_status(
    score: Any,
    field_name: str,
    allowed: frozenset[str],
) -> str | None:
    value = getattr(score, field_name, None)
    if value is None:
        return None
    if not isinstance(value, str):
        raise AdapterError(f"maimai WeChat returned an invalid {field_name}")
    normalized = value.strip().lower().replace("fdx", "fsd")
    if not normalized:
        return None
    if normalized not in allowed:
        raise AdapterError(f"maimai WeChat returned an invalid {field_name}")
    return normalized


def _stronger_html_chart(
    previous: dict[str, Any] | None,
    candidate: dict[str, Any],
) -> dict[str, Any]:
    if previous is None:
        return candidate
    result = dict(candidate)
    result["achievement"] = max(
        float(previous["achievement"]), float(candidate["achievement"])
    )
    combo_order = {None: 0, "": 0, "fc": 1, "fcp": 2, "ap": 3, "app": 4}
    sync_order = {
        None: 0,
        "": 0,
        "sync": 1,
        "fs": 2,
        "fsp": 3,
        "fsd": 4,
        "fsdp": 5,
    }
    for field, order in (("comboStatus", combo_order), ("syncStatus", sync_order)):
        left = previous.get(field)
        right = candidate.get(field)
        result[field] = right if order.get(right, 0) >= order.get(left, 0) else left
    return result
