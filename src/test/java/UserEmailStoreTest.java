import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Dependency-free tests for the durable one-to-one email mapping. */
public final class UserEmailStoreTest {
    private static int tests;

    private UserEmailStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-user-email-test-");
        try {
            testNormalization();
            testBindingAndRestart(temporary.resolve("binding"));
            testUniquenessAndRebinding(temporary.resolve("rebind"));
            testConcurrentUniqueness(temporary.resolve("concurrent"));
            testStrictStoredData(temporary.resolve("corrupt"));
            testWriteRollback(temporary.resolve("rollback"));
            testPathValidation(temporary.resolve("paths"));
            System.out.println("UserEmailStoreTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testNormalization() {
        expect(
                "player+tag@example.com",
                UserEmailStore.normalizeEmail("  Player+tag@EXAMPLE.COM  "),
                "the full login address is lower-cased");
        expect(
                "player@example.com",
                UserEmailStore.normalizeEmail("Ｐｌａｙｅｒ＠ＥＸＡＭＰＬＥ．ＣＯＭ"),
                "compatibility characters are normalized");
        expect(
                "mail@xn--fsqu00a.xn--0zwm56d",
                UserEmailStore.normalizeEmail("mail@例子.测试"),
                "internationalized domains use IDNA ASCII form");

        List<String> invalid = List.of(
                "",
                "no-at-sign.example.com",
                "two@@example.com",
                ".first@example.com",
                "last.@example.com",
                "two..dots@example.com",
                "quoted\"local@example.com",
                "邮箱@example.com",
                "a@localhost",
                "a@bad_domain.example",
                "a@-bad.example",
                "a@bad-.example",
                "a@example..com",
                "x".repeat(65) + "@example.com",
                "x@" + "a".repeat(250) + ".com");
        for (String email : invalid) {
            expectThrows(
                    UserEmailStore.ValidationException.class,
                    () -> UserEmailStore.normalizeEmail(email),
                    "invalid email is rejected: " + email);
        }
        expectThrows(
                UserEmailStore.ValidationException.class,
                () -> UserEmailStore.normalizeEmail(null),
                "missing email is rejected");
    }

    private static void testBindingAndRestart(Path root) throws Exception {
        UserEmailStore store = new UserEmailStore(root);
        String userId = UUID.randomUUID().toString();
        expect(Optional.empty(), store.findEmail(userId),
                "legacy user starts without an email mapping");
        expect(false, Files.exists(root.resolve("user_emails.json")),
                "read-only lookup does not create the database file");

        store.bind(userId, "Player@EXAMPLE.COM");
        store.bind(userId, "Player@example.com");
        expect(Optional.of("player@example.com"), store.findEmail(userId),
                "binding stores the normalized address");
        expect(Optional.of(userId), store.findUserId("Player@EXAMPLE.COM"),
                "reverse lookup normalizes its input");

        Map<String, Object> document = object(Json.parse(
                Files.readString(store.file(), StandardCharsets.UTF_8)));
        expect(2, document.size(), "database document has a compact strict schema");
        expect(1, ((BigDecimal) document.get("version")).intValueExact(),
                "database schema is versioned");
        Map<String, Object> emails = object(document.get("emails"));
        expect("player@example.com", emails.get(userId),
                "JSON is a direct userId-to-email mapping");

        UserEmailStore restarted = new UserEmailStore(root);
        expect(Optional.of("player@example.com"), restarted.findEmail(userId),
                "binding survives restart");
        expect(Optional.of(userId), restarted.findUserId("Player@example.com"),
                "reverse index is rebuilt after restart");
        try (java.util.stream.Stream<Path> paths = Files.list(root)) {
            expect(false, paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "atomic persistence leaves no temporary file");
        }
    }

    private static void testUniquenessAndRebinding(Path root) throws Exception {
        UserEmailStore store = new UserEmailStore(root);
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        String legacy = UUID.randomUUID().toString();
        store.bind(first, "first@example.com");
        store.bind(second, "second@example.com");

        expectThrows(
                UserEmailStore.EmailAlreadyBoundException.class,
                () -> store.bind(legacy, "FIRST@EXAMPLE.COM"),
                "one email cannot be bound to two users");
        expectThrows(
                UserEmailStore.UserAlreadyBoundException.class,
                () -> store.bind(first, "replacement@example.com"),
                "bind cannot silently replace an existing email");
        expectThrows(
                UserEmailStore.EmailNotBoundException.class,
                () -> store.rebind(legacy, "legacy@example.com"),
                "rebind requires an existing binding");
        expectThrows(
                UserEmailStore.EmailAlreadyBoundException.class,
                () -> store.rebind(first, "second@example.com"),
                "rebind preserves global email uniqueness");

        store.rebind(first, "replacement@EXAMPLE.COM");
        store.rebind(first, "replacement@example.com");
        expect(Optional.empty(), store.findUserId("first@example.com"),
                "successful rebind releases the previous email");
        expect(Optional.of(first), store.findUserId("replacement@example.com"),
                "successful rebind owns the replacement email");
        store.bind(legacy, "first@example.com");

        store.deleteUser(first);
        store.deleteUser(first);
        expect(Optional.empty(), store.findEmail(first),
                "account deletion removes its binding idempotently");
        store.bind(UUID.randomUUID().toString(), "replacement@example.com");
        expect(true, true, "deleted account releases its email for a new account");
    }

    private static void testConcurrentUniqueness(Path root) throws Exception {
        UserEmailStore store = new UserEmailStore(root);
        int workers = 12;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        try {
            for (int index = 0; index < workers; index++) {
                String userId = UUID.randomUUID().toString();
                ids.add(userId);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        store.bind(userId, "race@example.com");
                        return true;
                    } catch (UserEmailStore.EmailAlreadyBoundException expected) {
                        return false;
                    }
                }));
            }
            ready.await();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            expect(1, successes, "concurrent bind has exactly one winner");
            String winner = store.findUserId("race@example.com").orElseThrow();
            expect(true, ids.contains(winner), "reverse index identifies the winning user");

            UserEmailStore restarted = new UserEmailStore(root);
            expect(Optional.of(winner), restarted.findUserId("race@example.com"),
                    "concurrent winner is persisted without a lost update");
        } finally {
            executor.shutdownNow();
        }
    }

    private static void testStrictStoredData(Path root) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve("user_emails.json");
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        List<String> corrupt = List.of(
                "{}",
                "{\"version\":2,\"emails\":{}}",
                "{\"version\":1,\"emails\":{},\"extra\":true}",
                "{\"version\":1,\"emails\":[]}",
                "{\"version\":1,\"emails\":{\"not-a-uuid\":\"a@example.com\"}}",
                "{\"version\":1,\"emails\":{\"" + id1
                        + "\":\"A@EXAMPLE.COM\"}}",
                "{\"version\":1,\"emails\":{\"" + id1
                        + "\":\"same@example.com\",\"" + id2
                        + "\":\"same@example.com\"}}",
                "{\"version\":1,\"emails\":{\"" + id1 + "\":1}}",
                "{\"version\":1,\"emails\":{},\"version\":1}");
        for (String value : corrupt) {
            Files.writeString(file, value, StandardCharsets.UTF_8);
            expectThrows(IOException.class, () -> new UserEmailStore(root),
                    "strict persisted JSON rejects: " + value);
        }
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        expectThrows(IOException.class, () -> new UserEmailStore(root),
                "malformed stored UTF-8 is rejected");

        Files.delete(file);
        Files.createDirectory(file);
        expectThrows(IOException.class, () -> new UserEmailStore(root),
                "database path must be a regular file");
    }

    private static void testWriteRollback(Path root) throws Exception {
        UserEmailStore store = new UserEmailStore(root);
        String userId = UUID.randomUUID().toString();
        store.bind(userId, "before@example.com");
        Files.delete(store.file());
        Files.createDirectory(store.file());
        expectThrows(IOException.class,
                () -> store.rebind(userId, "after@example.com"),
                "failed atomic write reports an error");
        expect(Optional.of("before@example.com"), store.findEmail(userId),
                "failed rebind rolls the in-memory forward index back");
        expect(Optional.of(userId), store.findUserId("before@example.com"),
                "failed rebind rolls the reverse index back");
        expect(Optional.empty(), store.findUserId("after@example.com"),
                "failed rebind does not expose the replacement email");
    }

    private static void testPathValidation(Path root) throws Exception {
        UserEmailStore store = new UserEmailStore(root);
        expectThrows(UserEmailStore.ValidationException.class,
                () -> store.findEmail("../outside"),
                "path traversal user id is rejected");
        expectThrows(UserEmailStore.ValidationException.class,
                () -> store.findEmail(UUID.randomUUID().toString().toUpperCase()),
                "non-canonical UUID is rejected");
        expectThrows(UserEmailStore.ValidationException.class,
                () -> store.findEmail(null),
                "missing user id is rejected");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
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
                    label + ": expected " + type.getSimpleName() + ", got " + error,
                    error);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
