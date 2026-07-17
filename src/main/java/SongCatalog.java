import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Unified maimai catalogue whose public identity is always the base SongID.
 *
 * <p>Diving-Fish represents a DX chart by adding 10000 to the base SongID,
 * while LXNS and the official score payload use the base ID. This class folds
 * that offset before merging STD and DX charts, so a title can never become
 * two songs merely because both chart types exist. A valid SongID always wins
 * over title matching.</p>
 *
 * <p>Online refreshes combine the fixed public Diving-Fish and LXNS endpoints.
 * The complete result is validated and atomically written to
 * {@code cache/maimai-catalog.json} before it becomes visible. If any source,
 * validation, or write step fails, the last-known-good in-memory and on-disk
 * dataset remains unchanged.</p>
 */
public final class SongCatalog {
    static final URI DIVING_FISH_ENDPOINT = URI.create(
            "https://www.diving-fish.com/api/maimaidxprober/music_data");
    static final URI LXNS_SONG_ENDPOINT = URI.create(
            "https://maimai.lxns.net/api/v0/maimai/song/list");
    static final URI LXNS_ALIAS_ENDPOINT = URI.create(
            "https://maimai.lxns.net/api/v0/maimai/alias/list");
    static final URI LXNS_COVER_BASE = URI.create(
            "https://assets2.lxns.net/maimai/jacket/");
    static final Path DEFAULT_CACHE_PATH = Path.of("cache", "maimai-catalog.json");
    static final int MAX_REMOTE_BYTES = 2 * 1024 * 1024;
    static final int MAX_CACHE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LOCAL_COVER_BYTES = 2 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private static final int CACHE_SCHEMA_VERSION = 1;
    private static final int MAX_CATALOG_ENTRIES = 5_000;
    private static final int MAX_ALIASES_PER_SONG = 100;
    private static final int MAX_CHARTS_PER_SONG = 10;
    private static final int MIN_FUZZY_CODE_POINTS = 6;
    private static final Duration DEFAULT_REFRESH_TTL = Duration.ofMinutes(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,12}");
    private static final String[] DIFFICULTIES = {
        "BASIC", "ADVANCED", "EXPERT", "MASTER", "RE:MASTER"
    };

    private final Map<String, String> localCovers;
    private final RemoteFetcher remoteFetcher;
    private final Path cachePath;
    private final CacheWriter cacheWriter;
    private final long refreshTtlNanos;
    private final LongSupplier nanoClock;
    private final Clock wallClock;
    private final Object refreshLock = new Object();
    private volatile Dataset current;
    private volatile long lastAttemptNanos = Long.MIN_VALUE;
    private volatile String lastWarning;
    private volatile SyncStatus syncStatus;

    private SongCatalog(
            Map<String, String> localCovers,
            Dataset initial,
            RemoteFetcher remoteFetcher,
            Path cachePath,
            CacheWriter cacheWriter,
            Duration refreshTtl,
            LongSupplier nanoClock,
            Clock wallClock,
            String initialWarning) {
        this.localCovers = Map.copyOf(localCovers);
        this.current = Objects.requireNonNull(initial, "initial");
        this.remoteFetcher = Objects.requireNonNull(remoteFetcher, "remoteFetcher");
        this.cachePath = cachePath;
        this.cacheWriter = Objects.requireNonNull(cacheWriter, "cacheWriter");
        Objects.requireNonNull(refreshTtl, "refreshTtl");
        if (refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalArgumentException("refreshTtl must be positive");
        }
        this.refreshTtlNanos = refreshTtl.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.wallClock = Objects.requireNonNull(wallClock, "wallClock");
        this.lastWarning = initialWarning;
        this.syncStatus = new SyncStatus(
                "ready",
                null,
                initial.source().equals("cache") ? initial.generatedAt() : null,
                initialWarning,
                initial.songs().size());
    }

    /** Loads bundled data, then replaces it with a validated persistent cache. */
    public static SongCatalog load(Path songCatalogDirectory) throws IOException {
        return load(songCatalogDirectory, DEFAULT_CACHE_PATH);
    }

    /** Loads bundled data with an explicit cache path. */
    public static SongCatalog load(Path songCatalogDirectory, Path cachePath)
            throws IOException {
        Objects.requireNonNull(songCatalogDirectory, "songCatalogDirectory");
        Objects.requireNonNull(cachePath, "cachePath");
        String localJson = readLimitedUtf8(
                songCatalogDirectory.resolve("maidata.json"),
                MAX_REMOTE_BYTES,
                "Local song catalogue");
        String snapshotJson = readLimitedUtf8(
                songCatalogDirectory.resolve("diving-fish-music-data.json"),
                MAX_REMOTE_BYTES,
                "Bundled Diving-Fish snapshot");
        Map<String, String> covers = parseLocalCovers(
                localJson, songCatalogDirectory.resolve("cover"));
        Instant now = Instant.now();
        Dataset bundled = buildDataset(
                snapshotJson, null, null, covers, "snapshot", now,
                List.of(new Source(
                        "bundled-diving-fish",
                        songCatalogDirectory.resolve(
                                "diving-fish-music-data.json").toString())));
        Dataset initial = bundled;
        String warning = null;
        if (Files.exists(cachePath)) {
            try {
                initial = parseCache(readLimitedUtf8(
                        cachePath, MAX_CACHE_BYTES, "Maimai catalogue cache"));
            } catch (IOException | IllegalArgumentException error) {
                warning = "Maimai catalogue cache is invalid; using bundled snapshot: "
                        + safeMessage(error);
                System.err.println("Warning: " + warning);
            }
        }
        return new SongCatalog(
                covers,
                initial,
                new HttpRemoteFetcher(),
                cachePath,
                SongCatalog::writeAtomically,
                DEFAULT_REFRESH_TTL,
                System::nanoTime,
                Clock.systemUTC(),
                warning);
    }

    /** Returns an empty operational catalogue for fail-safe server startup. */
    static SongCatalog empty(String warning) {
        Instant now = Instant.now();
        return new SongCatalog(
                Map.of(),
                new Dataset("empty", now, List.of(), List.of()),
                new HttpRemoteFetcher(),
                DEFAULT_CACHE_PATH,
                SongCatalog::writeAtomically,
                DEFAULT_REFRESH_TTL,
                System::nanoTime,
                Clock.systemUTC(),
                warning);
    }

    /** Factory used by dependency-free tests; it performs no network I/O. */
    static SongCatalog fromJson(
            String localJson,
            String snapshotJson,
            RemoteFetcher remoteFetcher,
            Duration refreshTtl,
            LongSupplier nanoClock) {
        return fromJson(
                localJson,
                snapshotJson,
                remoteFetcher,
                null,
                (path, bytes) -> {
                    throw new AssertionError("cache writer must not run without a path");
                },
                refreshTtl,
                nanoClock,
                Clock.systemUTC());
    }

    /** Extended test factory with cache and wall-clock seams. */
    static SongCatalog fromJson(
            String localJson,
            String snapshotJson,
            RemoteFetcher remoteFetcher,
            Path cachePath,
            CacheWriter cacheWriter,
            Duration refreshTtl,
            LongSupplier nanoClock,
            Clock wallClock) {
        Map<String, String> covers = parseLocalCovers(localJson);
        Dataset snapshot = buildDataset(
                snapshotJson,
                null,
                null,
                covers,
                "snapshot",
                Instant.now(wallClock),
                List.of(new Source("bundled-diving-fish", "test:snapshot")));
        return new SongCatalog(
                covers,
                snapshot,
                remoteFetcher,
                cachePath,
                cacheWriter,
                refreshTtl,
                nanoClock,
                wallClock,
                null);
    }

