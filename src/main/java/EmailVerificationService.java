import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.text.Normalizer;

/** Thread-safe, in-memory lifecycle for one-time email verification codes. */
public final class EmailVerificationService implements AutoCloseable {
    public static final int CODE_DIGITS = 6;
    public static final Duration CODE_TTL = Duration.ofMinutes(10);
    public static final Duration RESEND_COOLDOWN = Duration.ofMinutes(2);
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration SEND_RATE_WINDOW = Duration.ofHours(1);
    public static final int MAX_SENDS_PER_EMAIL_WINDOW = 5;
    public static final int MAX_GLOBAL_SENDS_PER_WINDOW = 100;
    public static final int MAX_CONCURRENT_DELIVERIES = 4;

    private static final int SALT_BYTES = 32;
    private static final int MAX_CONTEXT_CODE_POINTS = 128;
    private static final int MAX_ACTIVE_CHALLENGES = 10_000;

    private final EmailSender sender;
    private final Clock clock;
    private final SecureRandom random;
    private final Object monitor = new Object();
    private final Map<ChallengeKey, Challenge> challenges = new HashMap<>();
    private final Map<String, Instant> resendAvailableByEmail = new HashMap<>();
    private final Map<ChallengeKey, Long> inFlight = new HashMap<>();
    private final Map<String, Long> inFlightByEmail = new HashMap<>();
    private final Map<String, ArrayDeque<Instant>> sendsByEmail = new HashMap<>();
    private final ArrayDeque<Instant> globalSends = new ArrayDeque<>();
    private long deliverySequence;
    private boolean closed;

    public EmailVerificationService(EmailSender sender) {
        this(sender, Clock.systemUTC(), new SecureRandom());
    }

    /** Constructor with deterministic collaborators for dependency-free tests. */
    public EmailVerificationService(
            EmailSender sender, Clock clock, SecureRandom random) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    /**
     * Generates and delivers a new code. Neither this result nor service state
     * exposes the plaintext code; only the injected sender sees it transiently.
     */
    public SendResult sendCode(Purpose purpose, String context, String email)
            throws IOException {
        Purpose safePurpose = Objects.requireNonNull(purpose, "purpose must not be null");
        String safeContext = normalizeContext(context);
        String normalizedEmail = UserEmailStore.normalizeEmail(email);
        ChallengeKey key = new ChallengeKey(safePurpose, safeContext, normalizedEmail);

        String code;
        byte[] salt = new byte[SALT_BYTES];
        byte[] codeHash;
        long deliveryId;
        Instant requestedAt = clock.instant();
        synchronized (monitor) {
            requireOpenLocked();
            cleanupLocked(requestedAt);
            Long currentDelivery = inFlightByEmail.get(normalizedEmail);
            if (currentDelivery != null) {
                throw new RateLimitException(
                        "A verification email is already being delivered", 1);
            }
            Instant availableAt = resendAvailableByEmail.get(normalizedEmail);
            if (availableAt != null && requestedAt.isBefore(availableAt)) {
                throw rateLimitedUntil(
                        "Please wait before requesting another verification code",
                        requestedAt,
                        availableAt);
            }

            ArrayDeque<Instant> sendHistory = sendsByEmail.get(normalizedEmail);
            if (sendHistory == null) {
                sendHistory = new ArrayDeque<>();
            }
            pruneHistory(sendHistory, requestedAt);
            if (sendHistory.size() >= MAX_SENDS_PER_EMAIL_WINDOW) {
                Instant retryAt = sendHistory.peekFirst().plus(SEND_RATE_WINDOW);
                throw rateLimitedUntil(
                        "Too many verification emails were requested",
                        requestedAt,
                        retryAt);
            }
            pruneHistory(globalSends, requestedAt);
            if (globalSends.size() >= MAX_GLOBAL_SENDS_PER_WINDOW) {
                Instant retryAt = globalSends.peekFirst().plus(SEND_RATE_WINDOW);
                throw rateLimitedUntil(
                        "The verification email service is temporarily busy",
                        requestedAt,
                        retryAt);
            }
            if (challenges.size() + inFlight.size() >= MAX_ACTIVE_CHALLENGES
                    && !challenges.containsKey(key)) {
                throw new RateLimitException(
                        "Too many verification requests are active", 60);
            }
            if (inFlight.size() >= MAX_CONCURRENT_DELIVERIES) {
                throw new RateLimitException(
                        "The verification email service is currently busy", 1);
            }

            int number = random.nextInt(1_000_000);
            code = String.format(Locale.ROOT, "%06d", number);
            random.nextBytes(salt);
            codeHash = hashCode(key, salt, code);
            deliveryId = ++deliverySequence;
            inFlight.put(key, deliveryId);
            inFlightByEmail.put(normalizedEmail, deliveryId);
            resendAvailableByEmail.put(
                    normalizedEmail, requestedAt.plus(RESEND_COOLDOWN));
            sendsByEmail.put(normalizedEmail, sendHistory);
            sendHistory.addLast(requestedAt);
            globalSends.addLast(requestedAt);
        }

        try {
            sender.sendVerificationCode(normalizedEmail, code);
        } catch (IOException | RuntimeException error) {
            synchronized (monitor) {
                inFlight.remove(key, deliveryId);
                inFlightByEmail.remove(normalizedEmail, deliveryId);
            }
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(codeHash, (byte) 0);
            throw error;
        }

        Instant deliveredAt = clock.instant();
        Instant expiresAt = deliveredAt.plus(CODE_TTL);
        Instant resendAt = deliveredAt.plus(RESEND_COOLDOWN);
        synchronized (monitor) {
            if (!inFlight.remove(key, deliveryId)) {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(codeHash, (byte) 0);
                throw new DeliveryCancelledException();
            }
            inFlightByEmail.remove(normalizedEmail, deliveryId);
            resendAvailableByEmail.put(normalizedEmail, resendAt);
            Challenge previous = challenges.put(
                    key, new Challenge(salt, codeHash, expiresAt, 0));
            if (previous != null) {
                previous.erase();
            }
        }
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(codeHash, (byte) 0);
        return new SendResult(
                normalizedEmail,
                expiresAt,
                resendAt);
    }

