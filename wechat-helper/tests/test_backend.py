from __future__ import annotations

import json
import unittest
from typing import Any

from wechat_helper.backend import BackendClient, BackendError
from wechat_helper.security import BackendTarget


SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
TOKEN = "A" * 43


class FakeResponse:
    def __init__(self, status: int = 200, body: bytes = b'{"session":{}}') -> None:
        self.status = status
        self._body = body

    def read(self, _size: int) -> bytes:
        return self._body


class FakeConnection:
    def __init__(self, response: FakeResponse | None = None) -> None:
        self.response = response or FakeResponse()
        self.requests: list[tuple[str, str, bytes, dict[str, str]]] = []
        self.closed = False

    def request(
        self,
        method: str,
        path: str,
        *,
        body: bytes,
        headers: dict[str, str],
    ) -> None:
        self.requests.append((method, path, body, headers))

    def getresponse(self) -> FakeResponse:
        return self.response

    def close(self) -> None:
        self.closed = True


def client(connection: FakeConnection) -> BackendClient:
    return BackendClient(
        BackendTarget("http", "127.0.0.1", 8080),
        3.0,
        connection_factory=lambda _target, _timeout: connection,  # type: ignore[arg-type]
    )


class BackendClientTests(unittest.IsolatedAsyncioTestCase):
    async def test_report_event_uses_fixed_session_path_and_bearer_token(self) -> None:
        connection = FakeConnection()
        result = await client(connection).report_event(
            SESSION_ID,
            TOKEN,
            "failed",
            "第一行\n第二行",
            game="chunithm",
        )
        self.assertIn("session", result)
        self.assertTrue(connection.closed)
        method, path, body, headers = connection.requests[0]
        self.assertEqual("POST", method)
        self.assertEqual(f"/api/sync/sessions/{SESSION_ID}/events", path)
        self.assertEqual(
            {
                "status": "failed",
                "game": "chunithm",
                "message": "第一行 第二行",
            },
            json.loads(body.decode("utf-8")),
        )
        self.assertEqual(f"Bearer {TOKEN}", headers["Authorization"])
        self.assertEqual("application/json; charset=utf-8", headers["Content-Type"])

    async def test_all_progress_events_are_supported_without_message(self) -> None:
        for status in ("waiting_auth", "callback_received", "fetching"):
            with self.subTest(status=status):
                connection = FakeConnection()
                game = None if status == "waiting_auth" else "maimai"
                await client(connection).report_event(
                    SESSION_ID, TOKEN, status, game=game
                )
                body = connection.requests[0][2]
                expected = {"status": status}
                if game is not None:
                    expected["game"] = game
                self.assertEqual(expected, json.loads(body.decode("utf-8")))

    async def test_fetching_event_carries_only_bounded_detail_progress(self) -> None:
        connection = FakeConnection()
        await client(connection).report_event(
            SESSION_ID,
            TOKEN,
            "fetching",
            game="chunithm",
            stage="play_details",
            completed=17,
            total=50,
            succeeded=16,
            skipped=1,
            failure_reasons={"request-timeout": 1},
        )
        self.assertEqual(
            {
                "status": "fetching",
                "game": "chunithm",
                "stage": "play_details",
                "completed": 17,
                "total": 50,
                "succeeded": 16,
                "skipped": 1,
                "failureReasons": {"request-timeout": 1},
            },
            json.loads(connection.requests[0][2].decode("utf-8")),
        )

    async def test_invalid_detail_progress_is_rejected_before_network(self) -> None:
        invalid = (
            {"stage": "play_details", "completed": 1, "total": 50},
            {
                "stage": "unknown",
                "completed": 1,
                "total": 50,
                "succeeded": 1,
            },
            {
                "stage": "play_details",
                "completed": 51,
                "total": 50,
                "succeeded": 50,
            },
            {
                "stage": "play_details",
                "completed": 1,
                "total": 101,
                "succeeded": 1,
            },
            {
                "stage": "play_details",
                "completed": True,
                "total": 50,
                "succeeded": 1,
            },
        )
        connection = FakeConnection()
        for values in invalid:
            with self.subTest(values=values):
                with self.assertRaises(BackendError):
                    await client(connection).report_event(
                        SESSION_ID,
                        TOKEN,
                        "fetching",
                        game="maimai",
                        **values,  # type: ignore[arg-type]
                    )
        with self.assertRaises(BackendError):
            await client(connection).report_event(
                SESSION_ID,
                TOKEN,
                "callback_received",
                game="maimai",
                stage="play_details",
                completed=1,
                total=50,
                succeeded=1,
            )
        for diagnostics in (
            {"skipped": 1},
            {"skipped": 0, "failure_reasons": {"private-idx": 1}},
            {"skipped": 1, "failure_reasons": {}},
            {"skipped": 0, "failure_reasons": {"request-timeout": 1}},
        ):
            with self.subTest(diagnostics=diagnostics):
                with self.assertRaises(BackendError) as raised:
                    await client(connection).report_event(
                        SESSION_ID,
                        TOKEN,
                        "fetching",
                        game="maimai",
                        stage="play_details",
                        completed=1,
                        total=50,
                        succeeded=0,
                        **diagnostics,  # type: ignore[arg-type]
                    )
                self.assertNotIn("private-idx", str(raised.exception))
        self.assertEqual([], connection.requests)

    async def test_invalid_event_is_rejected_before_network(self) -> None:
        connection = FakeConnection()
        for status in ("WAITING_AUTH", "completed", ""):
            with self.subTest(status=status):
                with self.assertRaises(BackendError):
                    await client(connection).report_event(SESSION_ID, TOKEN, status)
        with self.assertRaises(BackendError):
            await client(connection).report_event(SESSION_ID, TOKEN, "failed", "x" * 201)
        self.assertEqual([], connection.requests)

    async def test_backend_rejection_exposes_only_status_for_safe_routing(self) -> None:
        connection = FakeConnection(FakeResponse(401, b'{"error":"no"}'))
        with self.assertRaises(BackendError) as raised:
            await client(connection).report_event(SESSION_ID, TOKEN, "waiting_auth")
        self.assertEqual(401, raised.exception.status)
        self.assertNotIn(TOKEN, str(raised.exception))

    async def test_import_still_uses_one_time_import_endpoint(self) -> None:
        connection = FakeConnection(FakeResponse(200, b'{"ok":true}'))
        payload: dict[str, Any] = {
            "game": "maimai",
            "sessionId": SESSION_ID,
            "charts": [{"songId": "1"}],
            "records": [],
        }
        result = await client(connection).import_sync(payload, TOKEN)
        self.assertEqual({"ok": True}, result)
        self.assertEqual("/api/sync/import", connection.requests[0][1])

    async def test_chunithm_import_uses_the_same_one_time_endpoint(self) -> None:
        connection = FakeConnection(FakeResponse(200, b'{"ok":true}'))
        payload: dict[str, Any] = {
            "game": "chunithm",
            "sessionId": SESSION_ID,
            "charts": [{"songId": "42"}],
            "records": [],
        }
        result = await client(connection).import_sync(payload, TOKEN)
        self.assertEqual({"ok": True}, result)
        self.assertEqual("/api/sync/import", connection.requests[0][1])


if __name__ == "__main__":
    unittest.main()
