"""Environment-only configuration with fail-closed validation."""

from __future__ import annotations

from dataclasses import dataclass
import math
import os
from typing import Mapping

from .security import BackendTarget, ValidationError, parse_backend_target, validate_listen_host


FIXED_LISTEN_PORT = 8081
FIXED_SESSION_TTL_SECONDS = 900


@dataclass(frozen=True, slots=True)
class Config:
    listen_host: str
    listen_port: int
    backend: BackendTarget
    session_ttl_seconds: int
    max_pending_sessions: int
    upstream_timeout_seconds: float
    backend_timeout_seconds: float
    play_utc_offset_minutes: int

    @classmethod
    def from_env(cls, environ: Mapping[str, str] | None = None) -> "Config":
        values = os.environ if environ is None else environ
        listen_host = validate_listen_host(
            values.get("MAIMAI_WECHAT_LISTEN_HOST", "0.0.0.0")
        )
        listen_port = _fixed_integer(
            values,
            "MAIMAI_WECHAT_LISTEN_PORT",
            FIXED_LISTEN_PORT,
        )
        backend = parse_backend_target(
            values.get("MAIMAI_WECHAT_BACKEND_URL", "http://127.0.0.1:8080")
        )
        ttl = _fixed_integer(
            values,
            "MAIMAI_WECHAT_SESSION_TTL_SECONDS",
            FIXED_SESSION_TTL_SECONDS,
        )
        max_sessions = _integer(
            values,
            "MAIMAI_WECHAT_MAX_PENDING_SESSIONS",
            32,
            minimum=1,
            maximum=128,
        )
        upstream_timeout = _number(
            values,
            "MAIMAI_WECHAT_UPSTREAM_TIMEOUT_SECONDS",
            300.0,
            minimum=10.0,
            maximum=600.0,
        )
        backend_timeout = _number(
            values,
            "MAIMAI_WECHAT_BACKEND_TIMEOUT_SECONDS",
            30.0,
            minimum=2.0,
            maximum=120.0,
        )
        play_offset = _integer(
            values,
            "MAIMAI_WECHAT_PLAY_UTC_OFFSET_MINUTES",
            480,
            minimum=-720,
            maximum=840,
        )
        return cls(
            listen_host=listen_host,
            listen_port=listen_port,
            backend=backend,
            session_ttl_seconds=ttl,
            max_pending_sessions=max_sessions,
            upstream_timeout_seconds=upstream_timeout,
            backend_timeout_seconds=backend_timeout,
            play_utc_offset_minutes=play_offset,
        )

    def sanitized_summary(self) -> str:
        return (
            f"listen={self.listen_host}:{self.listen_port}, "
            f"backend={self.backend.display_url}, "
            f"session_ttl={self.session_ttl_seconds}s, "
            f"play_utc_offset={self.play_utc_offset_minutes:+d}m"
        )


def _integer(
    values: Mapping[str, str],
    name: str,
    default: int,
    *,
    minimum: int,
    maximum: int,
) -> int:
    raw = values.get(name)
    try:
        result = default if raw is None else int(raw, 10)
    except (TypeError, ValueError) as error:
        raise ValidationError(f"{name} must be an integer") from error
    if not math.isfinite(result) or result < minimum or result > maximum:
        raise ValidationError(f"{name} is out of range")
    return result


def _fixed_integer(
    values: Mapping[str, str],
    name: str,
    expected: int,
) -> int:
    raw = values.get(name)
    if raw is None:
        return expected
    try:
        actual = int(raw, 10)
    except (TypeError, ValueError) as error:
        raise ValidationError(f"{name} must be {expected}") from error
    if actual != expected:
        raise ValidationError(f"{name} is fixed at {expected}")
    return expected


def _number(
    values: Mapping[str, str],
    name: str,
    default: float,
    *,
    minimum: float,
    maximum: float,
) -> float:
    raw = values.get(name)
    try:
        result = default if raw is None else float(raw)
    except (TypeError, ValueError) as error:
        raise ValidationError(f"{name} must be a number") from error
    if not math.isfinite(result) or result < minimum or result > maximum:
        raise ValidationError(f"{name} is out of range")
    return result
