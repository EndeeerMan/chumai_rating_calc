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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Durable, dependency-free storage for local users and their score data.
 *
 * <p>Credential metadata and each user's charts are stored separately. All
 * writes use a temporary file in the destination directory followed by an
 * atomic replace where the file system supports it.</p>
 */
public final class UserStore {
    private static final int USERS_FILE_VERSION = 1;
    private static final int MAX_STORED_USERS = 100_000;
    private static final int MAX_STORED_CHARTS = 2_000;
    private static final int MAX_USERS_FILE_BYTES = 64 * 1024 * 1024;
    private static final int MAX_CHARTS_FILE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> USER_DOCUMENT_KEYS = Set.of("version", "users");
    private static final Set<String> USER_KEYS = Set.of(
            "id", "username", "canonicalUsername", "salt", "passwordHash",
            "iterations");
    private static final Set<String> CHART_DOCUMENT_KEYS = Set.of("charts");
    private static final Set<String> STORED_CHART_DOCUMENT_KEYS =
            Set.of("revision", "charts");
    private static final Set<String> CHART_UPDATE_KEYS =
            Set.of("expectedUserId", "revision", "charts");
    private static final Set<String> REQUIRED_CHART_KEYS = Set.of(
            "songId", "title", "chartType", "difficulty", "level",
            "achievement", "version");
    private static final Set<String> CHART_KEYS = Set.of(
            "songId", "title", "chartType", "difficulty", "level",
            "achievement", "version", "comboStatus", "syncStatus");

    private final Path root;
    private final Path usersFile;
    private final Path chartsDirectory;
    private final ReentrantReadWriteLock usersLock = new ReentrantReadWriteLock();
    private final Map<String, StoredUser> usersByCanonical = new LinkedHashMap<>();
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> chartLocks =
            new ConcurrentHashMap<>();

