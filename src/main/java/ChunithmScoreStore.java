import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Per-user durable storage for CHUNITHM chart scores.
 *
 * <p>The supplied root is expected to be {@code user_data/chunithm_charts}.
 * Each canonical user UUID owns one JSON file. Writes use optimistic revision
 * checks, a per-user lock and atomic replacement, so unrelated users never
 * block one another and stale browser tabs cannot overwrite newer data.</p>
 */
public final class ChunithmScoreStore {
    public static final int MAX_CHARTS = 3_000;
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;

    private static final int MAX_SONG_ID_LENGTH = 100;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_DIFFICULTY_LENGTH = 50;
    private static final int MAX_VERSION_LENGTH = 100;
    private static final Set<String> STORED_DOCUMENT_KEYS =
            Set.of("revision", "charts");
    private static final Set<String> UPDATE_DOCUMENT_KEYS =
            Set.of("expectedUserId", "revision", "charts");
    private static final Set<String> CHART_KEYS = Set.of(
            "songId", "title", "difficulty", "constant", "score", "version");

    private final Path root;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks =
            new ConcurrentHashMap<>();

    public ChunithmScoreStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (!Files.isDirectory(this.root)) {
            throw new IOException("CHUNITHM score root is not a directory");
        }
    }

    public Path root() {
        return root;
    }

    /** A missing user file is an empty revision-0 snapshot. */
    public Snapshot loadSnapshot(
            String userId, Collection<String> latestVersions) throws IOException {
        String safeUserId = canonicalUserId(userId);
        Collection<String> safeLatest = copyLatestVersions(latestVersions);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.readLock().lock();
        try {
            return readSnapshotLocked(safeUserId, safeLatest);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Convenience overload when only structural chart validation is needed. */
    public Snapshot loadSnapshot(String userId) throws IOException {
        return loadSnapshot(userId, List.of());
    }

    /** Removes every stored CHUNITHM score for one canonical user id. */
    public void deleteUserData(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Path file = scoreFile(safeUserId);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Stored CHUNITHM score path is not a regular file");
            }
            Files.delete(file);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Applies a parsed PUT update if owner and revision still match.
     *
     * @throws ConflictException if the request belongs to another user or is stale
     */
    public Snapshot save(
            String authenticatedUserId,
            Update update,
            Collection<String> latestVersions) throws IOException {
        Objects.requireNonNull(update, "update must not be null");
        return save(
                authenticatedUserId,
                update.expectedUserId(),
                update.revision(),
                update.charts(),
                latestVersions);
    }

    /**
     * Atomically replaces one user's complete CHUNITHM score list using CAS.
     */
    public Snapshot save(
            String authenticatedUserId,
            String expectedUserId,
            long expectedRevision,
            List<ChunithmChartInput> charts,
            Collection<String> latestVersions) throws IOException {
        String safeUserId = canonicalUserId(authenticatedUserId);
        Objects.requireNonNull(expectedUserId, "expectedUserId must not be null");
        if (!safeUserId.equals(expectedUserId)) {
            throw new ConflictException(
                    "Authenticated user does not match expectedUserId");
        }
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        Collection<String> safeLatest = copyLatestVersions(latestVersions);
        List<ChunithmChartInput> safeCharts = validateAndCopyCharts(charts, safeLatest);

        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Snapshot current = readSnapshotLocked(safeUserId, safeLatest);
            if (current.revision() != expectedRevision) {
                throw new ConflictException(
                        "CHUNITHM score data has changed; reload before saving");
            }
            if (current.revision() == Long.MAX_VALUE) {
                throw new IOException("CHUNITHM score revision limit has been reached");
            }

            Snapshot updated = new Snapshot(
                    safeUserId, current.revision() + 1, safeCharts);
            atomicWrite(scoreFile(safeUserId), storedDocument(updated));
            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Strictly parses {@code {expectedUserId, revision, charts}}. */
    public static Update parseUpdateDocument(
            Object value, Collection<String> latestVersions) {
        Map<String, Object> document = requireObject(value, "request body");
        requireExactKeys(document, UPDATE_DOCUMENT_KEYS, "request body");
        String expectedUserId = canonicalUserId(requireString(
                document.get("expectedUserId"), "expectedUserId", 0, 100));
        long revision = requireNonNegativeLong(document.get("revision"), "revision");
        List<ChunithmChartInput> charts = parseChartsValue(
                document.get("charts"), latestVersions);
        return new Update(expectedUserId, revision, charts);
    }

    /** Converts a snapshot to the exact public GET response shape. */
    public static Map<String, Object> snapshotToJsonValue(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("userId", snapshot.userId());
        value.put("revision", snapshot.revision());
        value.put("charts", chartsToJsonValues(snapshot.charts()));
        return value;
    }

    /** Serializes only the six persisted core fields. */
    public static List<Map<String, Object>> chartsToJsonValues(
            List<ChunithmChartInput> charts) {
        Objects.requireNonNull(charts, "charts must not be null");
        List<Map<String, Object>> values = new ArrayList<>(charts.size());
        for (ChunithmChartInput chart : charts) {
            Objects.requireNonNull(chart, "charts must not contain null");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", chart.songId());
            value.put("title", chart.title());
            value.put("difficulty", chart.difficulty());
            value.put("constant", BigDecimal.valueOf(chart.constant()));
            value.put("score", chart.score());
            value.put("version", chart.version());
            values.add(value);
        }
        return List.copyOf(values);
    }

    private Snapshot readSnapshotLocked(
            String userId, Collection<String> latestVersions) throws IOException {
        Path file = scoreFile(userId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(userId, 0, List.of());
        }
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Stored CHUNITHM score path is not a regular file");
        }

        byte[] bytes = readLimited(file);
        final Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Json.JsonException error) {
            throw new IOException("Stored CHUNITHM score data is invalid JSON", error);
        }

        try {
            Map<String, Object> document = requireObject(
                    parsed, "stored CHUNITHM score data");
            requireExactKeys(
                    document, STORED_DOCUMENT_KEYS, "stored CHUNITHM score data");
            long revision = requireNonNegativeLong(document.get("revision"), "revision");
            List<ChunithmChartInput> charts = parseChartsValue(
                    document.get("charts"), latestVersions);
            return new Snapshot(userId, revision, charts);
        } catch (IllegalArgumentException error) {
            throw new IOException(
                    "Stored CHUNITHM score data is invalid: " + error.getMessage(),
                    error);
        }
    }

    private static List<ChunithmChartInput> parseChartsValue(
            Object value, Collection<String> latestVersions) {
        if (!(value instanceof List<?> rawCharts)) {
            throw new IllegalArgumentException("charts must be an array");
        }
        if (rawCharts.size() > MAX_CHARTS) {
            throw new IllegalArgumentException("Too many CHUNITHM charts");
        }

        List<ChunithmChartInput> charts = new ArrayList<>(rawCharts.size());
        for (int index = 0; index < rawCharts.size(); index++) {
            int row = index + 1;
            Map<String, Object> chart = requireObject(
                    rawCharts.get(index), "charts[" + row + "]");
            requireExactKeys(chart, CHART_KEYS, "charts[" + row + "]");
            String songId = requireString(
                    chart.get("songId"), "songId", row, MAX_SONG_ID_LENGTH);
            String title = requireString(
                    chart.get("title"), "title", row, MAX_TITLE_LENGTH);
            String difficulty = requireString(
                    chart.get("difficulty"), "difficulty", row,
                    MAX_DIFFICULTY_LENGTH);
            double constant = requireNumber(chart.get("constant"), "constant", row);
            int score = requireInteger(chart.get("score"), "score", row);
            String version = requireString(
                    chart.get("version"), "version", row, MAX_VERSION_LENGTH);
            try {
                charts.add(new ChunithmChartInput(
                        songId, title, difficulty, constant, score, version));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IllegalArgumentException(
                        "chart[" + row + "]: " + error.getMessage(), error);
            }
        }
        return validateAndCopyCharts(charts, latestVersions);
    }

    private static List<ChunithmChartInput> validateAndCopyCharts(
            List<ChunithmChartInput> charts,
            Collection<String> latestVersions) {
        Objects.requireNonNull(charts, "charts must not be null");
        if (charts.size() > MAX_CHARTS) {
            throw new IllegalArgumentException("Too many CHUNITHM charts");
        }
        List<ChunithmChartInput> copied = List.copyOf(charts);
        ChunithmCalculator.calculate(copied, copyLatestVersions(latestVersions));
        return copied;
    }

    private static byte[] storedDocument(Snapshot snapshot) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("revision", snapshot.revision());
        document.put("charts", chartsToJsonValues(snapshot.charts()));
        byte[] bytes = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("CHUNITHM score data is too large");
        }
        return bytes;
    }

    private ReentrantReadWriteLock lockFor(String userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new ReentrantReadWriteLock());
    }

    private Path scoreFile(String userId) {
        return root.resolve(canonicalUserId(userId) + ".json");
    }

    private static String canonicalUserId(String userId) {
        Objects.requireNonNull(userId, "userId must not be null");
        try {
            String parsed = UUID.fromString(userId).toString();
            if (!parsed.equals(userId)) {
                throw new IllegalArgumentException("Invalid user id");
            }
            return parsed;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid user id", error);
        }
    }

    private static Collection<String> copyLatestVersions(
            Collection<String> latestVersions) {
        Objects.requireNonNull(latestVersions, "latestVersions must not be null");
        List<String> copied = List.copyOf(latestVersions);
        Set<String> checked = new HashSet<>();
        int index = 0;
        for (String version : copied) {
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException(
                        "latestVersions[" + index + "] must not be blank");
            }
            checked.add(version.trim());
            index++;
        }
        return Set.copyOf(checked);
    }

    private static byte[] readLimited(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException(
                    "Stored CHUNITHM score file is too large: " + file.getFileName());
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException(
                    "Stored CHUNITHM score file is too large: " + file.getFileName());
        }
        return bytes;
    }

    private static void atomicWrite(Path destination, byte[] content) throws IOException {
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(
                destination.getParent(), "." + destination.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Map<String, Object> requireObject(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactKeys(
            Map<String, Object> object, Set<String> expected, String field) {
        Set<String> actual = object.keySet();
        if (actual.equals(expected)) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unknown = new LinkedHashSet<>(actual);
        unknown.removeAll(expected);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains unknown field: " + unknown.iterator().next());
        }
        throw new IllegalArgumentException(
                field + " is missing field: " + missing.iterator().next());
    }

    private static String requireString(
            Object value, String field, int row, int maxLength) {
        String suffix = row == 0 ? "" : "[" + row + "]";
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + suffix + " must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + suffix + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + suffix + " is too long");
        }
        return normalized;
    }

    private static double requireNumber(Object value, String field, int row) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + "[" + row + "] must be a number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(field + "[" + row + "] is out of range");
        }
        return result;
    }

    private static int requireInteger(Object value, String field, int row) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + "[" + row + "] must be an integer");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(
                    field + "[" + row + "] must be an integer", error);
        }
    }

    private static long requireNonNegativeLong(Object value, String field) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        final long result;
        try {
            result = number.longValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
        }
        if (result < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return result;
    }

    public record Snapshot(
            String userId, long revision, List<ChunithmChartInput> charts) {
        public Snapshot {
            userId = canonicalUserId(userId);
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            charts = List.copyOf(Objects.requireNonNull(
                    charts, "charts must not be null"));
        }
    }

    public record Update(
            String expectedUserId,
            long revision,
            List<ChunithmChartInput> charts) {
        public Update {
            expectedUserId = canonicalUserId(expectedUserId);
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            charts = List.copyOf(Objects.requireNonNull(
                    charts, "charts must not be null"));
        }
    }

    /** A safe-to-map-to-HTTP-409 optimistic ownership or revision conflict. */
    public static final class ConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ConflictException(String message) {
            super(message);
        }
    }
}
