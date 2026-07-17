import java.io.IOException;
import java.nio.file.Path;
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

/** Dependency-free clock and lifecycle tests for the Taipei scheduler. */
public final class MaimaiCatalogSchedulerTest {
    private static final String LOCAL_JSON = """
            [{"title":"Alpha","artist":"A","category":"舞萌",
              "image_file":"alpha.png","lev_bas":"4","version":"maimai"}]
            """;
    private static final String SNAPSHOT_JSON = """
            [{"id":"10","title":"Alpha","type":"SD",
              "ds":[4.0,7.0,10.0,13.0],"level":["4","7","10","13"],
              "basic_info":{"artist":"A","genre":"舞萌"}}]
            """;
    private static int tests;

    private MaimaiCatalogSchedulerTest() {
    }

    public static void main(String[] args) {
        testBoundaryClockMath();
        testNonBlockingLifecycleAndReschedule();
        System.out.println(
                "MaimaiCatalogSchedulerTest: all " + tests + " tests passed.");
    }

    private static void testBoundaryClockMath() {
        expect(
                Instant.parse("2026-07-16T04:00:00Z"),
                MaimaiCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T03:59:00Z")),
                "11:59 Taipei schedules noon");
        expect(
                Instant.parse("2026-07-16T16:00:00Z"),
                MaimaiCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T04:00:00Z")),
                "exact noon schedules the following midnight");
        expect(
                Instant.parse("2026-07-16T16:00:00Z"),
                MaimaiCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T15:59:30Z")),
                "23:59:30 Taipei schedules midnight");
        expect(
                Instant.parse("2026-07-17T04:00:00Z"),
                MaimaiCatalogScheduler.nextRunAfter(
                        Instant.parse("2026-07-16T16:00:00Z")),
                "exact midnight schedules the following noon");
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-16T15:59:30Z"), ZoneOffset.UTC);
        expect(Duration.ofSeconds(30),
                MaimaiCatalogScheduler.delayUntilNext(clock),
                "delay uses the supplied clock exactly");
        expect(ZoneId.of("Asia/Taipei"), MaimaiCatalogScheduler.ZONE,
                "scheduler zone is explicitly Asia/Taipei");
    }

    private static void testNonBlockingLifecycleAndReschedule() {
        AtomicInteger fetches = new AtomicInteger();
        SongCatalog catalog = SongCatalog.fromJson(
                LOCAL_JSON,
                SNAPSHOT_JSON,
                endpoint -> {
                    fetches.incrementAndGet();
                    throw new IOException("offline test");
                },
                (Path) null,
                (path, bytes) -> { },
                Duration.ofMinutes(30),
                new AtomicLong(1)::get,
                Clock.fixed(
                        Instant.parse("2026-07-16T03:59:00Z"), ZoneOffset.UTC));
        MutableClock clock = new MutableClock(
                Instant.parse("2026-07-16T03:59:00Z"));
        FakeScheduler fake = new FakeScheduler();
        MaimaiCatalogScheduler scheduler = new MaimaiCatalogScheduler(
                catalog, clock, fake);

        scheduler.start();
        expect(0, fetches.get(), "start does not perform network I/O");
        expect(1, fake.actions.size(), "start schedules exactly one task");
        expect(Duration.ofMinutes(1), fake.delays.getFirst(),
                "initial delay reaches the next boundary");
        scheduler.start();
        expect(1, fake.actions.size(), "start is idempotent");

        clock.instant = Instant.parse("2026-07-16T04:00:00Z");
        fake.actions.getFirst().run();
        expect(1, fetches.get(), "scheduled task invokes one bounded refresh attempt");
        expect(2, fake.actions.size(), "task schedules the following boundary");
        expect(Duration.ofHours(12), fake.delays.get(1),
                "rescheduling is boundary-based, not drift-based");

        FakeFuture latest = fake.futures.get(1);
        scheduler.close();
        expect(true, latest.cancelled, "close cancels the pending task");
        expect(true, fake.closed, "close shuts down the scheduler backend");
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
            implements MaimaiCatalogScheduler.Scheduler {
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
