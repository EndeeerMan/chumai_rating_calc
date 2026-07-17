import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Schedules strict CHUNITHM snapshot refreshes at Taipei 00:00 and 12:00. */
public final class ChunithmCatalogScheduler implements AutoCloseable {
    public static final ZoneId ZONE = MaimaiCatalogScheduler.ZONE;

    private final ChunithmCatalog catalog;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Object lock = new Object();
    private boolean started;
    private boolean closed;
    private ScheduledFuture<?> pending;

    /** Creates the production scheduler. Calling {@link #start()} never fetches. */
    public ChunithmCatalogScheduler(ChunithmCatalog catalog) {
        this(
                catalog,
                Clock.systemUTC(),
                new ExecutorScheduler(Executors.newSingleThreadScheduledExecutor(
                        runnable -> Thread.ofPlatform()
                                .daemon(true)
                                .name("chunithm-catalog-sync")
                                .unstarted(runnable))));
    }

    ChunithmCatalogScheduler(
            ChunithmCatalog catalog, Clock clock, Scheduler scheduler) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** Schedules the next Taipei noon/midnight boundary and returns. */
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

    public static Instant nextRunAfter(Instant now) {
        return MaimaiCatalogScheduler.nextRunAfter(now);
    }

    public static Duration delayUntilNext(Clock clock) {
        return MaimaiCatalogScheduler.delayUntilNext(clock);
    }

    private void scheduleNextLocked() {
        pending = scheduler.schedule(this::runRefresh, delayUntilNext(clock));
    }

    private void runRefresh() {
        try {
            ChunithmCatalog.RefreshResult result = catalog.refreshNow();
            if (!result.success()) {
                System.err.println(result.message());
            }
        } catch (RuntimeException error) {
            System.err.println("Scheduled CHUNITHM catalogue refresh failed: "
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
