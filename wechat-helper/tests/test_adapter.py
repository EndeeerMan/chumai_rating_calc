from __future__ import annotations

import asyncio
import dataclasses
from datetime import datetime
from enum import Enum, IntEnum
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
from urllib.parse import urlencode

from httpx import Request, Response

from wechat_helper.adapter import (
    AdapterError,
    CallbackParameters,
    CHUNITHM_PAGE_URLS,
    MAIMAI_DETAIL_PAGE_URL,
    MaimaiPyAdapter,
    OAUTH_AUTHORIZE_URLS,
    RECORD_PAGE_URL,
    SyncProgress,
    _Bindings,
    _cookie_dict,
    _official_maimai_asset_url,
    _parse_chunithm_judgment_detail_html,
    _parse_chunithm_playlog_html,
    _parse_maimai_play_detail_html,
    _parse_maimai_record_html,
    _validate_official_destination,
    _validate_oauth_url,
)
from wechat_helper.config import Config
from wechat_helper.security import (
    BackendTarget,
    CALLBACK_HOST,
    CALLBACK_PATH,
    CALLBACK_PATHS,
)


SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"


class SongType(Enum):
    STANDARD = "standard"
    DX = "dx"
    UTAGE = "utage"


class LevelIndex(IntEnum):
    BASIC = 0
    ADVANCED = 1
    EXPERT = 2
    MASTER = 3
    REMASTER = 4


class Combo(Enum):
    AP = 1


class Rate(Enum):
    SSSP = 1