    /** Returns the newest last-known-good catalogue without network access. */
    public CatalogResult catalog() {
        return result(current, lastWarning);
    }

    /** Compatibility alias used by handlers that explicitly request current data. */
    public CatalogResult currentCatalog() {
        return catalog();
    }

    /** Searches SongID, title, aliases, artist, genre, version and chart fields. */
    public CatalogResult search(String query, int limit, boolean online) {
        validateSearch(query, limit);
        if (online) {
            refreshIfDue();
        }
        Dataset dataset = current;
        String needle = normalize(query);
        String compactNeedle = compactKey(query);
        List<ScoredSong> matches = new ArrayList<>();
        for (int index = 0; index < dataset.songs().size(); index++) {
            Song song = dataset.songs().get(index);
            int score = matchScore(song, needle, compactNeedle);
            if (score >= 0) {
                matches.add(new ScoredSong(song, score, index));
            }
        }
        matches.sort(Comparator
                .comparingInt(ScoredSong::score)
                .thenComparing(item -> normalize(item.song().title()))
                .thenComparingInt(ScoredSong::originalIndex));
        List<Song> selected = matches.stream()
                .limit(limit)
                .map(ScoredSong::song)
                .toList();
        return new CatalogResult(
                dataset.source(), lastWarning, dataset.generatedAt(), selected);
    }