    public UserStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        usersFile = this.root.resolve("users.json");
        chartsDirectory = this.root.resolve("charts");
        Files.createDirectories(chartsDirectory);
        loadUsers();
    }

    public Path root() {
        return root;
    }

    Optional<StoredUser> findByCanonicalUsername(String canonicalUsername) {
        usersLock.readLock().lock();
        try {
            return Optional.ofNullable(usersByCanonical.get(canonicalUsername));
        } finally {
            usersLock.readLock().unlock();
        }
    }

    Optional<StoredUser> findById(String userId) {
        String safeUserId = canonicalUserId(userId);
        usersLock.readLock().lock();
        try {
            return usersByCanonical.values().stream()
                    .filter(user -> user.id().equals(safeUserId))
                    .findFirst();
        } finally {
            usersLock.readLock().unlock();
        }
    }

    StoredUser updatePassword(
            String userId,
            byte[] expectedSalt,
            byte[] expectedPasswordHash,
            int expectedIterations,
            byte[] newSalt,
            byte[] newPasswordHash,
            int newIterations) throws IOException {
        String safeUserId = canonicalUserId(userId);
        Objects.requireNonNull(expectedSalt, "expectedSalt must not be null");
        Objects.requireNonNull(
                expectedPasswordHash, "expectedPasswordHash must not be null");
        Objects.requireNonNull(newSalt, "newSalt must not be null");
        Objects.requireNonNull(newPasswordHash, "newPasswordHash must not be null");
        if (newSalt.length < 16 || newSalt.length > 64
                || newPasswordHash.length < 32 || newPasswordHash.length > 64
                || newIterations < 100_000 || newIterations > 10_000_000) {
            throw new IllegalArgumentException("Invalid replacement credentials");
        }

        usersLock.writeLock().lock();
        try {
            Map.Entry<String, StoredUser> entry = usersByCanonical.entrySet().stream()
                    .filter(item -> item.getValue().id().equals(safeUserId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
            StoredUser current = entry.getValue();
            byte[] currentSalt = current.salt();
            byte[] currentHash = current.passwordHash();
            boolean credentialsMatch;
            try {
                credentialsMatch = current.iterations() == expectedIterations
                        && MessageDigest.isEqual(currentSalt, expectedSalt)
                        && MessageDigest.isEqual(currentHash, expectedPasswordHash);
            } finally {
                java.util.Arrays.fill(currentSalt, (byte) 0);
                java.util.Arrays.fill(currentHash, (byte) 0);
            }
            if (!credentialsMatch) {
                throw new CredentialConflictException();
            }

            StoredUser updated = new StoredUser(
                    current.id(),
                    current.username(),
                    current.canonicalUsername(),
                    newSalt,
                    newPasswordHash,
                    newIterations);
            usersByCanonical.put(entry.getKey(), updated);
            try {
                writeUsersLocked();
            } catch (IOException | RuntimeException error) {
                usersByCanonical.put(entry.getKey(), current);
                throw error;
            }
            return updated;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    /**
     * Atomically removes one user only while the supplied credential snapshot
     * is still current. This prevents a stale password verification from
     * deleting an account whose password changed concurrently.
     */
    StoredUser deleteUser(
            String userId,
            byte[] expectedSalt,
            byte[] expectedPasswordHash,
            int expectedIterations) throws IOException {
        String safeUserId = canonicalUserId(userId);
        Objects.requireNonNull(expectedSalt, "expectedSalt must not be null");
        Objects.requireNonNull(
                expectedPasswordHash, "expectedPasswordHash must not be null");

        usersLock.writeLock().lock();
        try {
            Map.Entry<String, StoredUser> entry = usersByCanonical.entrySet().stream()
                    .filter(item -> item.getValue().id().equals(safeUserId))
                    .findFirst()
                    .orElseThrow(CredentialConflictException::new);
            StoredUser current = entry.getValue();
            byte[] currentSalt = current.salt();
            byte[] currentHash = current.passwordHash();
            boolean credentialsMatch;
            try {
                credentialsMatch = current.iterations() == expectedIterations
                        && MessageDigest.isEqual(currentSalt, expectedSalt)
                        && MessageDigest.isEqual(currentHash, expectedPasswordHash);
            } finally {
                java.util.Arrays.fill(currentSalt, (byte) 0);
                java.util.Arrays.fill(currentHash, (byte) 0);
            }
            if (!credentialsMatch) {
                throw new CredentialConflictException();
            }

            Map<String, StoredUser> previousUsers =
                    new LinkedHashMap<>(usersByCanonical);
            usersByCanonical.remove(entry.getKey());
            try {
                writeUsersLocked();
            } catch (IOException | RuntimeException error) {
                usersByCanonical.clear();
                usersByCanonical.putAll(previousUsers);
                throw error;
            }
            return current;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    StoredUser createUser(
            String username,
            String canonicalUsername,
            byte[] salt,
            byte[] passwordHash,
            int iterations) throws IOException {
        Objects.requireNonNull(username, "username must not be null");
        Objects.requireNonNull(canonicalUsername, "canonicalUsername must not be null");
        Objects.requireNonNull(salt, "salt must not be null");
        Objects.requireNonNull(passwordHash, "passwordHash must not be null");

        StoredUser user = new StoredUser(
                UUID.randomUUID().toString(),
                username,
                canonicalUsername,
                salt,
                passwordHash,
                iterations);

        usersLock.writeLock().lock();
        try {
            if (usersByCanonical.containsKey(canonicalUsername)) {
                throw new UsernameAlreadyExistsException();
            }
            if (usersByCanonical.size() >= MAX_STORED_USERS) {
                throw new IOException("User storage limit has been reached");
            }
            usersByCanonical.put(canonicalUsername, user);
            try {
                writeUsersLocked();
            } catch (IOException | RuntimeException error) {
                usersByCanonical.remove(canonicalUsername);
                throw error;
            }
            return user;
        } finally {
            usersLock.writeLock().unlock();
        }
    }

    /** Loads a user's chart list. A new account starts with an empty list. */
    public List<ChartInput> loadCharts(String userId) throws IOException {
        return loadChartSnapshot(userId).charts();
    }

    /** Loads a user's chart list together with its optimistic-lock revision. */
    public ChartSnapshot loadChartSnapshot(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        usersLock.readLock().lock();
        try {
            requireKnownUserId(safeUserId);
            ReentrantReadWriteLock lock = chartLocks.computeIfAbsent(
                    safeUserId, ignored -> new ReentrantReadWriteLock());
            lock.readLock().lock();
            try {
                return readChartSnapshotLocked(safeUserId);
            } finally {
                lock.readLock().unlock();
            }
        } finally {
            usersLock.readLock().unlock();
        }
    }

    /** Replaces one user's complete chart list without touching any other user. */
    public void saveCharts(String userId, List<ChartInput> charts) throws IOException {
        ChartSnapshot current = loadChartSnapshot(userId);
        saveCharts(userId, userId, current.revision(), charts);
    }

    /**
     * Atomically replaces a user's chart list when both the expected owner and
     * revision still match the authenticated session.
     */
    public ChartSnapshot saveCharts(
            String authenticatedUserId,
            String expectedUserId,
            long expectedRevision,
            List<ChartInput> charts) throws IOException {
        String safeUserId = canonicalUserId(authenticatedUserId);
        Objects.requireNonNull(expectedUserId, "expectedUserId must not be null");
        validateChartsForStorage(charts);
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        usersLock.readLock().lock();
        try {
            requireKnownUserId(safeUserId);
            ReentrantReadWriteLock lock = chartLocks.computeIfAbsent(
                    safeUserId, ignored -> new ReentrantReadWriteLock());
            lock.writeLock().lock();
            try {
                if (!safeUserId.equals(expectedUserId)) {
                    throw new ChartConflictException(
                            "Authenticated user does not match expectedUserId");
                }
                ChartSnapshot current = readChartSnapshotLocked(safeUserId);
                if (current.revision() != expectedRevision) {
                    throw new ChartConflictException(
                            "Chart data has changed; reload before saving");
                }
                if (current.revision() == Long.MAX_VALUE) {
                    throw new IOException("Chart revision limit has been reached");
                }

                ChartSnapshot updated = new ChartSnapshot(
                        safeUserId, current.revision() + 1, charts);
                byte[] json = storedChartDocument(updated);
                atomicWrite(chartFile(safeUserId), json);
                return updated;
            } finally {
                lock.writeLock().unlock();
            }
        } finally {
            usersLock.readLock().unlock();
        }
    }

    /**
     * Idempotently removes the maimai chart file for one canonical user id.
     * Symbolic links and other non-regular entries are never followed or
     * deleted as user data.
     */
    public void deleteChartData(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = chartLocks.computeIfAbsent(
                safeUserId, ignored -> new ReentrantReadWriteLock());
        lock.writeLock().lock();
        try {
            if (!Files.exists(chartsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(chartsDirectory)
                    || !Files.isDirectory(
                            chartsDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Refusing to delete chart data through a linked directory");
            }
            Path file = chartFile(safeUserId);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Refusing to delete non-regular chart data: "
                                + file.getFileName());
            }
            Files.delete(file);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Strictly parses the public {@code {"charts": [...]}} document. */
    static List<ChartInput> parseChartsDocument(Object value, int maxCharts) {
        Map<String, Object> document = requireObject(value, "request body");
        requireExactKeys(document, CHART_DOCUMENT_KEYS, "request body");
        return parseChartsValue(document.get("charts"), maxCharts);
    }

    /** Strictly parses an optimistic-lock chart update request. */
    static ChartUpdate parseChartUpdateDocument(Object value, int maxCharts) {
        Map<String, Object> document = requireObject(value, "request body");
        requireExactKeys(document, CHART_UPDATE_KEYS, "request body");
        String expectedUserId = requireNonBlankString(
                document.get("expectedUserId"), "expectedUserId", 100);
        long revision = requireNonNegativeLong(document.get("revision"), "revision");
        List<ChartInput> charts = parseChartsValue(document.get("charts"), maxCharts);
        return new ChartUpdate(expectedUserId, revision, charts);
    }

    private static List<ChartInput> parseChartsValue(Object chartsValue, int maxCharts) {
        if (!(chartsValue instanceof List<?> rawCharts)) {
            throw new IllegalArgumentException("charts must be an array");
        }
        if (rawCharts.size() > maxCharts) {
            throw new IllegalArgumentException("Too many charts");
        }

        List<ChartInput> charts = new ArrayList<>(rawCharts.size());
        for (int index = 0; index < rawCharts.size(); index++) {
            int row = index + 1;
            Map<String, Object> item = requireObject(
                    rawCharts.get(index), "charts[" + row + "]");
            requireKeys(
                    item, REQUIRED_CHART_KEYS, CHART_KEYS, "charts[" + row + "]");
            String songId = requireString(item.get("songId"), "songId", row, 100);
            String title = requireString(item.get("title"), "title", row, 300);
            String chartTypeText = requireString(
                    item.get("chartType"), "chartType", row, 20);
            String difficulty = requireString(
                    item.get("difficulty"), "difficulty", row, 50);
            double level = requireNumber(item.get("level"), "level", row);
            double achievement = requireNumber(
                    item.get("achievement"), "achievement", row);
            String versionText = requireString(item.get("version"), "version", row, 20);
            String comboStatus = optionalString(
                    item.get("comboStatus"), "comboStatus", row, 10);
            String syncStatus = optionalString(
                    item.get("syncStatus"), "syncStatus", row, 10);

            ChartType chartType = switch (chartTypeText.toLowerCase(java.util.Locale.ROOT)) {
                case "standard" -> ChartType.STANDARD;
                case "dx" -> ChartType.DX;
                default -> throw new IllegalArgumentException(
                        "chartType[" + row + "] must be standard or dx");
            };
            Version version = switch (versionText.toLowerCase(java.util.Locale.ROOT)) {
                case "legacy" -> Version.LEGACY;
                case "current" -> Version.CURRENT;
                default -> throw new IllegalArgumentException(
                        "version[" + row + "] must be legacy or current");
            };
            try {
                charts.add(new ChartInput(
                        songId, title, chartType, difficulty,
                        level, achievement, version, comboStatus, syncStatus));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new IllegalArgumentException(
                        "chart[" + row + "]: " + error.getMessage(), error);
            }
        }
        // Repeated screenshots/import rows are idempotent: equal rows are
        // ignored and differing rows for one chart keep the higher score plus
        // the strongest FC/AP and sync badges.
        return MaimaiChartMerger.merge(List.of(), charts).charts();
    }

    private ChartSnapshot readChartSnapshotLocked(String userId) throws IOException {
        Path file = chartFile(userId);
        if (!Files.exists(file)) {
            return new ChartSnapshot(userId, 0, List.of());
        }
        byte[] bytes = readLimited(file, MAX_CHARTS_FILE_BYTES);
        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Json.JsonException error) {
            throw new IOException("Stored chart data is invalid JSON", error);
        }
        try {
            Map<String, Object> document = requireObject(parsed, "stored chart data");
            long revision;
            if (document.keySet().equals(CHART_DOCUMENT_KEYS)) {
                // Backward compatibility for chart files written before revisions.
                revision = 0;
            } else {
                requireExactKeys(
                        document, STORED_CHART_DOCUMENT_KEYS, "stored chart data");
                revision = requireNonNegativeLong(document.get("revision"), "revision");
            }
            List<ChartInput> charts = parseChartsValue(
                    document.get("charts"), MAX_STORED_CHARTS);
            return new ChartSnapshot(userId, revision, charts);
        } catch (IllegalArgumentException error) {
            throw new IOException("Stored chart data is invalid: " + error.getMessage(), error);
        }
    }

    private static void validateChartsForStorage(List<ChartInput> charts) {
        Objects.requireNonNull(charts, "charts must not be null");
        if (charts.size() > MAX_STORED_CHARTS) {
            throw new IllegalArgumentException("Too many charts");
        }
        // Applies duplicate-chart and per-song version consistency constraints.
        B50Calculator.calculate(charts);
    }

    private static byte[] storedChartDocument(ChartSnapshot snapshot) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("revision", snapshot.revision());
        document.put("charts", chartsToJsonValues(snapshot.charts()));
        byte[] json = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_CHARTS_FILE_BYTES) {
            throw new IllegalArgumentException("Chart data is too large");
        }
        return json;
    }

    static List<Map<String, Object>> chartsToJsonValues(List<ChartInput> charts) {
        List<Map<String, Object>> values = new ArrayList<>(charts.size());
        for (ChartInput chart : charts) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", chart.id());
            value.put("title", chart.title());
            value.put("chartType", chart.chartType() == ChartType.STANDARD
                    ? "standard" : "dx");
            value.put("difficulty", chart.difficulty());
            value.put("level", BigDecimal.valueOf(chart.level()));
            value.put("achievement", BigDecimal.valueOf(chart.achievement()));
            value.put("version", chart.version() == Version.LEGACY
                    ? "legacy" : "current");
            value.put("comboStatus", chart.comboStatus());
            value.put("syncStatus", chart.syncStatus());
            values.add(value);
        }
        return values;
    }

    private void loadUsers() throws IOException {
        if (!Files.exists(usersFile)) {
            return;
        }
        byte[] bytes = readLimited(usersFile, MAX_USERS_FILE_BYTES);
        Object parsed;
        try {
            parsed = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Json.JsonException error) {
            throw new IOException("User database is invalid JSON", error);
        }
        try {
            Map<String, Object> document = requireObject(parsed, "user database");
            requireExactKeys(document, USER_DOCUMENT_KEYS, "user database");
            int version = requireInteger(document.get("version"), "version");
            if (version != USERS_FILE_VERSION) {
                throw new IllegalArgumentException(
                        "Unsupported user database version: " + version);
            }
            if (!(document.get("users") instanceof List<?> users)) {
                throw new IllegalArgumentException("users must be an array");
            }
            if (users.size() > MAX_STORED_USERS) {
                throw new IllegalArgumentException("Too many stored users");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (int index = 0; index < users.size(); index++) {
                Map<String, Object> item = requireObject(
                        users.get(index), "users[" + (index + 1) + "]");
                requireExactKeys(item, USER_KEYS, "users[" + (index + 1) + "]");
                StoredUser user = readStoredUser(item);
                if (!ids.add(user.id())) {
                    throw new IllegalArgumentException("Duplicate stored user id");
                }
                if (usersByCanonical.putIfAbsent(user.canonicalUsername(), user) != null) {
                    throw new IllegalArgumentException("Duplicate stored username");
                }
            }
        } catch (IllegalArgumentException error) {
            usersByCanonical.clear();
            throw new IOException("User database is invalid: " + error.getMessage(), error);
        }
    }

    private StoredUser readStoredUser(Map<String, Object> item) {
        String id = requireNonBlankString(item.get("id"), "id", 100);
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException("id is not canonical");
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid stored user id", error);
        }
        String username = requireNonBlankString(item.get("username"), "username", 128);
        String canonical = requireNonBlankString(
                item.get("canonicalUsername"), "canonicalUsername", 128);
        AuthService.Username normalizedUsername = AuthService.validateUsername(username);
        if (!normalizedUsername.display().equals(username)
                || !normalizedUsername.canonical().equals(canonical)) {
            throw new IllegalArgumentException("Stored username is not normalized");
        }
        byte[] salt = decodeBase64(item.get("salt"), "salt");
        byte[] hash = decodeBase64(item.get("passwordHash"), "passwordHash");
        int iterations = requireInteger(item.get("iterations"), "iterations");
        if (salt.length < 16 || salt.length > 64) {
            throw new IllegalArgumentException("Invalid stored salt length");
        }
        if (hash.length < 32 || hash.length > 64) {
            throw new IllegalArgumentException("Invalid stored password hash length");
        }
        if (iterations < 100_000 || iterations > 10_000_000) {
            throw new IllegalArgumentException("Invalid stored PBKDF2 iteration count");
        }
        return new StoredUser(id, username, canonical, salt, hash, iterations);
    }

    private void writeUsersLocked() throws IOException {
        List<Map<String, Object>> users = new ArrayList<>(usersByCanonical.size());
        Base64.Encoder base64 = Base64.getEncoder();
        for (StoredUser user : usersByCanonical.values()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("id", user.id());
            value.put("username", user.username());
            value.put("canonicalUsername", user.canonicalUsername());
            value.put("salt", base64.encodeToString(user.salt()));
            value.put("passwordHash", base64.encodeToString(user.passwordHash()));
            value.put("iterations", user.iterations());
            users.add(value);
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", USERS_FILE_VERSION);
        document.put("users", users);
        byte[] json = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
        if (json.length > MAX_USERS_FILE_BYTES) {
            throw new IOException("User database is too large");
        }
        atomicWrite(usersFile, json);
    }

    private void requireKnownUserId(String userId) {
        String safeId = canonicalUserId(userId);
        usersLock.readLock().lock();
        try {
            boolean found = usersByCanonical.values().stream()
                    .anyMatch(user -> user.id().equals(safeId));
            if (!found) {
                throw new IllegalArgumentException("Unknown user");
            }
        } finally {
            usersLock.readLock().unlock();
        }
    }

    private Path chartFile(String userId) {
        return chartsDirectory.resolve(canonicalUserId(userId) + ".json");
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

    private static byte[] readLimited(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        if (size > maxBytes) {
            throw new IOException("Stored file is too large: " + file.getFileName());
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > maxBytes) {
            throw new IOException("Stored file is too large: " + file.getFileName());
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
        requireKeys(object, expected, expected, field);
    }

    private static void requireKeys(
            Map<String, Object> object,
            Set<String> required,
            Set<String> allowed,
            String field) {
        Set<String> actual = object.keySet();
        Set<String> unknown = new LinkedHashSet<>(actual);
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains unknown field: " + unknown.iterator().next());
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(actual);
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is missing field: " + missing.iterator().next());
        }
    }

    private static String requireString(
            Object value, String field, int row, int maxLength) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + "[" + row + "] must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + "[" + row + "] must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "[" + row + "] is too long");
        }
        return normalized;
    }

    private static String optionalString(
            Object value, String field, int row, int maxLength) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + "[" + row + "] must be a string");
        }
        String normalized = text.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + "[" + row + "] is too long");
        }
        return normalized;
    }

    private static String requireNonBlankString(
            Object value, String field, int maxLength) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        if (text.isBlank() || text.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return text;
    }

    private static double requireNumber(Object value, String field, int row) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + "[" + row + "] must be a number");
        }
        return number.doubleValue();
    }

    private static int requireInteger(Object value, String field) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return number.intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(field + " must be an integer", error);
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

    private static byte[] decodeBase64(Object value, String field) {
        String encoded = requireNonBlankString(value, field, 256);
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid stored " + field, error);
        }
    }

    public record ChartSnapshot(String userId, long revision, List<ChartInput> charts) {
        public ChartSnapshot {
            Objects.requireNonNull(userId, "userId must not be null");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            charts = List.copyOf(Objects.requireNonNull(charts, "charts must not be null"));
        }
    }

    record ChartUpdate(String expectedUserId, long revision, List<ChartInput> charts) {
        ChartUpdate {
            Objects.requireNonNull(expectedUserId, "expectedUserId must not be null");
            if (revision < 0) {
                throw new IllegalArgumentException("revision must not be negative");
            }
            charts = List.copyOf(Objects.requireNonNull(charts, "charts must not be null"));
        }
    }

    static final class StoredUser {
        private final String id;
        private final String username;
        private final String canonicalUsername;
        private final byte[] salt;
        private final byte[] passwordHash;
        private final int iterations;

        StoredUser(
                String id,
                String username,
                String canonicalUsername,
                byte[] salt,
                byte[] passwordHash,
                int iterations) {
            this.id = id;
            this.username = username;
            this.canonicalUsername = canonicalUsername;
            this.salt = salt.clone();
            this.passwordHash = passwordHash.clone();
            this.iterations = iterations;
        }

        String id() {
            return id;
        }

        String username() {
            return username;
        }

        String canonicalUsername() {
            return canonicalUsername;
        }

        byte[] salt() {
            return salt.clone();
        }

        byte[] passwordHash() {
            return passwordHash.clone();
        }

        int iterations() {
            return iterations;
        }
    }

    static final class UsernameAlreadyExistsException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        UsernameAlreadyExistsException() {
            super("Username is already registered");
        }
    }

    static final class ChartConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        ChartConflictException(String message) {
            super(message);
        }
    }

    static final class CredentialConflictException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        CredentialConflictException() {
            super("Credentials changed concurrently");
        }
    }
}
