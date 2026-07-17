import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

public final class SyncSessionStoreTest {
    private SyncSessionStoreTest() {
    }

    public static void main(String[] args) {
        progressesThroughHelperEventsAndCompletes();
        recordsMonotonicDetailProgressAndKeepsLegacyEventsCompatible();
        supportsChunithmAndBindsEveryLaterEventToItsGame();
        rejectsWrongOwnerTokenAndInvalidTransitions();
        failedEventConsumesToken();
        expiresAndRejectsFurtherProgress();
        deletesOnlyOneUsersSessions();
        System.out.println("SyncSessionStoreTest: all checks passed");
    }

    private static void deletesOnlyOneUsersSessions() {
        SyncSessionStore store = new SyncSessionStore();
        String deletedUser = UUID.randomUUID().toString();
        String retainedUser = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession firstDeleted =
                store.create(deletedUser, "maimai");
        SyncSessionStore.CreatedSession secondDeleted =
                store.create(deletedUser, "chunithm");
        SyncSessionStore.CreatedSession retained =
                store.create(retainedUser, "maimai");

        assertThrows(IllegalArgumentException.class,
                () -> store.deleteUserSessions("not-a-uuid"),
                "session deletion rejects an invalid UUID");
        assertEquals(2, store.listForUser(deletedUser).size(),
                "target user has two sessions before deletion");
        assertEquals(1, store.listForUser(retainedUser).size(),
                "other user has one session before deletion");

        assertEquals(2, store.deleteUserSessions(deletedUser),
                "session deletion reports every removed target session");
        assertEquals(0, store.listForUser(deletedUser).size(),
                "all target sessions are removed");
        assertTrue(store.findForUser(
                        deletedUser, firstDeleted.session().id()).isEmpty(),
                "first target session can no longer be found");
        assertTrue(store.findForUser(
                        deletedUser, secondDeleted.session().id()).isEmpty(),
                "second target session can no longer be found");
        assertEquals(1, store.listForUser(retainedUser).size(),
                "another user's sessions remain isolated");
        assertTrue(store.findForUser(
                        retainedUser, retained.session().id()).isPresent(),
                "another user's session remains usable");
        assertEquals(0, store.deleteUserSessions(deletedUser),
                "repeated session deletion is idempotent");
    }

