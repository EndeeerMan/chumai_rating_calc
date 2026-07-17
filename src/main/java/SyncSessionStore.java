import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, in-memory authorization for the optional WeChat sync helper.
 *
 * <p>The raw helper token is returned once when a session is created. Only its
 * SHA-256 digest remains in memory, and expired sessions are discarded. This
 * store deliberately has no persistence: OAuth state and helper credentials
 * must not survive a server restart.</p>
 */
public final class SyncSessionStore {
    static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    static final int MAX_ACTIVE_SESSIONS = 1_000;
    static final int MAX_DETAIL_PROGRESS_TOTAL = 100;
    static final Set<String> DETAIL_FAILURE_REASONS = Set.of(
            "missing-source-id",
            "invalid-source-id",
            "request-timeout",
            "request-failed",
            "request-error",
            "transport-failure",
            "invalid-response",
            "invalid-detail-template",
            "detail-validation-failure",
            "response-too-large",
            "non-retryable-status",
            "rate-limited",
            "upstream-error",
            "row-timeout",
            "unexpected-row-failure",
            "unavailable");

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER =
            Base64.getUrlEncoder().withoutPadding();

    private final Clock clock;
    private final Duration ttl;
    private final ConcurrentHashMap<String, StoredSession> sessions =
            new ConcurrentHashMap<>();

    public SyncSessionStore() {
        this(Clock.systemUTC(), DEFAULT_TTL);
    }

