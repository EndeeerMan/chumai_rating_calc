from __future__ import annotations

import asyncio
from io import StringIO
import logging
import re
from types import SimpleNamespace
import unittest
from urllib.parse import urlsplit
from unittest.mock import AsyncMock, Mock, patch

import httpx
from mitmproxy import http
from mitmproxy.addons import default_addons
from mitmproxy.master import Master
from mitmproxy.options import Options

from wechat_helper.config import Config
from wechat_helper.runner import (
    NON_HTTP_CONNECT_PASSTHROUGH_PATTERN,
    SENSITIVE_NETWORK_LOGGERS,
    _configure_proxy_options,
    _run,
    _suppress_sensitive_network_logs,
)
from wechat_helper.security import BackendTarget


class SensitiveLoggingTests(unittest.TestCase):
    def test_httpx_oauth_query_is_suppressed_but_helper_status_remains(self) -> None:
        root = logging.getLogger()
        dependency_state = {
            name: (
                logging.getLogger(name).level,
                logging.getLogger(name).propagate,
                list(logging.getLogger(name).handlers),
            )
            for name in SENSITIVE_NETWORK_LOGGERS
        }
        old_root_level = root.level
        output = StringIO()
        capture = logging.StreamHandler(output)
        root.addHandler(capture)
        root.setLevel(logging.INFO)
        try:
            _suppress_sensitive_network_logs()
            secret_url = (
                "https://tgk-wcaime.wahlap.com/wc_auth/oauth/callback/maimai-dx"
                "?r=SECRET_R&t=SECRET_T&code=SECRET_CODE&state=SECRET_STATE"
            )
            transport = httpx.MockTransport(
                lambda _request: httpx.Response(200, text="ok")
            )
            with httpx.Client(transport=transport) as client:
                client.get(secret_url)
            logging.getLogger("maimai_wechat_helper").info(
                "helper-status-visible"
            )
        finally:
            root.removeHandler(capture)
            root.setLevel(old_root_level)
            capture.close()
            for name, (level, propagate, handlers) in dependency_state.items():
                logger = logging.getLogger(name)
                logger.handlers.clear()
                logger.handlers.extend(handlers)
                logger.setLevel(level)
                logger.propagate = propagate

        logged = output.getvalue()
        self.assertIn("helper-status-visible", logged)
        for secret in ("SECRET_R", "SECRET_T", "SECRET_CODE", "SECRET_STATE"):
            self.assertNotIn(secret, logged)


class RunnerStartupTests(unittest.IsolatedAsyncioTestCase):
    async def test_regular_proxy_audits_connect_port_80_and_tunnels_other_ports(
        self,
    ) -> None:
        config = Config(
            listen_host="0.0.0.0",
            listen_port=8081,
            backend=BackendTarget("http", "127.0.0.1", 8080),
            session_ttl_seconds=900,
            max_pending_sessions=32,
            upstream_timeout_seconds=300.0,
            backend_timeout_seconds=30.0,
            play_utc_offset_minutes=480,
        )
        adapter = SimpleNamespace(close=AsyncMock())
        backend = object()
        addon = SimpleNamespace(shutdown=AsyncMock())
        default_items = (object(), object())
        masters = []

        class FakeAddonManager:
            def __init__(self) -> None:
                self.added: list[object] = []

            def add(self, *items: object) -> None:
                self.added.extend(items)

        class FakeMaster:
            def __init__(self, options: object) -> None:
                self.options = options
                self.addons = FakeAddonManager()
                self.run = AsyncMock()
                masters.append(self)

        adapter_factory = Mock(return_value=adapter)
        backend_factory = Mock(return_value=backend)
        addon_factory = Mock(return_value=addon)
        ensure_listener = AsyncMock()
        with (
            patch("wechat_helper.adapter.MaimaiPyAdapter", adapter_factory),
            patch("wechat_helper.backend.BackendClient", backend_factory),
            patch("wechat_helper.addon.WechatHelperAddon", addon_factory),
            patch("mitmproxy.master.Master", FakeMaster),
            patch("mitmproxy.addons.default_addons", return_value=default_items),
            patch("wechat_helper.runner._ensure_proxy_listener", ensure_listener),
            patch("wechat_helper.runner._suppress_sensitive_network_logs"),
        ):
            await _run(config)

        self.assertEqual(1, len(masters))
        master = masters[0]
        self.assertEqual("0.0.0.0", master.options.listen_host)
        self.assertEqual(8081, master.options.listen_port)
        self.assertEqual(["regular"], master.options.mode)
        self.assertEqual([r":(?!80$)\d+$"], master.options.ignore_hosts)
        self.assertEqual([], master.options.allow_hosts)
        self.assertFalse(master.options.show_ignored_hosts)
        self.assertEqual([], master.options.tcp_hosts)
        self.assertEqual([], master.options.udp_hosts)
        self.assertEqual([*default_items, addon], master.addons.added)
        ensure_listener.assert_awaited_once_with(master)
        master.run.assert_awaited_once_with()
        adapter.close.assert_awaited_once_with()
        addon.shutdown.assert_awaited_once_with()
        adapter_factory.assert_called_once_with(config)
        backend_factory.assert_called_once_with(config.backend, 30.0)
        addon_factory.assert_called_once_with(config, adapter, backend)