    /** Finds a song by numeric ID after applying the official 10000 offset rule. */
    public Optional<Song> findBySongId(String rawSongId) {
        if (rawSongId == null || rawSongId.isBlank()) {
            return Optional.empty();
        }
        final String canonical;
        try {
            canonical = canonicalSongId(rawSongId);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        return current.songs().stream()
                .filter(song -> song.songId().equals(canonical))
                .findFirst();
    }

    /**
     * Resolves a score identity. A known SongID always wins; title/alias
     * matching is only used when the supplied ID is absent or unknown.
     */
    public Optional<Song> resolveSong(String suppliedSongId, String title) {
        Optional<Song> byId = findBySongId(suppliedSongId);
        if (byId.isPresent()) {
            return byId;
        }
        if (title == null || title.isBlank()) {
            return Optional.empty();
        }
        String needle = normalize(title);
        String compactNeedle = compactKey(title);
        int bestScore = Integer.MAX_VALUE;
        Song best = null;
        boolean tied = false;
        for (Song song : current.songs()) {
            int score = titleMatchScore(song, needle, compactNeedle);
            if (score < 0) {
                continue;
            }
            if (score < bestScore) {
                bestScore = score;
                best = song;
                tied = false;
            } else if (score == bestScore
                    && best != null
                    && !best.songId().equals(song.songId())) {
                tied = true;
            }
        }
        return best == null || tied ? Optional.empty() : Optional.of(best);
    }

    /** Resolves one chart by the canonical score identity tuple. */
    public Optional<Chart> findChart(
            String rawSongId, String chartType, String difficulty) {
        String normalizedType;
        String normalizedDifficulty;
        try {
            normalizedType = canonicalChartType(chartType);
            normalizedDifficulty = canonicalDifficulty(difficulty);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
        return findBySongId(rawSongId).flatMap(song -> song.charts().stream()
                .filter(chart -> chart.chartType().equals(normalizedType)
                        && chart.difficulty().equals(normalizedDifficulty))
                .findFirst());
    }

    /** Forces a complete three-source refresh regardless of the search TTL. */
    public RefreshResult refreshNow() {
        synchronized (refreshLock) {
            Instant attemptedAt = Instant.now(wallClock);
            lastAttemptNanos = nanoClock.getAsLong();
            SyncStatus previous = syncStatus;
            syncStatus = new SyncStatus(
                    "running",
                    attemptedAt,
                    previous.lastSuccessAt(),
                    null,
                    current.songs().size());
            try {
                String divingFishJson = fetchUtf8(
                        DIVING_FISH_ENDPOINT, "Diving-Fish maimai response");
                String lxnsSongJson = fetchUtf8(
                        LXNS_SONG_ENDPOINT, "LXNS maimai song response");
                String lxnsAliasJson = fetchUtf8(
                        LXNS_ALIAS_ENDPOINT, "LXNS maimai alias response");
                Instant generatedAt = Instant.now(wallClock);
                Dataset refreshed = buildDataset(
                        divingFishJson,
                        lxnsSongJson,
                        lxnsAliasJson,
                        localCovers,
                        "online",
                        generatedAt,
                        onlineSources());
                if (refreshed.songs().isEmpty()) {
                    throw new IOException("Merged maimai catalogue is empty");
                }
                if (cachePath != null) {
                    byte[] cacheBytes = cacheJson(refreshed)
                            .getBytes(StandardCharsets.UTF_8);
                    if (cacheBytes.length > MAX_CACHE_BYTES) {
                        throw new IOException("Maimai catalogue cache exceeds 8 MiB");
                    }
                    cacheWriter.write(cachePath, cacheBytes);
                }
                current = refreshed;
                lastWarning = null;
                syncStatus = new SyncStatus(
                        "ready",
                        attemptedAt,
                        generatedAt,
                        null,
                        refreshed.songs().size());
                return new RefreshResult(
                        true,
                        "Maimai catalogue synchronized",
                        syncStatus);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return failedRefresh(attemptedAt, error);
            } catch (IOException | IllegalArgumentException error) {
                return failedRefresh(attemptedAt, error);
            } catch (RuntimeException error) {
                return failedRefresh(attemptedAt, error);
            }
        }
    }

    /** Returns synchronization health without performing network I/O. */
    public SyncStatus syncStatus() {
        return syncStatus;
    }

    /**
     * Canonicalizes official maimai IDs. For ordinary DX records,
     * Diving-Fish stores {@code baseSongId + 10000}; official score payloads
     * and LXNS store the base ID. UTAGE rows are excluded before this method is
     * used by the catalogue.
     */
    public static String canonicalSongId(String rawSongId) {
        if (rawSongId == null) {
            throw new IllegalArgumentException("songId is required");
        }
        String trimmed = rawSongId.trim();
        if (!NUMERIC_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("songId must be a decimal integer");
        }
        BigInteger numeric = new BigInteger(trimmed);
        if (numeric.compareTo(BigInteger.valueOf(10_000)) >= 0) {
            numeric = numeric.mod(BigInteger.valueOf(10_000));
        }
        return numeric.toString();
    }

    private void refreshIfDue() {
        long now = nanoClock.getAsLong();
        long attempted = lastAttemptNanos;
        if (attempted != Long.MIN_VALUE
                && now - attempted >= 0
                && now - attempted < refreshTtlNanos) {
            return;
        }
        refreshNow();
    }

    private RefreshResult failedRefresh(Instant attemptedAt, Throwable error) {
        String message = "Maimai catalogue refresh failed; retained last-known-good: "
                + safeMessage(error);
        lastWarning = message;
        SyncStatus previous = syncStatus;
        syncStatus = new SyncStatus(
                "failed",
                attemptedAt,
                previous.lastSuccessAt(),
                safeMessage(error),
                current.songs().size());
        return new RefreshResult(false, message, syncStatus);
    }

    private String fetchUtf8(URI endpoint, String context)
            throws IOException, InterruptedException {
        requireTrustedEndpoint(endpoint);
        byte[] body = remoteFetcher.fetch(endpoint);
        if (body == null) {
            throw new IOException(context + " has no body");
        }
        if (body.length > MAX_REMOTE_BYTES) {
            throw new IOException(context + " exceeds 2 MiB");
        }
        return decodeUtf8(body, context);
    }

    private static Dataset buildDataset(
            String divingFishJson,
            String lxnsSongJson,
            String lxnsAliasJson,
            Map<String, String> localCovers,
            String source,
            Instant generatedAt,
            List<Source> sources) {
        CatalogBuilder builder = new CatalogBuilder(localCovers);
        builder.mergeDivingFish(divingFishJson);
        if (lxnsSongJson != null || lxnsAliasJson != null) {
            if (lxnsSongJson == null || lxnsAliasJson == null) {
                throw new IllegalArgumentException(
                        "LXNS song and alias data must be supplied together");
            }
            builder.mergeLxnsSongs(lxnsSongJson);
            builder.mergeLxnsAliases(lxnsAliasJson);
        }
        List<Song> songs = builder.finish();
        if (songs.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("Merged maimai catalogue is too large");
        }
        return new Dataset(source, generatedAt, sources, songs);
    }

    private static Dataset parseCache(String json) {
        Map<?, ?> root = requireObject(Json.parse(json), "Maimai catalogue cache");
        int schemaVersion = requiredInteger(
                root.get("schemaVersion"), "Maimai catalogue cache schemaVersion");
        if (schemaVersion != CACHE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported maimai catalogue cache schemaVersion");
        }
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(requiredText(
                    root.get("generatedAt"),
                    "Maimai catalogue cache generatedAt",
                    false));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(
                    "Maimai catalogue cache generatedAt is invalid", error);
        }
        List<?> rawSources = requireArray(
                root.get("sources"), "Maimai catalogue cache sources");
        List<Source> sources = new ArrayList<>(rawSources.size());
        for (int index = 0; index < rawSources.size(); index++) {
            Map<?, ?> value = requireObject(
                    rawSources.get(index), "Maimai cache source at index " + index);
            sources.add(new Source(
                    requiredText(value.get("name"), "source name", false),
                    requiredText(value.get("url"), "source url", false)));
        }
        List<?> rawSongs = requireArray(
                root.get("songs"), "Maimai catalogue cache songs");
        if (rawSongs.isEmpty() || rawSongs.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException(
                    "Maimai catalogue cache has an invalid song count");
        }
        List<Song> songs = new ArrayList<>(rawSongs.size());
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < rawSongs.size(); index++) {
            String context = "Maimai cache song at index " + index;
            Map<?, ?> value = requireObject(rawSongs.get(index), context);
            String songId = canonicalSongId(requiredText(
                    value.get("songId"), context + " songId", false));
            if (!ids.add(songId)) {
                throw new IllegalArgumentException(context + " duplicates SongID " + songId);
            }
            List<String> aliases = parseStringList(
                    value.get("aliases"), context + " aliases", MAX_ALIASES_PER_SONG);
            List<?> rawCharts = requireArray(value.get("charts"), context + " charts");
            if (rawCharts.isEmpty() || rawCharts.size() > MAX_CHARTS_PER_SONG) {
                throw new IllegalArgumentException(context + " has invalid charts");
            }
            List<Chart> charts = new ArrayList<>(rawCharts.size());
            Set<ChartKey> chartKeys = new HashSet<>();
            for (int chartIndex = 0; chartIndex < rawCharts.size(); chartIndex++) {
                String chartContext = context + " chart at index " + chartIndex;
                Map<?, ?> chart = requireObject(
                        rawCharts.get(chartIndex), chartContext);
                String chartType = canonicalChartType(requiredText(
                        chart.get("chartType"), chartContext + " chartType", false));
                int difficultyIndex = requiredInteger(
                        chart.get("difficultyIndex"), chartContext + " difficultyIndex");
                if (difficultyIndex < 0 || difficultyIndex >= DIFFICULTIES.length) {
                    throw new IllegalArgumentException(
                            chartContext + " has invalid difficultyIndex");
                }
                String difficulty = canonicalDifficulty(requiredText(
                        chart.get("difficulty"), chartContext + " difficulty", false));
                if (!difficulty.equals(DIFFICULTIES[difficultyIndex])) {
                    throw new IllegalArgumentException(
                            chartContext + " difficulty does not match its index");
                }
                ChartKey key = new ChartKey(chartType, difficultyIndex);
                if (!chartKeys.add(key)) {
                    throw new IllegalArgumentException(chartContext + " is duplicated");
                }
                String poolVersion = requiredText(
                        chart.get("version"), chartContext + " version", false)
                        .toLowerCase(Locale.ROOT);
                if (!poolVersion.equals("current") && !poolVersion.equals("legacy")) {
                    throw new IllegalArgumentException(
                            chartContext + " version must be current or legacy");
                }
                Integer tap = optionalInteger(chart.get("tap"), chartContext + " tap");
                Integer hold = optionalInteger(chart.get("hold"), chartContext + " hold");
                Integer slide = optionalInteger(chart.get("slide"), chartContext + " slide");
                Integer touch = optionalInteger(chart.get("touch"), chartContext + " touch");
                Integer breakNotes = optionalInteger(
                        chart.get("break"), chartContext + " break");
                Integer total = optionalInteger(chart.get("total"), chartContext + " total");
                validateNoteTuple(
                        tap, hold, slide, touch, breakNotes, total, chartContext);
                charts.add(new Chart(
                        chartType,
                        difficulty,
                        difficultyIndex,
                        requiredText(chart.get("level"), chartContext + " level", false),
                        requiredDecimal(
                                chart.get("levelValue"), chartContext + " levelValue"),
                        poolVersion,
                        requiredText(
                                chart.get("releaseVersion"),
                                chartContext + " releaseVersion",
                                true),
                        tap,
                        hold,
                        slide,
                        touch,
                        breakNotes,
                        total,
                        optionalText(chart.get("charter"), chartContext + " charter", true)));
            }
            charts.sort(Chart.ORDER);
            String persistedCover = requiredText(
                    value.get("coverUrl"), context + " coverUrl", false);
            String persistedIdSource = optionalText(
                    value.get("idSource"), context + " idSource", true);
            // Migrate schema-v1 caches written before SongID-addressed jackets
            // became authoritative.  This repairs stale title-only mappings on
            // the first restart without rewriting user scores or hard-coding a song.
            boolean lxnsBacked = persistedIdSource.equals("lxns+diving-fish");
            songs.add(new Song(
                    songId,
                    requiredText(value.get("title"), context + " title", true),
                    aliases,
                    requiredText(value.get("artist"), context + " artist", true),
                    requiredText(value.get("genre"), context + " genre", true),
                    requiredText(value.get("version"), context + " version", true),
                    optionalBoolean(value.get("isNew"), context + " isNew"),
                    lxnsBacked ? remoteCoverUrl(songId) : persistedCover,
                    optionalDecimal(value.get("bpm"), context + " bpm", BigDecimal.ZERO),
                    charts,
                    persistedIdSource.isEmpty() ? "cache" : persistedIdSource));
        }
        songs.sort(Song.ORDER);
        return new Dataset("cache", generatedAt, sources, songs);
    }