    SyncSessionStore(Clock clock, Duration ttl) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("ttl must be between zero and one hour");
        }
    }

    /** Creates a one-time helper session for a signed-in local user. */
    public CreatedSession create(String userId, String game) {
        String safeUserId = requireUuid(userId, "userId");
        String safeGame = normalizeGame(game);
        Instant now = clock.instant();
        discardExpired(now);
        if (sessions.size() >= MAX_ACTIVE_SESSIONS) {
            throw new IllegalStateException("Too many active sync sessions");
        }

        byte[] tokenBytes = new byte[32];
        RANDOM.nextBytes(tokenBytes);
        String helperToken = TOKEN_ENCODER.encodeToString(tokenBytes);
        String id = UUID.randomUUID().toString();
        StoredSession stored = new StoredSession(
                id,
                safeUserId,
                safeGame,
                digest(helperToken),
                now,
                now.plus(ttl));
        sessions.put(id, stored);
        return new CreatedSession(stored.snapshot(now), helperToken);
    }

    /** Looks up a session, but only for its owning authenticated user. */
    public Optional<Session> findForUser(String userId, String sessionId) {
        String safeUserId = requireUuid(userId, "userId");
        String safeSessionId = requireUuid(sessionId, "sessionId");
        Instant now = clock.instant();
        StoredSession stored = sessions.get(safeSessionId);
        if (stored == null || !stored.userId.equals(safeUserId)) {
            return Optional.empty();
        }
        return Optional.of(stored.snapshot(now));
    }

    /** Returns recent sessions owned by a user, newest first. */
    public List<Session> listForUser(String userId) {
        String safeUserId = requireUuid(userId, "userId");
        Instant now = clock.instant();
        discardExpired(now);
        List<Session> result = new ArrayList<>();
        for (StoredSession stored : sessions.values()) {
            if (stored.userId.equals(safeUserId)) {
                result.add(stored.snapshot(now));
            }
        }
        result.sort(Comparator.comparing(Session::createdAt).reversed());
        return List.copyOf(result);
    }

    /** Revokes every in-memory synchronization session owned by one user. */
    public int deleteUserSessions(String userId) {
        String safeUserId = requireUuid(userId, "userId");
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> entry.getValue().userId.equals(safeUserId));
        return Math.max(0, before - sessions.size());
    }

    /**
     * Atomically consumes a helper token and moves the session to importing.
     * A token cannot be reused after import starts.
     */
    public ImportAuthorization beginImport(
            String sessionId,
            String helperToken,
            String expectedGame) {
        String safeSessionId = requireUuid(sessionId, "sessionId");
        String safeToken = requireToken(helperToken);
        String safeGame = normalizeGame(expectedGame);
        StoredSession stored = sessions.get(safeSessionId);
        if (stored == null) {
            throw new InvalidSessionException("Sync session was not found");
        }
        synchronized (stored) {
            Instant now = clock.instant();
            rejectIfExpired(stored, now);
            if (!stored.game.equals(safeGame)) {
                throw new InvalidSessionException("Sync game does not match the session");
            }
            if (!MessageDigest.isEqual(stored.tokenDigest, digest(safeToken))) {
                throw new InvalidSessionException("Invalid sync helper token");
            }
            if (stored.status != Status.FETCHING) {
                throw invalidTransition(stored.status, Status.IMPORTING);
            }
            stored.status = Status.IMPORTING;
            stored.message = "正在导入最佳成绩与最近游玩记录";
            stored.tokenDigest = new byte[32];
            return new ImportAuthorization(stored.id, stored.userId, stored.game);
        }
    }

    /**
     * Accepts a token-authenticated progress or failure event from the local
     * helper before the durable import starts.
     */
    public Session reportWaitingAuth(String sessionId, String helperToken) {
        String safeSessionId = requireUuid(sessionId, "sessionId");
        StoredSession stored = sessions.get(safeSessionId);
        if (stored == null) {
            throw new InvalidSessionException("Sync session was not found");
        }
        // The first helper request intentionally has no game field. The
        // authenticated response tells the helper which adapter to use.
        return reportHelperEvent(
                safeSessionId,
                helperToken,
                stored.game,
                HelperEvent.WAITING_AUTH,
                null);
    }

    /**
     * Accepts a game-bound progress or failure event after the first helper
     * handshake. The supplied game must exactly match the session.
     */
    public Session reportHelperEvent(
            String sessionId,
            String helperToken,
            String expectedGame,
            HelperEvent event,
            String message) {
        return reportHelperEvent(
                sessionId,
                helperToken,
                expectedGame,
                event,
                message,
                null);
    }

    /**
     * Accepts an optional detail-fetch progress snapshot on a fetching event.
     * Older helpers may continue to omit the snapshot.
     */
    public Session reportHelperEvent(
            String sessionId,
            String helperToken,
            String expectedGame,
            HelperEvent event,
            String message,
            Progress progress) {
        String safeSessionId = requireUuid(sessionId, "sessionId");
        String safeToken = requireToken(helperToken);
        String safeGame = normalizeGame(expectedGame);
        Objects.requireNonNull(event, "event must not be null");
        if (progress != null && event != HelperEvent.FETCHING) {
            throw new IllegalArgumentException(
                    "Progress is only accepted for a fetching event");
        }
        StoredSession stored = sessions.get(safeSessionId);
        if (stored == null) {
            throw new InvalidSessionException("Sync session was not found");
        }
        synchronized (stored) {
            Instant now = clock.instant();
            rejectIfExpired(stored, now);
            if (!stored.game.equals(safeGame)) {
                throw new InvalidSessionException("Sync game does not match the session");
            }
            if (!MessageDigest.isEqual(stored.tokenDigest, digest(safeToken))) {
                throw new InvalidSessionException("Invalid sync helper token");
            }
            if (!isBeforeImport(stored.status)) {
                throw new InvalidSessionException("Sync helper token has already been used");
            }

            if (event == HelperEvent.FAILED) {
                stored.status = Status.FAILED;
                stored.message = sanitizeMessage(message);
                stored.completedAt = now;
                stored.tokenDigest = new byte[32];
                return stored.snapshot(now);
            }

            Status target = event.targetStatus();
            if (stored.status == target) {
                // Progress events are idempotent so a helper may safely retry
                // after an HTTP response is lost.
                stored.updateProgress(progress);
                return stored.snapshot(now);
            }
            if (stored.status != event.requiredPreviousStatus()) {
                throw invalidTransition(stored.status, target);
            }
            stored.status = target;
            stored.message = event.message(stored.game);
            stored.updateProgress(progress);
            return stored.snapshot(now);
        }
    }

    /** Marks a previously authorized helper import as successful. */
    public Session completeImport(
            ImportAuthorization authorization,
            int addedCharts,
            int addedRecords,
            int enrichedRecords,
            int ignoredRecords) {
        Objects.requireNonNull(authorization, "authorization must not be null");
        if (addedCharts < 0
                || addedRecords < 0
                || enrichedRecords < 0
                || ignoredRecords < 0) {
            throw new IllegalArgumentException("Import counters must not be negative");
        }
        StoredSession stored = requireImporting(authorization);
        synchronized (stored) {
            requireSameAuthorization(stored, authorization);
            if (stored.status != Status.IMPORTING) {
                throw new InvalidSessionException("Sync session is not importing");
            }
            stored.status = Status.COMPLETED;
            stored.message = "同步完成";
            stored.completedAt = clock.instant();
            stored.addedCharts = addedCharts;
            stored.addedRecords = addedRecords;
            stored.enrichedRecords = enrichedRecords;
            stored.ignoredRecords = ignoredRecords;
            return stored.snapshot(clock.instant());
        }
    }

    /** Backward-compatible completion for imports without detail enrichment. */
    public Session completeImport(
            ImportAuthorization authorization,
            int addedCharts,
            int addedRecords,
            int ignoredRecords) {
        return completeImport(
                authorization, addedCharts, addedRecords, 0, ignoredRecords);
    }

    /** Marks an authorized import as failed without retaining a secret. */
    public Session failImport(ImportAuthorization authorization, String message) {
        Objects.requireNonNull(authorization, "authorization must not be null");
        StoredSession stored = requireImporting(authorization);
        synchronized (stored) {
            requireSameAuthorization(stored, authorization);
            if (stored.status != Status.IMPORTING) {
                return stored.snapshot(clock.instant());
            }
            stored.status = Status.FAILED;
            stored.message = sanitizeMessage(message);
            stored.completedAt = clock.instant();
            return stored.snapshot(clock.instant());
        }
    }

    private StoredSession requireImporting(ImportAuthorization authorization) {
        StoredSession stored = sessions.get(authorization.sessionId());
        if (stored == null) {
            throw new InvalidSessionException("Sync session was not found");
        }
        return stored;
    }

    private static void requireSameAuthorization(
            StoredSession stored,
            ImportAuthorization authorization) {
        if (!stored.userId.equals(authorization.userId())
                || !stored.game.equals(authorization.game())) {
            throw new InvalidSessionException("Sync authorization does not match the session");
        }
    }

    private static boolean isBeforeImport(Status status) {
        return status == Status.WAITING
                || status == Status.WAITING_AUTH
                || status == Status.CALLBACK_RECEIVED
                || status == Status.FETCHING;
    }

    private static InvalidSessionException invalidTransition(
            Status current,
            Status target) {
        return new InvalidSessionException(
                "Invalid sync helper state transition: "
                        + current.name().toLowerCase(Locale.ROOT)
                        + " -> "
                        + target.name().toLowerCase(Locale.ROOT));
    }

    private static void expire(StoredSession stored) {
        stored.status = Status.EXPIRED;
        stored.message = "同步链接已过期，请重新创建";
        stored.tokenDigest = new byte[32];
    }

    private static void rejectIfExpired(StoredSession stored, Instant now) {
        if (stored.status == Status.EXPIRED
                || (!now.isBefore(stored.expiresAt)
                        && isBeforeImport(stored.status))) {
            expire(stored);
            throw new InvalidSessionException("Sync session has expired");
        }
    }

    private void discardExpired(Instant now) {
        sessions.entrySet().removeIf(entry -> {
            StoredSession stored = entry.getValue();
            synchronized (stored) {
                return !now.isBefore(stored.expiresAt)
                        && stored.status != Status.IMPORTING;
            }
        });
    }

    private static String normalizeGame(String game) {
        Objects.requireNonNull(game, "game must not be null");
        String normalized = game.trim().toLowerCase(Locale.ROOT);
        if (!"maimai".equals(normalized) && !"chunithm".equals(normalized)) {
            throw new IllegalArgumentException(
                    "game must be maimai or chunithm");
        }
        return normalized;
    }

    private static String requireUuid(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException("Invalid " + field);
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid " + field, error);
        }
    }

    private static String requireToken(String token) {
        Objects.requireNonNull(token, "helperToken must not be null");
        String normalized = token.trim();
        if (normalized.length() < 40 || normalized.length() > 100) {
            throw new InvalidSessionException("Invalid sync helper token");
        }
        return normalized;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String sanitizeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "同步失败，请检查辅助程序后重试";
        }
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 200
                ? singleLine
                : singleLine.substring(0, 200);
    }

    public enum Status {
        WAITING,
        WAITING_AUTH,
        CALLBACK_RECEIVED,
        FETCHING,
        IMPORTING,
        COMPLETED,
        FAILED,
        EXPIRED
    }

    public enum HelperEvent {
        WAITING_AUTH(
                Status.WAITING,
                Status.WAITING_AUTH,
                "授权链接已生成，等待微信确认"),
        CALLBACK_RECEIVED(
                Status.WAITING_AUTH,
                Status.CALLBACK_RECEIVED,
                "已收到微信授权回调"),
        FETCHING(
                Status.CALLBACK_RECEIVED,
                Status.FETCHING,
                "正在读取最佳成绩与最近游玩记录"),
        FAILED(null, Status.FAILED, null);

        private final Status requiredPreviousStatus;
        private final Status targetStatus;
        private final String message;

        HelperEvent(
                Status requiredPreviousStatus,
                Status targetStatus,
                String message) {
            this.requiredPreviousStatus = requiredPreviousStatus;
            this.targetStatus = targetStatus;
            this.message = message;
        }

        private Status requiredPreviousStatus() {
            return requiredPreviousStatus;
        }

        private Status targetStatus() {
            return targetStatus;
        }

        private String message(String game) {
            if (this != FETCHING) {
                return message;
            }
            return "chunithm".equals(game)
                    ? "正在读取中二节奏最佳成绩与最近游玩记录"
                    : "正在读取舞萌最佳成绩与最近游玩记录";
        }
    }

    public record Session(
            String id,
            String userId,
            String game,
            Status status,
            String message,
            Instant createdAt,
            Instant expiresAt,
            Instant completedAt,
            int addedCharts,
            int addedRecords,
            int enrichedRecords,
            int ignoredRecords,
            Progress progress) {
    }

    /** A bounded, monotonic snapshot of official play-detail fetching. */
    public record Progress(
            String stage,
            int completed,
            int total,
            int succeeded,
            int skipped,
            Map<String, Integer> failureReasons) {
        public Progress(String stage, int completed, int total, int succeeded) {
            this(
                    stage,
                    completed,
                    total,
                    succeeded,
                    completed - succeeded,
                    defaultFailureReasons(completed - succeeded));
        }

        public Progress {
            Objects.requireNonNull(stage, "progress stage must not be null");
            if (!"play_details".equals(stage)) {
                throw new IllegalArgumentException(
                        "progress stage must be play_details");
            }
            if (total < 1 || total > MAX_DETAIL_PROGRESS_TOTAL) {
                throw new IllegalArgumentException(
                        "progress total is out of range");
            }
            if (completed < 0 || completed > total) {
                throw new IllegalArgumentException(
                        "progress completed must be between zero and total");
            }
            if (succeeded < 0 || succeeded > completed) {
                throw new IllegalArgumentException(
                        "progress succeeded must be between zero and completed");
            }
            if (skipped < 0 || skipped != completed - succeeded) {
                throw new IllegalArgumentException(
                        "progress skipped must equal completed minus succeeded");
            }
            Objects.requireNonNull(
                    failureReasons, "progress failure reasons must not be null");
            if (failureReasons.size() > DETAIL_FAILURE_REASONS.size()) {
                throw new IllegalArgumentException(
                        "progress has too many failure reasons");
            }
            Map<String, Integer> safeReasons = new LinkedHashMap<>();
            int failureTotal = 0;
            for (Map.Entry<String, Integer> entry : failureReasons.entrySet()) {
                String reason = entry.getKey();
                Integer count = entry.getValue();
                if (reason == null || !DETAIL_FAILURE_REASONS.contains(reason)) {
                    throw new IllegalArgumentException(
                            "progress failure reason is not allowed");
                }
                if (count == null || count < 1
                        || count > MAX_DETAIL_PROGRESS_TOTAL) {
                    throw new IllegalArgumentException(
                            "progress failure count is invalid");
                }
                failureTotal += count;
                safeReasons.put(reason, count);
            }
            if (failureTotal != skipped) {
                throw new IllegalArgumentException(
                        "progress failure counts must equal skipped");
            }
            failureReasons = Map.copyOf(safeReasons);
        }

        private static Map<String, Integer> defaultFailureReasons(int skipped) {
            return skipped > 0 ? Map.of("unavailable", skipped) : Map.of();
        }
    }

    public record CreatedSession(Session session, String helperToken) {
        public CreatedSession {
            Objects.requireNonNull(session, "session must not be null");
            Objects.requireNonNull(helperToken, "helperToken must not be null");
        }
    }

    public record ImportAuthorization(String sessionId, String userId, String game) {
        public ImportAuthorization {
            Objects.requireNonNull(sessionId, "sessionId must not be null");
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(game, "game must not be null");
        }
    }

    public static final class InvalidSessionException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        InvalidSessionException(String message) {
            super(message);
        }
    }

    private static final class StoredSession {
        private final String id;
        private final String userId;
        private final String game;
        private byte[] tokenDigest;
        private final Instant createdAt;
        private final Instant expiresAt;
        private Status status = Status.WAITING;
        private String message = "等待在微信中打开授权链接";
        private Instant completedAt;
        private int addedCharts;
        private int addedRecords;
        private int enrichedRecords;
        private int ignoredRecords;
        private Progress progress;

        private StoredSession(
                String id,
                String userId,
                String game,
                byte[] tokenDigest,
                Instant createdAt,
                Instant expiresAt) {
            this.id = id;
            this.userId = userId;
            this.game = game;
            this.tokenDigest = tokenDigest.clone();
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        private synchronized Session snapshot(Instant now) {
            Status visibleStatus = status;
            String visibleMessage = message;
            if (!now.isBefore(expiresAt) && isBeforeImport(status)) {
                expire(this);
                visibleStatus = status;
                visibleMessage = message;
            }
            return new Session(
                    id,
                    userId,
                    game,
                    visibleStatus,
                    visibleMessage,
                    createdAt,
                    expiresAt,
                    completedAt,
                    addedCharts,
                    addedRecords,
                    enrichedRecords,
                    ignoredRecords,
                    progress);
        }

        private void updateProgress(Progress incoming) {
            if (incoming == null) {
                return;
            }
            if (progress != null) {
                if (!progress.stage().equals(incoming.stage())
                        || progress.total() != incoming.total()) {
                    throw new InvalidSessionException(
                            "Sync detail progress target cannot change");
                }
                if (incoming.completed() < progress.completed()
                        || incoming.succeeded() < progress.succeeded()
                        || incoming.skipped() < progress.skipped()) {
                    throw new InvalidSessionException(
                            "Sync detail progress cannot move backward");
                }
                for (Map.Entry<String, Integer> entry
                        : progress.failureReasons().entrySet()) {
                    if (incoming.failureReasons().getOrDefault(entry.getKey(), 0)
                            < entry.getValue()) {
                        throw new InvalidSessionException(
                                "Sync detail failure counts cannot move backward");
                    }
                }
            }
            progress = incoming;
        }
    }
}