class RealConnectRoutingTests(unittest.IsolatedAsyncioTestCase):
    async def test_connect_port_80_inner_callback_reaches_http_addon(self) -> None:
        """Exercise mitmproxy's real CONNECT parser without external network.

        Clash may send a plain HTTP callback through an HTTP proxy node as a
        CONNECT tunnel.  mitmproxy must connect to the target before accepting
        that tunnel, so a loopback sink temporarily stands in for Wahlap:80.
        """

        async def upstream_sink(
            reader: asyncio.StreamReader,
            writer: asyncio.StreamWriter,
        ) -> None:
            try:
                await reader.read()
            finally:
                writer.close()
                await writer.wait_closed()

        try:
            upstream = await asyncio.start_server(upstream_sink, "127.0.0.1", 80)
        except OSError as error:
            self.skipTest(f"loopback port 80 is unavailable: {type(error).__name__}")

        class CallbackProbe:
            def __init__(self) -> None:
                self.urls: list[str] = []
                self.hit = asyncio.Event()

            async def request(self, flow: object) -> None:
                request = flow.request  # type: ignore[attr-defined]
                parsed = urlsplit(request.pretty_url)
                if (
                    parsed.scheme == "http"
                    and parsed.hostname == "tgk-wcaime.wahlap.com"
                    and parsed.path == "/wc_auth/oauth/callback/maimai-dx"
                ):
                    self.urls.append(request.pretty_url)
                    flow.response = http.Response.make(  # type: ignore[attr-defined]
                        200,
                        b"AUDITED_CALLBACK_OK",
                        {"Content-Type": "text/plain"},
                    )
                    self.hit.set()

        config = Config(
            listen_host="127.0.0.1",
            listen_port=0,
            backend=BackendTarget("http", "127.0.0.1", 8080),
            session_ttl_seconds=900,
            max_pending_sessions=32,
            upstream_timeout_seconds=300.0,
            backend_timeout_seconds=30.0,
            play_utc_offset_minutes=480,
        )
        master = Master(Options())
        master.addons.add(*default_addons())
        probe = CallbackProbe()
        master.addons.add(probe)
        _configure_proxy_options(master.options, config)
        proxyserver = master.addons.get("proxyserver")
        self.assertIsNotNone(proxyserver)
        self.assertTrue(await proxyserver.setup_servers())  # type: ignore[union-attr]
        listen_addrs = proxyserver.listen_addrs()  # type: ignore[union-attr]
        self.assertEqual(1, len(listen_addrs))
        proxy_host, proxy_port = listen_addrs[0]
        run_task = asyncio.create_task(master.run())
        writer: asyncio.StreamWriter | None = None
        try:
            reader, writer = await asyncio.open_connection(proxy_host, proxy_port)
            writer.write(
                b"CONNECT 127.0.0.1:80 HTTP/1.1\r\n"
                b"Host: 127.0.0.1:80\r\n"
                b"Proxy-Connection: keep-alive\r\n\r\n"
            )
            await writer.drain()
            connect_response = await asyncio.wait_for(
                reader.readuntil(b"\r\n\r\n"),
                5.0,
            )
            self.assertIn(b" 200 ", connect_response.split(b"\r\n", 1)[0])

            writer.write(
                b"GET /wc_auth/oauth/callback/maimai-dx?probe=redacted HTTP/1.1\r\n"
                b"Host: tgk-wcaime.wahlap.com:80\r\n"
                b"Connection: close\r\n\r\n"
            )
            await writer.drain()
            response = await asyncio.wait_for(reader.read(), 5.0)
            await asyncio.wait_for(probe.hit.wait(), 1.0)

            self.assertIn(b"HTTP/1.1 200", response)
            self.assertIn(b"AUDITED_CALLBACK_OK", response)
            self.assertEqual(1, len(probe.urls))
            self.assertNotIn("SECRET", probe.urls[0])
        finally:
            if writer is not None:
                writer.close()
                await writer.wait_closed()
            master.shutdown()
            await asyncio.wait_for(run_task, 5.0)
            await proxyserver.servers.update([])  # type: ignore[union-attr]
            upstream.close()
            await upstream.wait_closed()

    def test_every_non_http_connect_port_remains_passthrough(self) -> None:
        self.assertIsNone(
            re.search(
                NON_HTTP_CONNECT_PASSTHROUGH_PATTERN,
                "tgk-wcaime.wahlap.com:80",
            )
        )
        for destination in (
            "tgk-wcaime.wahlap.com:443",
            "tgk-wcaime.wahlap.com:8443",
            "127.0.0.1:443",
            "[::1]:443",
        ):
            with self.subTest(destination=destination):
                self.assertIsNotNone(
                    re.search(NON_HTTP_CONNECT_PASSTHROUGH_PATTERN, destination)
                )


if __name__ == "__main__":
    unittest.main()
