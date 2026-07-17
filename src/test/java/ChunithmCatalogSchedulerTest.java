import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Offline clock, refresh, and lifecycle tests for the CHUNITHM scheduler. */
public final class ChunithmCatalogSchedulerTest {
    private static final String MUSIC_JSON = """
            [{"id":1,"title":"Alpha","ds":[1.0],"level":["1"],
              "cids":[1],"charts":[{"combo":10,"charter":"Maker"}],
              "basic_info":{"title":"Alpha","artist":"A","genre":"原创",
                "bpm":120,"from":"CHUNITHM VERSE"}}]
            """;
    private static final String VERSION_JSON =
            "{\"version\":[\"CHUNITHM VERSE\"]}";
    private static int tests;

    private ChunithmCatalogSchedulerTest() {
    }

    public static void main(String[] args) {
        testBoundaryClockMath();
        testNonBlockingLifecycleAndReschedule();
        System.out.println(
                "ChunithmCatalogSchedulerTest: all " + tests
                        + " tests passed.");
    }

    private static void testBoundaryClockMath() {
        expect(
                Instant.parse("2026-07-16T04:00:00Z"),
                ChunithmCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T03:59:00Z")),
                "11:59 Taipei schedules noon");
        expect(
                Instant.parse("2026-07-16T16:00:00Z"),
                ChunithmCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T04:00:00Z")),
                "exact noon schedules midnight");
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-16T15:59:30Z"), ZoneOffset.UTC);
        expect(Duration.ofSeconds(30),
                ChunithmCatalogScheduler.delayUntilNext(clock),
                "delay reaches the exact Taipei boundary");
        expect(ZoneId.of("Asia/Taipei"), ChunithmCatalogScheduler.ZONE,
                "scheduler zone is explicitly Asia/Taipei");
    }

    private static void testNonBlockingLifecycleAndReschedule() {
        AtomicInteger fetches = new AtomicInteger();
        ChunithmCatalog catalog = ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                endpoint -> {
                    fetches.incrementAndGet();
                    throw new IOException("offline test");
                },
                Duration.ofMinutes(30),
                new AtomicLong(1)::get);
        MutableClock clock = new MutableClock(
                Instant.parse("2026-07-16T03:59:00Z"));
        FakeScheduler fake = new FakeScheduler();
        ChunithmCatalogScheduler scheduler = new ChunithmCatalogScheduler(
                catalog, clock, fake);

        scheduler.start();
        expect(0, fetches.get(), "start performs no network I/O");
        expect(1, fake.actions.size(), "start schedules one task");
        expect(Duration.ofMinutes(1), fake.delays.getFirst(),
                "first delay reaches noon");
        scheduler.start();
        expect(1, fake.actions.size(), "start is idempotent");

        clock.instant = Instant.parse("2026-07-16T04:00:00Z");
        fake.actions.getFirst().run();
        expect(1, fetches.get(),
                "scheduled task invokes one strict bounded refresh");
        expect(2, fake.actions.size(), "task schedules the next boundary");
        expect(Duration.ofHours(12), fake.delays.get(1),
                "rescheduling is boundary-based");

        FakeFuture latest = fake.futures.get(1);
        scheduler.close();
        expect(true, latest.cancelled, "close cancels the pending task");
        expect(true, fake.closed, "close shuts down scheduler backend");
        scheduler.close();
        expect(true, fake.closed, "close is idempotent");
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeScheduler
            implements ChunithmCatalogScheduler.Scheduler {
        private final List<Runnable> actions = new ArrayList<>();
        private final List<Duration> delays = new ArrayList<>();
        private final List<FakeFuture> futures = new ArrayList<>();
        private boolean closed;

        @Override
        public ScheduledFuture<?> schedule(Runnable action, Duration delay) {
            actions.add(action);
            delays.add(delay);
            FakeFuture future = new FakeFuture();
            futures.add(future);
            return future;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static final class FakeFuture implements ScheduledFuture<Object> {
        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
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