def oauth_url(
    *,
    appid: str = "wx1fcecfcbd16803b1",
    response_type: str = "code",
    scope: str = "snsapi_base",
    fragment: str = "wechat_redirect",
    outer_extra: dict[str, str] | None = None,
    callback_extra: dict[str, str] | None = None,
    game: str = "maimai",
    callback_path: str | None = None,
) -> str:
    callback_values = {"r": "r-secret", "t": "t-secret"}
    callback_values.update(callback_extra or {})
    redirect = (
        f"http://{CALLBACK_HOST}{callback_path or CALLBACK_PATHS[game]}?"
        + urlencode(callback_values)
    )
    outer = {
        "appid": appid,
        "redirect_uri": redirect,
        "response_type": response_type,
        "scope": scope,
        "state": "oauth-state",
    }
    outer.update(outer_extra or {})
    suffix = f"#{fragment}" if fragment else ""
    return (
        "https://open.weixin.qq.com/connect/oauth2/authorize?"
        + urlencode(outer)
        + suffix
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


def bindings() -> _Bindings:
    return _Bindings(object, object, SongType, SimpleNamespace(value=100))


def stub_maimai_authorization(adapter: MaimaiPyAdapter) -> None:
    async def exchange(_callback: CallbackParameters) -> dict[str, str]:
        return {"session": "temporary"}

    adapter._exchange_maimai_callback = exchange  # type: ignore[method-assign]


def maimai_detail_html(
    *,
    dx_score: int = 1200,
    rating_value: int = 15123,
    rating_delta: int = 9,
) -> str:
    note_rows = "".join(
        "<tr><th><img src='/maimai-mobile/img/playlog/notes_"
        + note_type
        + ".png'></th>"
        + "".join(f"<td><span>{count}</span></td>" for count in counts)
        + "</tr>"
        for note_type, counts in (
            ("tap", (10, 8, 2, 0, 0)),
            ("hold", (8, 4, 2, 1, 0)),
            ("slide", (10, 2, 2, 1, 0)),
            ("touch", (9, 3, 2, 1, 0)),
            ("break", (7, 4, 2, 1, 1)),
        )
    )
    sign = "+" if rating_delta >= 0 else "-"
    return f"""
    <html><body>
      <table class="playlog_notes_detail t_r f_l f_11 f_b">
        <tr><th>NOTE</th><td>CP</td><td>P</td><td>GREAT</td><td>GOOD</td><td>MISS</td></tr>
        {note_rows}
      </table>
      <div class="playlog_score_block">
        <div><img src="/maimai-mobile/img/playlog/deluxscore.png"></div>
        <div class="white">{dx_score:,} / 1,500</div>
        <div><img src="/maimai-mobile/img/playlog/maxcombo.png"></div>
        <div class="white">79 / 80</div>
        <div><img src="/maimai-mobile/img/playlog/maxsync.png"></div>
        <div class="white">8 / 1,139</div>
      </div>
      <div class="playlog_rating_detail_block"
           style="background-image:url('/maimai-mobile/img/rating_base_yellow.png?ver=1.50')">
        <div class="rating_block">{rating_value:,}</div>
        <span>({sign}{abs(rating_delta)})</span>
      </div>
    </body></html>
    """


def chunithm_detail_html(marker: str = "") -> str:
    return f"""
    <html><body data-marker="{marker}">
      <div class="play_data_detail_judge_text text_justice">JUSTICE 90</div>
      <div class="play_data_detail_judge_text text_miss">MISS 5</div>
      <div class="play_data_detail_judge_text text_critical">JUSTICE CRITICAL 900</div>
      <div class="play_data_detail_judge_text text_attack">ATTACK 10</div>
      <div class="play_data_detail_notes_text text_air_green">AIR 99.52%</div>
      <div class="play_data_detail_notes_text text_tap_red">TAP 97.05%</div>
      <div class="play_data_detail_notes_text text_flick_skyblue">FLICK 96.93%</div>
      <div class="play_data_detail_notes_text text_hold_yellow">HOLD 100.98%</div>
      <div class="play_data_detail_notes_text text_slide_blue">SLIDE 100.35%</div>
      <div class="play_data_detail_maxcombo_block">MAX COMBO 995</div>
    </body></html>
    """


class FakeSongs:
    async def by_id(self, song_id: int) -> object | None:
        if song_id == 1:
            return SimpleNamespace(title="测试歌曲")
        return None


class FakeClient:
    def __init__(self) -> None:
        self.calls: list[object] = []
        chart = SimpleNamespace(
            id=10001,
            title="测试歌曲",
            type=SongType.DX,
            achievements=100.5,
            level_value=14.5,
            version=100,
            level_index=LevelIndex.MASTER,
            fc=Combo.AP,
            fs=None,
        )
        self.score_collection = SimpleNamespace(scores=[chart])
        self.records_value = [
            SimpleNamespace(
                id=10001,
                type=SongType.DX,
                achievements=100.5,
                dx_score=1234,
                rate=Rate.SSSP,
                fc=Combo.AP,
                fs=None,
                level_index=LevelIndex.MASTER,
                play_time=datetime(2026, 7, 16, 12, 0, 0),
            )
        ]

    async def wechat(self, **values: str) -> object:
        self.calls.append(("wechat", bool(values)))
        return SimpleNamespace(credentials="temporary") if values else oauth_url()

    async def songs(self, *, alias_provider: object) -> FakeSongs:
        self.calls.append(("songs", alias_provider))
        return FakeSongs()

    async def scores(self, identifier: object, provider: object) -> object:
        self.calls.append("scores")
        await asyncio.sleep(0)
        return self.score_collection

    async def records(self, identifier: object, provider: object) -> object:
        self.calls.append("records")
        await asyncio.sleep(0)
        return self.records_value


class OAuthValidationTests(unittest.TestCase):
    def test_only_safe_cookie_pairs_leave_the_authorization_chain(self) -> None:
        self.assertEqual(
            {"session-id": "safe-value"},
            _cookie_dict(
                {
                    "session-id": "safe-value",
                    "bad name": "value",
                    "bad\x7f": "value",
                    "bad-value": "secret\x7f",
                    "empty": "",
                    "separator": "value;injected=true",
                }
            ),
        )

    def test_chunithm_uses_audited_cn_score_page_paths(self) -> None:
        self.assertEqual(
            (
                "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailBest/",
                "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailRecent/",
                "https://chunithm.wahlap.com/mobile/home/playerData/ratingDetailNext/",
                "https://chunithm.wahlap.com/mobile/record/playlog/",
            ),
            CHUNITHM_PAGE_URLS,
        )

    def test_real_shape_is_accepted_and_secrets_are_bound(self) -> None:
        for callback_path in (
            "/wc_auth/oauth/callback/maimai-dx",
            "/wc_auth/oauth/callback/maidx",
        ):
            with self.subTest(callback_path=callback_path):
                binding = _validate_oauth_url(
                    oauth_url(callback_path=callback_path)
                )
                self.assertEqual("oauth-state", binding.state)
                self.assertEqual("r-secret", binding.expected_r)
                self.assertEqual("t-secret", binding.expected_t)
                self.assertEqual("maimai", binding.game)
                self.assertEqual(callback_path, binding.callback_path)

        chunithm = _validate_oauth_url(
            oauth_url(game="chunithm"), "chunithm"
        )
        self.assertEqual("chunithm", chunithm.game)
        with self.assertRaises(AdapterError):
            _validate_oauth_url(oauth_url(), "chunithm")

    def test_fixed_wechat_fields_fragment_and_callback_shape_are_enforced(self) -> None:
        invalid_urls = (
            oauth_url(appid="another-app"),
            oauth_url(response_type="token"),
            oauth_url(scope="snsapi_userinfo"),
            oauth_url(fragment=""),
            oauth_url(outer_extra={"extra": "1"}),
            oauth_url(callback_extra={"extra": "1"}),
            oauth_url(callback_path="/wc_auth/oauth/callback/maidx/extra"),
            oauth_url().replace("open.weixin.qq.com", "evil.example"),
            oauth_url().replace(
                "open.weixin.qq.com/connect",
                "open.weixin.qq.com:0/connect",
            ),
            oauth_url().replace(
                "open.weixin.qq.com/connect",
                "open.weixin.qq.com:444/connect",
            ),
            oauth_url().replace(
                "tgk-wcaime.wahlap.com%2F",
                "tgk-wcaime.wahlap.com%3A0%2F",
            ),
            oauth_url().replace(
                "tgk-wcaime.wahlap.com%2F",
                "tgk-wcaime.wahlap.com%3A81%2F",
            ),
        )
        for value in invalid_urls:
            with self.subTest(value=value[:100]):
                with self.assertRaises(AdapterError):
                    _validate_oauth_url(value)

    def test_only_exact_official_redirect_destination_is_accepted(self) -> None:
        _validate_official_destination(
            "https://chunithm.wahlap.com/mobile/home/", "chunithm"
        )
        _validate_official_destination(
            "https://chunithm.wahlap.com:443/mobile/home/?from=oauth",
            "chunithm",
        )
        for value in (
            "http://chunithm.wahlap.com/mobile/home/",
            "https://chunithm.wahlap.com.evil/mobile/home/",
            "https://chunithm.wahlap.com:444/mobile/home/",
            "https://chunithm.wahlap.com/not-mobile/",
            "https://user@chunithm.wahlap.com/mobile/home/",
            "https://chunithm.wahlap.com/mobile/../admin/",
            "https://chunithm.wahlap.com/mobile/%2e%2e/admin/",
            "https://chunithm.wahlap.com/mobile\\admin/",
            "https://chunithm.wahlap.com/mobile/home/#secret",
        ):
            with self.subTest(value=value):
                with self.assertRaises(AdapterError):
                    _validate_official_destination(value, "chunithm")


class AdapterFetchTests(unittest.IsolatedAsyncioTestCase):
    async def test_chunithm_oauth_uses_the_audited_authorize_slug(self) -> None:
        class OAuthHttp:
            def __init__(self) -> None:
                self.urls: list[str] = []

            async def get(self, url: str) -> object:
                self.urls.append(url)
                return SimpleNamespace(
                    status_code=302,
                    headers={"location": oauth_url(game="chunithm")},
                )

        fake = FakeClient()
        oauth_http = OAuthHttp()
        fake._client = oauth_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        binding = await adapter.begin_oauth("chunithm")
        self.assertEqual("chunithm", binding.game)
        self.assertEqual([OAUTH_AUTHORIZE_URLS["chunithm"]], oauth_http.urls)

    async def test_chunithm_callback_keeps_cookie_from_intermediate_redirect(
        self,
    ) -> None:
        class RedirectHttp:
            def __init__(self) -> None:
                self.calls: list[tuple[str, dict[str, str]]] = []

            async def get(self, url: str, **kwargs: object) -> object:
                cookies = kwargs.get("cookies")
                jar = getattr(cookies, "jar", ())
                self.calls.append(
                    (url, {cookie.name: cookie.value for cookie in jar})
                )
                if len(self.calls) == 1:
                    return SimpleNamespace(
                        status_code=302,
                        headers={
                            "location": "https://chunithm.wahlap.com/mobile/auth/"
                        },
                        cookies={"oauth_bridge": "bridge-value"},
                    )
                if len(self.calls) == 2:
                    return SimpleNamespace(
                        status_code=302,
                        headers={"location": "/mobile/home/"},
                        cookies={"chuni_session": "official-value"},
                    )
                return SimpleNamespace(status_code=200, headers={}, cookies={})

        fake = FakeClient()
        redirect_http = RedirectHttp()
        fake._client = redirect_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        credentials = await adapter._exchange_chunithm_callback(
            CallbackParameters("r-secret", "t-secret", "code", "oauth-state")
        )

        self.assertEqual({"chuni_session": "official-value"}, credentials)
        self.assertEqual(
            [
                "https://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/chunithm",
                "https://chunithm.wahlap.com/mobile/auth/",
                "https://chunithm.wahlap.com/mobile/home/",
            ],
            [url for url, _cookies in redirect_http.calls],
        )
        self.assertEqual(
            "official-value", redirect_http.calls[2][1].get("chuni_session")
        )

    async def test_maimai_callback_uses_only_the_validated_official_chain(
        self,
    ) -> None:
        class RedirectHttp:
            def __init__(self) -> None:
                self.calls: list[tuple[str, dict[str, str]]] = []

            async def get(self, url: str, **kwargs: object) -> object:
                cookies = kwargs.get("cookies")
                jar = getattr(cookies, "jar", ())
                self.calls.append(
                    (url, {cookie.name: cookie.value for cookie in jar})
                )
                if len(self.calls) == 1:
                    return SimpleNamespace(
                        status_code=302,
                        headers={
                            "location": "https://maimai.wahlap.com/"
                            "maimai-mobile/auth/"
                        },
                        cookies={"oauth_bridge": "bridge-value"},
                    )
                if len(self.calls) == 2:
                    return SimpleNamespace(
                        status_code=302,
                        headers={"location": "/maimai-mobile/home/"},
                        cookies={"maimai_session": "official-value"},
                    )
                return SimpleNamespace(status_code=200, headers={}, cookies={})

        fake = FakeClient()
        redirect_http = RedirectHttp()
        fake._client = redirect_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        credentials = await adapter._exchange_maimai_callback(
            CallbackParameters("r-secret", "t-secret", "code", "oauth-state")
        )

        self.assertEqual({"maimai_session": "official-value"}, credentials)
        self.assertEqual(
            [
                "https://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx",
                "https://maimai.wahlap.com/maimai-mobile/auth/",
                "https://maimai.wahlap.com/maimai-mobile/home/",
            ],
            [url for url, _cookies in redirect_http.calls],
        )
        self.assertEqual(
            "official-value", redirect_http.calls[2][1].get("maimai_session")
        )

    async def test_maimai_legacy_callback_replays_the_same_audited_path(
        self,
    ) -> None:
        class RedirectHttp:
            def __init__(self) -> None:
                self.calls: list[str] = []

            async def get(self, url: str, **_kwargs: object) -> object:
                self.calls.append(url)
                if len(self.calls) == 1:
                    return SimpleNamespace(
                        status_code=302,
                        headers={
                            "location": "https://maimai.wahlap.com/"
                            "maimai-mobile/auth/"
                        },
                        cookies={},
                    )
                if len(self.calls) == 2:
                    return SimpleNamespace(
                        status_code=302,
                        headers={"location": "/maimai-mobile/home/"},
                        cookies={"maimai_session": "official-value"},
                    )
                return SimpleNamespace(status_code=200, headers={}, cookies={})

        fake = FakeClient()
        redirect_http = RedirectHttp()
        fake._client = redirect_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        credentials = await adapter._exchange_maimai_callback(
            CallbackParameters("r-secret", "t-secret", "code", "oauth-state"),
            "/wc_auth/oauth/callback/maidx",
        )

        self.assertEqual({"maimai_session": "official-value"}, credentials)
        self.assertEqual(
            "https://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maidx",
            redirect_http.calls[0],
        )

    async def test_maimai_callback_rejects_cross_origin_redirect(self) -> None:
        class RedirectHttp:
            def __init__(self) -> None:
                self.count = 0

            async def get(self, _url: str, **_kwargs: object) -> object:
                self.count += 1
                if self.count == 1:
                    return SimpleNamespace(
                        status_code=302,
                        headers={
                            "location": "https://maimai.wahlap.com/"
                            "maimai-mobile/auth/"
                        },
                        cookies={"oauth_bridge": "bridge-value"},
                    )
                return SimpleNamespace(
                    status_code=302,
                    headers={"location": "https://example.com/stolen"},
                    cookies={"maimai_session": "official-value"},
                )

        fake = FakeClient()
        redirect_http = RedirectHttp()
        fake._client = redirect_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        with self.assertRaises(AdapterError):
            await adapter._exchange_maimai_callback(
                CallbackParameters("r-secret", "t-secret", "code", "oauth-state")
            )
        self.assertEqual(2, redirect_http.count)

    async def test_chunithm_callback_rejects_cross_origin_redirect(self) -> None:
        class RedirectHttp:
            def __init__(self) -> None:
                self.count = 0

            async def get(self, _url: str, **_kwargs: object) -> object:
                self.count += 1
                if self.count == 1:
                    return SimpleNamespace(
                        status_code=302,
                        headers={
                            "location": "https://chunithm.wahlap.com/mobile/auth/"
                        },
                        cookies={"oauth_bridge": "bridge-value"},
                    )
                return SimpleNamespace(
                    status_code=302,
                    headers={"location": "https://example.com/stolen"},
                    cookies={"chuni_session": "official-value"},
                )

        fake = FakeClient()
        redirect_http = RedirectHttp()
        fake._client = redirect_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        with self.assertRaises(AdapterError):
            await adapter._exchange_chunithm_callback(
                CallbackParameters("r-secret", "t-secret", "code", "oauth-state")
            )
        self.assertEqual(2, redirect_http.count)

    async def test_song_cache_is_primed_without_yuzu_before_scores(self) -> None:
        fake = FakeClient()
        adapter = MaimaiPyAdapter(
            config(),
            bindings=bindings(),
            client=fake,
            provider=object(),
            catalog_path=Path(tempfile.gettempdir()) / "missing-maimai-catalog.json",
        )
        stub_maimai_authorization(adapter)
        payload = await adapter.fetch_import_payload(
            CallbackParameters("r-secret", "t-secret", "code", "oauth-state"),
            SESSION_ID,
        )
        self.assertEqual(("songs", None), fake.calls[0])
        self.assertGreater(fake.calls.index("scores"), 0)
        self.assertGreater(fake.calls.index("records"), 0)
        self.assertEqual("1", payload["charts"][0]["songId"])
        self.assertEqual("current", payload["charts"][0]["version"])
        self.assertEqual("ap", payload["charts"][0]["comboStatus"])
        self.assertEqual("2026-07-16T04:00:00Z", payload["records"][0]["playedAt"])
        self.assertEqual("sssp", payload["records"][0]["rank"])

    async def test_fetch_has_a_hard_deadline(self) -> None:
        fake = FakeClient()

        async def slow_exchange(_callback: CallbackParameters) -> dict[str, str]:
            await asyncio.sleep(0.05)
            return {"session": "temporary"}

        adapter = MaimaiPyAdapter(
            config(),
            bindings=bindings(),
            client=fake,
            provider=object(),
            catalog_path=Path(tempfile.gettempdir()) / "missing-maimai-catalog.json",
        )
        adapter._exchange_maimai_callback = slow_exchange  # type: ignore[method-assign]
        adapter._sync_deadline_seconds = 0.005
        with self.assertRaises(AdapterError) as raised:
            await adapter.fetch_import_payload(
                CallbackParameters("r", "t", "code", "state"),
                SESSION_ID,
            )
        self.assertIn("deadline", str(raised.exception))

    async def test_maimai_detail_fetch_uses_stable_idx_as_a_query_only(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.calls: list[tuple[str, dict[str, object]]] = []

            async def get(self, url: str, **kwargs: object) -> object:
                self.calls.append((url, kwargs))
                return SimpleNamespace(
                    status_code=200,
                    text="<html><body>detail</body></html>",
                )

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        pages = await adapter._fetch_maimai_detail_pages(
            {"session": "temporary"},
            ["stable-idx", None],
            progress_callback=on_progress,
        )

        self.assertEqual("<html><body>detail</body></html>", pages[0])
        self.assertIsNone(pages[1])
        self.assertEqual(1, len(detail_http.calls))
        url, kwargs = detail_http.calls[0]
        self.assertEqual(
            "https://maimai.wahlap.com/maimai-mobile/record/playlogDetail/",
            url,
        )
        self.assertEqual({"idx": "stable-idx"}, kwargs["params"])
        self.assertEqual(
            [
                SyncProgress("maimai", "play_details", 0, 2, 0),
                SyncProgress("maimai", "play_details", 1, 2, 1),
                SyncProgress(
                    "maimai",
                    "play_details",
                    2,
                    2,
                    1,
                    failure_reasons={"missing-source-id": 1},
                ),
            ],
            progress,
        )

    async def test_maimai_fetches_and_validates_all_fifty_detail_pages(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.calls: list[str] = []

            async def get(self, _url: str, **kwargs: object) -> object:
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                self.calls.append(idx)
                ordinal = int(idx.rsplit("-", 1)[1], 10)
                return SimpleNamespace(
                    status_code=200,
                    text=maimai_detail_html(dx_score=1000 + ordinal),
                )

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        parsed_ordinals: list[int] = []
        progress: list[SyncProgress] = []

        def validate(text: str, ordinal: int) -> bool:
            judgments, play = _parse_maimai_play_detail_html(text)
            self.assertEqual(10, judgments["byNoteType"]["tap"]["criticalPerfect"])
            self.assertEqual(1000 + ordinal, play["dxScore"]["current"])
            parsed_ordinals.append(ordinal)
            return True

        async def immediate_sleep(_delay: float) -> None:
            return None

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        ids = [f"stable-{ordinal}" for ordinal in range(50)]
        with patch("wechat_helper.adapter.asyncio.sleep", new=immediate_sleep):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"}, ids, validate, on_progress
            )

        self.assertEqual(ids, detail_http.calls)
        self.assertEqual(list(range(50)), parsed_ordinals)
        self.assertEqual(50, len(pages))
        self.assertTrue(all(page is not None for page in pages))
        self.assertEqual(51, len(progress))
        self.assertEqual(
            SyncProgress("maimai", "play_details", 0, 50, 0), progress[0]
        )
        self.assertEqual(
            SyncProgress("maimai", "play_details", 50, 50, 50), progress[-1]
        )

    async def test_maimai_detail_fetch_is_serial_and_rotates_cookies(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.active = 0
                self.peak = 0
                self.calls: list[str] = []
                self.seen_rotated: list[str | None] = []
                self.seen_rotated_scopes: list[
                    list[tuple[str, str, bool]]
                ] = []

            async def get(self, _url: str, **kwargs: object) -> object:
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                cookies = kwargs["cookies"]
                self.calls.append(idx)
                self.seen_rotated.append(cookies.get("rotated"))  # type: ignore[union-attr]
                self.seen_rotated_scopes.append(  # type: ignore[union-attr]
                    [
                        (cookie.value, cookie.domain, cookie.secure)
                        for cookie in cookies.jar
                        if cookie.name == "rotated"
                    ]
                )
                self.active += 1
                self.peak = max(self.peak, self.active)
                try:
                    await asyncio.sleep(0)
                    return Response(
                        200,
                        text=idx,
                        headers={
                            "Set-Cookie": (
                                f"rotated={idx}; Domain=maimai.wahlap.com; "
                                "Path=/; Secure; HttpOnly"
                            )
                        },
                        request=Request("GET", MAIMAI_DETAIL_PAGE_URL),
                    )
                finally:
                    self.active -= 1

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        ids = [f"stable-{ordinal}" for ordinal in range(3)]

        with patch(
            "wechat_helper.adapter.MAIMAI_DETAIL_REQUEST_INTERVAL_SECONDS", 0
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary", "rotated": "initial"}, ids
            )

        self.assertEqual(1, detail_http.peak)
        self.assertEqual(ids, detail_http.calls)
        self.assertEqual(
            ["initial", "stable-0", "stable-1"],
            detail_http.seen_rotated,
        )
        self.assertEqual(
            [
                [("initial", "", False)],
                [("stable-0", ".maimai.wahlap.com", True)],
                [("stable-1", ".maimai.wahlap.com", True)],
            ],
            detail_http.seen_rotated_scopes,
        )
        self.assertEqual(ids, pages)

    async def test_maimai_detail_recovers_after_5xx_and_session_refresh(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.detail_calls: list[str] = []
                self.refresh_calls = 0
                self.failed_once = False

            async def get(self, url: str, **kwargs: object) -> object:
                if url == RECORD_PAGE_URL:
                    self.refresh_calls += 1
                    return SimpleNamespace(
                        status_code=200,
                        text="<html></html>",
                        cookies={"session": "refreshed"},
                    )
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                self.detail_calls.append(idx)
                if idx == "stable-10" and not self.failed_once:
                    self.failed_once = True
                    return SimpleNamespace(status_code=503, text="", cookies={})
                return SimpleNamespace(status_code=200, text=idx, cookies={})

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        ids = [f"stable-{ordinal}" for ordinal in range(12)]

        with (
            patch("wechat_helper.adapter.asyncio.sleep", new=AsyncMock()),
            patch(
                "wechat_helper.adapter.MAIMAI_DETAIL_UPSTREAM_COOLDOWNS_SECONDS",
                (0.0,),
            ),
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"},
                ids,
            )

        self.assertEqual(ids, pages)
        self.assertEqual(1, detail_http.refresh_calls)
        self.assertEqual(2, detail_http.detail_calls.count("stable-10"))
        self.assertEqual("stable-11", detail_http.detail_calls[-1])

    async def test_single_bad_detail_idx_does_not_open_global_breaker(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.detail_calls: list[str] = []
                self.refresh_calls = 0

            async def get(self, url: str, **kwargs: object) -> object:
                if url == RECORD_PAGE_URL:
                    self.refresh_calls += 1
                    return SimpleNamespace(status_code=200, text="", cookies={})
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                self.detail_calls.append(idx)
                status = 503 if idx == "stable-10" else 200
                return SimpleNamespace(status_code=status, text=idx, cookies={})

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        ids = [f"stable-{ordinal}" for ordinal in range(13)]

        with (
            patch("wechat_helper.adapter.asyncio.sleep", new=AsyncMock()),
            patch(
                "wechat_helper.adapter.MAIMAI_DETAIL_UPSTREAM_COOLDOWNS_SECONDS",
                (0.0,),
            ),
            self.assertLogs("maimai_wechat_helper", level="WARNING"),
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"}, ids
            )

        self.assertEqual(2, detail_http.detail_calls.count("stable-10"))
        self.assertEqual("stable-12", detail_http.detail_calls[-1])
        self.assertIsNone(pages[10])
        self.assertEqual("stable-11", pages[11])
        self.assertEqual("stable-12", pages[12])

    async def test_maimai_detail_persistent_5xx_opens_global_breaker(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.detail_calls: list[str] = []
                self.refresh_calls = 0

            async def get(self, url: str, **kwargs: object) -> object:
                if url == RECORD_PAGE_URL:
                    self.refresh_calls += 1
                    return SimpleNamespace(status_code=503, text="", cookies={})
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                self.detail_calls.append(idx)
                status = 200 if len(self.detail_calls) <= 10 else 503
                return SimpleNamespace(status_code=status, text=idx, cookies={})

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        ids = [f"stable-{ordinal}" for ordinal in range(50)]
        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        with (
            patch("wechat_helper.adapter.asyncio.sleep", new=AsyncMock()),
            patch(
                "wechat_helper.adapter.MAIMAI_DETAIL_UPSTREAM_COOLDOWNS_SECONDS",
                (0.0, 0.0),
            ),
            self.assertLogs("maimai_wechat_helper", level="WARNING"),
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"}, ids, progress_callback=on_progress
            )

        self.assertEqual(ids[:12], detail_http.detail_calls)
        self.assertEqual(2, detail_http.refresh_calls)
        self.assertEqual(ids[:10], pages[:10])
        self.assertEqual([None] * 40, pages[10:])
        self.assertEqual(
            SyncProgress(
                "maimai",
                "play_details",
                50,
                50,
                10,
                failure_reasons={"upstream-error": 40},
            ),
            progress[-1],
        )

    async def test_maimai_detail_soft_deadline_preserves_base_window(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.calls = 0

            async def get(self, _url: str, **_kwargs: object) -> object:
                self.calls += 1
                return SimpleNamespace(status_code=200, text="unexpected")

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        with (
            patch(
                "wechat_helper.adapter.MAIMAI_DETAIL_STAGE_BUDGET_SECONDS",
                -1.0,
            ),
            self.assertLogs("maimai_wechat_helper", level="WARNING"),
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"},
                ["stable-0", "stable-1", "stable-2"],
                progress_callback=on_progress,
            )

        self.assertEqual(0, detail_http.calls)
        self.assertEqual([None, None, None], pages)
        self.assertEqual(
            SyncProgress(
                "maimai",
                "play_details",
                3,
                3,
                0,
                failure_reasons={"unavailable": 3},
            ),
            progress[-1],
        )

    async def test_maimai_detail_retries_an_http_200_error_template(self) -> None:
        valid_page = maimai_detail_html()

        class DetailHttp:
            def __init__(self) -> None:
                self.calls = 0

            async def get(self, _url: str, **_kwargs: object) -> object:
                self.calls += 1
                return SimpleNamespace(
                    status_code=200,
                    text=(
                        "<html><body><div>temporary error</div></body></html>"
                        if self.calls == 1
                        else valid_page
                    ),
                )

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )

        def validate(text: str, _ordinal: int) -> bool:
            _parse_maimai_play_detail_html(text)
            return True

        async def immediate_sleep(_delay: float) -> None:
            return None

        with patch("wechat_helper.adapter.asyncio.sleep", new=immediate_sleep):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"}, ["stable-idx"], validate
            )

        self.assertEqual(2, detail_http.calls)
        self.assertEqual([valid_page], pages)

    async def test_maimai_detail_timeout_is_bounded_and_next_row_continues(
        self,
    ) -> None:
        valid_page = maimai_detail_html()
        stalled_id = "private-stalled-idx"

        class DetailHttp:
            def __init__(self) -> None:
                self.calls: list[str] = []

            async def get(self, _url: str, **kwargs: object) -> object:
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                self.calls.append(idx)
                if idx == stalled_id:
                    await asyncio.Future()
                return SimpleNamespace(status_code=200, text=valid_page)

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        with (
            patch("wechat_helper.adapter.DETAIL_REQUEST_ATTEMPTS", 1),
            patch("wechat_helper.adapter.DETAIL_REQUEST_TIMEOUT_SECONDS", 0.01),
            patch("wechat_helper.adapter.DETAIL_ROW_TIMEOUT_SECONDS", 0.05),
            patch(
                "wechat_helper.adapter.MAIMAI_DETAIL_REQUEST_INTERVAL_SECONDS",
                0,
            ),
            self.assertLogs("maimai_wechat_helper", level="WARNING") as logs,
        ):
            pages = await asyncio.wait_for(
                adapter._fetch_maimai_detail_pages(
                    {"session": "temporary"},
                    [stalled_id, "healthy-idx"],
                    progress_callback=on_progress,
                ),
                timeout=0.5,
            )

        self.assertEqual([None, valid_page], pages)
        self.assertEqual([stalled_id, "healthy-idx"], detail_http.calls)
        self.assertEqual(
            [
                SyncProgress("maimai", "play_details", 0, 2, 0),
                SyncProgress(
                    "maimai",
                    "play_details",
                    1,
                    2,
                    0,
                    failure_reasons={"request-timeout": 1},
                ),
                SyncProgress(
                    "maimai",
                    "play_details",
                    2,
                    2,
                    1,
                    failure_reasons={"request-timeout": 1},
                ),
            ],
            progress,
        )
        sanitized_logs = "\n".join(logs.output)
        self.assertIn("request-timeout", sanitized_logs)
        self.assertNotIn(stalled_id, sanitized_logs)

    async def test_maimai_unexpected_detail_failure_is_sanitized_and_isolated(
        self,
    ) -> None:
        valid_page = maimai_detail_html()
        private_id = "private-unexpected-idx"
        private_message = "private transport diagnostics"

        class DetailHttp:
            async def get(self, _url: str, **kwargs: object) -> object:
                idx = str(kwargs["params"]["idx"])  # type: ignore[index]
                if idx == private_id:
                    raise RuntimeError(f"{private_message}: {idx}")
                return SimpleNamespace(status_code=200, text=valid_page)

        fake = FakeClient()
        fake._client = DetailHttp()  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        with (
            patch("wechat_helper.adapter.DETAIL_REQUEST_ATTEMPTS", 1),
            self.assertLogs("maimai_wechat_helper", level="WARNING") as logs,
        ):
            pages = await adapter._fetch_maimai_detail_pages(
                {"session": "temporary"},
                [private_id, "healthy-idx"],
                progress_callback=on_progress,
            )

        self.assertEqual([None, valid_page], pages)
        self.assertEqual(
            SyncProgress(
                "maimai",
                "play_details",
                2,
                2,
                1,
                failure_reasons={"transport-failure": 1},
            ),
            progress[-1],
        )
        sanitized_logs = "\n".join(logs.output)
        self.assertIn("transport-failure", sanitized_logs)
        self.assertNotIn(private_id, sanitized_logs)
        self.assertNotIn(private_message, sanitized_logs)

    async def test_chunithm_detail_post_redirect_chains_are_serialized(self) -> None:
        class DetailHttp:
            def __init__(self) -> None:
                self.selected: str | None = None
                self.calls: list[str] = []
                self.post_payloads: list[dict[str, str]] = []

            async def post(self, _url: str, **kwargs: object) -> object:
                data = dict(kwargs["data"])  # type: ignore[arg-type]
                idx = data["idx"]
                self.selected = idx
                self.calls.append(f"post:{idx}")
                self.post_payloads.append(data)
                await asyncio.sleep(0)
                return SimpleNamespace(
                    status_code=302,
                    headers={"location": "/mobile/record/playlogDetail/"},
                    cookies={"selected": idx},
                )

            async def get(self, _url: str, **kwargs: object) -> object:
                await asyncio.sleep(0)
                selected = self.selected
                cookies = {
                    cookie.name: cookie.value
                    for cookie in kwargs["cookies"].jar  # type: ignore[union-attr,index]
                }
                self.calls.append(f"get:{selected}")
                self.assert_cookie = cookies.get("selected")
                return SimpleNamespace(
                    status_code=200,
                    headers={},
                    cookies={},
                    text=chunithm_detail_html(str(selected)),
                )

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        submit = (
            "https://chunithm.wahlap.com/mobile/record/playlog/"
            "sendPlaylogDetail/"
        )
        pages = await adapter._fetch_chunithm_detail_pages(
            {"session": "temporary"},
            [
                {"url": submit, "idx": "0", "token": "temporary-token-0"},
                {"url": submit, "idx": "1", "token": "temporary-token-1"},
            ],
        )

        self.assertEqual(
            ["post:0", "get:0", "post:1", "get:1"], detail_http.calls
        )
        self.assertEqual(
            [chunithm_detail_html("0"), chunithm_detail_html("1")],
            pages,
        )
        self.assertEqual(
            [
                {"idx": "0", "token": "temporary-token-0"},
                {"idx": "1", "token": "temporary-token-1"},
            ],
            detail_http.post_payloads,
        )
        self.assertEqual("1", detail_http.assert_cookie)

    async def test_chunithm_detail_retries_an_http_200_error_template(self) -> None:
        valid_page = chunithm_detail_html()

        class DetailHttp:
            def __init__(self) -> None:
                self.post_calls = 0

            async def post(self, _url: str, **kwargs: object) -> object:
                self.post_calls += 1
                self.data = dict(kwargs["data"])  # type: ignore[arg-type]
                return SimpleNamespace(
                    status_code=200,
                    headers={},
                    cookies={},
                    text=(
                        "<html><body><div>temporary error</div></body></html>"
                        if self.post_calls == 1
                        else valid_page
                    ),
                )

            async def get(self, _url: str, **_kwargs: object) -> object:
                raise AssertionError("a final HTTP 200 response must not redirect")

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        submit = (
            "https://chunithm.wahlap.com/mobile/record/playlog/"
            "sendPlaylogDetail/"
        )

        async def immediate_sleep(_delay: float) -> None:
            return None

        with patch("wechat_helper.adapter.asyncio.sleep", new=immediate_sleep):
            pages = await adapter._fetch_chunithm_detail_pages(
                {"session": "temporary"},
                [{"url": submit, "idx": "0", "token": "temporary-token"}],
            )

        self.assertEqual(2, detail_http.post_calls)
        self.assertEqual({"idx": "0", "token": "temporary-token"}, detail_http.data)
        self.assertEqual([valid_page], pages)

    async def test_chunithm_fetches_and_validates_all_fifty_detail_pages(self) -> None:
        valid_page = chunithm_detail_html()

        class DetailHttp:
            def __init__(self) -> None:
                self.post_payloads: list[dict[str, str]] = []

            async def post(self, _url: str, **kwargs: object) -> object:
                self.post_payloads.append(
                    dict(kwargs["data"])  # type: ignore[arg-type]
                )
                return SimpleNamespace(
                    status_code=200,
                    headers={},
                    cookies={},
                    text=valid_page,
                )

            async def get(self, _url: str, **_kwargs: object) -> object:
                raise AssertionError("a final HTTP 200 response must not redirect")

        fake = FakeClient()
        detail_http = DetailHttp()
        fake._client = detail_http  # type: ignore[attr-defined]
        adapter = MaimaiPyAdapter(
            config(), bindings=bindings(), client=fake, provider=object()
        )
        submit = (
            "https://chunithm.wahlap.com/mobile/record/playlog/"
            "sendPlaylogDetail/"
        )
        requests = [
            {
                "url": submit,
                "idx": str(ordinal),
                "token": f"temporary-token-{ordinal}",
            }
            for ordinal in range(50)
        ]

        async def immediate_sleep(_delay: float) -> None:
            return None

        progress: list[SyncProgress] = []

        async def on_progress(value: SyncProgress) -> None:
            progress.append(value)

        with patch("wechat_helper.adapter.asyncio.sleep", new=immediate_sleep):
            pages = await adapter._fetch_chunithm_detail_pages(
                {"session": "temporary"}, requests, on_progress
            )

        self.assertEqual(
            [
                {"idx": str(ordinal), "token": f"temporary-token-{ordinal}"}
                for ordinal in range(50)
            ],
            detail_http.post_payloads,
        )
        self.assertEqual([valid_page] * 50, pages)
        self.assertEqual(51, len(progress))
        self.assertEqual(
            SyncProgress("chunithm", "play_details", 0, 50, 0), progress[0]
        )
        self.assertEqual(
            SyncProgress("chunithm", "play_details", 50, 50, 50), progress[-1]
        )

    def test_detail_progress_rejects_invalid_or_secret_bearing_shapes(self) -> None:
        invalid = (
            ("unknown", "play_details", 0, 1, 0),
            (" MAIMAI ", "play_details", 0, 1, 0),
            ("maimai", "other", 0, 1, 0),
            ("maimai", "play_details", -1, 1, 0),
            ("maimai", "play_details", 2, 1, 1),
            ("maimai", "play_details", 1, 1, 2),
            ("maimai", "play_details", 0, 101, 0),
        )
        for values in invalid:
            with self.subTest(values=values):
                with self.assertRaises(ValueError):
                    SyncProgress(*values)  # type: ignore[arg-type]
        self.assertEqual(
            {
                "game",
                "stage",
                "completed",
                "total",
                "succeeded",
                "skipped",
                "failure_reasons",
            },
            {field.name for field in dataclasses.fields(SyncProgress)},
        )
        for reasons in (
            {"private-idx": 1},
            {"request-timeout": 2},
            {"request-timeout": 0},
        ):
            with self.subTest(reasons=reasons):
                with self.assertRaises(ValueError) as raised:
                    SyncProgress(
                        "maimai",
                        "play_details",
                        1,
                        1,
                        0,
                        failure_reasons=reasons,
                    )
                self.assertNotIn("private-idx", str(raised.exception))

    async def test_legacy_fallback_collapses_repeated_chart_rows(self) -> None:
        fake = FakeClient()
        first = fake.score_collection.scores[0]
        higher = SimpleNamespace(
            id=first.id,
            title=first.title,
            type=first.type,
            achievements=100.75,
            level_value=first.level_value,
            version=first.version,
            level_index=first.level_index,
            fc=None,
            fs=None,
        )
        fake.score_collection = SimpleNamespace(scores=[first, first, higher])
        adapter = MaimaiPyAdapter(
            config(),
            bindings=bindings(),
            client=fake,
            provider=object(),
            catalog_path=Path(tempfile.gettempdir()) / "missing-maimai-catalog.json",
        )
        stub_maimai_authorization(adapter)
        payload = await adapter.fetch_import_payload(
            CallbackParameters("r", "t", "code", "state"), SESSION_ID
        )
        self.assertEqual(1, len(payload["charts"]))
        self.assertEqual(100.75, payload["charts"][0]["achievement"])
        self.assertEqual("ap", payload["charts"][0]["comboStatus"])

    async def test_raw_official_scores_use_canonical_song_ids_without_title_drops(
        self,
    ) -> None:
        fake = FakeClient()
        raw_scores = [
            SimpleNamespace(
                title="Overjoy ★ OVERDOSE!!",
                type="DX",
                level_index=3,
                achievements=100.1234,
                dx_score=1234,
                rate="sssp",
                fc="fc",
                fs="",
                play_time=None,
            ),
            SimpleNamespace(
                title="DATAERR0R",
                type="DX",
                level_index=3,
                achievements=99.5,
                dx_score=2345,
                rate="ss",
                fc="",
                fs="",
                play_time=None,
            ),
            SimpleNamespace(
                # Full-width punctuation is a harmless title variation.
                title="Help me， ERINNNNNN！！",
                type="DX",
                level_index=3,
                achievements=100.0,
                dx_score=3456,
                rate="sss",
                fc="ap",
                fs="fdx",
                play_time=None,
            ),
        ]
        raw_records = [
            SimpleNamespace(
                title="DATAERR0R",
                type="DX",
                level_index=3,
                achievements=99.5,
                dx_score=2345,
                rate="ss",
                fc="",
                fs="",
                play_time=datetime(2026, 7, 16, 12, 0, 0),
                source_record_id="stable-official-idx",
            )
        ]

        async def fetch_raw(identifier: object) -> tuple[list[object], list[object]]:
            self.assertIsNotNone(identifier)
            self.assertEqual(
                {"session": "temporary"},
                getattr(identifier, "credentials", None),
            )
            return raw_scores, raw_records

        songs = [
            ("1848", "Overjoy ★ OVERDOSE!!"),
            ("1849", "DATAERR0R"),
            ("1853", "Help me, ERINNNNNN!!"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "maimai-catalog.json"
            catalog_path.write_text(
                json.dumps(
                    {
                        "schemaVersion": 1,
                        "generatedAt": "2026-07-16T00:00:00Z",
                        "sources": ["test"],
                        "songs": [
                            {
                                "songId": song_id,
                                "title": title,
                                "aliases": [],
                                "charts": [
                                    {
                                        "chartType": "DX",
                                        "difficulty": "MASTER",
                                        "levelValue": 14.0,
                                        "version": "current",
                                    }
                                ],
                            }
                            for song_id, title in songs
                        ],
                    },
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            adapter = MaimaiPyAdapter(
                config(),
                bindings=bindings(),
                client=fake,
                provider=object(),
                catalog_path=catalog_path,
                raw_score_fetcher=fetch_raw,
            )
            stub_maimai_authorization(adapter)
            payload = await adapter.fetch_import_payload(
                CallbackParameters("r", "t", "code", "state"), SESSION_ID
            )

        self.assertEqual(["1848", "1849", "1853"], [
            chart["songId"] for chart in payload["charts"]
        ])
        self.assertEqual("Help me, ERINNNNNN!!", payload["charts"][2]["title"])
        self.assertEqual("fsd", payload["charts"][2]["syncStatus"])
        self.assertEqual("1849", payload["records"][0]["songId"])
        self.assertEqual(
            "stable-official-idx", payload["records"][0]["sourceRecordId"]
        )
        self.assertNotIn("scores", fake.calls)
        self.assertNotIn("records", fake.calls)

    async def test_chunithm_rating_and_playlog_are_canonicalized(self) -> None:
        fake = FakeClient()
        rating = """
        <html><body>
          <form action="https://chunithm.wahlap.com/mobile/record/musicGenre/sendMusicDetail/">
            <input type="hidden" name="idx" value="42">
            <div class="musiclist_box bg_master">
              <div class="music_title">Re：End of a Dream</div>
              <div class="play_musicdata_highscore">分数：1,009,999</div>
            </div>
          </form>
        </body></html>
        """
        lower_rating = rating.replace("1,009,999", "1,000,000")
        playlog = """
        <html><body>
        <form action="/mobile/record/playlog/sendPlaylogDetail/">
        <input name="idx" value="0">
        <input name="token" value="temporary-token">
        <div class="frame02 w400">
          <div class="play_track_text">TRACK 2</div>
          <div class="play_datalist_date">2026/07/16 12:34</div>
          <div class="play_musicdata_title">Re：End of a Dream</div>
          <div class="play_musicdata_score_text">1,005,000</div>
          <div class="play_track_result">
            <img src="/mobile/images/musiclevel_master.png">
          </div>
          <div class="play_musicdata_icon">
            <img src="/mobile/images/icon_rank_12.png">
            <img src="/mobile/images/icon_clear.png">
            <img src="/mobile/images/icon_fullcombo.png">
          </div>
        </div></form></body></html>
        """
        detail = chunithm_detail_html()

        async def raw_chunithm(
            credentials: dict[str, str],
        ) -> tuple[list[str], str, list[str | None]]:
            self.assertEqual({"session": "temporary"}, credentials)
            return [lower_rating, rating, "<html></html>"], playlog, [detail]

        with tempfile.TemporaryDirectory() as directory:
            catalog_path = Path(directory) / "chunithm.json"
            catalog_path.write_text(
                json.dumps(
                    [
                        {
                            "id": 42,
                            "title": "Re:End of a Dream",
                            "ds": [3.0, 7.0, 11.0, 14.5],
                            "level": ["3", "7", "11", "14+"],
                            "cids": [1, 2, 3, 4],
                            "charts": [{}, {}, {}, {}],
                            "basic_info": {
                                "title": "Re:End of a Dream",
                                "from": "CHUNITHM VERSE",
                            },
                        }
                    ],
                    ensure_ascii=False,
                ),
                encoding="utf-8",
            )
            adapter = MaimaiPyAdapter(
                config(),
                bindings=bindings(),
                client=fake,
                provider=object(),
                chunithm_catalog_path=catalog_path,
                raw_chunithm_fetcher=raw_chunithm,
            )

            async def exchange(_callback: CallbackParameters) -> dict[str, str]:
                return {"session": "temporary"}

            adapter._exchange_chunithm_callback = exchange  # type: ignore[method-assign]
            with self.assertLogs("maimai_wechat_helper", level="INFO") as logs:
                payload = await adapter.fetch_import_payload(
                    CallbackParameters("r", "t", "code", "state"),
                    SESSION_ID,
                    "chunithm",
                )

        self.assertEqual("chunithm", payload["game"])
        self.assertIn(
            "CHUNITHM play details synchronized: requested=1 fetched=1 "
            "parsed=1 unavailable=0",
            "\n".join(logs.output),
        )
        self.assertEqual(1_009_999, payload["charts"][0]["score"])
        self.assertEqual("42", payload["charts"][0]["songId"])
        record = payload["records"][0]
        self.assertEqual("42", record["songId"])
        self.assertIsNone(record["sourceRecordId"])
        self.assertEqual("sss", record["rank"])
        self.assertEqual("clear", record["clearStatus"])
        self.assertEqual("fc", record["comboStatus"])
        self.assertEqual(2, record["track"])
        self.assertEqual("2026-07-16T04:34:00Z", record["playedAt"])
        self.assertNotIn("detailRequest", record)
        self.assertEqual(900, record["judgmentDetails"]["judgments"]["justiceCritical"])
        self.assertEqual(
            {
                "tap": 97.05,
                "hold": 100.98,
                "slide": 100.35,
                "air": 99.52,
                "flick": 96.93,
            },
            record["judgmentDetails"]["noteAchievements"],
        )
        self.assertNotIn("noteCounts", record["judgmentDetails"])
        self.assertEqual(995, record["judgmentDetails"]["maxCombo"])

    def test_maimai_play_detail_parses_real_judgments_and_play_fields(self) -> None:
        rows = [
            ("tap", [159, 144, 39, 4, 1]),
            ("hold", [21, 7, 4, 0, 0]),
            ("slide", [12, 0, 0, 0, 0]),
            ("touch", [28, 0, 0, 0, 0]),
            ("break", [1, 2, 0, 0, 1]),
        ]
        table_rows = "".join(
            "<tr><th><img src='/maimai-mobile/img/playlog/notes_"
            + note_type
            + ".png'></th>"
            + "".join(f"<td><span>{count}</span></td>" for count in counts)
            + "</tr>"
            for note_type, counts in rows
        )
        page = f"""
        <html><body>
          <table class="playlog_notes_detail t_r f_l f_11 f_b">
            <tr><th>NOTE</th><td><img alt="CP"></td><td><img alt="P"></td><td><img alt="GREAT"></td><td><img alt="GOOD"></td><td><img alt="MISS"></td></tr>
            {table_rows}
          </table>
          <div class="playlog_fl_block m_b_5 f_r f_12">
            <div class="w_96 f_l t_r"><div class="p_t_5">LATE <span>63</span></div></div>
            <div class="w_96 f_l t_r"><div class="p_t_5">FAST <span>137</span></div></div>
          </div>
          <div class=" f_r f_14 white"><img alt="MAX SYNC"> 8 / 1,139</div>
          <div class="f_r f_14 white"><img alt="MAX COMBO"> 408 / 423</div>
          <div class="playlog_score_block">DX SCORE <span>1,200 / 1,269</span></div>
          <div class="playlog_rating_detail_block">
            <div class="playlog_rating_val_block">7</div><span>(+9)</span>
          </div>
          <div class="basic_block m_t_5 p_3 t_r f_0"><span class="f_14">622</span></div>
          <div class="playlog_chara_container">
            <div class="playlog_chara_block"><img class="chara_cycle_img" src="/maimai-mobile/img/chara/101.png"></div>
            <div class="playlog_chara_star_block"><img alt="star"> 3</div>
            <div class="playlog_chara_lv_block">等级156</div>
          </div>
        </body></html>
        """

        judgments, play = _parse_maimai_play_detail_html(page)

        self.assertEqual(159, judgments["byNoteType"]["tap"]["criticalPerfect"])
        self.assertEqual(1, judgments["byNoteType"]["break"]["miss"])
        self.assertEqual(137, play["fast"])
        self.assertEqual(63, play["late"])
        self.assertEqual({"current": 408, "maximum": 423}, play["maxCombo"])
        self.assertEqual({"current": 8, "maximum": 1139}, play["maxSync"])
        self.assertEqual({"current": 1200, "maximum": 1269}, play["dxScore"])
        self.assertEqual(
            {"value": 7, "playerTotal": 622, "delta": 9}, play["rating"]
        )
        self.assertEqual(
            [{
                "stars": 3,
                "level": 156,
                "imageUrl": "https://maimai.wahlap.com/maimai-mobile/img/chara/101.png",
            }],
            play["partners"],
        )
        with self.assertRaises(AdapterError):
            _parse_maimai_play_detail_html(
                page.replace("408 / 423", "408 / 424")
            )

    def test_maimai_official_score_block_order_and_dx_rating_frame(self) -> None:
        judgments, play = _parse_maimai_play_detail_html(
            maimai_detail_html(dx_score=1234, rating_value=15123, rating_delta=-12)
        )

        self.assertEqual(80, sum(
            sum(counts.values())
            for counts in judgments["byNoteType"].values()
        ))
        self.assertEqual({"current": 1234, "maximum": 1500}, play["dxScore"])
        self.assertEqual({"current": 79, "maximum": 80}, play["maxCombo"])
        self.assertEqual({"current": 8, "maximum": 1139}, play["maxSync"])
        self.assertEqual(
            {
                "value": 15123,
                "delta": -12,
                "frame": "yellow",
                "frameImageUrl": (
                    "https://maimai.wahlap.com/maimai-mobile/img/"
                    "rating_base_yellow.png"
                ),
            },
            play["rating"],
        )

    def test_maimai_play_detail_ignores_html_comments(self) -> None:
        page = maimai_detail_html(dx_score=1234).replace(
            "<body>",
            "<body><!-- DX SCORE 999 / 999; MAX COMBO 999 / 999 -->",
            1,
        )

        judgments, play = _parse_maimai_play_detail_html(page)

        self.assertEqual(10, judgments["byNoteType"]["tap"]["criticalPerfect"])
        self.assertEqual(1234, play["dxScore"]["current"])
        self.assertEqual({"current": 79, "maximum": 80}, play["maxCombo"])

    def test_maimai_detail_accepts_a_labeled_td_but_rejects_asset_queries(self) -> None:
        note_rows = "".join(
            "<tr><td>" + note_type.upper() + "</td>"
            + "<td>1</td><td>2</td><td>3</td><td>4</td><td>5</td></tr>"
            for note_type in ("tap", "hold", "slide", "touch", "break")
        )
        page = f"""
        <html><body>
          <table class="playlog_notes_detail">{note_rows}</table>
          <div class="playlog_chara_container">
            <img class="chara_cycle_img" src="/maimai-mobile/img/chara/101.png?token=secret">
            <div class="playlog_chara_star_block">1</div>
            <div class="playlog_chara_lv_block">10</div>
          </div>
        </body></html>
        """
        judgments, play = _parse_maimai_play_detail_html(page)
        self.assertEqual(1, judgments["byNoteType"]["tap"]["criticalPerfect"])
        self.assertIsNone(play)

    def test_partner_asset_url_is_strictly_canonical_and_static(self) -> None:
        self.assertEqual(
            "https://maimai.wahlap.com/maimai-mobile/img/chara/a.PNG",
            _official_maimai_asset_url(
                "https://maimai.wahlap.com:443/maimai-mobile/img/chara/a.PNG"
            ),
        )
        invalid = (
            "/maimai-mobile/img/chara/a.png?token=secret",
            "/maimai-mobile/img/chara/a.png#fragment",
            "/maimai-mobile/img/%2e%2e/secret.png",
            "/maimai-mobile/img/../secret.png",
            "/maimai-mobile/img/chara\\a.png",
            "/maimai-mobile/img/chara/a.svg",
            "https://user:pass@maimai.wahlap.com/maimai-mobile/img/a.png",
            "https://maimai.wahlap.com:444/maimai-mobile/img/a.png",
            "https://example.com/maimai-mobile/img/a.png",
            "/maimai-mobile/img/chara/a.png\x7f",
            "/maimai-mobile/img/chara/" + "a" * 2048 + ".png",
        )
        for value in invalid:
            with self.subTest(value=value):
                self.assertIsNone(_official_maimai_asset_url(value))

    def test_chunithm_playlog_retains_only_a_temporary_detail_request(self) -> None:
        page = """
        <html><body>
        <form action="/mobile/record/playlog/sendPlaylogDetail/">
          <input name="idx" value="49">
          <input name="token" value="temporary-token">
          <div class="frame02 w400">
            <div class="play_track_text">TRACK 1</div>
            <div class="play_datalist_date">2026/07/16 12:34</div>
            <div class="play_musicdata_title">DATAERR0R</div>
            <div class="play_musicdata_score_text">1,000,000</div>
            <div class="play_track_result"><img src="/mobile/images/musiclevel_expert.png"></div>
          </div>
        </form>
        </body></html>
        """
        rows = _parse_chunithm_playlog_html(page)
        self.assertEqual(
            {
                "url": "https://chunithm.wahlap.com/mobile/record/playlog/sendPlaylogDetail/",
                "idx": "49",
                "token": "temporary-token",
            },
            rows[0]["detailRequest"],
        )

    def test_chunithm_detail_parser_uses_semantic_classes_and_percentages(self) -> None:
        page = chunithm_detail_html()
        detail = _parse_chunithm_judgment_detail_html(page)
        self.assertEqual(
            {"justiceCritical": 900, "justice": 90, "attack": 10, "miss": 5},
            detail["judgments"],
        )
        self.assertEqual(
            {
                "tap": 97.05,
                "hold": 100.98,
                "slide": 100.35,
                "air": 99.52,
                "flick": 96.93,
            },
            detail["noteAchievements"],
        )
        self.assertNotIn("noteCounts", detail)
        self.assertEqual(995, detail["maxCombo"])
        with self.assertRaises(AdapterError):
            _parse_chunithm_judgment_detail_html(
                page.replace("MAX COMBO 995", "MAX COMBO 1006")
            )

    def test_maimai_recent_title_uses_direct_text_and_retains_idx(self) -> None:
        raw = SimpleNamespace(title="wrong", marker="parsed")
        page = """
        <html><body><div class="t_l v_b p_10 f_0">
          <input name="idx" value="stable-123">
          <div class="basic_block break"><div>14+</div>DATAERR0R</div>
        </div></body></html>
        """
        records = _parse_maimai_record_html(page, lambda _row: raw)
        self.assertEqual(1, len(records))
        self.assertEqual("DATAERR0R", records[0].title)
        self.assertEqual("stable-123", records[0].source_record_id)
        self.assertEqual("parsed", records[0].marker)
        oversized = _parse_maimai_record_html(
            page.replace("stable-123", "x" * 257), lambda _row: raw
        )
        self.assertIsNone(oversized[0].source_record_id)

    def test_maimai_recent_idx_accepts_trusted_enclosing_detail_form(self) -> None:
        raw = SimpleNamespace(title="wrong", marker="parsed")
        for action in (
            "playlogDetail/",
            "/maimai-mobile/record/playlogDetail/",
            "https://maimai.wahlap.com/maimai-mobile/record/playlogDetail/",
        ):
            with self.subTest(action=action):
                page = f"""
                <html><body>
                  <form action="{action}">
                    <input type="hidden" name="idx" value="official-42">
                    <div class="t_l v_b p_10 f_0">
                      <div class="basic_block break">Help me, ERINNNNNN!!</div>
                    </div>
                  </form>
                </body></html>
                """
                records = _parse_maimai_record_html(page, lambda _row: raw)
                self.assertEqual("official-42", records[0].source_record_id)

    def test_maimai_recent_idx_rejects_untrusted_detail_form(self) -> None:
        raw = SimpleNamespace(title="wrong", marker="parsed")
        for action in (
            "https://evil.example/maimai-mobile/record/playlogDetail/",
            "https://maimai.wahlap.com.evil/maimai-mobile/record/playlogDetail/",
            "http://maimai.wahlap.com/maimai-mobile/record/playlogDetail/",
            "https://maimai.wahlap.com:444/maimai-mobile/record/playlogDetail/",
            "/maimai-mobile/record/playlogDetail/extra",
            "/maimai-mobile/record/playlogDetail/?next=record",
        ):
            with self.subTest(action=action):
                page = f"""
                <html><body>
                  <form action="{action}">
                    <input name="idx" value="should-not-leak">
                    <div class="t_l v_b p_10 f_0">
                      <div class="basic_block break">DATAERR0R</div>
                    </div>
                  </form>
                </body></html>
                """
                records = _parse_maimai_record_html(page, lambda _row: raw)
                self.assertIsNone(records[0].source_record_id)

    def test_maimai_recent_idx_rejects_multiple_or_conflicting_values(self) -> None:
        raw = SimpleNamespace(title="wrong", marker="parsed")
        for inputs in (
            '<input name="idx" value="same"><input name="idx" value="same">',
            '<input name="idx" value="first"><input name="idx" value="second">',
            '<input name="idx" value="only"><input name="idx">',
        ):
            with self.subTest(inputs=inputs):
                page = f"""
                <html><body>
                  <form action="/maimai-mobile/record/playlogDetail/">
                    {inputs}
                    <div class="t_l v_b p_10 f_0">
                      <div class="basic_block break">Overjoy OVERDOSE!!</div>
                    </div>
                  </form>
                </body></html>
                """
                records = _parse_maimai_record_html(page, lambda _row: raw)
                self.assertIsNone(records[0].source_record_id)


if __name__ == "__main__":
    unittest.main()