    private static void recordsMonotonicDetailProgressAndKeepsLegacyEventsCompatible() {
        SyncSessionStore store = new SyncSessionStore();
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "chunithm");
        store.reportWaitingAuth(created.session().id(), created.helperToken());
        store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                null);

        SyncSessionStore.Progress started = new SyncSessionStore.Progress(
                "play_details", 0, 50, 0);
        SyncSessionStore.Session fetching = store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.FETCHING,
                null,
                started);
        assertEquals(started, fetching.progress(), "detail progress starts at zero");

        SyncSessionStore.Session legacyRetry = store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.FETCHING,
                null);
        assertEquals(started, legacyRetry.progress(),
                "legacy fetching retry does not erase progress");

        SyncSessionStore.Progress advanced = new SyncSessionStore.Progress(
                "play_details",
                12,
                50,
                10,
                2,
                Map.of("request-timeout", 2));
        fetching = store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.FETCHING,
                null,
                advanced);
        assertEquals(advanced, fetching.progress(), "detail progress advances");
        assertEquals(
                advanced,
                store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "chunithm",
                        SyncSessionStore.HelperEvent.FETCHING,
                        null,
                        advanced).progress(),
                "identical progress retry is idempotent");

        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "chunithm",
                        SyncSessionStore.HelperEvent.FETCHING,
                        null,
                        new SyncSessionStore.Progress(
                                "play_details", 11, 50, 10)),
                "completed progress cannot move backward");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "chunithm",
                        SyncSessionStore.HelperEvent.FETCHING,
                        null,
                        new SyncSessionStore.Progress(
                                "play_details", 12, 50, 9)),
                "successful detail count cannot move backward");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "chunithm",
                        SyncSessionStore.HelperEvent.FETCHING,
                        null,
                        new SyncSessionStore.Progress(
                                "play_details", 12, 49, 10)),
                "detail progress total cannot change");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "chunithm",
                        SyncSessionStore.HelperEvent.FETCHING,
                        null,
                        new SyncSessionStore.Progress(
                                "play_details",
                                13,
                                50,
                                10,
                                3,
                                Map.of(
                                        "request-timeout", 1,
                                        "unavailable", 2))),
                "individual failure reason counts cannot move backward");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "song_list", 0, 50, 0),
                "only detail-fetch progress is accepted");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details", 51, 50, 50),
                "completed cannot exceed total");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details", 10, 50, 11),
                "succeeded cannot exceed completed");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details", 0, 0, 0),
                "detail total must be positive");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details", 0,
                        SyncSessionStore.MAX_DETAIL_PROGRESS_TOTAL + 1, 0),
                "detail total has a hard upper bound");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details",
                        1,
                        50,
                        0,
                        1,
                        Map.of("private-idx", 1)),
                "failure diagnostics accept only controlled reason codes");
        assertThrows(IllegalArgumentException.class,
                () -> new SyncSessionStore.Progress(
                        "play_details",
                        1,
                        50,
                        0,
                        1,
                        Map.of()),
                "failure reason counts must equal skipped details");
        assertEquals(
                100,
                new SyncSessionStore.Progress(
                        "play_details", 100, 100, 100).total(),
                "detail total accepts the Helper maximum");
        assertEquals(advanced,
                store.findForUser(userId, created.session().id())
                        .orElseThrow().progress(),
                "rejected updates leave progress unchanged");

        SyncSessionStore.ImportAuthorization authorization = store.beginImport(
                created.session().id(), created.helperToken(), "chunithm");
        SyncSessionStore.Session completed = store.completeImport(
                authorization, 0, 0, 0, 0);
        assertEquals(advanced, completed.progress(),
                "completion retains the final detail progress");
    }

    private static void supportsChunithmAndBindsEveryLaterEventToItsGame() {
        SyncSessionStore store = new SyncSessionStore();
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "CHUNITHM");
        assertEquals("chunithm", created.session().game(), "canonical game");
        SyncSessionStore.Session waiting = store.reportWaitingAuth(
                created.session().id(), created.helperToken());
        assertEquals(
                SyncSessionStore.Status.WAITING_AUTH,
                waiting.status(),
                "initial event discovers game from the authenticated session");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        created.helperToken(),
                        "maimai",
                        SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                        null),
                "later event cannot switch games");
        SyncSessionStore.Session callback = store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                null);
        assertEquals(
                SyncSessionStore.Status.CALLBACK_RECEIVED,
                callback.status(),
                "matching CHUNITHM event advances");
        SyncSessionStore.Session fetching = store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "chunithm",
                SyncSessionStore.HelperEvent.FETCHING,
                null);
        assertTrue(fetching.message().contains("中二节奏"),
                "CHUNITHM progress is game specific");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(
                        created.session().id(), created.helperToken(), "maimai"),
                "import game cannot differ from the session");
        assertEquals(
                "chunithm",
                store.beginImport(
                        created.session().id(), created.helperToken(), "chunithm")
                        .game(),
                "import authorization retains game");
    }

    private static void progressesThroughHelperEventsAndCompletes() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T00:00:00Z"));
        SyncSessionStore store = new SyncSessionStore(clock, Duration.ofMinutes(15));
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "maimai");
        assertEquals(SyncSessionStore.Status.WAITING, created.session().status(), "waiting");
        assertTrue(created.helperToken().length() >= 40, "strong helper token");

        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(
                        created.session().id(), created.helperToken(), "maimai"),
                "import cannot skip helper progress events");

        SyncSessionStore.Session waitingAuth = report(
                store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null);
        assertEquals(
                SyncSessionStore.Status.WAITING_AUTH,
                waitingAuth.status(),
                "waiting for WeChat authorization");
        assertEquals(
                SyncSessionStore.Status.WAITING_AUTH,
                report(store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null).status(),
                "same progress event is idempotent");

        SyncSessionStore.Session callback = report(
                store, created, SyncSessionStore.HelperEvent.CALLBACK_RECEIVED, null);
        assertEquals(
                SyncSessionStore.Status.CALLBACK_RECEIVED,
                callback.status(),
                "callback received");
        assertEquals(
                SyncSessionStore.Status.CALLBACK_RECEIVED,
                report(
                        store,
                        created,
                        SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                        null).status(),
                "callback retry is idempotent");

        SyncSessionStore.Session fetching = report(
                store, created, SyncSessionStore.HelperEvent.FETCHING, null);
        assertEquals(SyncSessionStore.Status.FETCHING, fetching.status(), "fetching scores");
        assertEquals(
                SyncSessionStore.Status.FETCHING,
                report(store, created, SyncSessionStore.HelperEvent.FETCHING, null).status(),
                "fetching retry is idempotent");

        SyncSessionStore.ImportAuthorization authorization = store.beginImport(
                created.session().id(), created.helperToken(), "maimai");
        assertEquals(
                SyncSessionStore.Status.IMPORTING,
                store.findForUser(userId, created.session().id()).orElseThrow().status(),
                "import starts only after fetching");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(
                        store, created, SyncSessionStore.HelperEvent.FETCHING, null),
                "progress token is consumed when import starts");

        SyncSessionStore.Session completed = store.completeImport(
                authorization, 7, 20, 3, 2);
        assertEquals(SyncSessionStore.Status.COMPLETED, completed.status(), "complete");
        assertEquals(7, completed.addedCharts(), "chart count");
        assertEquals(20, completed.addedRecords(), "record count");
        assertEquals(3, completed.enrichedRecords(), "enriched record count");
        assertEquals(2, completed.ignoredRecords(), "ignored count");
        assertEquals(1, store.listForUser(userId).size(), "owner list");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.completeImport(authorization, 0, 0, 0),
                "completed authorization cannot be replayed");
        clock.advance(Duration.ofMinutes(16));
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(
                        created.session().id(), created.helperToken(), "maimai"),
                "expired token replay is rejected");
        assertEquals(
                SyncSessionStore.Status.COMPLETED,
                store.findForUser(userId, created.session().id()).orElseThrow().status(),
                "token replay after TTL does not overwrite completed state");
    }

    private static void rejectsWrongOwnerTokenAndInvalidTransitions() {
        SyncSessionStore store = new SyncSessionStore();
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "maimai");
        assertTrue(store.findForUser(
                UUID.randomUUID().toString(), created.session().id()).isEmpty(), "owner isolation");
        assertEquals(
                "chunithm",
                store.create(userId, "chunithm").session().game(),
                "CHUNITHM is supported");
        assertThrows(IllegalArgumentException.class,
                () -> store.create(userId, "ongeki"), "unknown game");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(created.session().id(), "x".repeat(43), "maimai"),
                "wrong token");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.reportHelperEvent(
                        created.session().id(),
                        "x".repeat(43),
                        "maimai",
                        SyncSessionStore.HelperEvent.WAITING_AUTH,
                        null),
                "events require the helper token");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(
                        store,
                        created,
                        SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                        null),
                "callback cannot skip waiting_auth");
        assertEquals(
                SyncSessionStore.Status.WAITING,
                store.findForUser(userId, created.session().id()).orElseThrow().status(),
                "rejected event leaves state unchanged");

        report(store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null);
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(store, created, SyncSessionStore.HelperEvent.FETCHING, null),
                "fetching cannot skip callback");
        report(store, created, SyncSessionStore.HelperEvent.CALLBACK_RECEIVED, null);
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(
                        store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null),
                "helper state cannot move backward to waiting_auth");
        report(store, created, SyncSessionStore.HelperEvent.FETCHING, null);
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(
                        store,
                        created,
                        SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                        null),
                "helper state cannot move backward to callback_received");
    }

    private static void failedEventConsumesToken() {
        SyncSessionStore store = new SyncSessionStore();
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "maimai");
        report(store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null);
        String longMessage = "network failed\n" + "x".repeat(220);
        SyncSessionStore.Session failed = report(
                store, created, SyncSessionStore.HelperEvent.FAILED, longMessage);
        assertEquals(SyncSessionStore.Status.FAILED, failed.status(), "helper failure");
        assertTrue(failed.completedAt() != null, "failure records completion time");
        assertTrue(!failed.message().contains("\n"), "failure message is one line");
        assertTrue(failed.message().length() <= 200, "failure message is bounded");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(
                        created.session().id(), created.helperToken(), "maimai"),
                "failed helper token cannot import");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(store, created, SyncSessionStore.HelperEvent.FAILED, "retry"),
                "failed helper token cannot replay an event");
    }

    private static void expiresAndRejectsFurtherProgress() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-16T00:00:00Z"));
        SyncSessionStore store = new SyncSessionStore(clock, Duration.ofMinutes(1));
        String userId = UUID.randomUUID().toString();
        SyncSessionStore.CreatedSession created = store.create(userId, "maimai");
        report(store, created, SyncSessionStore.HelperEvent.WAITING_AUTH, null);
        clock.advance(Duration.ofMinutes(2));
        SyncSessionStore.Session expired = store.findForUser(
                userId, created.session().id()).orElseThrow();
        assertEquals(SyncSessionStore.Status.EXPIRED, expired.status(), "expired");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> store.beginImport(
                        created.session().id(), created.helperToken(), "maimai"),
                "expired helper token");
        assertThrows(SyncSessionStore.InvalidSessionException.class,
                () -> report(
                        store,
                        created,
                        SyncSessionStore.HelperEvent.CALLBACK_RECEIVED,
                        null),
                "expired session rejects progress events");
    }

    private static SyncSessionStore.Session report(
            SyncSessionStore store,
            SyncSessionStore.CreatedSession created,
            SyncSessionStore.HelperEvent event,
            String message) {
        return store.reportHelperEvent(
                created.session().id(),
                created.helperToken(),
                "maimai",
                event,
                message);
    }

    private static void assertThrows(
            Class<? extends Throwable> type,
            Runnable action,
            String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception " + error, error);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName());
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported in this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
