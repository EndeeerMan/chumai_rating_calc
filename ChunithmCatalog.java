import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/**
 * Dependency-free CHUNITHM song catalogue backed by Diving-Fish snapshots.
 *
 * <p>The bundled snapshot keeps the site usable without a network connection.
 * Callers may opt in to a rate-limited online refresh. Both the song data and
 * latest-version metadata have to validate before refreshed data is exposed;
 * otherwise the complete bundled snapshot is returned.</p>
 */
public final class ChunithmCatalog {
    static final URI DIVING_FISH_MUSIC_ENDPOINT = URI.create(
            "https://www.diving-fish.com/api/chunithmprober/music_data");
    static final URI DIVING_FISH_VERSION_ENDPOINT = URI.create(
            "https://www.diving-fish.com/api/chunithmprober/latest_version");
    static final URI LXNS_SONG_ENDPOINT = URI.create(
            "https://maimai.lxns.net/api/v0/chunithm/song/list");
    static final URI LXNS_ALIAS_ENDPOINT = URI.create(
            "https://maimai.lxns.net/api/v0/chunithm/alias/list");
    static final int MAX_REMOTE_BYTES = 2 * 1024 * 1024;
    static final String DIVING_FISH_MUSIC_FILE =
            "diving-fish-music-data.json";
    static final String DIVING_FISH_VERSION_FILE =
            "diving-fish-latest-version.json";
    static final String LXNS_SONG_FILE = "lxns-song-list.json";
    static final String LXNS_ALIAS_FILE = "lxns-alias-list.json";
    public static final List<String> PUBLIC_REFRESH_SOURCES = List.of(
            DIVING_FISH_MUSIC_ENDPOINT.toString(),
            DIVING_FISH_VERSION_ENDPOINT.toString(),
            LXNS_SONG_ENDPOINT.toString(),
            LXNS_ALIAS_ENDPOINT.toString());

