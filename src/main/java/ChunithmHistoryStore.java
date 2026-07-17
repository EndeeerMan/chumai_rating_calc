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
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Durable, append-only, per-user CHUNITHM recent-play history. */
public final class ChunithmHistoryStore {
    public static final int MAX_RECORDS = 50_000;
    public static final int MAX_APPEND_RECORDS = 2_000;
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    private static final int FILE_VERSION = 1;
    private static final int MAX_SOURCE_LENGTH = 64;
    private static final int MAX_SOURCE_RECORD_ID_LENGTH = 256;
    private static final int MAX_SONG_ID_LENGTH = 100;
    private static final int MAX_TITLE_LENGTH = 300;
    private static final int MAX_DIFFICULTY_LENGTH = 50;
    private static final int MAX_STATUS_LENGTH = 32;
    private static final int MAX_TIMESTAMP_LENGTH = 64;
    private static final int MAX_IMPORT_BATCH_ID_LENGTH = 128;
    private static final Set<String> STORED_DOCUMENT_KEYS =
            Set.of("version", "records");
    private static final Set<String> RECORD_KEYS = Set.of(
            "source", "sourceRecordId", "songId", "title", "difficulty",
            "score", "rank", "clearStatus", "comboStatus", "playedAt",
            "track", "judgmentDetails", "importedAt", "importBatchId");
    private static final Set<String> STORED_REQUIRED_RECORD_KEYS = Set.of(
            "source", "sourceRecordId", "songId", "title", "difficulty",
            "score", "rank", "clearStatus", "comboStatus", "playedAt",
            "track", "importedAt", "importBatchId");
    private static final Set<String> IMPORT_REQUIRED_KEYS = Set.of(
            "source", "sourceRecordId", "songId", "title", "difficulty",
            "score", "rank", "clearStatus", "comboStatus", "playedAt", "track");
    private static final Set<String> CLEAR_STATUSES = Set.of("clear", "failed");
    private static final Set<String> COMBO_STATUSES = Set.of("fc", "aj", "ajc");

    private final Path root;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks =
            new ConcurrentHashMap<>();

