from __future__ import annotations

import unittest

from wechat_helper.session_store import (
    OAuthBinding,
    PendingSessionStore,
    SessionError,
)


SESSION_ID = "123e4567-e89b-12d3-a456-426614174000"
SESSION_ID_2 = "123e4567-e89b-12d3-a456-426614174001"
TOKEN = "A" * 43


def binding(
    state: str = "oauth-state",
    game: str = "maimai",
    callback_path: str | None = None,
) -> OAuthBinding:
    return OAuthBinding(
        "https://open.weixin.qq.com/example",
        state,
        "r-secret",
        "t-secret",
        game,
        callback_path,
    )


class PendingSessionStoreTests(unittest.TestCase):
    def test_claim_is_bound_and_one_shot(self) -> None:
        store = PendingSessionStore(900, 2)
        store.register(SESSION_ID, TOKEN, binding())
        self.assertTrue(store.has_session(SESSION_ID))
        with self.assertRaises(SessionError):
            store.claim("oauth-state", "wrong-r", "t-secret")
        self.assertTrue(store.has_session(SESSION_ID))
        lease = store.claim("oauth-state", "r-secret", "t-secret")
        self.assertEqual(SESSION_ID, lease.session_id)
        self.assertEqual(TOKEN, lease.token)
        self.assertFalse(store.has_session(SESSION_ID))
        with self.assertRaises(SessionError):
            store.claim("oauth-state", "r-secret", "t-secret")

    def test_callback_game_is_bound_and_mismatch_does_not_consume_state(self) -> None:
        store = PendingSessionStore(900, 2)
        store.register(SESSION_ID, TOKEN, binding(game="chunithm"))
        with self.assertRaises(SessionError):
            store.claim("oauth-state", "r-secret", "t-secret", "maimai")
        lease = store.claim(
            "oauth-state", "r-secret", "t-secret", "chunithm"
        )
        self.assertEqual("chunithm", lease.game)

    def test_maimai_callback_alias_is_bound_and_forwarded_exactly(self) -> None:
        store = PendingSessionStore(900, 2)
        store.register(
            SESSION_ID,
            TOKEN,
            binding(callback_path="/wc_auth/oauth/callback/maimai-dx"),
        )
        with self.assertRaises(ValueError):
            store.claim(
                "oauth-state",
                "r-secret",
                "t-secret",
                "maimai",
                "/wc_auth/oauth/callback/maidx/extra",
            )
        self.assertTrue(store.has_session(SESSION_ID))

        lease = store.claim(
            "oauth-state",
            "r-secret",
            "t-secret",
            "maimai",
            "/wc_auth/oauth/callback/maidx",
        )
        self.assertEqual("/wc_auth/oauth/callback/maidx", lease.callback_path)

    def test_pending_oauth_replay_requires_same_session_and_token(self) -> None:
        store = PendingSessionStore(900, 2)
        oauth = binding(
            callback_path="/wc_auth/oauth/callback/maimai-dx"
        )
        store.register(SESSION_ID, TOKEN, oauth)

        replay = store.replay(SESSION_ID, TOKEN)
        self.assertIsNotNone(replay)
        self.assertEqual(oauth.url, replay.url)
        self.assertEqual(oauth.state, replay.state)
        self.assertEqual(oauth.game, replay.game)
        self.assertEqual(oauth.callback_path, replay.callback_path)
        self.assertEqual(1, store.pending_count())

        self.assertIsNone(store.replay(SESSION_ID, "B" * 43))
        self.assertIsNone(store.replay(SESSION_ID_2, TOKEN))
        self.assertEqual(1, store.pending_count())

        replay_after_wrong_token = store.replay(SESSION_ID, TOKEN)
        self.assertIsNotNone(replay_after_wrong_token)
        self.assertEqual(oauth.url, replay_after_wrong_token.url)

    def test_claimed_oauth_binding_cannot_be_replayed(self) -> None:
        store = PendingSessionStore(900, 2)
        store.register(SESSION_ID, TOKEN, binding())
        lease = store.claim(
            "oauth-state",
            "r-secret",
            "t-secret",
            "maimai",
            "/wc_auth/oauth/callback/maimai-dx",
        )
        self.assertEqual(SESSION_ID, lease.session_id)
        self.assertIsNone(store.replay(SESSION_ID, TOKEN))
        self.assertEqual(0, store.pending_count())

    def test_duplicate_state_session_and_capacity_fail_closed(self) -> None:
        store = PendingSessionStore(900, 1)
        store.register(SESSION_ID, TOKEN, binding())
        with self.assertRaises(SessionError):
            store.register(SESSION_ID, TOKEN, binding("another-state"))
        with self.assertRaises(SessionError):
            store.register(SESSION_ID_2, TOKEN, binding("oauth-state"))
        with self.assertRaises(SessionError):
            store.register(SESSION_ID_2, TOKEN, binding("other-state"))

    def test_expired_entries_do_not_hold_capacity(self) -> None:
        now = [100.0]
        store = PendingSessionStore(10, 1, clock=lambda: now[0])
        store.register(SESSION_ID, TOKEN, binding())
        now[0] = 110.0
        self.assertFalse(store.has_session(SESSION_ID))
        store.register(SESSION_ID_2, TOKEN, binding("state-2"))
        self.assertEqual(1, store.pending_count())


if __name__ == "__main__":
    unittest.main()
