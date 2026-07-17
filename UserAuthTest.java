import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free tests for credentials, sessions, and per-user persistence. */
public final class UserAuthTest {
    private static int tests;

    private UserAuthTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-user-auth-test-");
        try {
            MutableClock clock = new MutableClock(Instant.parse("2026-07-15T00:00:00Z"));
            UserStore store = new UserStore(temporary);
            AuthService auth = new AuthService(store, new SecureRandom(), clock);

            expectThrows(AuthService.ValidationException.class,
                    () -> auth.register("ab", "correct horse battery staple"),
                    "short username is rejected");
            expectThrows(AuthService.ValidationException.class,
                    () -> auth.register("bad name", "correct horse battery staple"),
                    "whitespace in username is rejected");
            expectThrows(AuthService.ValidationException.class,
                    () -> auth.register("player", "short"),
                    "short password is rejected");

            String secret = "correct horse battery staple";
            AuthService.SessionHandle first = auth.register("  Player_01  ", secret);
            expect("Player_01", first.user().username(), "username is normalized");
            expect(true, auth.authenticateSession(first.token()).isPresent(),
                    "new registration is logged in");
            expectThrows(UserStore.UsernameAlreadyExistsException.class,
                    () -> auth.register("player_01", "another secure password"),
                    "canonical usernames are unique");

            String usersJson = Files.readString(
                    temporary.resolve("users.json"), StandardCharsets.UTF_8);
            expect(false, usersJson.contains(secret), "plaintext password is not persisted");
            Map<String, Object> usersDocument = object(Json.parse(usersJson));
            List<?> users = (List<?>) usersDocument.get("users");
            Map<String, Object> persisted = object(users.getFirst());
            expect(true, ((String) persisted.get("salt")).length() >= 20,
                    "random salt is persisted");
            expect(true, ((String) persisted.get("passwordHash")).length() >= 40,
                    "password hash is persisted");
            expect(true,
                    ((java.math.BigDecimal) persisted.get("iterations")).intValueExact()
                            >= 300_000,
                    "PBKDF2 uses a high iteration count");

            expectThrows(AuthService.InvalidCredentialsException.class,
                    () -> auth.login("Player_01", "wrong password"),
                    "wrong password is rejected");
            AuthService.SessionHandle login = auth.login("PLAYER_01", secret);
            expect("Player_01", login.user().username(), "login is case-insensitive");
            auth.logout(login.token());
            expect(false, auth.authenticateSession(login.token()).isPresent(),
                    "logout invalidates the session");

            List<ChartInput> charts = List.of(new ChartInput(
                    "123", "Test Song", ChartType.DX, "MASTER",
                    14.7, 100.5000, Version.CURRENT, "APP", "FsDp"));
            store.saveCharts(first.user().id(), charts);
            expect(charts, store.loadCharts(first.user().id()), "charts round-trip");
            String chartJson = Files.readString(
                    temporary.resolve("charts").resolve(first.user().id() + ".json"),
                    StandardCharsets.UTF_8);
            expect(true, chartJson.contains("\"comboStatus\":\"app\""),
                    "combo status is persisted canonically");
            expect(true, chartJson.contains("\"syncStatus\":\"fsdp\""),
                    "sync status is persisted canonically");

            AuthService.SessionHandle second = auth.register(
                    "玩家二号", "a different secure password");
            expect(List.of(), store.loadCharts(second.user().id()),
                    "new user's charts are isolated");
            store.saveCharts(second.user().id(), List.of(new ChartInput(
                    "999", "Other Song", ChartType.STANDARD, "EXPERT",
                    12.0, 99.1234, Version.LEGACY)));
            expect(charts, store.loadCharts(first.user().id()),
                    "saving another user does not overwrite the first");

            expectThrows(AuthService.InvalidCurrentPasswordException.class,
                    () -> auth.verifyCurrentPassword(
                            first.token(), "not the current password"),
                    "current-password verification rejects a wrong password");
            expect(true, auth.authenticateSession(first.token()).isPresent(),
                    "failed current-password verification keeps the session");
            expect(first.user(), auth.verifyCurrentPassword(first.token(), secret),
                    "current-password verification returns the authenticated user");
            expect(true, auth.authenticateSession(first.token()).isPresent(),
                    "successful current-password verification does not rotate the session");

            AuthService.SessionHandle preChange = auth.login("player_01", secret);
            expectThrows(AuthService.InvalidCurrentPasswordException.class,
                    () -> auth.changePassword(
                            first.token(), "not the current password", "new secure password"),
                    "password change rejects the wrong current password");
            expect(true, auth.authenticateSession(first.token()).isPresent(),
                    "failed password change keeps the current session");
            expectThrows(AuthService.ValidationException.class,
                    () -> auth.changePassword(first.token(), secret, "short"),
                    "password change reuses password validation");

            String replacementSecret = "a newly rotated secure password";
            AuthService.SessionHandle rotated = auth.changePassword(
                    first.token(), secret, replacementSecret);
            expect(false, auth.authenticateSession(first.token()).isPresent(),
                    "password change revokes the initiating session");
            expect(false, auth.authenticateSession(preChange.token()).isPresent(),
                    "password change revokes the user's other sessions");
            expect(true, auth.authenticateSession(rotated.token()).isPresent(),
                    "password change returns a fresh session");
            expect(true, auth.authenticateSession(second.token()).isPresent(),
                    "password change leaves other users signed in");
            expectThrows(AuthService.InvalidCredentialsException.class,
                    () -> auth.login("player_01", secret),
                    "old password no longer logs in");
            expect("Player_01", auth.login("player_01", replacementSecret)
                            .user().username(),
                    "new password logs in");
            String rotatedUsersJson = Files.readString(
                    temporary.resolve("users.json"), StandardCharsets.UTF_8);
            expect(false, rotatedUsersJson.contains(replacementSecret),
                    "replacement password is never persisted as plaintext");

            String deletionSecret = "delete this account securely";
            AuthService.SessionHandle deletionSession = auth.register(
                    "Delete_Me", deletionSecret);
            AuthService.SessionHandle deletionOtherSession = auth.login(
                    "delete_me", deletionSecret);
            store.saveCharts(deletionSession.user().id(), charts);
            Path deletionChartFile = temporary.resolve("charts")
                    .resolve(deletionSession.user().id() + ".json");
            expect(true, Files.isRegularFile(deletionChartFile),
                    "account scheduled for deletion has chart data");

            expectThrows(AuthService.InvalidCurrentPasswordException.class,
                    () -> auth.deleteAccount(
                            deletionSession.token(), "not the current password"),
                    "account deletion rejects a wrong current password");
            expect(true, auth.authenticateSession(deletionSession.token()).isPresent(),
                    "failed account deletion keeps the initiating session");
            expect(true, auth.authenticateSession(deletionOtherSession.token()).isPresent(),
                    "failed account deletion keeps the user's other sessions");
            expect(true, Files.isRegularFile(deletionChartFile),
                    "failed account deletion keeps chart data");

            AuthService.AuthenticatedUser deleted = auth.deleteAccount(
                    deletionSession.token(), deletionSecret);
            expect(deletionSession.user(), deleted,
                    "account deletion returns the deleted authenticated user");
            expect(false, auth.authenticateSession(deletionSession.token()).isPresent(),
                    "account deletion revokes the initiating session");
            expect(false, auth.authenticateSession(deletionOtherSession.token()).isPresent(),
                    "account deletion revokes all sessions for that user");
            expect(true, auth.authenticateSession(second.token()).isPresent(),
                    "account deletion leaves another user's session intact");
            expect(false, store.findById(deletionSession.user().id()).isPresent(),
                    "deleted account is removed from the in-memory user index");
            expect(false, Files.exists(deletionChartFile),
                    "account deletion removes the user's maimai chart file");
            store.deleteChartData(deletionSession.user().id());
            expect(false, Files.exists(deletionChartFile),
                    "chart cleanup is idempotent");
            expectThrows(AuthService.InvalidCredentialsException.class,
                    () -> auth.login("delete_me", deletionSecret),
                    "deleted credentials can no longer log in");

            Map<String, Object> afterDeletionDocument = object(Json.parse(
                    Files.readString(
                            temporary.resolve("users.json"), StandardCharsets.UTF_8)));
            expect(java.util.Set.of("version", "users"),
                    afterDeletionDocument.keySet(),
                    "account deletion preserves the version-1 document shape");
            expect(new java.math.BigDecimal("1"), afterDeletionDocument.get("version"),
                    "account deletion preserves users.json version 1");
            List<?> afterDeletionUsers = (List<?>) afterDeletionDocument.get("users");
            expect(false, afterDeletionUsers.stream()
                            .map(UserAuthTest::object)
                            .anyMatch(user -> deletionSession.user().id()
                                    .equals(user.get("id"))),
                    "deleted account is absent from persisted users.json");
            for (Object value : afterDeletionUsers) {
                expect(java.util.Set.of(
                                "id", "username", "canonicalUsername", "salt",
                                "passwordHash", "iterations"),
                        object(value).keySet(),
                        "remaining users keep the six-field v1 record shape");
            }

            String nonRegularId = java.util.UUID.randomUUID().toString();
            Path nonRegularChartEntry = temporary.resolve("charts")
                    .resolve(nonRegularId + ".json");
            Files.createDirectory(nonRegularChartEntry);
            expectThrows(IOException.class,
                    () -> store.deleteChartData(nonRegularId),
                    "chart cleanup rejects non-regular file-system entries");
            expect(true, Files.isDirectory(nonRegularChartEntry),
                    "rejected chart entry is not deleted");
            Files.delete(nonRegularChartEntry);

            Map<String, Object> badDocument = new LinkedHashMap<>();
            badDocument.put("charts", List.of());
            badDocument.put("unexpected", true);
            expectThrows(IllegalArgumentException.class,
                    () -> UserStore.parseChartsDocument(badDocument, 2_000),
                    "unknown top-level chart fields are rejected");

            Map<String, Object> legacyChart = new LinkedHashMap<>();
            legacyChart.put("songId", "legacy-song");
            legacyChart.put("title", "Legacy Song");
            legacyChart.put("chartType", "standard");
            legacyChart.put("difficulty", "EXPERT");
            legacyChart.put("level", new java.math.BigDecimal("12.3"));
            legacyChart.put("achievement", new java.math.BigDecimal("99.1234"));
            legacyChart.put("version", "legacy");
            Map<String, Object> legacyRequest = new LinkedHashMap<>();
            legacyRequest.put("charts", List.of(legacyChart));
            ChartInput parsedLegacy = UserStore.parseChartsDocument(
                    legacyRequest, 2_000).getFirst();
            expect("", parsedLegacy.comboStatus(),
                    "legacy request without combo status remains valid");
            expect("", parsedLegacy.syncStatus(),
                    "legacy request without sync status remains valid");

            Map<String, Object> invalidStatusChart = new LinkedHashMap<>(legacyChart);
            invalidStatusChart.put("comboStatus", "fc+");
            invalidStatusChart.put("syncStatus", "fs");
            Map<String, Object> invalidStatusDocument = new LinkedHashMap<>();
            invalidStatusDocument.put("charts", List.of(invalidStatusChart));
            expectThrows(IllegalArgumentException.class,
                    () -> UserStore.parseChartsDocument(invalidStatusDocument, 2_000),
                    "unknown completion status is rejected");

            Map<String, Object> unknownChartField = new LinkedHashMap<>(legacyChart);
            unknownChartField.put("unexpected", true);
            Map<String, Object> unknownChartDocument = new LinkedHashMap<>();
            unknownChartDocument.put("charts", List.of(unknownChartField));
            expectThrows(IllegalArgumentException.class,
                    () -> UserStore.parseChartsDocument(unknownChartDocument, 2_000),
                    "unknown per-chart fields remain rejected");

            UserStore reloadedStore = new UserStore(temporary);
            AuthService reloadedAuth = new AuthService(
                    reloadedStore, new SecureRandom(), clock);
            AuthService.SessionHandle afterRestart = reloadedAuth.login(
                    "player_01", replacementSecret);
            expect("Player_01", afterRestart.user().username(),
                    "credentials survive restart");
            expect(charts, reloadedStore.loadCharts(afterRestart.user().id()),
                    "charts survive restart");

            Map<String, Object> legacyStoredDocument = new LinkedHashMap<>();
            legacyStoredDocument.put("revision", 7);
            legacyStoredDocument.put("charts", List.of(legacyChart));
            Files.writeString(
                    temporary.resolve("charts").resolve(second.user().id() + ".json"),
                    Json.stringify(legacyStoredDocument),
                    StandardCharsets.UTF_8);
            ChartInput legacyStored = reloadedStore
                    .loadChartSnapshot(second.user().id()).charts().getFirst();
            expect("legacy-song", legacyStored.id(),
                    "legacy stored chart without statuses can be loaded");
            expect("", legacyStored.comboStatus(),
                    "legacy stored combo status defaults to empty");
            expect("", legacyStored.syncStatus(),
                    "legacy stored sync status defaults to empty");

            clock.set(Instant.parse("2026-07-23T00:00:01Z"));
            expect(false, reloadedAuth.authenticateSession(afterRestart.token()).isPresent(),
                    "expired sessions are rejected");

            System.out.println("UserAuthTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> type, ThrowingAction action, String label) {
        tests++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(
                    label + ": expected " + type.getSimpleName()
                            + ", got " + error, error);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock only supports UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
