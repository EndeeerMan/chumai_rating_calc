"""Small validation helpers kept independent from mitmproxy and maimai.py."""

from __future__ import annotations

from dataclasses import dataclass
import ipaddress
import re
from urllib.parse import SplitResult, urlsplit
import uuid


CALLBACK_HOST = "tgk-wcaime.wahlap.com"
CALLBACK_PATHS = {
    "maimai": "/wc_auth/oauth/callback/maimai-dx",
    "chunithm": "/wc_auth/oauth/callback/chunithm",
}
# Wahlap currently publishes ``maimai-dx`` in the authorization URL, while
# some Android WeChat flows have been observed continuing on the legacy
# ``maidx`` callback path.  Keep the allow-list explicit: both paths belong to
# maimai, and no prefix/wildcard matching is permitted.
CALLBACK_PATH_ALIASES = {
    "maimai": frozenset(
        {
            CALLBACK_PATHS["maimai"],
            "/wc_auth/oauth/callback/maidx",
        }
    ),
    "chunithm": frozenset({CALLBACK_PATHS["chunithm"]}),
}
# Kept as a compatibility name for callers that only handle maimai.
CALLBACK_PATH = CALLBACK_PATHS["maimai"]
SUPPORTED_GAMES = frozenset(CALLBACK_PATH_ALIASES)
BACKEND_IMPORT_PATH = "/api/sync/import"
BACKEND_SESSION_PATH_PREFIX = "/api/sync/sessions/"
SESSION_ID_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
)
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}")

_PRIVATE_V4 = tuple(
    ipaddress.ip_network(network)
    for network in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
)
_PRIVATE_V6 = ipaddress.ip_network("fc00::/7")


class ValidationError(ValueError):
    """Raised for a configuration or request that fails closed."""


def validate_game(value: str) -> str:
    if not isinstance(value, str):
        raise ValidationError("game must be text")
    normalized = value.strip().lower()
    if normalized not in SUPPORTED_GAMES:
        raise ValidationError("game must be maimai or chunithm")
    return normalized


def callback_game_for_path(path: str) -> str | None:
    """Return the game for one exact audited callback path."""
    if not isinstance(path, str):
        return None
    return next(
        (
            game
            for game, paths in CALLBACK_PATH_ALIASES.items()
            if path in paths
        ),
        None,
    )


def validate_callback_path(value: str, game: str) -> str:
    """Validate one exact callback path for the already-bound game."""
    normalized_game = validate_game(game)
    if (
        not isinstance(value, str)
        or value not in CALLBACK_PATH_ALIASES[normalized_game]
    ):
        raise ValidationError("callback path is not allowed for this game")
    return value


@dataclass(frozen=True, slots=True)
class BackendTarget:
    scheme: str
    host: str
    port: int

    @property
    def display_url(self) -> str:
        rendered_host = f"[{self.host}]" if ":" in self.host else self.host
        return f"{self.scheme}://{rendered_host}:{self.port}"


def validate_session_id(value: str) -> str:
    if not isinstance(value, str) or SESSION_ID_PATTERN.fullmatch(value) is None:
        raise ValidationError("sessionId must be a canonical UUID")
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError
    except ValueError as error:
        raise ValidationError("sessionId must be a canonical UUID") from error
    return value


def validate_helper_token(value: str) -> str:
    if not isinstance(value, str) or TOKEN_PATTERN.fullmatch(value) is None:
        raise ValidationError("token has an invalid format")
    return value


def backend_session_event_path(session_id: str) -> str:
    """Build the only per-session helper endpoint this process may call."""
    safe_session_id = validate_session_id(session_id)
    return f"{BACKEND_SESSION_PATH_PREFIX}{safe_session_id}/events"


def validate_short_secret(value: str, field: str, maximum: int) -> str:
    if not isinstance(value, str) or not value or len(value) > maximum:
        raise ValidationError(f"{field} has an invalid format")
    if any(ord(character) < 0x20 for character in value):
        raise ValidationError(f"{field} has an invalid format")
    return value


def is_loopback_or_private_host(host: str) -> bool:
    normalized = host.strip().lower()
    if normalized == "localhost":
        return True
    if not normalized or "%" in normalized:
        return False
    try:
        address = ipaddress.ip_address(normalized)
    except ValueError:
        return False
    if address.is_unspecified or address.is_multicast or address.is_link_local:
        return False
    if address.is_loopback:
        return True
    if isinstance(address, ipaddress.IPv4Address):
        return any(address in network for network in _PRIVATE_V4)
    mapped = address.ipv4_mapped
    if mapped is not None:
        return mapped.is_loopback or any(mapped in network for network in _PRIVATE_V4)
    return address in _PRIVATE_V6


def validate_listen_host(host: str) -> str:
    if not isinstance(host, str) or not host.strip():
        raise ValidationError("listen host must not be blank")
    normalized = host.strip()
    if normalized in {"0.0.0.0", "::"}:
        return normalized
    if not is_loopback_or_private_host(normalized):
        raise ValidationError(
            "listen host must be a loopback, private, or wildcard IP address"
        )
    return normalized


def parse_backend_target(value: str) -> BackendTarget:
    if not isinstance(value, str) or not value.strip():
        raise ValidationError("backend URL must not be blank")
    try:
        parsed = urlsplit(value.strip())
        port = parsed.port
    except ValueError as error:
        raise ValidationError("backend URL is invalid") from error
    _validate_backend_parts(parsed)
    assert parsed.hostname is not None
    host = parsed.hostname.lower()
    if host == "localhost":
        host = "127.0.0.1"
    if not is_loopback_or_private_host(host):
        raise ValidationError(
            "backend host must be a literal loopback/private IP or localhost"
        )
    resolved_port = port if port is not None else (443 if parsed.scheme == "https" else 80)
    if resolved_port < 1 or resolved_port > 65535:
        raise ValidationError("backend port is out of range")
    return BackendTarget(parsed.scheme, host, resolved_port)


def _validate_backend_parts(parsed: SplitResult) -> None:
    if parsed.scheme not in {"http", "https"}:
        raise ValidationError("backend URL scheme must be http or https")
    if parsed.hostname is None:
        raise ValidationError("backend URL must contain a host")
    if parsed.username is not None or parsed.password is not None:
        raise ValidationError("backend URL must not contain user information")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValidationError(
            "backend URL must contain only scheme, host, and optional port"
        )
