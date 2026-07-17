import java.io.IOException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Dependency-free tests for verification-code security and rate limits. */
public final class EmailVerificationServiceTest {
    private static int tests;

    private EmailVerificationServiceTest() {
    }

    public static void main(String[] args) throws Exception {
        testDeliveryAndOneTimeVerification();
        testPurposeAndContextIsolation();
        testTwoMinuteCooldownAndReplacement();
        testExpirationBoundary();
        testFiveAttemptLimit();
        testHourlyEmailRateLimit();
        testGlobalHourlyBudget();
        testConcurrentDeliveryIsSerializedPerEmail();
        testGlobalConcurrentDeliveryLimit();
        testInvalidateCancelsInFlightPublication();
        testCloseErasesChallengesAndSender();
        testFailedDeliveryIsNotVerifiable();
        testValidation();
        System.out.println(
                "EmailVerificationServiceTest: all " + tests + " tests passed.");
    }

    private static void testDeliveryAndOneTimeVerification() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 7L);
        EmailVerificationService.SendResult result = service.sendCode(
                EmailVerificationService.Purpose.REGISTER,
                "NewPlayer",
                "Player@EXAMPLE.COM");

        expect(1, sender.deliveries.size(), "one verification email is delivered");
        Delivery delivery = sender.deliveries.getFirst();
        expect("player@example.com", delivery.recipient(),
                "delivery uses the canonical case-insensitive address");
        expect(true, delivery.code().matches("[0-9]{6}"),
                "code is exactly six ASCII digits");
        expect("player@example.com", result.email(),
                "send result exposes only normalized metadata");
        expect(clock.instant().plus(Duration.ofMinutes(10)), result.expiresAt(),
                "code expires ten minutes after successful delivery");
        expect(clock.instant().plus(Duration.ofMinutes(2)), result.resendAvailableAt(),
                "resend becomes available after two minutes");

        expect(EmailVerificationService.VerificationResult.INVALID_CODE,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "NewPlayer",
                        "PLAYER@example.com",
                        wrong(delivery.code())),
                "wrong code is rejected");
        expect(EmailVerificationService.VerificationResult.VERIFIED,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "NewPlayer",
                        "player@example.com",
                        delivery.code()),
                "correct code verifies and consumes atomically");
        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "NewPlayer",
                        "player@example.com",
                        delivery.code()),
                "a consumed code cannot be replayed");
    }

    private static void testPurposeAndContextIsolation() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 11L);
        service.sendCode(
                EmailVerificationService.Purpose.REGISTER,
                "register-session",
                "isolated@example.com");
        String code = sender.last().code();

        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REBIND,
                        "register-session",
                        "isolated@example.com",
                        code),
                "registration code cannot authorize an email rebind");
        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "another-session",
                        "isolated@example.com",
                        code),
                "code cannot cross its registration context");
        expect(EmailVerificationService.VerificationResult.VERIFIED,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        " register-session ",
                        "isolated@example.com",
                        code),
                "normalized matching context verifies");
    }

    private static void testTwoMinuteCooldownAndReplacement() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 19L);
        service.sendCode(
                EmailVerificationService.Purpose.BIND,
                "legacy-user",
                "cooldown@example.com");
        String oldCode = sender.last().code();

        EmailVerificationService.RateLimitException immediate = expectThrows(
                EmailVerificationService.RateLimitException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.BIND,
                        "legacy-user",
                        "cooldown@example.com"),
                "immediate resend is rate limited");
        expect(120L, immediate.retryAfterSeconds(),
                "initial cooldown advertises 120 seconds");
        clock.advance(Duration.ofSeconds(119));
        EmailVerificationService.RateLimitException finalSecond = expectThrows(
                EmailVerificationService.RateLimitException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.BIND,
                        "legacy-user",
                        "cooldown@example.com"),
                "cooldown remains active through second 119");
        expect(1L, finalSecond.retryAfterSeconds(),
                "remaining cooldown rounds up to one second");

        clock.advance(Duration.ofSeconds(1));
        service.sendCode(
                EmailVerificationService.Purpose.BIND,
                "legacy-user",
                "cooldown@example.com");
        String newCode = sender.last().code();
        expect(2, sender.deliveries.size(), "resend is accepted at exactly two minutes");
        if (newCode.equals(oldCode)) {
            throw new AssertionError("deterministic test random unexpectedly repeated a code");
        }
        expect(EmailVerificationService.VerificationResult.INVALID_CODE,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.BIND,
                        "legacy-user",
                        "cooldown@example.com",
                        oldCode),
                "successful resend replaces the old code");
        expect(EmailVerificationService.VerificationResult.VERIFIED,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.BIND,
                        "legacy-user",
                        "cooldown@example.com",
                        newCode),
                "newly delivered code verifies");
    }

    private static void testExpirationBoundary() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 23L);
        service.sendCode(
                EmailVerificationService.Purpose.REBIND,
                "user-id",
                "expiry@example.com");
        String code = sender.last().code();
        clock.advance(Duration.ofMinutes(10));
        expect(EmailVerificationService.VerificationResult.EXPIRED,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REBIND,
                        "user-id",
                        "expiry@example.com",
                        code),
                "code is expired at the exact ten-minute boundary");
        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REBIND,
                        "user-id",
                        "expiry@example.com",
                        code),
                "expired code is removed after first observation");
    }

    private static void testFiveAttemptLimit() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 29L);
        service.sendCode(
                EmailVerificationService.Purpose.BIND,
                "attempt-user",
                "attempts@example.com");
        String correct = sender.last().code();
        List<String> invalid = List.of("", "１２３４５６", "abcdef", "12345");
        for (String supplied : invalid) {
            expect(EmailVerificationService.VerificationResult.INVALID_CODE,
                    service.verifyAndConsume(
                            EmailVerificationService.Purpose.BIND,
                            "attempt-user",
                            "attempts@example.com",
                            supplied),
                    "malformed input consumes one failed attempt");
        }
        expect(EmailVerificationService.VerificationResult.ATTEMPTS_EXHAUSTED,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.BIND,
                        "attempt-user",
                        "attempts@example.com",
                        wrong(correct)),
                "fifth failure exhausts the challenge");
        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.BIND,
                        "attempt-user",
                        "attempts@example.com",
                        correct),
                "correct code cannot revive an exhausted challenge");
    }

    private static void testHourlyEmailRateLimit() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 31L);
        for (int index = 0; index < 5; index++) {
            service.sendCode(
                    EmailVerificationService.Purpose.REGISTER,
                    "rate-session-" + index,
                    "RATE@example.com");
            if (index < 4) {
                clock.advance(Duration.ofMinutes(2));
            }
        }
        clock.advance(Duration.ofMinutes(2));
        EmailVerificationService.RateLimitException limited = expectThrows(
                EmailVerificationService.RateLimitException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REBIND,
                        "different-purpose",
                        "rate@example.com"),
                "per-email hourly limit spans contexts and purposes");
        expect(3000L, limited.retryAfterSeconds(),
                "hourly limit reports the first window boundary");
        clock.advance(Duration.ofMinutes(50));
        service.sendCode(
                EmailVerificationService.Purpose.REBIND,
                "different-purpose",
                "rate@example.com");
        expect(6, sender.deliveries.size(),
                "old rate entries leave the window at exactly one hour");
    }

    private static void testFailedDeliveryIsNotVerifiable() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        sender.failure = new IOException("test SMTP unavailable");
        EmailVerificationService service = service(sender, clock, 37L);
        expectThrows(IOException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "delivery-failure",
                        "failure@example.com"),
                "SMTP failure is propagated without publishing a challenge");
        String undeliveredCode = sender.last().code();
        expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "delivery-failure",
                        "failure@example.com",
                        undeliveredCode),
                "undelivered code is never verifiable");
        expectThrows(EmailVerificationService.RateLimitException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "delivery-failure",
                        "failure@example.com"),
                "failed delivery still receives abuse-protection cooldown");
        clock.advance(Duration.ofMinutes(2));
        sender.failure = null;
        service.sendCode(
                EmailVerificationService.Purpose.REGISTER,
                "delivery-failure",
                "failure@example.com");
        expect(2, sender.deliveries.size(), "delivery can retry after cooldown");
    }

    private static void testConcurrentDeliveryIsSerializedPerEmail() throws Exception {
        MutableClock clock = clock();
        BlockingSender sender = new BlockingSender();
        EmailVerificationService service = new EmailVerificationService(
                sender, clock, new SequenceSecureRandom(43));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<EmailVerificationService.SendResult> first = executor.submit(
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "first-context",
                        "parallel@example.com"));
        try {
            expect(true, sender.entered.await(3, TimeUnit.SECONDS),
                    "first delivery reaches the blocking sender");
            EmailVerificationService.RateLimitException limited = expectThrows(
                    EmailVerificationService.RateLimitException.class,
                    () -> service.sendCode(
                            EmailVerificationService.Purpose.REBIND,
                            "second-context",
                            "PARALLEL@example.com"),
                    "same email cannot start a parallel delivery via another context");
            expect(1L, limited.retryAfterSeconds(),
                    "in-flight delivery returns a short retry hint");
            sender.release.countDown();
            expect("parallel@example.com", first.get().email(),
                    "original delivery completes after the sender is released");
        } finally {
            sender.release.countDown();
            executor.shutdownNow();
        }
    }

    private static void testGlobalHourlyBudget() throws Exception {
        MutableClock clock = clock();
        CountingSender sender = new CountingSender();
        EmailVerificationService service = new EmailVerificationService(
                sender, clock, new SequenceSecureRandom(53));
        for (int index = 0;
             index < EmailVerificationService.MAX_GLOBAL_SENDS_PER_WINDOW;
             index++) {
            service.sendCode(
                    EmailVerificationService.Purpose.REGISTER,
                    "global-" + index,
                    "global-" + index + "@example.com");
        }
        EmailVerificationService.RateLimitException limited = expectThrows(
                EmailVerificationService.RateLimitException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "global-overflow",
                        "global-overflow@example.com"),
                "global hourly budget limits many distinct target emails");
        expect(3600L, limited.retryAfterSeconds(),
                "global budget advertises its hour boundary");
        expect(EmailVerificationService.MAX_GLOBAL_SENDS_PER_WINDOW, sender.count,
                "request beyond global budget never invokes sender");
        clock.advance(Duration.ofHours(1));
        service.sendCode(
                EmailVerificationService.Purpose.REGISTER,
                "global-after-window",
                "global-after-window@example.com");
        expect(EmailVerificationService.MAX_GLOBAL_SENDS_PER_WINDOW + 1, sender.count,
                "global budget entries expire at exactly one hour");
    }

    private static void testGlobalConcurrentDeliveryLimit() throws Exception {
        MutableClock clock = clock();
        MultiBlockingSender sender = new MultiBlockingSender(
                EmailVerificationService.MAX_CONCURRENT_DELIVERIES);
        EmailVerificationService service = new EmailVerificationService(
                sender, clock, new SequenceSecureRandom(61));
        ExecutorService executor = Executors.newFixedThreadPool(
                EmailVerificationService.MAX_CONCURRENT_DELIVERIES);
        List<Future<EmailVerificationService.SendResult>> deliveries = new ArrayList<>();
        try {
            for (int index = 0;
                 index < EmailVerificationService.MAX_CONCURRENT_DELIVERIES;
                 index++) {
                int item = index;
                deliveries.add(executor.submit(() -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "concurrent-" + item,
                        "concurrent-" + item + "@example.com")));
            }
            expect(true, sender.entered.await(3, TimeUnit.SECONDS),
                    "configured number of deliveries may run concurrently");
            EmailVerificationService.RateLimitException limited = expectThrows(
                    EmailVerificationService.RateLimitException.class,
                    () -> service.sendCode(
                            EmailVerificationService.Purpose.REGISTER,
                            "concurrent-overflow",
                            "concurrent-overflow@example.com"),
                    "fifth simultaneous SMTP delivery is rejected immediately");
            expect(1L, limited.retryAfterSeconds(),
                    "concurrent-capacity rejection has a short retry hint");
            sender.release.countDown();
            for (Future<EmailVerificationService.SendResult> delivery : deliveries) {
                delivery.get();
            }
            service.sendCode(
                    EmailVerificationService.Purpose.REGISTER,
                    "concurrent-after-release",
                    "concurrent-after-release@example.com");
            expect(EmailVerificationService.MAX_CONCURRENT_DELIVERIES + 1,
                    sender.deliveryCount,
                    "completed deliveries release global SMTP capacity");
        } finally {
            sender.release.countDown();
            executor.shutdownNow();
        }
    }

    private static void testInvalidateCancelsInFlightPublication() throws Exception {
        MutableClock clock = clock();
        BlockingSender sender = new BlockingSender();
        EmailVerificationService service = new EmailVerificationService(
                sender, clock, new SequenceSecureRandom(47));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<EmailVerificationService.SendResult> delivery = executor.submit(
                () -> service.sendCode(
                        EmailVerificationService.Purpose.BIND,
                        "cancel-context",
                        "cancel@example.com"));
        try {
            expect(true, sender.entered.await(3, TimeUnit.SECONDS),
                    "delivery enters sender before cancellation");
            service.invalidate(
                    EmailVerificationService.Purpose.BIND,
                    "cancel-context",
                    "cancel@example.com");
            sender.release.countDown();
            ExecutionException cancelled = expectThrows(
                    ExecutionException.class,
                    delivery::get,
                    "cancelled in-flight delivery cannot publish its challenge");
            expect(true,
                    cancelled.getCause()
                            instanceof EmailVerificationService.DeliveryCancelledException,
                    "in-flight sender receives an explicit cancellation result");
            expect(EmailVerificationService.VerificationResult.NOT_FOUND,
                    service.verifyAndConsume(
                            EmailVerificationService.Purpose.BIND,
                            "cancel-context",
                            "cancel@example.com",
                            sender.code),
                    "emailed code remains unusable after concurrent invalidation");
        } finally {
            sender.release.countDown();
            executor.shutdownNow();
        }
    }

    private static void testValidation() throws Exception {
        MutableClock clock = clock();
        CapturingSender sender = new CapturingSender();
        EmailVerificationService service = service(sender, clock, 41L);
        expectThrows(NullPointerException.class,
                () -> service.sendCode(null, "context", "a@example.com"),
                "purpose is required");
        expectThrows(EmailVerificationService.ValidationException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "   ",
                        "a@example.com"),
                "blank context is rejected");
        expectThrows(EmailVerificationService.ValidationException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "x".repeat(129),
                        "a@example.com"),
                "oversized context is rejected");
        expectThrows(UserEmailStore.ValidationException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "context",
                        "not-an-email"),
                "invalid recipient is rejected before sending");
        expect(0, sender.deliveries.size(), "invalid requests never invoke sender");
    }

    private static void testCloseErasesChallengesAndSender() throws Exception {
        MutableClock clock = clock();
        CloseTrackingSender sender = new CloseTrackingSender();
        EmailVerificationService service = new EmailVerificationService(
                sender, clock, new SequenceSecureRandom(59));
        service.sendCode(
                EmailVerificationService.Purpose.REGISTER,
                "close-context",
                "close@example.com");

        java.lang.reflect.Field challengesField =
                EmailVerificationService.class.getDeclaredField("challenges");
        challengesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> challenges =
                (Map<Object, Object>) challengesField.get(service);
        Object challenge = challenges.values().iterator().next();
        java.lang.reflect.Field saltField = challenge.getClass().getDeclaredField("salt");
        java.lang.reflect.Field hashField =
                challenge.getClass().getDeclaredField("codeHash");
        saltField.setAccessible(true);
        hashField.setAccessible(true);
        byte[] salt = (byte[]) saltField.get(challenge);
        byte[] hash = (byte[]) hashField.get(challenge);
        expect(false, allZero(salt), "active challenge salt exists before close");
        expect(false, allZero(hash), "active challenge hash exists before close");

        service.close();
        service.close();
        expect(true, allZero(salt), "close erases pending challenge salt in place");
        expect(true, allZero(hash), "close erases pending challenge hash in place");
        expect(0, challenges.size(), "close removes every pending challenge");
        expect(1, sender.closeCount, "closeable email sender is closed exactly once");
        expectThrows(IllegalStateException.class,
                () -> service.sendCode(
                        EmailVerificationService.Purpose.REGISTER,
                        "close-context",
                        "close@example.com"),
                "closed service rejects new deliveries");
        expectThrows(IllegalStateException.class,
                () -> service.verifyAndConsume(
                        EmailVerificationService.Purpose.REGISTER,
                        "close-context",
                        "close@example.com",
                        sender.code),
                "closed service rejects verification");
    }

    private static EmailVerificationService service(
            CapturingSender sender, MutableClock clock, long seed) {
        SecureRandom random = new SequenceSecureRandom((int) seed);
        return new EmailVerificationService(sender, clock, random);
    }

    private static MutableClock clock() {
        return new MutableClock(
                Instant.parse("2026-07-17T00:00:00Z"), ZoneOffset.UTC);
    }

    private static String wrong(String correct) {
        char replacement = correct.charAt(0) == '9' ? '8' : '9';
        return replacement + correct.substring(1);
    }

    private static boolean allZero(byte[] value) {
        for (byte item : value) {
            if (item != 0) {
                return false;
            }
        }
        return true;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> type, ThrowingAction action, String label) {
        tests++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return type.cast(error);
            }
            throw new AssertionError(
                    label + ": expected " + type.getSimpleName() + ", got " + error,
                    error);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    private record Delivery(String recipient, String code) {
    }

    private static final class CapturingSender
            implements EmailVerificationService.EmailSender {
        private final List<Delivery> deliveries = new ArrayList<>();
        private IOException failure;

        @Override
        public void sendVerificationCode(String recipient, String code)
                throws IOException {
            deliveries.add(new Delivery(recipient, code));
            if (failure != null) {
                throw failure;
            }
        }

        Delivery last() {
            return deliveries.getLast();
        }
    }

    private static final class BlockingSender
            implements EmailVerificationService.EmailSender {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile String code;

        @Override
        public void sendVerificationCode(String recipient, String code)
                throws IOException {
            this.code = code;
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("test delivery interrupted", error);
            }
        }
    }

    private static final class CountingSender
            implements EmailVerificationService.EmailSender {
        private int count;

        @Override
        public void sendVerificationCode(String recipient, String code) {
            count++;
        }
    }

    private static final class MultiBlockingSender
            implements EmailVerificationService.EmailSender {
        private final CountDownLatch entered;
        private final CountDownLatch release = new CountDownLatch(1);
        private int deliveryCount;

        MultiBlockingSender(int expectedConcurrent) {
            entered = new CountDownLatch(expectedConcurrent);
        }

        @Override
        public void sendVerificationCode(String recipient, String code)
                throws IOException {
            synchronized (this) {
                deliveryCount++;
            }
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("test delivery interrupted", error);
            }
        }
    }

    private static final class CloseTrackingSender
            implements EmailVerificationService.EmailSender, AutoCloseable {
        private String code;
        private int closeCount;

        @Override
        public void sendVerificationCode(String recipient, String code) {
            this.code = code;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class SequenceSecureRandom extends SecureRandom {
        private static final long serialVersionUID = 1L;
        private int value;

        SequenceSecureRandom(int seed) {
            value = seed;
        }

        @Override
        public int nextInt(int bound) {
            int result = Math.floorMod(value, bound);
            value += 104_729;
            return result;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (value + index * 31);
            }
            value += bytes.length;
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
