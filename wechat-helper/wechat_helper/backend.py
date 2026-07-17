"""Fixed-destination backend client; deliberately has no redirect support."""

from __future__ import annotations

import asyncio
import http.client
import json
import ssl
from typing import Any, Callable

from .security import (
    BACKEND_IMPORT_PATH,
    BackendTarget,
    backend_session_event_path,
    validate_helper_token,
    validate_game,
    validate_session_id,
)


MAX_REQUEST_BYTES = 8 * 1024 * 1024
MAX_RESPONSE_BYTES = 1024 * 1024
MAX_EVENT_MESSAGE_LENGTH = 200
MAX_PROGRESS_TOTAL = 100
HELPER_EVENT_STATUSES = frozenset(
    {"waiting_auth", "callback_received", "fetching", "failed"}
)
HELPER_PROGRESS_STAGES = frozenset({"play_details"})
HELPER_DETAIL_FAILURE_REASONS = frozenset({
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


class BackendError(RuntimeError):
    """A safe, non-secret-bearing backend failure."""

    def __init__(self, message: str, *, status: int | None = None) -> None:
        super().__init__(message)
        self.status = status


ConnectionFactory = Callable[[BackendTarget, float], http.client.HTTPConnection]


class BackendClient:
    def __init__(
        self,
        target: BackendTarget,
        timeout_seconds: float,
        connection_factory: ConnectionFactory | None = None,
    ) -> None:
        self._target = target
        self._timeout_seconds = timeout_seconds
        self._connection_factory = connection_factory or _connection

    async def import_sync(self, payload: dict[str, Any], token: str) -> dict[str, Any]:
        token = validate_helper_token(token)
        _validate_payload(payload)
        try:
            body = json.dumps(
                payload,
                ensure_ascii=False,
                allow_nan=False,
                separators=(",", ":"),
            ).encode("utf-8")
        except (TypeError, ValueError) as error:
            raise BackendError("normalized import payload is not valid JSON") from error
        if len(body) > MAX_REQUEST_BYTES:
            raise BackendError("normalized import payload is too large")
        return await asyncio.to_thread(
            self._send,
            BACKEND_IMPORT_PATH,
            body,
            token,
            "one-time import",
        )

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
    ) -> dict[str, Any]:
        """Authenticate a helper session and publish pre-import progress."""
        path = backend_session_event_path(session_id)
        token = validate_helper_token(token)
        if not isinstance(status, str) or status not in HELPER_EVENT_STATUSES:
            raise BackendError("helper event status is invalid")
        document: dict[str, Any] = {"status": status}
        if game is not None:
            try:
                document["game"] = validate_game(game)
            except ValueError as error:
                raise BackendError("helper event game is invalid") from error
        if message is not None:
            if not isinstance(message, str):
                raise BackendError("helper event message must be a string")
            normalized = message.replace("\r", " ").replace("\n", " ").strip()
            if not normalized or len(normalized) > MAX_EVENT_MESSAGE_LENGTH:
                raise BackendError("helper event message is invalid")
            document["message"] = normalized
        progress_values = (stage, completed, total, succeeded)
        diagnostic_values = (skipped, failure_reasons)
        if any(value is not None for value in diagnostic_values) and not any(
            value is not None for value in progress_values
        ):
            raise BackendError("helper event progress diagnostics have no progress")
        if any(value is not None for value in progress_values):
            if any(value is None for value in progress_values):
                raise BackendError("helper event progress is incomplete")
            if status != "fetching" or game is None:
                raise BackendError("helper event progress state is invalid")
            if not isinstance(stage, str) or stage not in HELPER_PROGRESS_STAGES:
                raise BackendError("helper event progress stage is invalid")
            for name, value in (
                ("completed", completed),
                ("total", total),
                ("succeeded", succeeded),
            ):
                if isinstance(value, bool) or not isinstance(value, int):
                    raise BackendError(f"helper event progress {name} is invalid")
            assert isinstance(completed, int)
            assert isinstance(total, int)
            assert isinstance(succeeded, int)
            if total < 1 or total > MAX_PROGRESS_TOTAL:
                raise BackendError("helper event progress total is invalid")
            if completed < 0 or completed > total:
                raise BackendError("helper event progress completed is invalid")
            if succeeded < 0 or succeeded > completed:
                raise BackendError("helper event progress succeeded is invalid")
            document.update(
                {
                    "stage": stage,
                    "completed": completed,
                    "total": total,
                    "succeeded": succeeded,
                }
            )
            if any(value is not None for value in diagnostic_values):
                if any(value is None for value in diagnostic_values):
                    raise BackendError("helper event progress diagnostics are incomplete")
                if isinstance(skipped, bool) or not isinstance(skipped, int):
                    raise BackendError("helper event progress skipped is invalid")
                if skipped != completed - succeeded:
                    raise BackendError("helper event progress skipped is invalid")
                if not isinstance(failure_reasons, dict):
                    raise BackendError("helper event progress failure reasons are invalid")
                safe_reasons: dict[str, int] = {}
                for reason, count in failure_reasons.items():
                    if (
                        not isinstance(reason, str)
                        or reason not in HELPER_DETAIL_FAILURE_REASONS
                        or reason in safe_reasons
                    ):
                        raise BackendError("helper event progress failure reason is invalid")
                    if isinstance(count, bool) or not isinstance(count, int) or count < 1:
                        raise BackendError("helper event progress failure count is invalid")
                    safe_reasons[reason] = count
                if sum(safe_reasons.values()) != skipped:
                    raise BackendError("helper event progress failure counts are invalid")
                document["skipped"] = skipped
                document["failureReasons"] = dict(sorted(safe_reasons.items()))
        body = _json_bytes(document, "helper event")
        return await asyncio.to_thread(
            self._send,
            path,
            body,
            token,
            "helper event",
        )

    def _send(
        self,
        path: str,
        body: bytes,
        token: str,
        operation: str,
    ) -> dict[str, Any]:
        connection: http.client.HTTPConnection | None = None
        try:
            connection = self._connection_factory(self._target, self._timeout_seconds)
            connection.request(
                "POST",
                path,
                body=body,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json; charset=utf-8",
                    "Accept": "application/json",
                    "Connection": "close",
                },
            )
            response = connection.getresponse()
            response_body = response.read(MAX_RESPONSE_BYTES + 1)
            if len(response_body) > MAX_RESPONSE_BYTES:
                raise BackendError("backend response is too large")
            if response.status != 200:
                raise BackendError(
                    f"backend rejected the {operation} (HTTP {response.status})",
                    status=response.status,
                )
            try:
                value = json.loads(response_body.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise BackendError("backend returned invalid JSON") from error
            if not isinstance(value, dict):
                raise BackendError("backend returned an invalid response shape")
            return value
        except BackendError:
            raise
        except (OSError, http.client.HTTPException, ssl.SSLError) as error:
            raise BackendError("unable to reach the configured backend") from None
        finally:
            if connection is not None:
                connection.close()


def _connection(target: BackendTarget, timeout: float) -> http.client.HTTPConnection:
    if target.scheme == "https":
        return http.client.HTTPSConnection(
            target.host,
            target.port,
            timeout=timeout,
            context=ssl.create_default_context(),
        )
    return http.client.HTTPConnection(target.host, target.port, timeout=timeout)


def _validate_payload(payload: dict[str, Any]) -> None:
    if not isinstance(payload, dict):
        raise BackendError("import payload must be an object")
    if set(payload) != {"game", "sessionId", "charts", "records"}:
        raise BackendError("import payload has an invalid shape")
    try:
        validate_game(payload.get("game"))
    except ValueError as error:
        raise BackendError("import payload game is invalid") from error
    validate_session_id(payload.get("sessionId"))
    if not isinstance(payload.get("charts"), list) or not isinstance(
        payload.get("records"), list
    ):
        raise BackendError("charts and records must be arrays")
    if not payload["charts"] and not payload["records"]:
        raise BackendError("import payload contains no matched score data")


def _json_bytes(value: Any, label: str) -> bytes:
    try:
        body = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise BackendError(f"{label} is not valid JSON") from error
    if len(body) > MAX_REQUEST_BYTES:
        raise BackendError(f"{label} is too large")
    return body
