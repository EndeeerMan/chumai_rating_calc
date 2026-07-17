import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Schedules maimai catalogue refreshes at 00:00 and 12:00 Asia/Taipei. */
public final class MaimaiCatalogScheduler implements AutoCloseable {
    public static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    private final SongCatalog catalog;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Object lock = new Object();
    private boolean started;
    private boolean closed;
    private ScheduledFuture<?> pending;

    /** Creates the production scheduler. Calling {@link #start()} never fetches. */
    public MaimaiCatalogScheduler(SongCatalog catalog) {
        this(
                catalog,
                Clock.systemUTC(),
                new ExecutorScheduler(Executors.newSingleThreadScheduledExecutor(
                        runnable -> Thread.ofPlatform()
                                .daemon(true)
                                .name("maimai-catalog-sync")
                                .unstarted(runnable))));
    }

    MaimaiCatalogScheduler(
            SongCatalog catalog, Clock clock, Scheduler scheduler) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Schedules the next boundary and returns immediately. */
    public void start() {
        synchronized (lock) {
            if (closed) {
                throw new IllegalStateException("Scheduler is closed");
            }
            if (started) {
                return;
            }
            started = true;
            scheduleNextLocked();
        }
    }

    /** Returns the first 00:00/12:00 boundary strictly after {@code now}. */
    public static Instant nextRunAfter(Instant now) {
        Objects.requireNonNull(now, "now");
        ZonedDateTime local = now.atZone(ZONE);
        ZonedDateTime midnight = local.toLocalDate()
                .plusDays(1)
                .atStartOfDay(ZONE);
        ZonedDateTime noon = local.toLocalDate()
                .atTime(LocalTime.NOON)
                .atZone(ZONE);
        if (noon.toInstant().isAfter(now)) {
            return noon.toInstant();
        }
        return midnight.toInstant();
    }

    /** Exact non-negative delay to the next Taipei boundary. */
    public static Duration delayUntilNext(Clock clock) {
        Instant now = Instant.now(Objects.requireNonNull(clock, "clock"));
        return Duration.between(now, nextRunAfter(now));
    }

    private void scheduleNextLocked() {
        Duration delay = delayUntilNext(clock);
        pending = scheduler.schedule(this::runRefresh, delay);
    }

    private void runRefresh() {
        try {
            SongCatalog.RefreshResult result = catalog.refreshNow();
            if (!result.success()) {
                System.err.println(result.message());
            }
        } catch (RuntimeException error) {
            System.err.println("Scheduled maimai catalogue refresh failed: "
                    + safeMessage(error));
        } finally {
            synchronized (lock) {
                pending = null;
                if (started && !closed) {
                    scheduleNextLocked();
                }
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            scheduler.close();
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.replaceAll("[\\p{Cntrl}]+", " ").trim();
    }

    interface Scheduler extends AutoCloseable {
        ScheduledFuture<?> schedule(Runnable action, Duration delay);

        @Override
        void close();
    }

    private record ExecutorScheduler(ScheduledExecutorService executor)
            implements Scheduler {
        private ExecutorScheduler {
            Objects.requireNonNull(executor, "executor");
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable action, Duration delay) {
            long nanoseconds;
            try {
                nanoseconds = delay.toNanos();
            } catch (ArithmeticException error) {
                nanoseconds = Long.MAX_VALUE;
            }
            return executor.schedule(
                    action, Math.max(0L, nanoseconds), TimeUnit.NANOSECONDS);
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
