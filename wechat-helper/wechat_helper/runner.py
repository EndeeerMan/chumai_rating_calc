"""CLI and embedded mitmproxy runner."""

from __future__ import annotations

import argparse
import asyncio
from importlib import metadata
import inspect
import logging
import sys
from typing import Any, Sequence

from . import __version__
from .adapter import CapabilityError
from .config import Config
from .security import ValidationError


EXPECTED_MITMPROXY_VERSION = "12.1.1"
SENSITIVE_NETWORK_LOGGERS = ("httpx", "httpcore", "mitmproxy")
# A regular HTTP proxy client may use CONNECT for an ordinary port-80 URL
# (Clash does this for an ``http`` proxy node).  Ignoring every CONNECT target
# would therefore tunnel Wahlap's plain-HTTP OAuth callback past the audited
# addon.  Keep every non-80 destination opaque, but let CONNECT :80 enter the
# HTTP layer so the callback can be validated and consumed.
NON_HTTP_CONNECT_PASSTHROUGH_PATTERN = r":(?!80$)\d+$"


class ListenerStartupError(RuntimeError):
    """Raised when the fixed local proxy listener cannot be established."""


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="舞萌 DX / 中二节奏微信同步辅助程序"
    )
    parser.add_argument(
        "--dry-run",
        "--check",
        action="store_true",
        dest="dry_run",
        help="只校验环境、配置和依赖 API，不监听端口或联网",
    )
    parser.add_argument("--version", action="version", version=__version__)
    args = parser.parse_args(argv)
    try:
        from .adapter import load_bindings

        config = Config.from_env()
        load_bindings()
        _check_mitmproxy()
    except (ValidationError, CapabilityError) as error:
        print(f"配置或依赖检查失败：{error}", file=sys.stderr)
        return 2

    if args.dry_run:
        print(f"检查通过：{config.sanitized_summary()}")
        print(
            "能力：舞萌 DX / 中二节奏微信最佳成绩与最近游玩；规范 SongID"
        )
        return 0

    _suppress_sensitive_network_logs()
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    try:
        asyncio.run(_run(config))
    except KeyboardInterrupt:
        return 0
    except ListenerStartupError:
        print(
            "辅助程序无法监听 8081 端口；请关闭占用该端口的程序后重试。",
            file=sys.stderr,
        )
        return 1
    except Exception as error:
        # Runtime exception messages can contain request URLs; print only the type.
        print(f"辅助程序意外停止（{type(error).__name__}）", file=sys.stderr)
        return 1
    return 0


def _check_mitmproxy() -> None:
    try:
        installed = metadata.version("mitmproxy")
    except metadata.PackageNotFoundError as error:
        raise CapabilityError("mitmproxy is not installed") from error
    if installed != EXPECTED_MITMPROXY_VERSION:
        raise CapabilityError(
            f"mitmproxy {EXPECTED_MITMPROXY_VERSION} is required; found {installed}"
        )
    try:
        from mitmproxy.addons import default_addons
        from mitmproxy.master import Master
        from mitmproxy.options import Options
    except (ImportError, AttributeError) as error:
        raise CapabilityError("mitmproxy embedded API is unavailable") from error
    if not callable(default_addons) or not inspect.iscoroutinefunction(Master.run):
        raise CapabilityError("mitmproxy embedded API is incompatible")
    if Options is None:
        raise CapabilityError("mitmproxy Options API is incompatible")


async def _run(config: Config) -> None:
    from .adapter import MaimaiPyAdapter
    from .addon import WechatHelperAddon
    from .backend import BackendClient
    from mitmproxy.addons import default_addons
    from mitmproxy.master import Master
    from mitmproxy.options import Options

    # Keep this guard here as well: tests and embedders may call _run()
    # directly instead of entering through main().
    _suppress_sensitive_network_logs()
    adapter = MaimaiPyAdapter(config)
    backend = BackendClient(config.backend, config.backend_timeout_seconds)
    addon = WechatHelperAddon(config, adapter, backend)
    master = Master(Options())
    master.addons.add(*default_addons())
    master.addons.add(addon)
    _configure_proxy_options(master.options, config)
    try:
        await _ensure_proxy_listener(master)
        logging.getLogger("maimai_wechat_helper").info(
            "helper ready (%s); only audited game callbacks are accepted",
            config.sanitized_summary(),
        )
        await master.run()
    finally:
        await addon.shutdown()
        await adapter.close()


def _configure_proxy_options(options: Any, config: Config) -> None:
    # In regular mode, ignore_hosts applies once CONNECT has supplied a
    # destination.  Preserve opaque tunnels on every non-80 port, while still
    # parsing CONNECT :80 clients (including Clash HTTP proxy nodes).
    options.update(
        listen_host=config.listen_host,
        listen_port=config.listen_port,
        mode=["regular"],
        ignore_hosts=[NON_HTTP_CONNECT_PASSTHROUGH_PATTERN],
        allow_hosts=[],
        show_ignored_hosts=False,
        tcp_hosts=[],
        udp_hosts=[],
    )


async def _ensure_proxy_listener(master: object) -> None:
    """Bind the configured listener and fail if mitmproxy only logged an error.

    Embedded ``Master`` instances do not include mitmdump's ErrorCheck addon.
    ``Proxyserver.setup_servers()`` reports bind failures as ``False`` instead
    of raising, so an explicit check is required before claiming readiness.
    """
    addons = getattr(master, "addons", None)
    proxyserver = None if addons is None else addons.get("proxyserver")
    if proxyserver is None or not await proxyserver.setup_servers():
        raise ListenerStartupError("proxy listener failed to start")


def _suppress_sensitive_network_logs() -> None:
    """Drop dependency request logs that may contain one-time OAuth secrets."""
    for name in SENSITIVE_NETWORK_LOGGERS:
        logger = logging.getLogger(name)
        logger.setLevel(logging.CRITICAL)
        logger.handlers.clear()
        logger.addHandler(logging.NullHandler())
        logger.propagate = False
