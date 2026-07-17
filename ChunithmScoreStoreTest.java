import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free persistence and concurrency tests. */
public final class ChunithmScoreStoreTest {
    private static final String OLD = "CHUNITHM SUN";
    private static final String NEW = "CHUNITHM VERSE";
    private static final List<String> LATEST = List.of(NEW);
    private static int testsRun;

    private ChunithmScoreStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("chunithm-score-store-test-");
        try {
            testMissingSnapshotAndPublicShape(temporary.resolve("missing"));
            testTwoUsersAndPersistence(temporary.resolve("two-users"));
            testDeleteUserData(temporary.resolve("delete-user-data"));
            testStrictUpdateParser(temporary.resolve("parser"));
            testIdentityAndRevisionConflicts(temporary.resolve("conflicts"));
            testConcurrentRevisionCAS(temporary.resolve("concurrent"));
            testCorruptAndOversizedFiles(temporary.resolve("bad-files"));
            testCountAndSerializedSizeLimits(temporary.resolve("limits"));
            testRevisionOverflow(temporary.resolve("revision-overflow"));
            testImmutableValues(temporary.resolve("immutable"));

            System.out.println(
                    "ChunithmScoreStoreTest: all " + testsRun + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testMissingSnapshotAndPublicShape(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String userId = uuid();
        ChunithmScoreStore.Snapshot snapshot = store.loadSnapshot(userId, LATEST);

        assertEquals(userId, snapshot.userId(), "missing snapshot owner");
        assertEquals(0L, snapshot.revision(), "missing snapshot revision");
        assertEquals(List.of(), snapshot.charts(), "missing snapshot charts");
        Map<String, Object> json = ChunithmScoreStore.snapshotToJsonValue(snapshot);
        assertEquals(Set.of("userId", "revision", "charts"), json.keySet(),
                "public snapshot has exact keys");
        assertEquals(userId, json.get("userId"), "public snapshot userId");
        assertEquals(0L, json.get("revision"), "public snapshot revision");

        expectThrows(IllegalArgumentException.class,
                () -> store.loadSnapshot("../not-a-uuid", LATEST),
                "path traversal cannot be used as a user ID");
        expectThrows(IllegalArgumentException.class,
                () -> store.loadSnapshot(userId.toUpperCase(java.util.Locale.ROOT), LATEST),
                "non-canonical UUID is rejected");
    }

    private static void testTwoUsersAndPersistence(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String firstUser = uuid();
        String secondUser = uuid();
        List<ChunithmChartInput> firstCharts = List.of(
                chart("100", "First", "MASTER", 13.7, 1_008_765, OLD),
                chart("100", "First", "ULTIMA", 14.7, 1_009_000, NEW),
                chart("8000", "[止] Test", "World's End", 0.0, 1_010_000, OLD));
        List<ChunithmChartInput> secondCharts = List.of(
                chart("200", "Second", "ULTIMA", 14.2, 1_009_000, NEW));

        ChunithmScoreStore.Snapshot first = store.save(
                firstUser, firstUser, 0, firstCharts, LATEST);
        ChunithmScoreStore.Snapshot second = store.save(
                secondUser, secondUser, 0, secondCharts, LATEST);
        assertEquals(1L, first.revision(), "first revision after save");
        assertEquals(1L, second.revision(), "second revision after save");
        assertEquals(firstCharts, store.loadSnapshot(firstUser, LATEST).charts(),
                "same-song difficulties from different versions persist");
        assertEquals(secondCharts, store.loadSnapshot(secondUser, LATEST).charts(),
                "second user's charts are isolated");

        Path firstFile = root.resolve(firstUser + ".json");
        Path secondFile = root.resolve(secondUser + ".json");
        assertEquals(true, Files.isRegularFile(firstFile), "first UUID file exists");
        assertEquals(true, Files.isRegularFile(secondFile), "second UUID file exists");

        Map<String, Object> stored = object(Json.parse(Files.readString(firstFile)));
        assertEquals(Set.of("revision", "charts"), stored.keySet(),
                "stored document has exact keys");
        Map<String, Object> storedChart = object(list(stored.get("charts")).getFirst());
        assertEquals(Set.of(
                "songId", "title", "difficulty", "constant", "score", "version"),
                storedChart.keySet(), "only six core chart fields are persisted");
        assertEquals(false, storedChart.containsKey("rating"), "rating is not persisted");
        assertEquals(false, storedChart.containsKey("selected"),
                "selection state is not persisted");

        ChunithmScoreStore restarted = new ChunithmScoreStore(root);
        assertEquals(firstCharts, restarted.loadSnapshot(firstUser, LATEST).charts(),
                "first user survives restart");
        assertEquals(secondCharts, restarted.loadSnapshot(secondUser, LATEST).charts(),
                "second user survives restart");
    }

    private static void testDeleteUserData(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String deletedUser = uuid();
        String retainedUser = uuid();
        List<ChunithmChartInput> deletedCharts = List.of(
                chart("delete", "Delete", "MASTER", 13.5, 1_005_000, OLD));
        List<ChunithmChartInput> retainedCharts = List.of(
                chart("retain", "Retain", "ULTIMA", 14.5, 1_009_000, NEW));
        store.save(deletedUser, deletedUser, 0, deletedCharts, LATEST);
        store.save(retainedUser, retainedUser, 0, retainedCharts, LATEST);

        Path deletedFile = root.resolve(deletedUser + ".json");
        Path retainedFile = root.resolve(retainedUser + ".json");
        assertEquals(true, Files.isRegularFile(deletedFile),
                "deleted user's score file exists before cleanup");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "retained user's score file exists before cleanup");
        expectThrows(IllegalArgumentException.class,
                () -> store.deleteUserData("../not-a-uuid"),
                "score deletion rejects an invalid UUID");

        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "score deletion removes the selected user's file");
        assertEquals(List.of(), store.loadSnapshot(deletedUser, LATEST).charts(),
                "deleted score data reloads as an empty snapshot");
        assertEquals(retainedCharts, store.loadSnapshot(retainedUser, LATEST).charts(),
                "score deletion does not affect another user");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "another user's score file remains present");
        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "repeated score deletion is idempotent");

        String nonRegularUser = uuid();
        Path nonRegularEntry = root.resolve(nonRegularUser + ".json");
        Files.createDirectory(nonRegularEntry);
        expectThrows(IOException.class,
                () -> store.deleteUserData(nonRegularUser),
                "score deletion rejects a non-regular entry");
        assertEquals(true, Files.isDirectory(nonRegularEntry),
                "rejected non-regular score entry is not deleted");
        Files.delete(nonRegularEntry);

        String linkedUser = uuid();
        Path link = root.resolve(linkedUser + ".json");
        Path target = root.resolve("score-link-target.json");
        Files.writeString(target, "target", StandardCharsets.UTF_8);
        boolean linkCreated = false;
        try {
            Files.createSymbolicLink(link, target);
            linkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows may require Developer Mode or elevated symbolic-link rights.
        }
        if (linkCreated) {
            expectThrows(IOException.class,
                    () -> store.deleteUserData(linkedUser),
                    "score deletion rejects a symbolic link");
            assertEquals(true,
                    Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    "rejected score link itself remains present");
            assertEquals(true, Files.isRegularFile(target),
                    "rejected score link target remains present");
            Files.delete(link);
        }
        Files.delete(target);
    }

    private static void testStrictUpdateParser(Path root) throws Exception {
        Files.createDirectories(root);
        String userId = uuid();
        List<ChunithmChartInput> charts = List.of(
                chart("1", "Song", "expert", 10.5, 987_654, OLD),
                chart("8001", "WE", "World's End", 0.0, 1_010_000, NEW));
        Map<String, Object> updateValue = updateValue(userId, 7, charts);

        ChunithmScoreStore.Update parsed = ChunithmScoreStore.parseUpdateDocument(
                parsedJson(updateValue), LATEST);
        assertEquals(userId, parsed.expectedUserId(), "parsed expected user ID");
        assertEquals(7L, parsed.revision(), "parsed revision");
        assertEquals(charts, parsed.charts(), "parsed charts including World's End");

        Map<String, Object> extraTopLevel = new LinkedHashMap<>(updateValue);
        extraTopLevel.put("rating", 99);
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(extraTopLevel), LATEST),
                "unknown update field is rejected");

        Map<String, Object> missingRevision = new LinkedHashMap<>(updateValue);
        missingRevision.remove("revision");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(missingRevision), LATEST),
                "missing update field is rejected");

        Map<String, Object> extraChart = new LinkedHashMap<>(
                ChunithmScoreStore.chartsToJsonValues(charts).getFirst());
        extraChart.put("ra", BigDecimal.ZERO);
        Map<String, Object> badChartUpdate = new LinkedHashMap<>();
        badChartUpdate.put("expectedUserId", userId);
        badChartUpdate.put("revision", 0);
        badChartUpdate.put("charts", List.of(extraChart));
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(badChartUpdate), LATEST),
                "unknown chart field is rejected");

        Map<String, Object> fractionalScore = new LinkedHashMap<>(
                ChunithmScoreStore.chartsToJsonValues(charts).getFirst());
        fractionalScore.put("score", new BigDecimal("1000000.5"));
        Map<String, Object> fractionalUpdate = new LinkedHashMap<>(updateValue);
        fractionalUpdate.put("charts", List.of(fractionalScore));
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(fractionalUpdate), LATEST),
                "fractional score is rejected");

        Map<String, Object> duplicateUpdate = updateValue(userId, 0, List.of(
                chart("dup", "One", "master", 10.0, 1_000_000, OLD),
                chart("dup", "Two", "MASTER", 11.0, 1_000_000, OLD)));
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(duplicateUpdate), LATEST),
                "calculator validation rejects duplicate song+difficulty");

        Map<String, Object> forgedUser = new LinkedHashMap<>(updateValue);
        forgedUser.put("expectedUserId", "../../victim");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(forgedUser), LATEST),
                "parser requires a canonical expectedUserId");

        List<Map<String, Object>> tooMany = new ArrayList<>();
        Map<String, Object> oneChart = ChunithmScoreStore.chartsToJsonValues(
                List.of(charts.getFirst())).getFirst();
        for (int index = 0; index <= ChunithmScoreStore.MAX_CHARTS; index++) {
            tooMany.add(oneChart);
        }
        Map<String, Object> tooManyUpdate = new LinkedHashMap<>(updateValue);
        tooManyUpdate.put("charts", tooMany);
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmScoreStore.parseUpdateDocument(
                        parsedJson(tooManyUpdate), LATEST),
                "parser enforces the 3000-chart limit");
    }

    private static void testIdentityAndRevisionConflicts(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String owner = uuid();
        String forgedOwner = uuid();
        List<ChunithmChartInput> original = List.of(
                chart("1", "Original", "MASTER", 12.0, 1_000_000, OLD));
        store.save(owner, owner, 0, original, LATEST);

        ChunithmScoreStore.ConflictException identity = expectThrows(
                ChunithmScoreStore.ConflictException.class,
                () -> store.save(owner, forgedOwner, 1, List.of(), LATEST),
                "forged expected user is a conflict");
        assertContains(identity.getMessage(), "expectedUserId",
                "identity conflict message is explicit");
        assertEquals(original, store.loadSnapshot(owner, LATEST).charts(),
                "identity conflict writes nothing");
        assertEquals(0L, store.loadSnapshot(forgedOwner, LATEST).revision(),
                "identity conflict cannot create victim file");

        ChunithmScoreStore.ConflictException stale = expectThrows(
                ChunithmScoreStore.ConflictException.class,
                () -> store.save(owner, owner, 0, List.of(), LATEST),
                "stale revision is a conflict");
        assertContains(stale.getMessage(), "reload", "revision conflict is explicit");
        assertEquals(1L, store.loadSnapshot(owner, LATEST).revision(),
                "stale write leaves revision unchanged");
    }

    private static void testConcurrentRevisionCAS(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String userId = uuid();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        Thread first = writerThread(
                store, userId, "first", ready, start, done,
                successes, conflicts, unexpected);
        Thread second = writerThread(
                store, userId, "second", ready, start, done,
                successes, conflicts, unexpected);
        first.start();
        second.start();
        ready.await();
        start.countDown();
        done.await();
        first.join();
        second.join();

        if (unexpected.get() != null) {
            throw new AssertionError("concurrent writer failed unexpectedly", unexpected.get());
        }
        assertEquals(1, successes.get(), "exactly one concurrent CAS succeeds");
        assertEquals(1, conflicts.get(), "exactly one concurrent CAS conflicts");
        ChunithmScoreStore.Snapshot finalSnapshot = store.loadSnapshot(userId, LATEST);
        assertEquals(1L, finalSnapshot.revision(), "concurrent CAS increments once");
        assertEquals(1, finalSnapshot.charts().size(), "one winner's data is stored");
        assertEquals(true,
                Set.of("first", "second").contains(finalSnapshot.charts().getFirst().songId()),
                "stored data belongs to one complete winner");
    }

    private static void testCorruptAndOversizedFiles(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String invalidJsonUser = uuid();
        Files.writeString(root.resolve(invalidJsonUser + ".json"), "{broken");
        IOException invalidJson = expectThrows(IOException.class,
                () -> store.loadSnapshot(invalidJsonUser, LATEST),
                "invalid stored JSON fails closed");
        assertContains(invalidJson.getMessage(), "invalid JSON",
                "invalid JSON error is explicit");

        String invalidShapeUser = uuid();
        Files.writeString(
                root.resolve(invalidShapeUser + ".json"),
                "{\"revision\":0,\"charts\":[],\"unexpected\":true}",
                StandardCharsets.UTF_8);
        IOException invalidShape = expectThrows(IOException.class,
                () -> store.loadSnapshot(invalidShapeUser, LATEST),
                "unknown stored field fails closed");
        assertContains(invalidShape.getMessage(), "invalid",
                "invalid stored shape error is explicit");

        String oversizedUser = uuid();
        Files.write(
                root.resolve(oversizedUser + ".json"),
                new byte[ChunithmScoreStore.MAX_FILE_BYTES + 1]);
        IOException oversized = expectThrows(IOException.class,
                () -> store.loadSnapshot(oversizedUser, LATEST),
                "oversized stored file is rejected before parsing");
        assertContains(oversized.getMessage(), "too large",
                "oversized error is explicit");
    }

    private static void testCountAndSerializedSizeLimits(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String maxUser = uuid();
        List<ChunithmChartInput> maximum = new ArrayList<>();
        for (int index = 0; index < ChunithmScoreStore.MAX_CHARTS; index++) {
            maximum.add(chart(
                    "song-" + index, "S", "BASIC", 1.0, 0, OLD));
        }
        ChunithmScoreStore.Snapshot saved = store.save(
                maxUser, maxUser, 0, maximum, LATEST);
        assertEquals(1L, saved.revision(), "exactly 3000 charts are allowed");
        assertEquals(ChunithmScoreStore.MAX_CHARTS,
                store.loadSnapshot(maxUser, LATEST).charts().size(),
                "all 3000 charts persist");

        List<ChunithmChartInput> overCount = new ArrayList<>(maximum);
        overCount.add(chart("over-limit", "S", "BASIC", 1.0, 0, OLD));
        String overCountUser = uuid();
        expectThrows(IllegalArgumentException.class,
                () -> store.save(
                        overCountUser, overCountUser, 0, overCount, LATEST),
                "more than 3000 charts are rejected");

        String largeUser = uuid();
        String escapedTitle = "\\".repeat(300);
        String escapedVersion = "\\".repeat(100);
        String escapedIdTail = "\\".repeat(80);
        List<ChunithmChartInput> tooLarge = new ArrayList<>();
        for (int index = 0; index < ChunithmScoreStore.MAX_CHARTS; index++) {
            tooLarge.add(chart(
                    "song-" + index + escapedIdTail,
                    escapedTitle,
                    "MASTER",
                    10.0,
                    1_000_000,
                    escapedVersion));
        }
        IllegalArgumentException large = expectThrows(IllegalArgumentException.class,
                () -> store.save(largeUser, largeUser, 0, tooLarge, List.of()),
                "serialized data above 2 MiB is rejected");
        assertContains(large.getMessage(), "too large",
                "serialized-size error is explicit");
        assertEquals(false, Files.exists(root.resolve(largeUser + ".json")),
                "oversized serialization creates no destination file");

        try (var temporaryFiles = Files.list(root)) {
            long count = temporaryFiles
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count();
            assertEquals(0L, count, "atomic writes leave no temporary files");
        }
    }

    private static void testRevisionOverflow(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String userId = uuid();
        Files.writeString(
                root.resolve(userId + ".json"),
                "{\"revision\":9223372036854775807,\"charts\":[]}",
                StandardCharsets.UTF_8);
        IOException overflow = expectThrows(IOException.class,
                () -> store.save(userId, userId, Long.MAX_VALUE, List.of(), LATEST),
                "revision overflow is rejected");
        assertContains(overflow.getMessage(), "revision limit",
                "revision overflow error is explicit");
        assertEquals(Long.MAX_VALUE, store.loadSnapshot(userId, LATEST).revision(),
                "overflow attempt does not alter stored revision");
    }

    private static void testImmutableValues(Path root) throws Exception {
        ChunithmScoreStore store = new ChunithmScoreStore(root);
        String userId = uuid();
        List<ChunithmChartInput> mutable = new ArrayList<>();
        mutable.add(chart("1", "One", "MASTER", 10.0, 1_000_000, OLD));
        ChunithmScoreStore.Snapshot snapshot = store.save(
                userId, userId, 0, mutable, LATEST);
        mutable.clear();
        assertEquals(1, snapshot.charts().size(), "snapshot defensively copies charts");
        expectThrows(UnsupportedOperationException.class,
                () -> snapshot.charts().clear(), "snapshot charts are immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> ChunithmScoreStore.chartsToJsonValues(snapshot.charts()).clear(),
                "JSON chart list is immutable");
    }

    private static Thread writerThread(
            ChunithmScoreStore store,
            String userId,
            String songId,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger successes,
            AtomicInteger conflicts,
            AtomicReference<Throwable> unexpected) {
        return new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                store.save(userId, userId, 0, List.of(
                        chart(songId, songId, "MASTER", 10.0, 1_000_000, OLD)),
                        LATEST);
                successes.incrementAndGet();
            } catch (ChunithmScoreStore.ConflictException error) {
                conflicts.incrementAndGet();
            } catch (Throwable error) {
                unexpected.compareAndSet(null, error);
            } finally {
                done.countDown();
            }
        }, "chunithm-store-writer-" + songId);
    }

    private static Map<String, Object> updateValue(
            String expectedUserId,
            long revision,
            List<ChunithmChartInput> charts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("expectedUserId", expectedUserId);
        value.put("revision", revision);
        value.put("charts", ChunithmScoreStore.chartsToJsonValues(charts));
        return value;
    }

    private static Object parsedJson(Object value) {
        return Json.parse(Json.stringify(value));
    }

    private static ChunithmChartInput chart(
            String songId,
            String title,
            String difficulty,
            double constant,
            int score,
            String version) {
        return new ChunithmChartInput(
                songId, title, difficulty, constant, score, version);
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (!ObjectsEqual.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expected, String message) {
        testsRun++;
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(
                    message + ": expected <" + actual + "> to contain <" + expected + ">");
        }
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> expectedType, ThrowingAction action, String message) {
        testsRun++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expectedType.isInstance(error)) {
                return expectedType.cast(error);
            }
            throw new AssertionError(
                    message + ": expected " + expectedType.getSimpleName()
                            + " but caught " + error.getClass().getSimpleName(),
                    error);
        }
        throw new AssertionError(
                message + ": expected " + expectedType.getSimpleName()
                        + " to be thrown");
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

    /** Keeps the test helper independent of static-import style. */
    private static final class ObjectsEqual {
        private ObjectsEqual() {
        }

        static boolean equals(Object left, Object right) {
            return java.util.Objects.equals(left, right);
        }
    }
}
