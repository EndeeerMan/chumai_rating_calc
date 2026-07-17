"""Short-lived in-memory binding between OAuth state and a backend session."""

from __future__ import annotations

from dataclasses import dataclass, field
import hmac
import time
from typing import Callable

from .security import (
    CALLBACK_PATHS,
    validate_callback_path,
    validate_game,
    validate_helper_token,
    validate_session_id,
    validate_short_secret,
)


class SessionError(RuntimeError):
    """Raised when a pending authorization cannot be registered or claimed."""


@dataclass(frozen=True, slots=True)
class OAuthBinding:
    url: str = field(repr=False)
    state: str = field(repr=False)
    expected_r: str = field(repr=False)
    expected_t: str = field(repr=False)
    game: str = "maimai"
    callback_path: str | None = None


@dataclass(frozen=True, slots=True)
class AuthorizationLease:
    session_id: str
    token: str = field(repr=False)
    game: str = "maimai"
    callback_path: str = CALLBACK_PATHS["maimai"]


@dataclass(frozen=True, slots=True)
class _PendingAuthorization:
    session_id: str
    token: str = field(repr=False)
    url: str = field(repr=False)
    state: str = field(repr=False)
    expected_r: str = field(repr=False)
    expected_t: str = field(repr=False)
    game: str
    callback_path: str
    expires_at: float


class PendingSessionStore:
    def __init__(
        self,
        ttl_seconds: int,
        max_sessions: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        if ttl_seconds < 1 or max_sessions < 1:
            raise ValueError("session limits must be positive")
        self._ttl_seconds = ttl_seconds
        self._max_sessions = max_sessions
        self._clock = clock
        self._by_state: dict[str, _PendingAuthorization] = {}
        self._state_by_session: dict[str, str] = {}

    def register(
        self,
        session_id: str,
        token: str,
        oauth: OAuthBinding,
    ) -> None:
        session_id = validate_session_id(session_id)
        token = validate_helper_token(token)
        url = validate_short_secret(oauth.url, "authorization URL", 8192)
        validate_short_secret(oauth.state, "state", 512)
        validate_short_secret(oauth.expected_r, "r", 2048)
        validate_short_secret(oauth.expected_t, "t", 2048)
        game = validate_game(oauth.game)
        callback_path = validate_callback_path(
            oauth.callback_path or CALLBACK_PATHS[game],
            game,
        )
        self._purge_expired()
        if session_id in self._state_by_session:
            raise SessionError("this sync session is already waiting for authorization")
        if oauth.state in self._by_state:
            raise SessionError("OAuth state collision; create a new sync session")
        if len(self._by_state) >= self._max_sessions:
            raise SessionError("too many pending sync sessions")
        pending = _PendingAuthorization(
            session_id=session_id,
            token=token,
            url=url,
            state=oauth.state,
            expected_r=oauth.expected_r,
            expected_t=oauth.expected_t,
            game=game,
            callback_path=callback_path,
            expires_at=self._clock() + self._ttl_seconds,
        )
        self._by_state[oauth.state] = pending
        self._state_by_session[session_id] = oauth.state

    def claim(
        self,
        state: str,
        r_value: str,
        t_value: str,
        game: str = "maimai",
        callback_path: str | None = None,
    ) -> AuthorizationLease:
        validate_short_secret(state, "state", 512)
        validate_short_secret(r_value, "r", 2048)
        validate_short_secret(t_value, "t", 2048)
        expected_game = validate_game(game)
        incoming_path = validate_callback_path(
            callback_path or CALLBACK_PATHS[expected_game],
            expected_game,
        )
        self._purge_expired()
        pending = self._by_state.get(state)
        if pending is None:
            raise SessionError("authorization session is missing, expired, or already used")
        if not hmac.compare_digest(pending.expected_r, r_value) or not hmac.compare_digest(
            pending.expected_t, t_value
        ):
            raise SessionError("OAuth callback does not match the pending session")
        if pending.game != expected_game:
            raise SessionError("OAuth callback game does not match the pending session")
        # The two explicitly audited maimai callback aliases are equivalent.
        # Validating both the OAuth-bound and incoming paths keeps this exact
        # and fail-closed without relying on a path prefix.
        validate_callback_path(pending.callback_path, pending.game)
        self._remove(pending)
        return AuthorizationLease(
            pending.session_id,
            pending.token,
            pending.game,
            incoming_path,
        )

    def replay(self, session_id: str, token: str) -> OAuthBinding | None:
        """Return the still-pending OAuth binding only to its original token."""
        session_id = validate_session_id(session_id)
        token = validate_helper_token(token)
        self._purge_expired()
        state = self._state_by_session.get(session_id)
        pending = self._by_state.get(state) if state is not None else None
        if pending is None or not hmac.compare_digest(pending.token, token):
            return None
        return OAuthBinding(
            pending.url,
            pending.state,
            pending.expected_r,
            pending.expected_t,
            pending.game,
            pending.callback_path,
        )

    def pending_count(self) -> int:
        self._purge_expired()
        return len(self._by_state)

    def has_session(self, session_id: str) -> bool:
        """Return whether this exact backend session already owns an OAuth flow."""
        session_id = validate_session_id(session_id)
        self._purge_expired()
        return session_id in self._state_by_session

    def clear(self) -> None:
        self._by_state.clear()
        self._state_by_session.clear()

    def _purge_expired(self) -> None:
        now = self._clock()
        expired = [item for item in self._by_state.values() if item.expires_at <= now]
        for item in expired:
            self._remove(item)

    def _remove(self, pending: _PendingAuthorization) -> None:
        self._by_state.pop(pending.state, None)
        self._state_by_session.pop(pending.session_id, None)
