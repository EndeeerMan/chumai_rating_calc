from __future__ import annotations

import unittest

from wechat_helper.config import (
    FIXED_LISTEN_PORT,
    FIXED_SESSION_TTL_SECONDS,
    Config,
)
from wechat_helper.security import (
    ValidationError,
    backend_session_event_path,
    callback_game_for_path,
    is_loopback_or_private_host,
    parse_backend_target,
    validate_helper_token,
    validate_callback_path,
    validate_session_id,
)


SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
TOKEN = "A" * 43


class SecurityTests(unittest.TestCase):
    def test_only_exact_audited_callback_paths_are_accepted(self) -> None:
        for path in (
            "/wc_auth/oauth/callback/maimai-dx",
            "/wc_auth/oauth/callback/maidx",
        ):
            self.assertEqual("maimai", callback_game_for_path(path))
            self.assertEqual(path, validate_callback_path(path, "maimai"))
        self.assertEqual(
            "chunithm",
            callback_game_for_path("/wc_auth/oauth/callback/chunithm"),
        )
        for path in (
            "/wc_auth/oauth/callback/maidx/extra",
            "/wc_auth/oauth/callback/mai",
            "",
        ):
            self.assertIsNone(callback_game_for_path(path))
            with self.assertRaises(ValidationError):
                validate_callback_path(path, "maimai")

    def test_session_and_token_are_canonical_and_path_safe(self) -> None:
        self.assertEqual(SESSION_ID, validate_session_id(SESSION_ID))
        self.assertEqual(TOKEN, validate_helper_token(TOKEN))
        self.assertEqual(
            f"/api/sync/sessions/{SESSION_ID}/events",
            backend_session_event_path(SESSION_ID),
        )
        for bad in (SESSION_ID.upper(), "../events", "", None):
            with self.assertRaises(ValidationError):
                validate_session_id(bad)  # type: ignore[arg-type]
        with self.assertRaises(ValidationError):
            validate_helper_token("short")

    def test_only_loopback_and_private_networks_are_accepted(self) -> None:
        for host in ("localhost", "127.0.0.1", "192.168.1.4", "10.0.0.2", "::1"):
            self.assertTrue(is_loopback_or_private_host(host), host)
        for host in ("8.8.8.8", "example.com", "169.254.1.1", "0.0.0.0"):
            self.assertFalse(is_loopback_or_private_host(host), host)

    def test_backend_target_has_no_path_credentials_or_public_host(self) -> None:
        target = parse_backend_target("http://localhost:8080")
        self.assertEqual(("http", "127.0.0.1", 8080), (target.scheme, target.host, target.port))
        for value in (
            "http://8.8.8.8:8080",
            "http://user:pass@127.0.0.1:8080",
            "http://127.0.0.1:8080/api",
            "http://127.0.0.1:0",
            "ftp://127.0.0.1:8080",
        ):
            with self.assertRaises(ValidationError):
                parse_backend_target(value)


class ConfigTests(unittest.TestCase):
    def test_fixed_contract_and_default_backend(self) -> None:
        config = Config.from_env({})
        self.assertEqual(FIXED_LISTEN_PORT, config.listen_port)
        self.assertEqual(FIXED_SESSION_TTL_SECONDS, config.session_ttl_seconds)
        self.assertEqual("http://127.0.0.1:8080", config.backend.display_url)

    def test_explicit_fixed_values_are_allowed(self) -> None:
        config = Config.from_env(
            {
                "MAIMAI_WECHAT_LISTEN_PORT": "8081",
                "MAIMAI_WECHAT_SESSION_TTL_SECONDS": "900",
            }
        )
        self.assertEqual(8081, config.listen_port)
        self.assertEqual(900, config.session_ttl_seconds)

    def test_port_and_ttl_cannot_drift_from_web_contract(self) -> None:
        for environment in (
            {"MAIMAI_WECHAT_LISTEN_PORT": "8082"},
            {"MAIMAI_WECHAT_LISTEN_PORT": "not-a-port"},
            {"MAIMAI_WECHAT_SESSION_TTL_SECONDS": "901"},
            {"MAIMAI_WECHAT_SESSION_TTL_SECONDS": "not-a-ttl"},
        ):
            with self.subTest(environment=environment):
                with self.assertRaises(ValidationError):
                    Config.from_env(environment)

    def test_non_finite_timeouts_are_rejected(self) -> None:
        for name in (
            "MAIMAI_WECHAT_UPSTREAM_TIMEOUT_SECONDS",
            "MAIMAI_WECHAT_BACKEND_TIMEOUT_SECONDS",
        ):
            for value in ("nan", "inf", "-inf"):
                with self.subTest(name=name, value=value):
                    with self.assertRaises(ValidationError):
                        Config.from_env({name: value})


if __name__ == "__main__":
    unittest.main()