    public ChunithmHistoryStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath()
                .normalize();
        ensureSafeRoot();
    }

    public Path root() {
        return root;
    }

    /** Removes all CHUNITHM play-history records owned by one user. */
    public void deleteUserData(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            ensureSafeRoot();
            Path file = historyFile(safeUserId);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
                throw new IOException(
                        "Stored CHUNITHM play history path is not a regular file");
            }
            Files.delete(file);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PlayRecord> load(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.readLock().lock();
        try {
            ensureSafeRoot();
            return readLocked(safeUserId);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Appends only plays not already present under the semantic fingerprint. */
    public AppendResult append(String userId, List<PlayRecord> records)
            throws IOException {
        String safeUserId = canonicalUserId(userId);
        List<PlayRecord> safeRecords = validateAppendBatch(records);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            ensureSafeRoot();
            List<PlayRecord> existing = readLocked(safeUserId);
            List<PlayRecord> combined = new ArrayList<>(
                    Math.min(MAX_RECORDS, existing.size() + safeRecords.size()));
            combined.addAll(existing);
            Map<String, Integer> duplicateIndexes = new LinkedHashMap<>();
            for (int index = 0; index < existing.size(); index++) {
                duplicateIndexes.putIfAbsent(
                        duplicateKey(existing.get(index)), index);
            }
            int added = 0;
            int enriched = 0;
            for (PlayRecord record : safeRecords) {
                String key = duplicateKey(record);
                Integer existingIndex = duplicateIndexes.get(key);
                if (existingIndex == null) {
                    duplicateIndexes.put(key, combined.size());
                    combined.add(record);
                    added++;
                } else {
                    PlayRecord stored = combined.get(existingIndex);
                    if (record.judgmentDetails() != null) {
                        JudgmentDetails merged = stored.judgmentDetails() == null
                                ? record.judgmentDetails()
                                : stored.judgmentDetails().mergeMissing(
                                        record.judgmentDetails());
                        if (merged.equals(stored.judgmentDetails())) {
                            continue;
                        }
                        combined.set(
                                existingIndex,
                                stored.withJudgmentDetails(merged));
                        enriched++;
                    }
                }
            }
            int ignored = safeRecords.size() - added - enriched;
            if (combined.size() > MAX_RECORDS) {
                throw new IllegalArgumentException(
                        "CHUNITHM play history record limit has been reached");
            }
            if (added != 0 || enriched != 0) {
                atomicWrite(historyFile(safeUserId), storedDocument(combined));
            }
            return new AppendResult(added, enriched, ignored, combined.size());
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Strictly parses the public helper/manual import records array. */
    public static List<PlayRecord> parseImportRecords(Object value, int maxRecords) {
        if (maxRecords < 0 || maxRecords > MAX_RECORDS) {
            throw new IllegalArgumentException("Invalid CHUNITHM import record limit");
        }
        return parseRecords(value, maxRecords, false);
    }

    public static List<Map<String, Object>> recordsToJsonValues(
            List<PlayRecord> records) {
        Objects.requireNonNull(records, "records must not be null");
        List<Map<String, Object>> values = new ArrayList<>(records.size());
        for (PlayRecord record : records) {
            Objects.requireNonNull(record, "records must not contain null");
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", record.source());
            value.put("sourceRecordId", record.sourceRecordId());
            value.put("songId", record.songId());
            value.put("title", record.title());
            value.put("difficulty", record.difficulty());
            value.put("score", record.score());
            value.put("rank", record.rank());
            value.put("clearStatus", record.clearStatus());
            value.put("comboStatus", record.comboStatus());
            value.put("playedAt", record.playedAt());
            value.put("track", record.track());
            value.put(
                    "judgmentDetails",
                    record.judgmentDetails() == null
                            ? null
                            : record.judgmentDetails().toJsonValue());
            value.put("importedAt", record.importedAt());
            value.put("importBatchId", record.importBatchId());
            values.add(Collections.unmodifiableMap(value));
        }
        return List.copyOf(values);
    }

    private List<PlayRecord> readLocked(String userId) throws IOException {
        Path file = historyFile(userId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        BasicFileAttributes attributes = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException(
                    "Stored CHUNITHM play history path is not a regular file");
        }
        if (attributes.size() > MAX_FILE_BYTES) {
            throw new IOException("Stored CHUNITHM play history file is too large");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IOException("Stored CHUNITHM play history file is too large");
        }

        final Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Json.JsonException error) {
            throw new IOException(
                    "Stored CHUNITHM play history is invalid JSON", error);
        }
        try {
            Map<String, Object> document = requireObject(
                    parsed, "stored CHUNITHM play history");
            requireExactKeys(
                    document, STORED_DOCUMENT_KEYS, "stored CHUNITHM play history");
            int version = requireInteger(document.get("version"), "version", 0);
            if (version != FILE_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported CHUNITHM play history version: " + version);
            }
            List<PlayRecord> parsedRecords = parseRecords(
                    document.get("records"), MAX_RECORDS, true);
            Set<String> keys = new LinkedHashSet<>();
            List<PlayRecord> unique = new ArrayList<>(parsedRecords.size());
            for (PlayRecord record : parsedRecords) {
                if (keys.add(duplicateKey(record))) {
                    unique.add(record);
                }
            }
            return List.copyOf(unique);
        } catch (IllegalArgumentException error) {
            throw new IOException(
                    "Stored CHUNITHM play history is invalid: "
                            + error.getMessage(),
                    error);
        }
    }

    private static List<PlayRecord> parseRecords(
            Object value, int maxRecords, boolean requireImportMetadata) {
        if (!(value instanceof List<?> rawRecords)) {
            throw new IllegalArgumentException("records must be an array");
        }
        if (rawRecords.size() > maxRecords) {
            throw new IllegalArgumentException(
                    "Too many CHUNITHM play history records");
        }
        List<PlayRecord> records = new ArrayList<>(rawRecords.size());
        for (int index = 0; index < rawRecords.size(); index++) {
            int row = index + 1;
            Map<String, Object> item = requireObject(
                    rawRecords.get(index), "records[" + row + "]");
            if (requireImportMetadata) {
                // Keep version-1 files written before optional judgment details
                // readable while continuing to reject unknown fields.
                requireKeys(
                        item,
                        STORED_REQUIRED_RECORD_KEYS,
                        RECORD_KEYS,
                        "records[" + row + "]");
            } else {
                requireKeys(
                        item,
                        IMPORT_REQUIRED_KEYS,
                        RECORD_KEYS,
                        "records[" + row + "]");
            }
            String importedAt = optionalString(
                    item.get("importedAt"), "importedAt", row,
                    MAX_TIMESTAMP_LENGTH);
            String batchId = optionalString(
                    item.get("importBatchId"), "importBatchId", row,
                    MAX_IMPORT_BATCH_ID_LENGTH);
            if (requireImportMetadata && (importedAt == null || batchId == null)) {
                throw new IllegalArgumentException(
                        "records[" + row + "] is missing import metadata");
            }
            try {
                records.add(new PlayRecord(
                        requireString(item.get("source"), "source", row,
                                MAX_SOURCE_LENGTH),
                        optionalString(item.get("sourceRecordId"),
                                "sourceRecordId", row,
                                MAX_SOURCE_RECORD_ID_LENGTH),
                        requireString(item.get("songId"), "songId", row,
                                MAX_SONG_ID_LENGTH),
                        requireString(item.get("title"), "title", row,
                                MAX_TITLE_LENGTH),
                        requireString(item.get("difficulty"), "difficulty", row,
                                MAX_DIFFICULTY_LENGTH),
                        requireInteger(item.get("score"), "score", row),
                        optionalString(item.get("rank"), "rank", row,
                                MAX_STATUS_LENGTH),
                        optionalString(item.get("clearStatus"),
                                "clearStatus", row, MAX_STATUS_LENGTH),
                        optionalString(item.get("comboStatus"),
                                "comboStatus", row, MAX_STATUS_LENGTH),
                        requireString(item.get("playedAt"), "playedAt", row,
                                MAX_TIMESTAMP_LENGTH),
                        optionalInteger(item.get("track"), "track", row),
                        JudgmentDetails.parseChunithm(
                                item.get("judgmentDetails")),
                        importedAt,
                        batchId));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IllegalArgumentException(
                        "record[" + row + "]: " + error.getMessage(), error);
            }
        }
        return List.copyOf(records);
    }

    private static List<PlayRecord> validateAppendBatch(List<PlayRecord> records) {
        Objects.requireNonNull(records, "records must not be null");
        if (records.size() > MAX_APPEND_RECORDS) {
            throw new IllegalArgumentException(
                    "Too many CHUNITHM records in one append batch");
        }
        List<PlayRecord> copied = List.copyOf(records);
        int row = 0;
        for (PlayRecord record : copied) {
            row++;
            Objects.requireNonNull(record, "records must not contain null");
            if (!record.hasImportMetadata()) {
                throw new IllegalArgumentException(
                        "record[" + row + "] is missing import metadata");
            }
        }
        return copied;
    }

    private static byte[] storedDocument(List<PlayRecord> records) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", FILE_VERSION);
        document.put("records", recordsToJsonValues(records));
        byte[] bytes = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException(
                    "CHUNITHM play history data is too large");
        }
        return bytes;
    }

    private static String duplicateKey(PlayRecord record) {
        MessageDigest digest = sha256();
        // Deliberately exclude source, sourceRecordId and trusted import metadata.
        updateDigest(digest, record.songId());
        updateDigest(digest, record.title());
        updateDigest(digest, record.difficulty());
        updateDigest(digest, Integer.toString(record.score()));
        updateDigest(digest, record.rank());
        updateDigest(digest, record.clearStatus());
        updateDigest(digest, record.comboStatus());
        updateDigest(digest, record.playedAt());
        updateDigest(digest, record.track() == null
                ? null
                : Integer.toString(record.track()));
        return "semantic:" + HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is not available", error);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private ReentrantReadWriteLock lockFor(String userId) {
        return userLocks.computeIfAbsent(
                userId, ignored -> new ReentrantReadWriteLock());
    }

    private Path historyFile(String userId) {
        Path file = root.resolve(canonicalUserId(userId) + ".json").normalize();
        if (!root.equals(file.getParent()) || !file.startsWith(root)) {
            throw new IllegalArgumentException("Invalid user id path");
        }
        return file;
    }

    private void ensureSafeRoot() throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "CHUNITHM play history root is not a safe directory");
        }
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

    private static void atomicWrite(Path destination, byte[] content)
            throws IOException {
        Path directory = destination.getParent();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(destination)
                || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException(
                    "CHUNITHM play history destination is not a regular file");
        }
        Path temporary = Files.createTempFile(
                directory, "." + destination.getFileName() + ".", ".tmp");
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
                throw new IllegalArgumentException(
                        field + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireExactKeys(
            Map<String, Object> object, Set<String> expected, String field) {
        requireKeys(object, expected, expected, field);
    }

    private static void requireKeys(
            Map<String, Object> object,
            Set<String> required,
            Set<String> allowed,
            String field) {
        Set<String> unknown = new LinkedHashSet<>(object.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains unknown field: " + unknown.iterator().next());
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(object.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is missing field: " + missing.iterator().next());
        }
    }

    private static String requireString(
            Object value, String field, int row, int maxLength) {
        String suffix = row == 0 ? "" : "[" + row + "]";
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + suffix + " must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    field + suffix + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + suffix + " is too long");
        }
        return normalized;
    }

    private static String optionalString(
            Object value, String field, int row, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    field + "[" + row + "] must be a string or null");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "[" + row + "] is too long");
        }
        return normalized;
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

    private static Integer optionalInteger(Object value, String field, int row) {
        return value == null ? null : requireInteger(value, field, row);
    }

    static String normalizeDifficulty(String value) {
        Objects.requireNonNull(value, "difficulty must not be null");
        String compact = value.trim()
                .toUpperCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[\\s:_-]+", "");
        return switch (compact) {
            case "BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA" -> compact;
            case "WORLD'SEND", "WORLDSEND" -> "WORLD'S END";
            default -> throw new IllegalArgumentException(
                    "unsupported difficulty: " + value);
        };
    }

    private static String normalizeRank(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim()
                .toLowerCase(Locale.ROOT)
                .replace("＋", "+")
                .replace("sss+", "sssp")
                .replace("ss+", "ssp")
                .replace("s+", "sp");
        if (!Set.of(
                "d", "c", "b", "bb", "bbb", "a", "aa", "aaa",
                "s", "sp", "ss", "ssp", "sss", "sssp")
                .contains(normalized)) {
            throw new IllegalArgumentException("unsupported rank: " + value);
        }
        return normalized;
    }

    public static String rankForScore(int score) {
        if (score < 0 || score > ChunithmChartInput.MAX_SCORE) {
            throw new IllegalArgumentException("score is out of range");
        }
        if (score < 500_000) return "d";
        if (score < 600_000) return "c";
        if (score < 700_000) return "b";
        if (score < 800_000) return "bb";
        if (score < 900_000) return "bbb";
        if (score < 925_000) return "a";
        if (score < 950_000) return "aa";
        if (score < 975_000) return "aaa";
        if (score < 990_000) return "s";
        if (score < 1_000_000) return "sp";
        if (score < 1_005_000) return "ss";
        if (score < 1_007_500) return "ssp";
        if (score < 1_009_000) return "sss";
        return "sssp";
    }

    private static String normalizeOptionalStatus(
            String value, String field, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value);
        }
        return normalized;
    }

    private static String normalizeInstant(
            String value, String field, boolean optional) {
        if (value == null || value.isBlank()) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            return Instant.parse(value.trim()).toString();
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    field + " must be an ISO-8601 instant", error);
        }
    }

    public record PlayRecord(
            String source,
            String sourceRecordId,
            String songId,
            String title,
            String difficulty,
            int score,
            String rank,
            String clearStatus,
            String comboStatus,
            String playedAt,
            Integer track,
            JudgmentDetails judgmentDetails,
            String importedAt,
            String importBatchId) {
        public PlayRecord {
            source = requireText(source, "source", MAX_SOURCE_LENGTH)
                    .toLowerCase(Locale.ROOT);
            sourceRecordId = optionalText(
                    sourceRecordId, "sourceRecordId", MAX_SOURCE_RECORD_ID_LENGTH);
            songId = requireText(songId, "songId", MAX_SONG_ID_LENGTH);
            title = requireText(title, "title", MAX_TITLE_LENGTH);
            difficulty = normalizeDifficulty(requireText(
                    difficulty, "difficulty", MAX_DIFFICULTY_LENGTH));
            String derivedRank = rankForScore(score);
            String suppliedRank = normalizeRank(rank);
            if (suppliedRank != null && !suppliedRank.equals(derivedRank)) {
                throw new IllegalArgumentException("rank does not match score");
            }
            rank = derivedRank;
            clearStatus = normalizeOptionalStatus(
                    clearStatus, "clearStatus", CLEAR_STATUSES);
            comboStatus = normalizeOptionalStatus(
                    comboStatus, "comboStatus", COMBO_STATUSES);
            judgmentDetails = JudgmentDetails.requireChunithm(judgmentDetails);
            playedAt = normalizeInstant(playedAt, "playedAt", false);
            if (track != null && (track < 1 || track > 3)) {
                throw new IllegalArgumentException("track must be between 1 and 3");
            }
            importedAt = normalizeInstant(importedAt, "importedAt", true);
            importBatchId = optionalText(
                    importBatchId, "importBatchId", MAX_IMPORT_BATCH_ID_LENGTH);
        }

        public boolean hasImportMetadata() {
            return importedAt != null && importBatchId != null;
        }

        public PlayRecord withImportMetadata(String time, String batchId) {
            return new PlayRecord(
                    source,
                    sourceRecordId,
                    songId,
                    title,
                    difficulty,
                    score,
                    rank,
                    clearStatus,
                    comboStatus,
                    playedAt,
                    track,
                    judgmentDetails,
                    time,
                    batchId);
        }

        /** Adds a newly available breakdown without changing play identity. */
        public PlayRecord withJudgmentDetails(JudgmentDetails details) {
            Objects.requireNonNull(details, "details must not be null");
            return new PlayRecord(
                    source, sourceRecordId, songId, title, difficulty, score,
                    rank, clearStatus, comboStatus, playedAt, track, details,
                    importedAt, importBatchId);
        }

        /** Backward-compatible constructor for callers without result details. */
        public PlayRecord(
                String source,
                String sourceRecordId,
                String songId,
                String title,
                String difficulty,
                int score,
                String rank,
                String clearStatus,
                String comboStatus,
                String playedAt,
                Integer track,
                String importedAt,
                String importBatchId) {
            this(
                    source, sourceRecordId, songId, title, difficulty, score,
                    rank, clearStatus, comboStatus, playedAt, track, null,
                    importedAt, importBatchId);
        }

        private static String requireText(
                String value, String field, int maxLength) {
            Objects.requireNonNull(value, field + " must not be null");
            String normalized = value.trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
            if (normalized.length() > maxLength) {
                throw new IllegalArgumentException(field + " is too long");
            }
            return normalized;
        }

        private static String optionalText(
                String value, String field, int maxLength) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String normalized = value.trim();
            if (normalized.length() > maxLength) {
                throw new IllegalArgumentException(field + " is too long");
            }
            return normalized;
        }
    }

    public record AppendResult(int added, int enriched, int ignored, int total) {
        public AppendResult {
            if (added < 0
                    || enriched < 0
                    || ignored < 0
                    || total < 0
                    || total > MAX_RECORDS) {
                throw new IllegalArgumentException(
                        "Invalid CHUNITHM append result counts");
            }
        }

        /** Backward-compatible constructor for results without enrichment. */
        public AppendResult(int added, int ignored, int total) {
            this(added, 0, ignored, total);
        }
    }
}