    private static final URI REMOTE_COVER_BASE = URI.create(
            "https://assets2.lxns.net/chunithm/jacket/");
    private static final String COVER_PLACEHOLDER_URL =
            "/song-catalog/cover-placeholder.svg";
    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,12}");
    private static final Pattern LEGACY_WORLDS_END_TITLE = Pattern.compile(
            "^\\[([^\\[\\]]+)\\](.+)$");
    private static final int MAX_CATALOG_ENTRIES = 3_000;
    private static final int MAX_CHARTS_PER_SONG = 6;
    private static final int MAX_LATEST_VERSIONS = 20;
    private static final int MAX_ALIASES_PER_SONG = 100;
    private static final int MIN_PARTIAL_QUERY_CODE_POINTS = 2;
    private static final int MIN_COMPACT_PARTIAL_CODE_POINTS = 4;
    private static final int MIN_FUZZY_QUERY_CODE_POINTS = 6;
    private static final Duration DEFAULT_REFRESH_TTL = Duration.ofMinutes(30);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final String[] DIFFICULTIES = {
        "BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA", "WORLD'S END"
    };

    private final Dataset snapshot;
    private final RemoteFetcher remoteFetcher;
    private final long refreshTtlNanos;
    private final LongSupplier nanoClock;
    private final Path snapshotDirectory;
    private final SnapshotWriter snapshotWriter;
    private final Object refreshLock = new Object();
    private volatile RefreshState refreshState;
    private volatile LxnsEnhancements lastLxnsEnhancements;
    private volatile SyncStatus syncStatus;

    private ChunithmCatalog(
            Dataset snapshot,
            RemoteFetcher remoteFetcher,
            Duration refreshTtl,
            LongSupplier nanoClock) {
        this(
                snapshot,
                remoteFetcher,
                refreshTtl,
                nanoClock,
                null,
                (directory, files) -> {
                    throw new AssertionError(
                            "snapshot writer must not run without a directory");
                });
    }

    private ChunithmCatalog(
            Dataset snapshot,
            RemoteFetcher remoteFetcher,
            Duration refreshTtl,
            LongSupplier nanoClock,
            Path snapshotDirectory,
            SnapshotWriter snapshotWriter) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.remoteFetcher = Objects.requireNonNull(remoteFetcher, "remoteFetcher");
        Objects.requireNonNull(refreshTtl, "refreshTtl");
        if (refreshTtl.isZero() || refreshTtl.isNegative()) {
            throw new IllegalArgumentException("refreshTtl must be positive");
        }
        this.refreshTtlNanos = refreshTtl.toNanos();
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.snapshotDirectory = snapshotDirectory;
        this.snapshotWriter = Objects.requireNonNull(
                snapshotWriter, "snapshotWriter");
        this.syncStatus = new SyncStatus(
                "ready", null, null, snapshot.warning(), snapshot.songs().size());
    }

    /** Loads the bundled Diving-Fish music and latest-version snapshots. */
    public static ChunithmCatalog load(Path catalogDirectory) throws IOException {
        Objects.requireNonNull(catalogDirectory, "catalogDirectory");
        String musicJson = readLimitedUtf8(
                catalogDirectory.resolve(DIVING_FISH_MUSIC_FILE),
                MAX_REMOTE_BYTES,
                "Bundled CHUNITHM song snapshot");
        String versionJson = readLimitedUtf8(
                catalogDirectory.resolve(DIVING_FISH_VERSION_FILE),
                MAX_REMOTE_BYTES,
                "Bundled CHUNITHM version snapshot");
        Dataset snapshot = parseDataset(musicJson, versionJson, "snapshot", null);
        if (snapshot.songs().isEmpty()) {
            throw new IOException("Bundled CHUNITHM song snapshot is empty");
        }
        LxnsEnhancements persistedEnhancements = null;
        Path lxnsSongPath = catalogDirectory.resolve(LXNS_SONG_FILE);
        Path lxnsAliasPath = catalogDirectory.resolve(LXNS_ALIAS_FILE);
        if (Files.exists(lxnsSongPath) || Files.exists(lxnsAliasPath)) {
            try {
                if (!Files.isRegularFile(lxnsSongPath)
                        || !Files.isRegularFile(lxnsAliasPath)) {
                    throw new IOException(
                            "Persisted LXNS CHUNITHM snapshots are incomplete");
                }
                persistedEnhancements = parseLxnsEnhancements(
                        readLimitedUtf8(
                                lxnsSongPath,
                                MAX_REMOTE_BYTES,
                                "Persisted LXNS CHUNITHM song snapshot"),
                        readLimitedUtf8(
                                lxnsAliasPath,
                                MAX_REMOTE_BYTES,
                                "Persisted LXNS CHUNITHM alias snapshot"));
                snapshot = applyLxnsEnhancements(
                        snapshot, persistedEnhancements);
            } catch (IOException | IllegalArgumentException error) {
                persistedEnhancements = null;
                snapshot = withWarning(
                        snapshot,
                        "Persisted LXNS CHUNITHM snapshots are invalid; using "
                                + "Diving-Fish snapshot: " + safeMessage(error));
            }
        }
        ChunithmCatalog catalog = new ChunithmCatalog(
                snapshot,
                new HttpRemoteFetcher(),
                DEFAULT_REFRESH_TTL,
                System::nanoTime,
                catalogDirectory,
                ChunithmCatalog::writeSnapshotsAtomically);
        catalog.lastLxnsEnhancements = persistedEnhancements;
        return catalog;
    }

    /** Returns an empty operational catalogue for fail-safe server startup. */
    static ChunithmCatalog empty(String warning) {
        return new ChunithmCatalog(
                new Dataset("empty", warning, List.of(), List.of()),
                new HttpRemoteFetcher(),
                DEFAULT_REFRESH_TTL,
                System::nanoTime);
    }

    static ChunithmCatalog empty(String warning, Path catalogDirectory) {
        return new ChunithmCatalog(
                new Dataset("empty", warning, List.of(), List.of()),
                new HttpRemoteFetcher(),
                DEFAULT_REFRESH_TTL,
                System::nanoTime,
                Objects.requireNonNull(catalogDirectory, "catalogDirectory"),
                ChunithmCatalog::writeSnapshotsAtomically);
    }

    /** Factory used by dependency-free tests; it performs no network I/O. */
    static ChunithmCatalog fromJson(
            String snapshotMusicJson,
            String snapshotVersionJson,
            RemoteFetcher remoteFetcher,
            Duration refreshTtl,
            LongSupplier nanoClock) {
        return new ChunithmCatalog(
                parseDataset(
                        snapshotMusicJson,
                        snapshotVersionJson,
                        "snapshot",
                        null),
                remoteFetcher,
                refreshTtl,
                nanoClock);
    }

    /** Extended persistence seam used by offline refresh tests. */
    static ChunithmCatalog fromJson(
            String snapshotMusicJson,
            String snapshotVersionJson,
            RemoteFetcher remoteFetcher,
            Duration refreshTtl,
            LongSupplier nanoClock,
            Path snapshotDirectory,
            SnapshotWriter snapshotWriter) {
        return new ChunithmCatalog(
                parseDataset(
                        snapshotMusicJson,
                        snapshotVersionJson,
                        "snapshot",
                        null),
                remoteFetcher,
                refreshTtl,
                nanoClock,
                snapshotDirectory,
                snapshotWriter);
    }

    /** Returns the complete bundled catalogue without network access. */
    public CatalogResult catalog() {
        return result(snapshot);
    }

    /**
     * Returns the newest dataset already obtained by this process without
     * initiating network I/O. Calculation and persistence handlers use this
     * view so their version/disabled rules stay aligned with online searches.
     */
    public CatalogResult currentCatalog() {
        RefreshState state = refreshState;
        return result(state == null ? snapshot : state.dataset());
    }

    /**
     * Forces a strict four-source refresh and publishes all validated raw
     * snapshots before exposing the merged catalogue in memory.
     *
     * <p>Unlike an interactive {@code online=true} lookup, scheduled/manual
     * refreshes require both Diving-Fish files and both LXNS files. A failure
     * leaves every previously usable in-memory and on-disk snapshot in place.</p>
     */
    public RefreshResult refreshNow() {
        synchronized (refreshLock) {
            Instant attemptedAt = Instant.now();
            SyncStatus previous = syncStatus;
            syncStatus = new SyncStatus(
                    "running",
                    attemptedAt,
                    previous.lastSuccessAt(),
                    null,
                    currentCatalog().songs().size());
            try {
                byte[] musicBody = fetchSnapshot(
                        DIVING_FISH_MUSIC_ENDPOINT,
                        "Remote CHUNITHM song response");
                byte[] versionBody = fetchSnapshot(
                        DIVING_FISH_VERSION_ENDPOINT,
                        "Remote CHUNITHM version response");
                byte[] lxnsSongBody = fetchSnapshot(
                        LXNS_SONG_ENDPOINT,
                        "LXNS CHUNITHM song response");
                byte[] lxnsAliasBody = fetchSnapshot(
                        LXNS_ALIAS_ENDPOINT,
                        "LXNS CHUNITHM alias response");

                Dataset divingFish = parseDataset(
                        decodeUtf8(
                                musicBody,
                                "Remote CHUNITHM song response"),
                        decodeUtf8(
                                versionBody,
                                "Remote CHUNITHM version response"),
                        "online",
                        null);
                if (divingFish.songs().isEmpty()) {
                    throw new IOException(
                            "Remote CHUNITHM song response is empty");
                }
                LxnsEnhancements enhancements = parseLxnsEnhancements(
                        decodeUtf8(
                                lxnsSongBody,
                                "LXNS CHUNITHM song response"),
                        decodeUtf8(
                                lxnsAliasBody,
                                "LXNS CHUNITHM alias response"));
                Dataset refreshed = applyLxnsEnhancements(
                        divingFish, enhancements);
                if (snapshotDirectory != null) {
                    Map<String, byte[]> files = new LinkedHashMap<>();
                    files.put(DIVING_FISH_MUSIC_FILE, musicBody);
                    files.put(DIVING_FISH_VERSION_FILE, versionBody);
                    files.put(LXNS_SONG_FILE, lxnsSongBody);
                    files.put(LXNS_ALIAS_FILE, lxnsAliasBody);
                    snapshotWriter.write(snapshotDirectory, files);
                }

                long completedAtNanos = nanoClock.getAsLong();
                refreshState = new RefreshState(completedAtNanos, refreshed);
                lastLxnsEnhancements = enhancements;
                Instant completedAt = Instant.now();
                syncStatus = new SyncStatus(
                        "ready",
                        attemptedAt,
                        completedAt,
                        null,
                        refreshed.songs().size());
                return new RefreshResult(
                        true,
                        "CHUNITHM catalogue synchronized",
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

    /** Current strict scheduled/manual synchronization state. */
    public SyncStatus syncStatus() {
        return syncStatus;
    }

    private byte[] fetchSnapshot(URI endpoint, String context)
            throws IOException, InterruptedException {
        requireHttps(endpoint);
        return checkedBody(remoteFetcher.fetch(endpoint), context);
    }

    private RefreshResult failedRefresh(Instant attemptedAt, Throwable error) {
        String safeError = safeMessage(error);
        SyncStatus previous = syncStatus;
        syncStatus = new SyncStatus(
                "failed",
                attemptedAt,
                previous.lastSuccessAt(),
                safeError,
                currentCatalog().songs().size());
        return new RefreshResult(
                false,
                "CHUNITHM catalogue refresh failed; retained last-known-good: "
                        + safeError,
                syncStatus);
    }

    /** Returns the complete catalogue, optionally refreshing it online. */
    public CatalogResult catalog(boolean online) {
        return result(online ? onlineDataset() : snapshot);
    }

    /**
     * Searches song metadata and chart fields.
     *
     * @param query non-blank query of at most 120 UTF-16 code units
     * @param limit result limit from 1 through 100
     * @param online whether a rate-limited online refresh may be attempted
     */
    public CatalogResult search(String query, int limit, boolean online) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("q must not be blank");
        }
        if (query.length() > 120) {
            throw new IllegalArgumentException("q is too long");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }

        Dataset dataset = online ? onlineDataset() : snapshot;
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

        List<Song> selected = new ArrayList<>(Math.min(limit, matches.size()));
        for (int index = 0; index < matches.size() && index < limit; index++) {
            selected.add(matches.get(index).song());
        }
        return new CatalogResult(
                dataset.source(),
                dataset.warning(),
                dataset.latestVersions(),
                selected);
    }

    /**
     * Returns the fixed HTTPS upstream cover URI used by the server-side proxy.
     */
    public static URI remoteCoverUriFor(String songId) {
        String validated = validateNumericId(songId, "CHUNITHM cover");
        return REMOTE_COVER_BASE.resolve(validated + ".png");
    }

    private static CatalogResult result(Dataset dataset) {
        return new CatalogResult(
                dataset.source(),
                dataset.warning(),
                dataset.latestVersions(),
                dataset.songs());
    }

    private static Dataset withWarning(Dataset dataset, String warning) {
        return new Dataset(
                dataset.source(),
                warning,
                dataset.latestVersions(),
                dataset.songs());
    }

    private Dataset onlineDataset() {
        long now = nanoClock.getAsLong();
        RefreshState state = refreshState;
        if (state != null && now - state.attemptedAtNanos() < refreshTtlNanos) {
            return state.dataset();
        }

        synchronized (refreshLock) {
            now = nanoClock.getAsLong();
            state = refreshState;
            if (state != null && now - state.attemptedAtNanos() < refreshTtlNanos) {
                return state.dataset();
            }

            Dataset refreshed;
            try {
                requireHttps(DIVING_FISH_MUSIC_ENDPOINT);
                requireHttps(DIVING_FISH_VERSION_ENDPOINT);
                byte[] musicBody = checkedBody(remoteFetcher.fetch(
                        DIVING_FISH_MUSIC_ENDPOINT), "Remote CHUNITHM song response");
                byte[] versionBody = checkedBody(remoteFetcher.fetch(
                        DIVING_FISH_VERSION_ENDPOINT),
                        "Remote CHUNITHM version response");
                Dataset divingFish = parseDataset(
                        decodeUtf8(musicBody, "Remote CHUNITHM song response"),
                        decodeUtf8(versionBody, "Remote CHUNITHM version response"),
                        "online",
                        null);
                if (divingFish.songs().isEmpty()) {
                    throw new IOException("Remote CHUNITHM song response is empty");
                }
                refreshed = enhanceOnlineDataset(divingFish);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                refreshed = fallbackDataset(error);
            } catch (IOException | IllegalArgumentException error) {
                refreshed = fallbackDataset(error);
            } catch (RuntimeException error) {
                // HttpClient construction can fail in heavily sandboxed hosts.
                refreshed = fallbackDataset(error);
            }
            refreshState = new RefreshState(now, refreshed);
            return refreshed;
        }
    }

    private Dataset fallbackDataset(Exception error) {
        RefreshState previous = refreshState;
        Dataset fallback = previous != null
                        && "online".equals(previous.dataset().source())
                ? previous.dataset()
                : snapshot;
        String provenance = fallback == snapshot
                ? "bundled snapshot"
                : "last valid online dataset";
        return new Dataset(
                fallback.source(),
                "Online CHUNITHM refresh failed; using " + provenance + ": "
                        + safeMessage(error),
                fallback.latestVersions(),
                fallback.songs());
    }

    private Dataset enhanceOnlineDataset(Dataset divingFish)
            throws InterruptedException {
        try {
            requireHttps(LXNS_SONG_ENDPOINT);
            requireHttps(LXNS_ALIAS_ENDPOINT);
            byte[] songBody = checkedBody(remoteFetcher.fetch(
                    LXNS_SONG_ENDPOINT), "LXNS CHUNITHM song response");
            byte[] aliasBody = checkedBody(remoteFetcher.fetch(
                    LXNS_ALIAS_ENDPOINT), "LXNS CHUNITHM alias response");
            LxnsEnhancements enhancements = parseLxnsEnhancements(
                    decodeUtf8(songBody, "LXNS CHUNITHM song response"),
                    decodeUtf8(aliasBody, "LXNS CHUNITHM alias response"));
            lastLxnsEnhancements = enhancements;
            return applyLxnsEnhancements(divingFish, enhancements);
        } catch (IOException | IllegalArgumentException error) {
            return lxnsFallbackDataset(divingFish, error);
        } catch (RuntimeException error) {
            // HttpClient construction may fail in heavily sandboxed hosts.
            return lxnsFallbackDataset(divingFish, error);
        }
    }

    private Dataset lxnsFallbackDataset(Dataset divingFish, Exception error) {
        LxnsEnhancements cached = lastLxnsEnhancements;
        Dataset usable = cached == null
                ? divingFish
                : applyLxnsEnhancements(divingFish, cached);
        String fallback = cached == null
                ? "without aliases/disabled flags"
                : "with the last valid LXNS metadata";
        return new Dataset(
                usable.source(),
                "LXNS CHUNITHM metadata refresh failed; using refreshed "
                        + "Diving-Fish data " + fallback + ": "
                        + safeMessage(error),
                usable.latestVersions(),
                usable.songs());
    }

    private static byte[] checkedBody(byte[] body, String context)
            throws IOException {
        if (body == null) {
            throw new IOException(context + " has no body");
        }
        if (body.length > MAX_REMOTE_BYTES) {
            throw new IOException(context + " exceeds 2 MiB");
        }
        return body;
    }

    private static int matchScore(
            Song song, String needle, String compactNeedle) {
        String title = normalize(song.title());
        String id = normalize(song.songId());
        List<String> aliases = song.aliases().stream()
                .map(ChunithmCatalog::normalize)
                .toList();
        if (title.equals(needle) || id.equals(needle)
                || aliases.stream().anyMatch(alias -> alias.equals(needle))
                || song.charts().stream().anyMatch(
                        chart -> normalize(chart.cid()).equals(needle))) {
            return 0;
        }

        String compactTitle = compactKey(song.title());
        List<String> compactAliases = song.aliases().stream()
                .map(ChunithmCatalog::compactKey)
                .filter(alias -> !alias.isEmpty())
                .toList();
        if (!compactNeedle.isEmpty()
                && (compactTitle.equals(compactNeedle)
                || compactAliases.stream().anyMatch(
                        alias -> alias.equals(compactNeedle)))) {
            return 1;
        }

        int strictLength = needle.codePointCount(0, needle.length());
        int compactLength = compactNeedle.codePointCount(
                0, compactNeedle.length());
        if (strictLength < MIN_PARTIAL_QUERY_CODE_POINTS
                || compactNeedle.isEmpty()) {
            return -1;
        }
        if (title.startsWith(needle)
                || aliases.stream().anyMatch(alias -> alias.startsWith(needle))) {
            return 2;
        }
        if (compactLength >= MIN_COMPACT_PARTIAL_CODE_POINTS
                && (compactTitle.startsWith(compactNeedle)
                || compactAliases.stream().anyMatch(
                        alias -> alias.startsWith(compactNeedle)))) {
            return 3;
        }
        if (title.contains(needle)
                || aliases.stream().anyMatch(alias -> alias.contains(needle))) {
            return 4;
        }
        if (compactLength >= MIN_COMPACT_PARTIAL_CODE_POINTS
                && (compactTitle.contains(compactNeedle)
                || compactAliases.stream().anyMatch(
                        alias -> alias.contains(compactNeedle)))) {
            return 5;
        }

        int fuzzyDistance = fuzzyTitleDistance(
                compactTitle, compactAliases, compactNeedle);
        if (fuzzyDistance >= 0) {
            return 10 + fuzzyDistance;
        }
        String artist = normalize(song.artist());
        if (artist.startsWith(needle)) {
            return 20;
        }
        if (artist.contains(needle)
                || normalize(song.genre()).contains(needle)
                || normalize(song.version()).contains(needle)
                || normalize(song.bpm().toPlainString()).contains(needle)
                || id.contains(needle)) {
            return 21;
        }
        for (Chart chart : song.charts()) {
            if (normalize(chart.difficulty()).contains(needle)
                    || normalize(chart.displayLevel()).contains(needle)
                    || normalize(chart.version()).contains(needle)
                    || normalize(chart.charter()).contains(needle)
                    || normalize(chart.cid()).contains(needle)) {
                return 21;
            }
        }
        return -1;
    }

    private static int fuzzyTitleDistance(
            String compactTitle,
            List<String> compactAliases,
            String compactNeedle) {
        int queryLength = compactNeedle.codePointCount(
                0, compactNeedle.length());
        if (queryLength < MIN_FUZZY_QUERY_CODE_POINTS) {
            return -1;
        }
        int limit = queryLength <= 12 ? 1 : queryLength <= 24 ? 2 : 3;
        int best = boundedEditDistance(compactNeedle, compactTitle, limit);
        for (String alias : compactAliases) {
            best = Math.min(best,
                    boundedEditDistance(compactNeedle, alias, limit));
        }
        return best <= limit ? best : -1;
    }

    private static int boundedEditDistance(
            String left, String right, int limit) {
        int[] leftPoints = left.codePoints().toArray();
        int[] rightPoints = right.codePoints().toArray();
        if (Math.abs(leftPoints.length - rightPoints.length) > limit) {
            return limit + 1;
        }
        if (leftPoints.length > rightPoints.length) {
            int[] swap = leftPoints;
            leftPoints = rightPoints;
            rightPoints = swap;
        }

        int[] previous = new int[leftPoints.length + 1];
        int[] current = new int[leftPoints.length + 1];
        for (int column = 0; column <= leftPoints.length; column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= rightPoints.length; row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= leftPoints.length; column++) {
                int substitution = previous[column - 1]
                        + (leftPoints[column - 1] == rightPoints[row - 1]
                        ? 0 : 1);
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
        return previous[leftPoints.length];
    }

    private static Dataset parseDataset(
            String musicJson,
            String versionJson,
            String source,
            String warning) {
        List<String> latestVersions = parseLatestVersions(versionJson);
        Set<String> latestNormalized = new HashSet<>();
        for (String version : latestVersions) {
            latestNormalized.add(normalize(version));
        }
        List<Song> songs = parseSongs(musicJson, latestNormalized);
        return new Dataset(source, warning, latestVersions, songs);
    }

    static LxnsEnhancements parseLxnsEnhancements(
            String songJson, String aliasJson) {
        Map<?, ?> songRoot = requireObject(
                Json.parse(songJson), "LXNS CHUNITHM song data");
        List<?> rawVersions = requireArray(
                songRoot.get("versions"), "LXNS CHUNITHM versions");
        if (rawVersions.isEmpty() || rawVersions.size() > 50) {
            throw new IllegalArgumentException(
                    "LXNS CHUNITHM version data has an invalid size");
        }
        Map<Integer, String> versionTitles = new LinkedHashMap<>();
        for (int index = 0; index < rawVersions.size(); index++) {
            String context = "LXNS CHUNITHM version at index " + index;
            Map<?, ?> version = requireObject(rawVersions.get(index), context);
            int versionNumber = requiredNonNegativeInteger(
                    version.get("version"), context + " version");
            String title = requiredString(version, "title", context, false);
            if (title.length() > 200
                    || versionTitles.put(versionNumber, title) != null) {
                throw new IllegalArgumentException(context + " is invalid");
            }
        }
        List<?> rawSongs = requireArray(
                songRoot.get("songs"), "LXNS CHUNITHM songs");
        if (rawSongs.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("LXNS CHUNITHM song data is too large");
        }

        Map<String, LxnsSongMetadata> metadata = new LinkedHashMap<>();
        for (int index = 0; index < rawSongs.size(); index++) {
            String context = "LXNS CHUNITHM song at index " + index;
            Map<?, ?> song = requireObject(rawSongs.get(index), context);
            String songId = requiredNumericId(song.get("id"), context);
            boolean disabled = optionalBoolean(song.get("disabled"), context + " disabled");
            List<?> difficulties = requireArray(
                    song.get("difficulties"), context + " difficulties");
            if (difficulties.size() > MAX_CHARTS_PER_SONG) {
                throw new IllegalArgumentException(context + " has too many difficulties");
            }
            String originId = null;
            Map<Integer, String> chartVersions = new LinkedHashMap<>();
            for (int difficultyIndex = 0;
                    difficultyIndex < difficulties.size(); difficultyIndex++) {
                String difficultyContext = context + " difficulty at index "
                        + difficultyIndex;
                Map<?, ?> difficulty = requireObject(
                        difficulties.get(difficultyIndex), difficultyContext);
                int levelIndex = requiredNonNegativeInteger(
                        difficulty.get("difficulty"), difficultyContext + " difficulty");
                if (levelIndex > 5) {
                    throw new IllegalArgumentException(
                            difficultyContext + " has an invalid difficulty");
                }
                int chartVersionNumber = requiredNonNegativeInteger(
                        difficulty.get("version"), difficultyContext + " version");
                String chartVersion = versionTitles.get(chartVersionNumber);
                if (chartVersion == null) {
                    throw new IllegalArgumentException(
                            difficultyContext + " references an unknown version");
                }
                if (chartVersions.put(levelIndex, chartVersion) != null) {
                    throw new IllegalArgumentException(
                            context + " has duplicate difficulty indexes");
                }
                if (levelIndex == 5) {
                    if (originId != null) {
                        throw new IllegalArgumentException(
                                context + " has duplicate WORLD'S END difficulties");
                    }
                    originId = requiredNumericId(
                            difficulty.get("origin_id"),
                            difficultyContext + " origin_id");
                }
            }
            if (metadata.put(songId, new LxnsSongMetadata(
                    disabled, originId, chartVersions))
                    != null) {
                throw new IllegalArgumentException(context + " has a duplicate id");
            }
        }

        Map<?, ?> aliasRoot = requireObject(
                Json.parse(aliasJson), "LXNS CHUNITHM alias data");
        List<?> rawAliases = requireArray(
                aliasRoot.get("aliases"), "LXNS CHUNITHM aliases");
        if (rawAliases.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException("LXNS CHUNITHM alias data is too large");
        }
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        for (int index = 0; index < rawAliases.size(); index++) {
            String context = "LXNS CHUNITHM alias at index " + index;
            Map<?, ?> entry = requireObject(rawAliases.get(index), context);
            String songId = requiredNumericId(entry.get("song_id"), context);
            List<?> values = requireArray(entry.get("aliases"), context + " aliases");
            if (values.size() > MAX_ALIASES_PER_SONG) {
                throw new IllegalArgumentException(context + " has too many aliases");
            }
            List<String> parsed = new ArrayList<>(values.size());
            Set<String> seen = new HashSet<>();
            for (int aliasIndex = 0; aliasIndex < values.size(); aliasIndex++) {
                String alias = requiredArrayString(
                        values.get(aliasIndex),
                        context + " alias at index " + aliasIndex,
                        false);
                if (alias.length() > 200) {
                    throw new IllegalArgumentException(context + " has an oversized alias");
                }
                if (seen.add(normalize(alias))) {
                    parsed.add(alias);
                }
            }
            if (aliases.put(songId, List.copyOf(parsed)) != null) {
                throw new IllegalArgumentException(context + " has a duplicate song_id");
            }
        }
        return new LxnsEnhancements(metadata, aliases);
    }

    private static Dataset applyLxnsEnhancements(
            Dataset dataset, LxnsEnhancements enhancements) {
        List<Song> songs = new ArrayList<>(dataset.songs().size());
        for (Song song : dataset.songs()) {
            LxnsSongMetadata metadata = enhancements.songs().get(song.songId());
            String coverSongId = song.coverSongId();
            boolean disabled = false;
            List<Chart> charts = song.charts();
            if (metadata != null) {
                disabled = metadata.disabled();
                if (metadata.worldsEndOriginId() != null
                        && isWorldsEndSong(song)) {
                    coverSongId = metadata.worldsEndOriginId();
                }
                List<Chart> enrichedCharts = new ArrayList<>(song.charts().size());
                for (Chart chart : song.charts()) {
                    String version = metadata.chartVersions()
                            .getOrDefault(chart.difficultyIndex(), chart.version());
                    enrichedCharts.add(copyChart(chart, version));
                }
                charts = List.copyOf(enrichedCharts);
            }
            List<String> aliases = enhancements.aliases()
                    .getOrDefault(song.songId(), List.of());
            songs.add(copySong(
                    song, coverSongId, disabled, aliases, charts));
        }
        return new Dataset(
                dataset.source(),
                dataset.warning(),
                dataset.latestVersions(),
                songs);
    }

    private static List<Song> applyOfflineCoverPolicy(
            List<Song> songs,
            Map<String, String> legacyWorldsEndBaseTitles) {
        Map<String, Set<String>> standardCoverIdsByTitle = new LinkedHashMap<>();
        for (Song song : songs) {
            if (isWorldsEndSong(song)) {
                continue;
            }
            standardCoverIdsByTitle
                    .computeIfAbsent(
                            exactCoverTitleKey(song.title()),
                            ignored -> new HashSet<>())
                    .add(song.coverSongId());
        }

        List<Song> mapped = new ArrayList<>(songs.size());
        for (Song song : songs) {
            // The documented CDN key for WORLD'S END is origin_id, not the
            // score/song ID. Strict legacy six-entry rows can still reuse a
            // standard jacket when their tagged title has exactly one NFKC
            // title match. Every ambiguous or structurally different row
            // stays on the placeholder until LXNS supplies origin_id.
            String coverSongId = song.coverSongId();
            if (isWorldsEndSong(song)) {
                coverSongId = "";
                String baseTitle = legacyWorldsEndBaseTitles.get(song.songId());
                if (baseTitle != null) {
                    Set<String> candidates = standardCoverIdsByTitle.get(
                            exactCoverTitleKey(baseTitle));
                    if (candidates != null && candidates.size() == 1) {
                        coverSongId = candidates.iterator().next();
                    }
                }
            }
            mapped.add(copySong(
                    song,
                    coverSongId,
                    song.disabled(),
                    song.aliases(),
                    song.charts()));
        }
        return List.copyOf(mapped);
    }

    private static String exactCoverTitleKey(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC);
    }

    private static String legacyWorldsEndBaseTitle(
            Map<?, ?> song, String title) {
        List<?> levels = requireArray(song.get("level"), "legacy WORLD'S END level");
        if (levels.size() != 6) {
            return null;
        }
        for (int index = 0; index < 5; index++) {
            if (!"-".equals(levels.get(index))) {
                return null;
            }
        }
        var matcher = LEGACY_WORLDS_END_TITLE.matcher(title);
        if (!matcher.matches()
                || matcher.group(1).codePointCount(
                        0, matcher.group(1).length()) != 1
                || matcher.group(2).startsWith("[")) {
            return null;
        }
        return matcher.group(2);
    }

    private static boolean isWorldsEndSong(Song song) {
        return song.charts().stream().anyMatch(chart -> chart.difficultyIndex() == 5);
    }

    private static Song copySong(
            Song song,
            String coverSongId,
            boolean disabled,
            List<String> aliases,
            List<Chart> charts) {
        return new Song(
                song.songId(),
                song.title(),
                song.artist(),
                song.genre(),
                song.bpm(),
                song.version(),
                coverSongId,
                coverSongId.isEmpty()
                        ? COVER_PLACEHOLDER_URL
                        : localCoverUrl(coverSongId),
                song.isNew(),
                disabled,
                aliases,
                charts);
    }

    private static Chart copyChart(Chart chart, String version) {
        return new Chart(
                chart.difficultyIndex(),
                chart.difficulty(),
                chart.cid(),
                chart.constant(),
                chart.displayLevel(),
                chart.combo(),
                chart.charter(),
                version);
    }

    private static List<String> parseLatestVersions(String json) {
        Map<?, ?> object = requireObject(
                Json.parse(json), "Diving-Fish latest-version data");
        List<?> rawVersions = requireArray(
                object.get("version"), "Diving-Fish latest-version data version");
        if (rawVersions.isEmpty() || rawVersions.size() > MAX_LATEST_VERSIONS) {
            throw new IllegalArgumentException(
                    "Diving-Fish latest-version data has an invalid version list");
        }
        List<String> versions = new ArrayList<>(rawVersions.size());
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < rawVersions.size(); index++) {
            Object rawVersion = rawVersions.get(index);
            if (!(rawVersion instanceof String text)
                    || text.isBlank() || text.length() > 200) {
                throw new IllegalArgumentException(
                        "Diving-Fish latest-version entry " + index + " is invalid");
            }
            String version = text.trim();
            if (!seen.add(normalize(version))) {
                throw new IllegalArgumentException(
                        "Diving-Fish latest-version data contains a duplicate");
            }
            versions.add(version);
        }
        return List.copyOf(versions);
    }

    private static List<Song> parseSongs(
            String json, Set<String> latestVersions) {
        List<?> array = requireArray(Json.parse(json), "Diving-Fish CHUNITHM data");
        if (array.size() > MAX_CATALOG_ENTRIES) {
            throw new IllegalArgumentException(
                    "Diving-Fish CHUNITHM data is too large");
        }
        List<Song> songs = new ArrayList<>(array.size());
        Map<String, String> legacyWorldsEndBaseTitles = new LinkedHashMap<>();
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < array.size(); index++) {
            String context = "Diving-Fish CHUNITHM song at index " + index;
            Map<?, ?> object = requireObject(array.get(index), context);
            String songId = requiredNumericId(object.get("id"), context);
            if (!ids.add(songId)) {
                throw new IllegalArgumentException(context + " has a duplicate id");
            }
            String title = requiredString(object, "title", context, true);
            Map<?, ?> basicInfo = requireObject(
                    object.get("basic_info"), context + " basic_info");
            // Validate the duplicated upstream title while retaining the top-level
            // value, which is the documented canonical search/display field.
            requiredString(basicInfo, "title", context + " basic_info", true);
            String artist = requiredString(
                    basicInfo, "artist", context + " basic_info", true);
            String genre = requiredString(
                    basicInfo, "genre", context + " basic_info", false);
            BigDecimal bpm = requiredDecimal(
                    basicInfo.get("bpm"), context + " basic_info bpm");
            if (bpm.signum() < 0 || bpm.compareTo(BigDecimal.valueOf(5_000)) > 0) {
                throw new IllegalArgumentException(context + " has an invalid bpm");
            }
            String version = requiredString(
                    basicInfo, "from", context + " basic_info", false);
            List<Chart> charts = parseCharts(object, context, version);
            String legacyWorldsEndBaseTitle = legacyWorldsEndBaseTitle(
                    object, title);
            if (legacyWorldsEndBaseTitle != null) {
                legacyWorldsEndBaseTitles.put(
                        songId, legacyWorldsEndBaseTitle);
            }
            songs.add(new Song(
                    songId,
                    title,
                    artist,
                    genre,
                    bpm,
                    version,
                    songId,
                    localCoverUrl(songId),
                    latestVersions.contains(normalize(version)),
                    false,
                    List.of(),
                    charts));
        }
        return applyOfflineCoverPolicy(songs, legacyWorldsEndBaseTitles);
    }

    private static List<Chart> parseCharts(
            Map<?, ?> song, String context, String songVersion) {
        List<?> constants = requireArray(song.get("ds"), context + " ds");
        List<?> levels = requireArray(song.get("level"), context + " level");
        List<?> cids = requireArray(song.get("cids"), context + " cids");
        List<?> charts = requireArray(song.get("charts"), context + " charts");
        int size = constants.size();
        if (size < 1 || size > MAX_CHARTS_PER_SONG
                || levels.size() != size || cids.size() != size
                || charts.size() != size
                || size == 2 || size == 3) {
            throw new IllegalArgumentException(context + " has invalid chart arrays");
        }

        List<Chart> parsed = new ArrayList<>(size);
        for (int rawIndex = 0; rawIndex < size; rawIndex++) {
            int difficultyIndex = size == 1 ? 5 : rawIndex;
            String chartContext = context + " chart at index " + rawIndex;
            BigDecimal constant = requiredDecimal(
                    constants.get(rawIndex), chartContext + " constant");
            if (constant.signum() < 0
                    || constant.compareTo(BigDecimal.valueOf(20)) > 0) {
                throw new IllegalArgumentException(chartContext + " has invalid constant");
            }
            String displayLevel = requiredArrayString(
                    levels.get(rawIndex), chartContext + " display level", false);
            String cid = requiredNumericId(cids.get(rawIndex), chartContext + " cid");
            Map<?, ?> chart = requireObject(charts.get(rawIndex), chartContext);
            int combo = requiredNonNegativeInteger(
                    chart.get("combo"), chartContext + " combo");
            String charter = requiredString(
                    chart, "charter", chartContext, true);

            // Six-entry World's End rows contain five placeholder normal
            // difficulties. Do not expose those as selectable charts.
            if (size == 6 && rawIndex < 5 && "-".equals(displayLevel)) {
                continue;
            }
            parsed.add(new Chart(
                    difficultyIndex,
                    DIFFICULTIES[difficultyIndex],
                    cid,
                    constant,
                    displayLevel,
                    combo,
                    charter,
                    songVersion));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException(context + " has no selectable charts");
        }
        return List.copyOf(parsed);
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

    private static String requiredString(
            Map<?, ?> object,
            String field,
            String context,
            boolean allowBlank) {
        return requiredArrayString(object.get(field), context + " " + field, allowBlank);
    }

    private static String requiredArrayString(
            Object value, String context, boolean allowBlank) {
        if (!(value instanceof String text)
                || (!allowBlank && text.isBlank()) || text.length() > 2_000) {
            throw new IllegalArgumentException(context + " is invalid");
        }
        return text.trim();
    }

    private static BigDecimal requiredDecimal(Object value, String context) {
        if (!(value instanceof BigDecimal number)) {
            throw new IllegalArgumentException(context + " must be a number");
        }
        return number;
    }

    private static int requiredNonNegativeInteger(Object value, String context) {
        BigDecimal number = requiredDecimal(value, context);
        try {
            int integer = number.intValueExact();
            if (integer < 0) {
                throw new IllegalArgumentException(context + " must not be negative");
            }
            return integer;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException(context + " must be an integer", error);
        }
    }

    private static boolean optionalBoolean(Object value, String context) {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalArgumentException(context + " must be a boolean");
    }

    private static String requiredNumericId(Object value, String context) {
        String id;
        if (value instanceof String text) {
            id = text.trim();
        } else if (value instanceof BigDecimal number
                && number.stripTrailingZeros().scale() <= 0) {
            id = number.toBigIntegerExact().toString();
        } else {
            throw new IllegalArgumentException(context + " has an invalid id");
        }
        return validateNumericId(id, context);
    }

    private static String validateNumericId(String id, String context) {
        if (id == null || !NUMERIC_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(context + " has an invalid numeric id");
        }
        return id;
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

    private static String localCoverUrl(String songId) {
        return "/api/chunithm/covers/" + songId + ".png";
    }

    private static String readLimitedUtf8(Path path, int maxBytes, String context)
            throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException(context + " exceeds 2 MiB");
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

    static void writeSnapshotsAtomically(
            Path directory, Map<String, byte[]> files) throws IOException {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(files, "files");
        Set<String> expected = Set.of(
                DIVING_FISH_MUSIC_FILE,
                DIVING_FISH_VERSION_FILE,
                LXNS_SONG_FILE,
                LXNS_ALIAS_FILE);
        if (!files.keySet().equals(expected)) {
            throw new IOException(
                    "CHUNITHM snapshot publication has unexpected files");
        }
        Path absoluteDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(absoluteDirectory);
        Path lockPath = absoluteDirectory.resolve(".catalog-sync.lock");
        try (FileChannel lockChannel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE)) {
            try (FileLock publicationLock = lockChannel.lock()) {
                if (!publicationLock.isValid()) {
                    throw new IOException(
                            "CHUNITHM snapshot publication lock is invalid");
                }
                writeSnapshotsWithRollback(absoluteDirectory, files);
            } catch (OverlappingFileLockException error) {
                throw new IOException(
                        "Another CHUNITHM snapshot publication is active",
                        error);
            }
        }
    }

    private static void writeSnapshotsWithRollback(
            Path absoluteDirectory, Map<String, byte[]> files)
            throws IOException {
        Map<String, Path> targets = new LinkedHashMap<>();
        Map<String, Path> staged = new LinkedHashMap<>();
        Map<String, Path> backups = new LinkedHashMap<>();
        List<String> published = new ArrayList<>();
        try {
            for (String name : List.of(
                    DIVING_FISH_MUSIC_FILE,
                    DIVING_FISH_VERSION_FILE,
                    LXNS_SONG_FILE,
                    LXNS_ALIAS_FILE)) {
                byte[] body = Objects.requireNonNull(files.get(name), name);
                if (body.length == 0 || body.length > MAX_REMOTE_BYTES) {
                    throw new IOException(
                            "CHUNITHM snapshot file has an invalid size: " + name);
                }
                Path target = absoluteDirectory.resolve(name).normalize();
                if (!target.getParent().equals(absoluteDirectory)) {
                    throw new IOException("CHUNITHM snapshot path is invalid");
                }
                Path temporary = Files.createTempFile(
                        absoluteDirectory, "." + name + ".", ".tmp");
                writeDurably(temporary, body);
                targets.put(name, target);
                staged.put(name, temporary);
            }
            // Prepare every rollback copy before publishing the first file.
            for (String name : targets.keySet()) {
                Path target = targets.get(name);
                if (Files.exists(target)) {
                    if (!Files.isRegularFile(target)) {
                        throw new IOException(
                                "CHUNITHM snapshot target is not a file: " + name);
                    }
                    Path backup = Files.createTempFile(
                            absoluteDirectory, "." + name + ".", ".bak");
                    Files.copy(
                            target,
                            backup,
                            StandardCopyOption.REPLACE_EXISTING);
                    forceFile(backup);
                    backups.put(name, backup);
                }
            }
            for (String name : targets.keySet()) {
                moveAtomically(staged.get(name), targets.get(name));
                published.add(name);
            }
        } catch (IOException error) {
            for (int index = published.size() - 1; index >= 0; index--) {
                String name = published.get(index);
                try {
                    Path backup = backups.get(name);
                    if (backup == null) {
                        Files.deleteIfExists(targets.get(name));
                    } else {
                        moveAtomically(backup, targets.get(name));
                        backups.remove(name);
                    }
                } catch (IOException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
            }
            throw error;
        } finally {
            for (Path temporary : staged.values()) {
                deleteQuietly(temporary);
            }
            for (Path backup : backups.values()) {
                deleteQuietly(backup);
            }
        }
    }

    private static void writeDurably(Path path, byte[] body)
            throws IOException {
        try (FileChannel channel = FileChannel.open(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(body);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void forceFile(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveAtomically(Path source, Path target)
            throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException(
                    "Atomic CHUNITHM snapshot replacement is not supported",
                    error);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A stale dot-prefixed temporary is safer than reporting a
            // validated publication as failed after every target was moved.
        }
    }

    private static void requireHttps(URI endpoint) throws IOException {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IOException("Remote CHUNITHM endpoint must use HTTPS");
        }
    }

    /** Remote fetch seam used to keep tests offline. */
    @FunctionalInterface
    interface RemoteFetcher {
        byte[] fetch(URI endpoint) throws IOException, InterruptedException;
    }

    /** Validated multi-file snapshot publication seam for offline tests. */
    @FunctionalInterface
    interface SnapshotWriter {
        void write(Path directory, Map<String, byte[]> files) throws IOException;
    }

    static HttpRequest buildRemoteRequest(URI endpoint) throws IOException {
        requireHttps(endpoint);
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
                        "Remote CHUNITHM service returned HTTP "
                                + response.statusCode());
            }
            long declaredLength = response.headers()
                    .firstValueAsLong("Content-Length")
                    .orElse(-1L);
            if (declaredLength > MAX_REMOTE_BYTES) {
                throw new IOException("Remote CHUNITHM response exceeds 2 MiB");
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.isEmpty()
                    && !contentType.startsWith("application/json")) {
                throw new IOException("Remote CHUNITHM service did not return JSON");
            }
            return response.body();
        }

        private HttpClient client() {
            HttpClient current = client;
            if (current != null) {
                return current;
            }
            synchronized (this) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder()
                            .connectTimeout(CONNECT_TIMEOUT)
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .build();
                    client = current;
                }
                return current;
            }
        }
    }

    /** A selectable CHUNITHM chart. */
    public record Chart(
            int difficultyIndex,
            String difficulty,
            String cid,
            BigDecimal constant,
            String displayLevel,
            int combo,
            String charter,
            String version) {
        public Chart {
            if (difficultyIndex < 0 || difficultyIndex >= DIFFICULTIES.length) {
                throw new IllegalArgumentException("difficultyIndex is invalid");
            }
            Objects.requireNonNull(difficulty, "difficulty");
            Objects.requireNonNull(cid, "cid");
            Objects.requireNonNull(constant, "constant");
            Objects.requireNonNull(displayLevel, "displayLevel");
            Objects.requireNonNull(charter, "charter");
            Objects.requireNonNull(version, "version");
            if (combo < 0) {
                throw new IllegalArgumentException("combo must not be negative");
            }
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("difficultyIndex", difficultyIndex);
            value.put("difficulty", difficulty);
            value.put("cid", cid);
            value.put("constant", constant);
            value.put("displayLevel", displayLevel);
            value.put("combo", combo);
            value.put("charter", charter);
            value.put("version", version);
            return value;
        }
    }

    /** One CHUNITHM song with all selectable difficulties. */
    public record Song(
            String songId,
            String title,
            String artist,
            String genre,
            BigDecimal bpm,
            String version,
            String coverSongId,
            String coverUrl,
            boolean isNew,
            boolean disabled,
            List<String> aliases,
            List<Chart> charts) {
        public Song {
            Objects.requireNonNull(songId, "songId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(artist, "artist");
            Objects.requireNonNull(genre, "genre");
            Objects.requireNonNull(bpm, "bpm");
            Objects.requireNonNull(version, "version");
            Objects.requireNonNull(coverSongId, "coverSongId");
            Objects.requireNonNull(coverUrl, "coverUrl");
            aliases = List.copyOf(aliases);
            charts = List.copyOf(charts);
        }

        /** Returns chart IDs in the same order as {@link #charts()}. */
        public List<String> cids() {
            return charts.stream().map(Chart::cid).toList();
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", songId);
            // Keep the documented Diving-Fish name as a compatibility alias.
            value.put("id", songId);
            value.put("title", title);
            value.put("artist", artist);
            value.put("genre", genre);
            value.put("bpm", bpm);
            value.put("version", version);
            value.put("coverSongId", coverSongId);
            value.put("coverUrl", coverUrl);
            value.put("isNew", isNew);
            value.put("disabled", disabled);
            value.put("aliases", aliases);
            value.put("cids", cids());
            List<Map<String, Object>> chartValues = new ArrayList<>(charts.size());
            for (Chart chart : charts) {
                chartValues.add(chart.toJsonValue());
            }
            value.put("charts", chartValues);
            return value;
        }
    }

    /** Endpoint-ready result envelope with refresh provenance. */
    public record CatalogResult(
            String source,
            String warning,
            List<String> latestVersions,
            List<Song> songs) {
        public CatalogResult {
            Objects.requireNonNull(source, "source");
            latestVersions = List.copyOf(latestVersions);
            songs = List.copyOf(songs);
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("source", source);
            value.put("warning", warning);
            value.put("latestVersions", latestVersions);
            value.put("count", songs.size());
            List<Map<String, Object>> songValues = new ArrayList<>(songs.size());
            for (Song song : songs) {
                songValues.add(song.toJsonValue());
            }
            value.put("songs", songValues);
            return Json.stringify(value);
        }

        /** Song IDs that LXNS says must not contribute to Rating. */
        public Set<String> disabledSongIds() {
            Set<String> disabled = new HashSet<>();
            for (Song song : songs) {
                if (song.disabled()) {
                    disabled.add(song.songId());
                }
            }
            return Set.copyOf(disabled);
        }
    }

    /** Current state of strict scheduled/manual public-API synchronization. */
    public record SyncStatus(
            String state,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            String error,
            int songCount) {
        public SyncStatus {
            Objects.requireNonNull(state, "state");
            if (songCount < 0) {
                throw new IllegalArgumentException(
                        "songCount must not be negative");
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

    /** Result returned by strict scheduled/manual CHUNITHM refreshes. */
    public record RefreshResult(
            boolean success, String message, SyncStatus status) {
        public RefreshResult {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(status, "status");
        }

        public String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("message", message);
            value.put("sources", PUBLIC_REFRESH_SOURCES);
            value.put("status", Json.parse(status.toJson()));
            return Json.stringify(value);
        }
    }

    private record Dataset(
            String source,
            String warning,
            List<String> latestVersions,
            List<Song> songs) {
        private Dataset {
            latestVersions = List.copyOf(latestVersions);
            songs = List.copyOf(songs);
        }
    }

    private record RefreshState(long attemptedAtNanos, Dataset dataset) {
    }

    private record ScoredSong(Song song, int score, int originalIndex) {
    }

    record LxnsSongMetadata(
            boolean disabled,
            String worldsEndOriginId,
            Map<Integer, String> chartVersions) {
        LxnsSongMetadata {
            chartVersions = Map.copyOf(chartVersions);
        }
    }

    record LxnsEnhancements(
            Map<String, LxnsSongMetadata> songs,
            Map<String, List<String>> aliases) {
        LxnsEnhancements {
            songs = Map.copyOf(songs);
            aliases = Map.copyOf(aliases);
        }
    }
}