    /** Checks the code exactly once; a successful code is consumed atomically. */
    public VerificationResult verifyAndConsume(
            Purpose purpose, String context, String email, String suppliedCode) {
        Purpose safePurpose = Objects.requireNonNull(purpose, "purpose must not be null");
        String safeContext = normalizeContext(context);
        String normalizedEmail = UserEmailStore.normalizeEmail(email);
        ChallengeKey key = new ChallengeKey(safePurpose, safeContext, normalizedEmail);
        Instant now = clock.instant();
        synchronized (monitor) {
            requireOpenLocked();
            Challenge challenge = challenges.get(key);
            if (challenge == null) {
                cleanupLocked(now);
                return VerificationResult.NOT_FOUND;
            }
            if (!now.isBefore(challenge.expiresAt())) {
                challenges.remove(key);
                challenge.erase();
                cleanupLocked(now);
                return VerificationResult.EXPIRED;
            }

            boolean validFormat = isSixDigitCode(suppliedCode);
            String candidateCode = validFormat ? suppliedCode : "000000";
            byte[] candidateHash = hashCode(key, challenge.salt, candidateCode);
            boolean matches;
            try {
                matches = validFormat
                        && MessageDigest.isEqual(challenge.codeHash, candidateHash);
            } finally {
                Arrays.fill(candidateHash, (byte) 0);
            }
            if (matches) {
                challenges.remove(key);
                challenge.erase();
                cleanupLocked(now);
                return VerificationResult.VERIFIED;
            }

            int failedAttempts = challenge.failedAttempts() + 1;
            if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
                challenges.remove(key);
                challenge.erase();
                cleanupLocked(now);
                return VerificationResult.ATTEMPTS_EXHAUSTED;
            }
            challenges.put(
                    key,
                    new Challenge(
                            challenge.salt,
                            challenge.codeHash,
                            challenge.expiresAt(),
                            failedAttempts));
            challenge.erase();
            cleanupLocked(now);
            return VerificationResult.INVALID_CODE;
        }
    }

    /** Invalidates a pending code, for example when its account flow is cancelled. */
    public void invalidate(Purpose purpose, String context, String email) {
        ChallengeKey key = new ChallengeKey(
                Objects.requireNonNull(purpose, "purpose must not be null"),
                normalizeContext(context),
                UserEmailStore.normalizeEmail(email));
        synchronized (monitor) {
            requireOpenLocked();
            Challenge removed = challenges.remove(key);
            if (removed != null) {
                removed.erase();
            }
            Long deliveryId = inFlight.remove(key);
            if (deliveryId != null) {
                inFlightByEmail.remove(key.email(), deliveryId);
            }
        }
    }

    /** Erases all pending hashes/salts and closes a closeable mail sender once. */
    @Override
    public void close() {
        synchronized (monitor) {
            if (closed) {
                return;
            }
            closed = true;
            for (Challenge challenge : challenges.values()) {
                challenge.erase();
            }
            challenges.clear();
            resendAvailableByEmail.clear();
            inFlight.clear();
            inFlightByEmail.clear();
            sendsByEmail.clear();
            globalSends.clear();
        }
        if (sender instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (RuntimeException error) {
                throw error;
            } catch (Exception error) {
                throw new EmailSenderCloseException(error);
            }
        }
    }

    private void requireOpenLocked() {
        if (closed) {
            throw new IllegalStateException("Email verification service is closed");
        }
    }

    private void cleanupLocked(Instant now) {
        Iterator<Map.Entry<ChallengeKey, Challenge>> challengesIterator =
                challenges.entrySet().iterator();
        while (challengesIterator.hasNext()) {
            Map.Entry<ChallengeKey, Challenge> entry = challengesIterator.next();
            if (!now.isBefore(entry.getValue().expiresAt())) {
                entry.getValue().erase();
                challengesIterator.remove();
            }
        }
        resendAvailableByEmail.entrySet().removeIf(
                entry -> !now.isBefore(entry.getValue())
                        && !inFlightByEmail.containsKey(entry.getKey()));
        Iterator<Map.Entry<String, ArrayDeque<Instant>>> historyIterator =
                sendsByEmail.entrySet().iterator();
        while (historyIterator.hasNext()) {
            ArrayDeque<Instant> history = historyIterator.next().getValue();
            pruneHistory(history, now);
            if (history.isEmpty()) {
                historyIterator.remove();
            }
        }
        pruneHistory(globalSends, now);
    }

    private static void pruneHistory(ArrayDeque<Instant> history, Instant now) {
        Instant threshold = now.minus(SEND_RATE_WINDOW);
        while (!history.isEmpty() && !history.peekFirst().isAfter(threshold)) {
            history.removeFirst();
        }
    }

    private static RateLimitException rateLimitedUntil(
            String message, Instant now, Instant availableAt) {
        long milliseconds = Math.max(1, Duration.between(now, availableAt).toMillis());
        long seconds = Math.max(1, (milliseconds + 999) / 1_000);
        return new RateLimitException(message, seconds);
    }

    private static String normalizeContext(String supplied) {
        if (supplied == null) {
            throw new ValidationException("verification context is required");
        }
        String normalized = Normalizer.normalize(supplied, Normalizer.Form.NFKC).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_CONTEXT_CODE_POINTS) {
            throw new ValidationException(
                    "verification context must be between 1 and 128 characters");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new ValidationException(
                        "verification context must not contain control characters");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static boolean isSixDigitCode(String code) {
        if (code == null || code.length() != CODE_DIGITS) {
            return false;
        }
        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }

    private static byte[] hashCode(ChallengeKey key, byte[] salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update((byte) 0);
            digest.update(key.purpose().name().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            digest.update(key.context().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(key.email().getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            byte[] codeBytes = code.getBytes(StandardCharsets.US_ASCII);
            try {
                return digest.digest(codeBytes);
            } finally {
                Arrays.fill(codeBytes, (byte) 0);
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @FunctionalInterface
    public interface EmailSender {
        void sendVerificationCode(String recipient, String sixDigitCode)
                throws IOException;
    }

    public enum Purpose {
        REGISTER,
        BIND,
        REBIND
    }

    public enum VerificationResult {
        VERIFIED,
        INVALID_CODE,
        EXPIRED,
        ATTEMPTS_EXHAUSTED,
        NOT_FOUND
    }

    public record SendResult(
            String email, Instant expiresAt, Instant resendAvailableAt) {
        public SendResult {
            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(
                    resendAvailableAt, "resendAvailableAt must not be null");
        }
    }

    public static final class RateLimitException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private final long retryAfterSeconds;

        RateLimitException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public static final class DeliveryCancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        DeliveryCancelledException() {
            super("Verification email delivery was cancelled");
        }
    }

    public static final class EmailSenderCloseException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        EmailSenderCloseException(Exception cause) {
            super("Unable to close email sender", cause);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ValidationException(String message) {
            super(message);
        }
    }

    private record ChallengeKey(Purpose purpose, String context, String email) {
    }

    private static final class Challenge {
        private final byte[] salt;
        private final byte[] codeHash;
        private final Instant expiresAt;
        private final int failedAttempts;

        Challenge(byte[] salt, byte[] codeHash, Instant expiresAt, int failedAttempts) {
            this.salt = salt.clone();
            this.codeHash = codeHash.clone();
            this.expiresAt = expiresAt;
            this.failedAttempts = failedAttempts;
        }

        Instant expiresAt() {
            return expiresAt;
        }

        int failedAttempts() {
            return failedAttempts;
        }

        void erase() {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(codeHash, (byte) 0);
        }
    }
}
