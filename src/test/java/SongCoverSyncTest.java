import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline retry, continuation, and JSON contract tests for cover prefetching. */
public final class SongCoverSyncTest {
    private static int tests;

    private SongCoverSyncTest() {
    }

    public static void main(String[] args) throws Exception {
        testRetryAndContinuation();
        testJsonContract();
        System.out.println(
                "SongCoverSyncTest: all " + tests + " tests passed.");
    }

    private static void testRetryAndContinuation() throws Exception {
        FakeStore store = new FakeStore(Set.of("1"));
        store.failuresRemaining.put("2", new AtomicInteger(1));
        store.failuresRemaining.put("3", new AtomicInteger(99));

        SongCoverSync.DownloadResult first = SongCoverSync.downloadMissing(
                "test",
                Set.of("1", "2", "3"),
                store,
                3,
                2,
                ignored -> { });
        expect(1, first.availableBefore(), "one cover starts cached");
        expect(1, first.downloaded(), "retry eventually downloads ID 2");
        expect(Set.of("3"), first.remainingIds(),
                "permanent failure remains explicit");
        expect(1, first.failures().size(), "failure sample is retained");
        expect("3", first.failures().getFirst().songId(),
                "failure identifies canonical SongID");

        store.failuresRemaining.get("3").set(0);
        SongCoverSync.DownloadResult second = SongCoverSync.downloadMissing(
                "test",
                Set.of("1", "2", "3"),
                store,
                3,
                2,
                ignored -> { });
        expect(2, second.availableBefore(),
                "continuation skips covers completed by the previous run");
        expect(1, second.downloaded(), "continuation downloads only the remainder");
        expect(Set.of(), second.remainingIds(), "continuation reaches completeness");
        expect(0, second.failures().size(), "successful continuation has no failures");
    }

    private static void testJsonContract() {
        SongCoverSync.GameResult maimai = new SongCoverSync.GameResult(
                true, 1_251, 1_251, 1_228, 23, 0, 1_251, 0, List.of(), null);
        SongCoverSync.GameResult chunithm = new SongCoverSync.GameResult(
                false,
                1_557,
                1_430,
                74,
                1_355,
                1,
                1_556,
                0,
                List.of(new SongCoverSync.Failure("99", "offline")),
                "Some CHUNITHM covers remain unavailable");
        SongCoverSync.SyncResult result = new SongCoverSync.SyncResult(
                false, Instant.EPOCH, maimai, chunithm);
        Map<?, ?> root = (Map<?, ?>) Json.parse(result.toJson());
        expect(false, root.get("success"), "one incomplete game fails the report");
        expect(false, root.containsKey("officialPlayerData"),
                "cover command has no official player-data channel");
        Map<?, ?> maimaiValue = (Map<?, ?>) root.get("maimai");
        expect(23, ((Number) maimaiValue.get("downloaded")).intValue(),
                "JSON reports newly downloaded covers");
        Map<?, ?> chunithmValue = (Map<?, ?>) root.get("chunithm");
        expect(1, ((Number) chunithmValue.get("remainingFiles")).intValue(),
                "JSON reports remaining cover files");
        expect(1, ((List<?>) chunithmValue.get("failures")).size(),
                "JSON carries a bounded failure sample");
    }

    private static final class FakeStore implements SongCoverSync.CoverStore {
        private final Set<String> cached = ConcurrentHashMap.newKeySet();
        private final Map<String, AtomicInteger> failuresRemaining =
                new ConcurrentHashMap<>();

        private FakeStore(Set<String> initial) {
            cached.addAll(initial);
        }

        @Override
        public boolean isCached(String id) {
            return cached.contains(id);
        }

        @Override
        public void ensure(String id) throws IOException {
            AtomicInteger remaining = failuresRemaining.get(id);
            if (remaining != null && remaining.getAndUpdate(value ->
                    Math.max(0, value - 1)) > 0) {
                throw new IOException("temporary failure");
            }
            cached.add(id);
        }
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }
}