    private static String cacheJson(Dataset dataset) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", CACHE_SCHEMA_VERSION);
        root.put("generatedAt", dataset.generatedAt().toString());
        List<Map<String, Object>> sourceValues = new ArrayList<>();
        for (Source source : dataset.sources()) {
            sourceValues.add(source.toJsonValue());
        }
        root.put("sources", sourceValues);
        List<Map<String, Object>> songValues = new ArrayList<>();
        for (Song song : dataset.songs()) {
            songValues.add(song.toJsonValue());
        }
        root.put("songs", songValues);
        return Json.stringify(root);
    }

    static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(bytes, "bytes");
        Path absolute = target.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            throw new IOException("Maimai cache path has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(
                parent, absolute.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        absolute,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                throw new IOException(
                        "Atomic cache replacement is not supported by this filesystem",
                        error);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static Map<String, String> parseLocalCovers(String json) {
        return parseLocalCovers(json, null);
    }

    private static Map<String, String> parseLocalCovers(
            String json, Path coverDirectory) {
        List<?> songs = requireArray(Json.parse(json), "Local song catalogue");
        if (songs.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("Local song catalogue is too large");
        }
        Map<String, String> candidates = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (int index = 0; index < songs.size(); index++) {
            Map<?, ?> song = requireObject(
                    songs.get(index), "Local song at index " + index);
            String title = requiredText(
                    song.get("title"), "Local song title", true);
            String image = requiredText(
                    song.get("image_file"), "Local song image_file", false);
            String key = compactKey(title);
            if (key.isEmpty()) {
                continue;
            }
            String cover = localCoverUrl(image);
            if (coverDirectory != null
                    && !isValidLocalCover(coverDirectory, image)) {
                continue;
            }
            String previous = candidates.putIfAbsent(key, cover);
            if (previous != null && !previous.equals(cover)) {
                ambiguous.add(key);
            }
        }
        ambiguous.forEach(candidates::remove);
        return Map.copyOf(candidates);
    }

    private static boolean isValidLocalCover(Path coverDirectory, String imageFile) {
        try {
            Path realDirectory = coverDirectory.toAbsolutePath().normalize().toRealPath();
            Path candidate = realDirectory.resolve(imageFile).normalize();
            if (!candidate.getParent().equals(realDirectory)
                    || !Files.isRegularFile(candidate)
                    || !candidate.toRealPath().startsWith(realDirectory)) {
                return false;
            }
            long size = Files.size(candidate);
            if (size < PNG_SIGNATURE.length || size > MAX_LOCAL_COVER_BYTES) {
                return false;
            }
            byte[] signature = new byte[PNG_SIGNATURE.length];
            try (InputStream input = Files.newInputStream(candidate)) {
                if (input.readNBytes(signature, 0, signature.length) != signature.length) {
                    return false;
                }
            }
            return java.util.Arrays.equals(PNG_SIGNATURE, signature);
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static String localCoverUrl(String imageFile) {
        if (imageFile.indexOf('/') >= 0 || imageFile.indexOf('\\') >= 0
                || imageFile.equals(".") || imageFile.equals("..")) {
            throw new IllegalArgumentException("image_file must be a file name");
        }
        byte[] bytes = imageFile.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte value : bytes) {
            int unsigned = Byte.toUnsignedInt(value);
            boolean unreserved = unsigned >= 'a' && unsigned <= 'z'
                    || unsigned >= 'A' && unsigned <= 'Z'
                    || unsigned >= '0' && unsigned <= '9'
                    || unsigned == '-' || unsigned == '_'
                    || unsigned == '.' || unsigned == '~';
            if (unreserved) {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%')
                        .append(hex[(unsigned >>> 4) & 0xf])
                        .append(hex[unsigned & 0xf]);
            }
        }
        return "/song-catalog/cover/" + encoded;
    }

    private static String remoteCoverUrl(String songId) {
        return "/api/maimai/covers/" + songId + ".png";
    }

    private static int matchScore(
            Song song, String needle, String compactNeedle) {
        int titleScore = titleMatchScore(song, needle, compactNeedle);
        if (titleScore >= 0) {
            return titleScore;
        }
        String id = normalize(song.songId());
        if (id.equals(needle)) {
            return 0;
        }
        String artist = normalize(song.artist());
        if (artist.startsWith(needle)) {
            return 20;
        }
        if (artist.contains(needle)
                || normalize(song.genre()).contains(needle)
                || normalize(song.version()).contains(needle)
                || id.contains(needle)) {
            return 21;
        }
        for (Chart chart : song.charts()) {
            if (normalize(chart.chartType()).contains(needle)
                    || normalize(chart.difficulty()).contains(needle)
                    || normalize(chart.level()).contains(needle)
                    || normalize(chart.releaseVersion()).contains(needle)) {
                return 21;
            }
        }
        return -1;
    }

    private static int titleMatchScore(
            Song song, String needle, String compactNeedle) {
        String title = normalize(song.title());
        List<String> aliases = song.aliases().stream()
                .map(SongCatalog::normalize)
                .toList();
        if (title.equals(needle)
                || aliases.stream().anyMatch(alias -> alias.equals(needle))) {
            return 0;
        }
        String compactTitle = compactKey(song.title());
        List<String> compactAliases = song.aliases().stream()
                .map(SongCatalog::compactKey)
                .filter(alias -> !alias.isEmpty())
                .toList();
        if (!compactNeedle.isEmpty()
                && (compactTitle.equals(compactNeedle)
                || compactAliases.stream().anyMatch(
                        alias -> alias.equals(compactNeedle)))) {
            return 1;
        }
        if (needle.codePointCount(0, needle.length()) >= 2
                && (title.startsWith(needle)
                || aliases.stream().anyMatch(alias -> alias.startsWith(needle)))) {
            return 2;
        }
        if (compactNeedle.codePointCount(0, compactNeedle.length()) >= 4
                && (compactTitle.contains(compactNeedle)
                || compactAliases.stream().anyMatch(
                        alias -> alias.contains(compactNeedle)))) {
            return 3;
        }
        int queryLength = compactNeedle.codePointCount(
                0, compactNeedle.length());
        if (queryLength < MIN_FUZZY_CODE_POINTS) {
            return -1;
        }
        int limit = queryLength <= 12 ? 1 : queryLength <= 24 ? 2 : 3;
        int distance = boundedEditDistance(compactNeedle, compactTitle, limit);
        for (String alias : compactAliases) {
            distance = Math.min(
                    distance, boundedEditDistance(compactNeedle, alias, limit));
        }
        return distance <= limit ? 10 + distance : -1;
    }

    private static int boundedEditDistance(String left, String right, int limit) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        if (Math.abs(leftPoints.length - rightPoints.length) > limit) {
            return limit + 1;
        }
        int[] previous = new int[rightPoints.length + 1];
        int[] current = new int[rightPoints.length + 1];
        for (int index = 0; index <= rightPoints.length; index++) {
            previous[index] = index;
        }
        for (int row = 1; row <= leftPoints.length; row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= rightPoints.length; column++) {
                int substitution = previous[column - 1]
                        + (leftPoints[row - 1] == rightPoints[column - 1] ? 0 : 1);
                current[column] = Math.min(
                        Math.min(previous[column] + 1, current[column - 1] + 1),
                        substitution);
                rowMinimum = Math.min(rowMinimum, current[column]);
            }
            if (rowMinimum > limit) {
                return limit + 1;
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[rightPoints.length];
    }

    static String normalize(String value) {
        String normalized = Normalizer.normalize(
                value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = output.length() > 0;
            } else {
                if (pendingSpace) {
                    output.append(' ');
                    pendingSpace = false;
                }
                output.appendCodePoint(codePoint);
            }
        }
        return output.toString();
    }

    static String compactKey(String value) {
        String normalized = normalize(value);
        StringBuilder output = new StringBuilder(normalized.length());
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                output.appendCodePoint(codePoint);
            }
        }
        return output.toString();
    }

    private static String canonicalChartType(String value) {
        String normalized = normalize(value).replace(" ", "");
        return switch (normalized) {
            case "sd", "std", "standard", "标准" -> "STD";
            case "dx", "deluxe", "豪华" -> "DX";
            default -> throw new IllegalArgumentException("Unknown chartType");
        };
    }

    private static String canonicalDifficulty(String value) {
        String normalized = normalize(value).replace(" ", "");
        return switch (normalized) {
            case "basic", "bas", "绿", "绿色" -> "BASIC";
            case "advanced", "adv", "黄", "黄色" -> "ADVANCED";
            case "expert", "exp", "红", "红色" -> "EXPERT";
            case "master", "mas", "紫", "紫色" -> "MASTER";
            case "re:master", "remaster", "re-master", "remas", "白",
                    "白紫", "白色" -> "RE:MASTER";
            default -> throw new IllegalArgumentException("Unknown difficulty");
        };
    }

    private static void validateSearch(String query, int limit) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        if (query.length() > 120) {
            throw new IllegalArgumentException("q is too long");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static CatalogResult result(Dataset dataset, String warning) {
        return new CatalogResult(
                dataset.source(), warning, dataset.generatedAt(), dataset.songs());
    }

    private static List<Source> onlineSources() {
        return List.of(
                new Source("diving-fish", DIVING_FISH_ENDPOINT.toString()),
                new Source("lxns-songs", LXNS_SONG_ENDPOINT.toString()),
                new Source("lxns-aliases", LXNS_ALIAS_ENDPOINT.toString()),
                new Source("lxns-covers", LXNS_COVER_BASE.toString()));
    }

    private static String readLimitedUtf8(Path path, int maxBytes, String context)
            throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException(context + " exceeds its size limit");
        }
        return decodeUtf8(Files.readAllBytes(path), context);
    }

    private static String decodeUtf8(byte[] bytes, String context) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException(context + " is not valid UTF-8", error);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    private static void requireTrustedEndpoint(URI endpoint) throws IOException {
        boolean trusted = endpoint.equals(DIVING_FISH_ENDPOINT)
                || endpoint.equals(LXNS_SONG_ENDPOINT)
                || endpoint.equals(LXNS_ALIAS_ENDPOINT);
        if (!trusted || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IOException("Remote maimai endpoint is not trusted");
        }
    }

    /** Remote-fetch seam used by dependency-free tests. */
    @FunctionalInterface
    interface RemoteFetcher {
        byte[] fetch(URI endpoint) throws IOException, InterruptedException;
    }

    /** Atomic-write seam used to prove last-known-good behavior in tests. */
    @FunctionalInterface
    interface CacheWriter {
        void write(Path path, byte[] bytes) throws IOException;
    }

    static HttpRequest buildRemoteRequest(URI endpoint) throws IOException {
        requireTrustedEndpoint(endpoint);
        return HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "maimai-rating-calc/1.0")
                .GET()
                .build();
    }

    static HttpResponse.BodyHandler<byte[]> limitedRemoteBodyHandler() {
        return HttpResponse.BodyHandlers.limiting(
                HttpResponse.BodyHandlers.ofByteArray(), MAX_REMOTE_BYTES);
    }

    private static final class HttpRemoteFetcher implements RemoteFetcher {
        private volatile HttpClient client;

        @Override
        public byte[] fetch(URI endpoint) throws IOException, InterruptedException {
            HttpResponse<byte[]> response = client().send(
                    buildRemoteRequest(endpoint), limitedRemoteBodyHandler());
            if (response.statusCode() != 200) {
                throw new IOException(
                        "Remote maimai service returned HTTP "
                                + response.statusCode());
            }
            long declaredLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (declaredLength > MAX_REMOTE_BYTES) {
                throw new IOException("Remote maimai response exceeds 2 MiB");
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.isEmpty()
                    && !contentType.startsWith("application/json")) {
                throw new IOException("Remote maimai service did not return JSON");
            }
            return response.body();
        }

        private HttpClient client() {
            HttpClient currentClient = client;
            if (currentClient != null) {
                return currentClient;
            }
            synchronized (this) {
                currentClient = client;
                if (currentClient == null) {
                    currentClient = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    client = currentClient;
                }
                return currentClient;
            }
        }
    }

    /** One STD or DX difficulty belonging to a base SongID. */
    public record Chart(
            String chartType,
            String difficulty,
            int difficultyIndex,
            String level,
            BigDecimal levelValue,
            String version,
            String releaseVersion,
            Integer tap,
            Integer hold,
            Integer slide,
            Integer touch,
            Integer breakNotes,
            Integer total,
            String charter) {
        private static final Comparator<Chart> ORDER = Comparator
                .comparingInt((Chart chart) -> chart.chartType().equals("STD") ? 0 : 1)
                .thenComparingInt(Chart::difficultyIndex);

        public Chart {
            Objects.requireNonNull(chartType, "chartType");
            Objects.requireNonNull(difficulty, "difficulty");
            Objects.requireNonNull(level, "level");
            Objects.requireNonNull(levelValue, "levelValue");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(releaseVersion, "releaseVersion");
            if (difficultyIndex < 0 || difficultyIndex >= DIFFICULTIES.length) {
                throw new IllegalArgumentException("difficultyIndex is invalid");
            }
        }

        /** Compatibility name retained for the original front end. */
        public String displayLevel() {
            return level;
        }

        /** Compatibility name retained for the original front end. */
        public BigDecimal constant() {
            return levelValue;
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("chartType", chartType);
            value.put("difficulty", difficulty);
            value.put("difficultyIndex", difficultyIndex);
            value.put("level", level);
            value.put("displayLevel", level);
            value.put("levelValue", levelValue);
            value.put("constant", levelValue);
            value.put("version", version);
            value.put("releaseVersion", releaseVersion);
            value.put("tap", tap);
            value.put("hold", hold);
            value.put("slide", slide);
            value.put("touch", touch);
            value.put("break", breakNotes);
            value.put("total", total);
            value.put("charter", charter);
            return value;
        }
    }

    /** One base SongID with all of its STD and DX charts. */
    public record Song(
            String songId,
            String title,
            List<String> aliases,
            String artist,
            String genre,
            String version,
            boolean isNew,
            String coverUrl,
            BigDecimal bpm,
            List<Chart> charts,
            String idSource) {
        private static final Comparator<Song> ORDER = Comparator
                .comparingInt((Song song) -> Integer.parseInt(song.songId()))
                .thenComparing(song -> normalize(song.title()));

        public Song {
            Objects.requireNonNull(songId, "songId");
            Objects.requireNonNull(title, "title");
            aliases = List.copyOf(aliases);
            Objects.requireNonNull(artist, "artist");
            Objects.requireNonNull(genre, "genre");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(coverUrl, "coverUrl");
            Objects.requireNonNull(bpm, "bpm");
            charts = List.copyOf(charts);
            Objects.requireNonNull(idSource, "idSource");
        }

        /** Compatibility alias for the former local catalogue field. */
        public String category() {
            return genre;
        }

        /** Returns STD, DX, or MIXED for compatibility with older callers. */
        public String chartType() {
            boolean std = charts.stream().anyMatch(chart -> chart.chartType().equals("STD"));
            boolean dx = charts.stream().anyMatch(chart -> chart.chartType().equals("DX"));
            return std && dx ? "MIXED" : std ? "STD" : "DX";
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", songId);
            value.put("id", songId);
            value.put("title", title);
            value.put("aliases", aliases);
            value.put("artist", artist);
            value.put("genre", genre);
            value.put("category", genre);
            value.put("version", version);
            value.put("isNew", isNew);
            value.put("coverUrl", coverUrl);
            value.put("bpm", bpm);
            value.put("chartType", chartType());
            List<Map<String, Object>> chartValues = new ArrayList<>();
            for (Chart chart : charts) {
                chartValues.add(chart.toJsonValue());
            }
            value.put("charts", chartValues);
            value.put("idSource", idSource);
            return value;
        }
    }

    /** Endpoint-ready result envelope with persistent refresh provenance. */
    public record CatalogResult(
            String source,
            String warning,
            Instant generatedAt,
            List<Song> songs) {
        public CatalogResult {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(generatedAt, "generatedAt");
            songs = List.copyOf(songs);
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", source);
            value.put("warning", warning);
            value.put("generatedAt", generatedAt.toString());
            value.put("count", songs.size());
            List<Map<String, Object>> songValues = new ArrayList<>();
            for (Song song : songs) {
                songValues.add(song.toJsonValue());
            }
            value.put("songs", songValues);
            return Json.stringify(value);
        }
    }

    /** Current synchronization state for health/status endpoints and CLI output. */
    public record SyncStatus(
            String state,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String error,
            int songCount) {
        public SyncStatus {
            Objects.requireNonNull(state, "state");
            if (songCount < 0) {
                throw new IllegalArgumentException("songCount must not be negative");
            }
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("state", state);
            value.put("lastAttemptAt", lastAttemptAt == null
                    ? null : lastAttemptAt.toString());
            value.put("lastSuccessAt", lastSuccessAt == null
                    ? null : lastSuccessAt.toString());
            value.put("error", error);
            value.put("songCount", songCount);
            return Json.stringify(value);
        }
    }

    /** Result returned by manual and scheduled refreshes. */
    public record RefreshResult(boolean success, String message, SyncStatus status) {
        public RefreshResult {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(status, "status");
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("message", message);
            value.put("status", Json.parse(status.toJson()));
            return Json.stringify(value);
        }
    }

    private static final class CatalogBuilder {
        private final Map<String, MutableSong> songs = new LinkedHashMap<>();
        private final Map<String, String> localCovers;
        private final Set<String> acceptedLxnsRawSongIds = new HashSet<>();

        private CatalogBuilder(Map<String, String> localCovers) {
            this.localCovers = localCovers;
        }

        private void mergeDivingFish(String json) {
            List<?> rawSongs = requireArray(
                    Json.parse(json), "Diving-Fish maimai data");
            if (rawSongs.isEmpty() || rawSongs.size() > MAX_CATALOG_ENTRIES * 2) {
                throw new IllegalArgumentException(
                        "Diving-Fish maimai data has an invalid song count");
            }
            for (int index = 0; index < rawSongs.size(); index++) {
                String context = "Diving-Fish song at index " + index;
                Map<?, ?> value = requireObject(rawSongs.get(index), context);
                Map<?, ?> basic = requireObject(
                        value.get("basic_info"), context + " basic_info");
                String genre = requiredText(
                        basic.get("genre"), context + " genre", false);
                if (normalize(genre).equals(normalize("宴会場"))) {
                    continue;
                }
                String rawId = requiredNumericText(value.get("id"), context + " id");
                String songId = canonicalSongId(rawId);
                String chartType = canonicalChartType(requiredText(
                        value.get("type"), context + " type", false));
                String title = requiredText(
                        value.get("title"), context + " title", true);
                String artist = optionalText(
                        basic.get("artist"), context + " artist", true);
                String releaseVersion = optionalText(
                        basic.get("from"), context + " version", true);
                BigDecimal bpm = optionalDecimal(
                        basic.get("bpm"), context + " bpm", BigDecimal.ZERO);
                boolean isNew = optionalBoolean(
                        basic.get("is_new"), context + " is_new");
                MutableSong song = songs.computeIfAbsent(
                        songId, ignored -> new MutableSong(songId));
                song.mergeMetadata(
                        title, artist, genre, releaseVersion, bpm, isNew, false);

                List<?> constants = requireArray(value.get("ds"), context + " ds");
                List<?> levels = requireArray(value.get("level"), context + " level");
                if (constants.size() < 4 || constants.size() > 5
                        || levels.size() != constants.size()) {
                    throw new IllegalArgumentException(context + " has invalid chart arrays");
                }
                List<?> rawCharts = optionalArray(value.get("charts"), context + " charts");
                if (rawCharts != null && rawCharts.size() != constants.size()) {
                    throw new IllegalArgumentException(context + " has invalid charts array");
                }
                for (int difficulty = 0; difficulty < constants.size(); difficulty++) {
                    String chartContext = context + " chart at index " + difficulty;
                    BigDecimal levelValue = requiredDecimal(
                            constants.get(difficulty), chartContext + " constant");
                    String level = requiredText(
                            levels.get(difficulty), chartContext + " level", false);
                    NoteCounts notes = rawCharts == null
                            ? NoteCounts.empty()
                            : parseDivingFishNotes(
                                    requireObject(rawCharts.get(difficulty), chartContext),
                                    chartType,
                                    chartContext);
                    String charter = rawCharts == null
                            ? ""
                            : optionalText(
                                    requireObject(rawCharts.get(difficulty), chartContext)
                                            .get("charter"),
                                    chartContext + " charter",
                                    true);
                    MutableChart chart = new MutableChart(
                            chartType,
                            difficulty,
                            level,
                            levelValue,
                            isNew ? "current" : "legacy",
                            releaseVersion,
                            notes,
                            charter);
                    song.mergeChart(chart, false, chartContext);
                }
            }
        }

        private void mergeLxnsSongs(String json) {
            Map<?, ?> root = requireObject(Json.parse(json), "LXNS maimai song data");
            List<?> versions = requireArray(root.get("versions"), "LXNS versions");
            NavigableMap<Integer, String> versionNames = new TreeMap<>();
            for (int index = 0; index < versions.size(); index++) {
                Map<?, ?> version = requireObject(
                        versions.get(index), "LXNS version at index " + index);
                int code = requiredInteger(
                        version.get("version"), "LXNS version code");
                String title = requiredText(
                        version.get("title"), "LXNS version title", false);
                if (versionNames.put(code, title) != null) {
                    throw new IllegalArgumentException("LXNS has duplicate version code");
                }
            }
            if (versionNames.isEmpty()) {
                throw new IllegalArgumentException("LXNS version list is empty");
            }
            int currentVersionCode = versionNames.lastKey();
            List<?> rawSongs = requireArray(root.get("songs"), "LXNS maimai songs");
            if (rawSongs.isEmpty() || rawSongs.size() > MAX_CATALOG_ENTRIES) {
                throw new IllegalArgumentException("LXNS maimai song count is invalid");
            }
            Set<String> lxnsIds = new HashSet<>();
            for (int index = 0; index < rawSongs.size(); index++) {
                String context = "LXNS song at index " + index;
                Map<?, ?> value = requireObject(rawSongs.get(index), context);
                String genre = requiredText(
                        value.get("genre"), context + " genre", true);
                // LXNS includes UTAGE rows in the same response. Their IDs use
                // a different high-offset namespace which would collide after
                // the normal DX %10000 rule, so reject them before canonicalizing.
                if (normalize(genre).equals(normalize("宴会場"))) {
                    continue;
                }
                String rawSongId = normalizedNumericText(
                        value.get("id"), context + " id");
                acceptedLxnsRawSongIds.add(rawSongId);
                String songId = canonicalSongId(rawSongId);
                if (!lxnsIds.add(songId)) {
                    throw new IllegalArgumentException(
                            context + " duplicates base SongID " + songId);
                }
                int songVersionCode = requiredInteger(
                        value.get("version"), context + " version");
                String releaseVersion = versionName(versionNames, songVersionCode);
                MutableSong song = songs.computeIfAbsent(
                        songId, ignored -> new MutableSong(songId));
                song.mergeMetadata(
                        requiredText(value.get("title"), context + " title", true),
                        requiredText(value.get("artist"), context + " artist", true),
                        genre,
                        releaseVersion,
                        requiredDecimal(value.get("bpm"), context + " bpm"),
                        songVersionCode >= currentVersionCode,
                        true);

                Map<?, ?> difficulties = requireObject(
                        value.get("difficulties"), context + " difficulties");
                mergeLxnsCharts(
                        song,
                        optionalArray(difficulties.get("standard"),
                                context + " standard"),
                        "STD",
                        versionNames,
                        currentVersionCode,
                        context);
                mergeLxnsCharts(
                        song,
                        optionalArray(difficulties.get("dx"), context + " dx"),
                        "DX",
                        versionNames,
                        currentVersionCode,
                        context);
                if (song.charts.isEmpty()) {
                    throw new IllegalArgumentException(context + " has no charts");
                }
            }
        }

        private void mergeLxnsCharts(
                MutableSong song,
                List<?> charts,
                String expectedType,
                NavigableMap<Integer, String> versionNames,
                int currentVersionCode,
                String songContext) {
            if (charts == null) {
                return;
            }
            if (charts.size() > DIFFICULTIES.length) {
                throw new IllegalArgumentException(songContext + " has too many charts");
            }
            Set<Integer> difficulties = new HashSet<>();
            for (int index = 0; index < charts.size(); index++) {
                String context = songContext + " " + expectedType
                        + " chart at index " + index;
                Map<?, ?> value = requireObject(charts.get(index), context);
                String actualType = canonicalChartType(requiredText(
                        value.get("type"), context + " type", false));
                if (!actualType.equals(expectedType)) {
                    throw new IllegalArgumentException(context + " type is inconsistent");
                }
                int difficulty = requiredInteger(
                        value.get("difficulty"), context + " difficulty");
                if (difficulty < 0 || difficulty >= DIFFICULTIES.length
                        || !difficulties.add(difficulty)) {
                    throw new IllegalArgumentException(context + " difficulty is invalid");
                }
                int versionCode = requiredInteger(
                        value.get("version"), context + " version");
                MutableChart chart = new MutableChart(
                        expectedType,
                        difficulty,
                        requiredText(value.get("level"), context + " level", false),
                        requiredDecimal(
                                value.get("level_value"), context + " level_value"),
                        versionCode >= currentVersionCode ? "current" : "legacy",
                        versionName(versionNames, versionCode),
                        NoteCounts.empty(),
                        optionalText(
                                value.get("note_designer"),
                                context + " note_designer",
                                true));
                song.mergeChart(chart, true, context);
            }
        }

        private void mergeLxnsAliases(String json) {
            Map<?, ?> root = requireObject(Json.parse(json), "LXNS maimai alias data");
            List<?> aliases = requireArray(root.get("aliases"), "LXNS aliases");
            if (aliases.size() > MAX_CATALOG_ENTRIES) {
                throw new IllegalArgumentException("LXNS alias data is too large");
            }
            Set<String> ids = new HashSet<>();
            for (int index = 0; index < aliases.size(); index++) {
                String context = "LXNS alias at index " + index;
                Map<?, ?> value = requireObject(aliases.get(index), context);
                String rawSongId = normalizedNumericText(
                        value.get("song_id"), context + " song_id");
                // Alias rows for excluded UTAGE songs must never be folded onto
                // the regular song that happens to share their low four digits.
                if (!acceptedLxnsRawSongIds.contains(rawSongId)) {
                    continue;
                }
                String songId = canonicalSongId(rawSongId);
                if (!ids.add(songId)) {
                    throw new IllegalArgumentException(context + " duplicates SongID");
                }
                MutableSong song = songs.get(songId);
                if (song == null) {
                    continue;
                }
                List<String> parsed = parseStringList(
                        value.get("aliases"),
                        context + " aliases",
                        MAX_ALIASES_PER_SONG);
                song.aliases.clear();
                for (String alias : parsed) {
                    if (!normalize(alias).equals(normalize(song.title))) {
                        song.aliases.add(alias);
                    }
                }
            }
        }

        private List<Song> finish() {
            List<Song> result = new ArrayList<>(songs.size());
            for (MutableSong mutable : songs.values()) {
                if (mutable.title == null || mutable.charts.isEmpty()) {
                    continue;
                }
                List<Chart> charts = mutable.charts.values().stream()
                        .map(MutableChart::freeze)
                        .sorted(Chart.ORDER)
                        .toList();
                // LXNS jackets are keyed by the canonical base SongID, so they remain
                // unambiguous when a newly released chart reuses an older song title.
                // The bundled catalogue has no SongID for its image rows and can only
                // associate them by title; keep that best-effort fallback exclusively
                // for Diving-Fish-only rows.  Preferring a title-only image for an
                // LXNS-enriched row can silently attach an old or censored jacket to a
                // different song that happens to share the same title.
                String localCover = mutable.lxnsMetadata
                        ? null
                        : localCovers.get(compactKey(mutable.title));
                String cover = localCover == null
                        ? remoteCoverUrl(mutable.songId)
                        : localCover;
                result.add(new Song(
                        mutable.songId,
                        mutable.title,
                        List.copyOf(mutable.aliases),
                        mutable.artist == null ? "" : mutable.artist,
                        mutable.genre == null ? "" : mutable.genre,
                        mutable.releaseVersion == null ? "" : mutable.releaseVersion,
                        mutable.isNew,
                        cover,
                        mutable.bpm == null ? BigDecimal.ZERO : mutable.bpm,
                        charts,
                        mutable.lxnsMetadata ? "lxns+diving-fish" : "diving-fish"));
            }
            result.sort(Song.ORDER);
            Set<String> ids = new HashSet<>();
            for (Song song : result) {
                if (!ids.add(song.songId())) {
                    throw new IllegalArgumentException(
                            "Merged catalogue contains duplicate SongID "
                                    + song.songId());
                }
            }
            return List.copyOf(result);
        }
    }

    private static final class MutableSong {
        private final String songId;
        private final Map<ChartKey, MutableChart> charts = new LinkedHashMap<>();
        private final LinkedHashSet<String> aliases = new LinkedHashSet<>();
        private String title;
        private String artist;
        private String genre;
        private String releaseVersion;
        private BigDecimal bpm;
        private boolean isNew;
        private boolean lxnsMetadata;

        private MutableSong(String songId) {
            this.songId = songId;
        }

        private void mergeMetadata(
                String newTitle,
                String newArtist,
                String newGenre,
                String newReleaseVersion,
                BigDecimal newBpm,
                boolean newIsNew,
                boolean lxns) {
            if (title == null || lxns) {
                title = newTitle;
                artist = newArtist;
                genre = newGenre;
                releaseVersion = newReleaseVersion;
                bpm = newBpm;
                isNew = newIsNew;
            } else {
                isNew |= newIsNew;
            }
            lxnsMetadata |= lxns;
        }

        private void mergeChart(
                MutableChart incoming,
                boolean lxns,
                String context) {
            ChartKey key = new ChartKey(
                    incoming.chartType, incoming.difficultyIndex);
            MutableChart existing = charts.get(key);
            if (existing == null) {
                charts.put(key, incoming);
            } else if (lxns) {
                existing.applyLxns(incoming);
            } else {
                throw new IllegalArgumentException(context + " duplicates a chart");
            }
        }
    }

    private static final class MutableChart {
        private final String chartType;
        private final int difficultyIndex;
        private String level;
        private BigDecimal levelValue;
        private String poolVersion;
        private String releaseVersion;
        private NoteCounts notes;
        private String charter;

        private MutableChart(
                String chartType,
                int difficultyIndex,
                String level,
                BigDecimal levelValue,
                String poolVersion,
                String releaseVersion,
                NoteCounts notes,
                String charter) {
            this.chartType = chartType;
            this.difficultyIndex = difficultyIndex;
            this.level = level;
            this.levelValue = levelValue;
            this.poolVersion = poolVersion;
            this.releaseVersion = releaseVersion;
            this.notes = notes;
            this.charter = charter;
        }

        private void applyLxns(MutableChart lxns) {
            level = lxns.level;
            levelValue = lxns.levelValue;
            poolVersion = lxns.poolVersion;
            releaseVersion = lxns.releaseVersion;
            if (lxns.charter != null && !lxns.charter.isBlank()) {
                charter = lxns.charter;
            }
        }

        private Chart freeze() {
            return new Chart(
                    chartType,
                    DIFFICULTIES[difficultyIndex],
                    difficultyIndex,
                    level,
                    levelValue,
                    poolVersion,
                    releaseVersion == null ? "" : releaseVersion,
                    notes.tap,
                    notes.hold,
                    notes.slide,
                    notes.touch,
                    notes.breakNotes,
                    notes.total,
                    charter == null ? "" : charter);
        }
    }

    private static NoteCounts parseDivingFishNotes(
            Map<?, ?> chart, String chartType, String context) {
        List<?> notes = optionalArray(chart.get("notes"), context + " notes");
        if (notes == null) {
            return NoteCounts.empty();
        }
        int expected = chartType.equals("DX") ? 5 : 4;
        if (notes.size() != expected) {
            throw new IllegalArgumentException(context + " has invalid note counts");
        }
        int tap = requiredInteger(notes.get(0), context + " tap");
        int hold = requiredInteger(notes.get(1), context + " hold");
        int slide = requiredInteger(notes.get(2), context + " slide");
        int touch = chartType.equals("DX")
                ? requiredInteger(notes.get(3), context + " touch")
                : 0;
        int breakNotes = requiredInteger(
                notes.get(chartType.equals("DX") ? 4 : 3), context + " break");
        if (tap < 0 || hold < 0 || slide < 0 || touch < 0 || breakNotes < 0) {
            throw new IllegalArgumentException(context + " has negative note counts");
        }
        int total;
        try {
            total = Math.addExact(
                    Math.addExact(Math.addExact(tap, hold), Math.addExact(slide, touch)),
                    breakNotes);
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(context + " note total overflows", error);
        }
        return new NoteCounts(tap, hold, slide, touch, breakNotes, total);
    }

    private static void validateNoteTuple(
            Integer tap,
            Integer hold,
            Integer slide,
            Integer touch,
            Integer breakNotes,
            Integer total,
            String context) {
        boolean allNull = tap == null && hold == null && slide == null
                && touch == null && breakNotes == null && total == null;
        boolean allPresent = tap != null && hold != null && slide != null
                && touch != null && breakNotes != null && total != null;
        if (!allNull && !allPresent) {
            throw new IllegalArgumentException(
                    context + " note counts must be all present or all null");
        }
        if (allPresent) {
            int expected;
            try {
                expected = Math.addExact(
                        Math.addExact(
                                Math.addExact(tap, hold), Math.addExact(slide, touch)),
                        breakNotes);
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(context + " note total overflows", error);
            }
            if (expected != total) {
                throw new IllegalArgumentException(context + " note total is inconsistent");
            }
        }
    }

    private static String versionName(
            NavigableMap<Integer, String> versions, int versionCode) {
        Map.Entry<Integer, String> match = versions.floorEntry(versionCode);
        if (match == null) {
            throw new IllegalArgumentException(
                    "LXNS chart references an unknown version code");
        }
        return match.getValue();
    }

    private static List<String> parseStringList(
            Object value, String context, int maximum) {
        List<?> raw = requireArray(value, context);
        if (raw.size() > maximum) {
            throw new IllegalArgumentException(context + " is too large");
        }
        List<String> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < raw.size(); index++) {
            String text = requiredText(
                    raw.get(index), context + " at index " + index, false);
            String key = normalize(text);
            if (seen.add(key)) {
                result.add(text);
            }
        }
        return List.copyOf(result);
    }

    private static Map<?, ?> requireObject(Object value, String context) {
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException(context + " must be a JSON object");
        }
        return object;
    }

    private static List<?> requireArray(Object value, String context) {
        if (!(value instanceof List<?> array)) {
            throw new IllegalArgumentException(context + " must be a JSON array");
        }
        return array;
    }

    private static List<?> optionalArray(Object value, String context) {
        return value == null ? null : requireArray(value, context);
    }

    private static String requiredText(Object value, String context, boolean allowBlank) {
        if (!(value instanceof String text)
                || (!allowBlank && text.isBlank())
                || text.length() > 2_000) {
            throw new IllegalArgumentException(context + " is invalid");
        }
        return text.trim();
    }

    private static String optionalText(Object value, String context, boolean allowBlank) {
        return value == null ? "" : requiredText(value, context, allowBlank);
    }

    private static String requiredNumericText(Object value, String context) {
        if (value instanceof String text) {
            return text.trim();
        }
        if (value instanceof BigDecimal number
                && number.stripTrailingZeros().scale() <= 0) {
            return number.toBigIntegerExact().toString();
        }
        throw new IllegalArgumentException(context + " is not an integer ID");
    }

    private static String normalizedNumericText(Object value, String context) {
        String text = requiredNumericText(value, context);
        if (!NUMERIC_ID.matcher(text).matches()) {
            throw new IllegalArgumentException(context + " is not a decimal ID");
        }
        return new BigInteger(text).toString();
    }

    private static BigDecimal requiredDecimal(Object value, String context) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(context + " must be a number");
        }
        return number;
    }

    private static BigDecimal optionalDecimal(
            Object value, String context, BigDecimal fallback) {
        return value == null ? fallback : requiredDecimal(value, context);
    }

    private static int requiredInteger(Object value, String context) {
        BigDecimal number = requiredDecimal(value, context);
        try {
            return number.intValueExact();
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(context + " must be an integer", error);
        }
    }

    private static Integer optionalInteger(Object value, String context) {
        if (value == null) {
            return null;
        }
        int number = requiredInteger(value, context);
        if (number < 0) {
            throw new IllegalArgumentException(context + " must not be negative");
        }
        return number;
    }

    private static boolean optionalBoolean(Object value, String context) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(context + " must be boolean");
    }

    private record Dataset(
            String source,
            Instant generatedAt,
            List<Source> sources,
            List<Song> songs) {
        private Dataset {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(generatedAt, "generatedAt");
            sources = List.copyOf(sources);
            songs = List.copyOf(songs);
        }
    }

    private record Source(String name, String url) {
        private Source {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(url, "url");
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("name", name);
            value.put("url", url);
            return value;
        }
    }

    private record ChartKey(String chartType, int difficultyIndex) {
    }

    private record NoteCounts(
            Integer tap,
            Integer hold,
            Integer slide,
            Integer touch,
            Integer breakNotes,
            Integer total) {
        private static NoteCounts empty() {
            return new NoteCounts(null, null, null, null, null, null);
        }
    }

    private record ScoredSong(Song song, int score, int originalIndex) {
    }
}
