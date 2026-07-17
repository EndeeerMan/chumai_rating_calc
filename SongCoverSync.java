import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/** Downloads every resolvable maimai DX and CHUNITHM cover into local caches. */
public final class SongCoverSync {
    private static final int DEFAULT_ATTEMPTS = 3;
    private static final int DEFAULT_CONCURRENCY = 6;
    private static final int MAX_ATTEMPTS = 10;
    private static final int MAX_CONCURRENCY = 16;
    private static final int MAX_FAILURE_SAMPLES = 20;
    private static final int MAX_LOCAL_COVER_BYTES = 2 * 1024 * 1024;
    private static final String LOCAL_MAIMAI_COVER_PREFIX =
            "/song-catalog/cover/";
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Object PROGRESS_LOCK = new Object();

    private SongCoverSync() {
    }

    public static void main(String[] args) {
        if (args.length > 7) {
            System.err.println(
                    "Usage: SongCoverSync [maimai-directory] [maimai-catalog-cache] "
                            + "[maimai-cover-cache] [chunithm-directory] "
                            + "[chunithm-cover-cache] [attempts] [concurrency]");
            System.exit(2);
        }
        Path maimaiDirectory = argumentPath(
                args, 0, Path.of("web", "song-catalog"));
        Path maimaiCatalogCache = argumentPath(
                args, 1, SongCatalog.DEFAULT_CACHE_PATH);
        Path maimaiCoverCache = argumentPath(
                args, 2, Path.of("cache", "maimai-covers"));
        Path chunithmDirectory = argumentPath(
                args, 3, Path.of("web", "chunithm-catalog"));
        Path chunithmCoverCache = argumentPath(
                args, 4, Path.of("cache", "chunithm-covers"));
        int attempts;
        int concurrency;
        try {
            attempts = argumentInteger(
                    args, 5, DEFAULT_ATTEMPTS, 1, MAX_ATTEMPTS, "attempts");
            concurrency = argumentInteger(
                    args,
                    6,
                    DEFAULT_CONCURRENCY,
                    1,
                    MAX_CONCURRENCY,
                    "concurrency");
        } catch (IllegalArgumentException error) {
            System.err.println(error.getMessage());
            System.exit(2);
            return;
        }

        GameResult maimai = synchronizeMaimaiSafely(
                maimaiDirectory,
                maimaiCatalogCache,
                maimaiCoverCache,
                attempts,
                concurrency);
        GameResult chunithm = synchronizeChunithmSafely(
                chunithmDirectory,
                chunithmCoverCache,
                attempts,
                concurrency);
        SyncResult result = new SyncResult(
                maimai.success() && chunithm.success(),
                Instant.now(),
                maimai,
                chunithm);
        System.out.println(result.toJson());
        if (!result.success()) {
            System.exit(1);
        }
    }

    private static Path argumentPath(String[] args, int index, Path fallback) {
        return args.length > index ? Path.of(args[index]) : fallback;
    }

    private static int argumentInteger(
            String[] args,
            int index,
            int fallback,
            int minimum,
            int maximum,
            String name) {
        if (args.length <= index) {
            return fallback;
        }
        final int value;
        try {
            value = Integer.parseInt(args[index]);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(name + " must be an integer", error);
        }
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private static GameResult synchronizeMaimaiSafely(
            Path directory,
            Path catalogCache,
            Path coverCache,
            int attempts,
            int concurrency) {
        try {
            SongCatalog catalog = SongCatalog.load(directory, catalogCache);
            MaimaiCoverService service = new MaimaiCoverService(coverCache);
            return synchronizeMaimai(
                    catalog.catalog().songs(),
                    directory.resolve("cover"),
                    new CoverStore() {
                        @Override
                        public boolean isCached(String id) throws IOException {
                            return service.isCached(id);
                        }

                        @Override
                        public void ensure(String id)
                                throws IOException, InterruptedException {
                            service.cover(id);
                        }
                    },
                    attempts,
                    concurrency,
                    Thread::sleep);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failedGame("maimai", safeMessage(error));
        } catch (IOException | RuntimeException error) {
            return failedGame("maimai", safeMessage(error));
        }
    }

    private static GameResult synchronizeChunithmSafely(
            Path directory,
            Path coverCache,
            int attempts,
            int concurrency) {
        try {
            ChunithmCatalog catalog = ChunithmCatalog.load(directory);
            ChunithmCoverService service = new ChunithmCoverService(coverCache);
            return synchronizeChunithm(
                    catalog.currentCatalog().songs(),
                    new CoverStore() {
                        @Override
                        public boolean isCached(String id) throws IOException {
                            return service.isCached(id);
                        }

                        @Override
                        public void ensure(String id)
                                throws IOException, InterruptedException {
                            service.cover(id);
                        }
                    },
                    attempts,
                    concurrency,
                    Thread::sleep);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return failedGame("chunithm", safeMessage(error));
        } catch (IOException | RuntimeException error) {
            return failedGame("chunithm", safeMessage(error));
        }
    }

    static GameResult synchronizeMaimai(
            List<SongCatalog.Song> songs,
            Path bundledCoverDirectory,
            CoverStore store,
            int attempts,
            int concurrency,
            RetryPause pause) throws IOException, InterruptedException {
        Objects.requireNonNull(songs, "songs");
        Objects.requireNonNull(bundledCoverDirectory, "bundledCoverDirectory");
        Objects.requireNonNull(store, "store");
        Set<String> bundledAssets = new LinkedHashSet<>();
        Set<String> remoteIds = new LinkedHashSet<>();
        Map<String, Boolean> bundledBySongId = new LinkedHashMap<>();
        for (SongCatalog.Song song : songs) {
            String fileName = localMaimaiCoverFileName(song.coverUrl());
            boolean bundled = fileName != null
                    && isValidLocalPng(bundledCoverDirectory, fileName);
            bundledBySongId.put(song.songId(), bundled);
            if (bundled) {
                bundledAssets.add(fileName);
            } else {
                remoteIds.add(song.songId());
            }
        }

        DownloadResult downloads = downloadMissing(
                "舞萌 DX", remoteIds, store, attempts, concurrency, pause);
        int coveredSongs = 0;
        for (SongCatalog.Song song : songs) {
            if (Boolean.TRUE.equals(bundledBySongId.get(song.songId()))
                    || store.isCached(song.songId())) {
                coveredSongs++;
            }
        }
        int remaining = songs.size() - coveredSongs;
        return new GameResult(
                remaining == 0,
                songs.size(),
                bundledAssets.size() + remoteIds.size(),
                bundledAssets.size() + downloads.availableBefore(),
                downloads.downloaded(),
                downloads.remainingIds().size(),
                coveredSongs,
                0,
                downloads.failures(),
                remaining == 0 ? null : "Some maimai covers remain unavailable");
    }

    static GameResult synchronizeChunithm(
            List<ChunithmCatalog.Song> songs,
            CoverStore store,
            int attempts,
            int concurrency,
            RetryPause pause) throws IOException, InterruptedException {
        Objects.requireNonNull(songs, "songs");
        Objects.requireNonNull(store, "store");
        Set<String> coverIds = new LinkedHashSet<>();
        int unresolvedSongs = 0;
        for (ChunithmCatalog.Song song : songs) {
            if (song.coverSongId().isBlank()) {
                unresolvedSongs++;
            } else {
                coverIds.add(song.coverSongId());
            }
        }

        DownloadResult downloads = downloadMissing(
                "中二节奏", coverIds, store, attempts, concurrency, pause);
        int coveredSongs = 0;
        for (ChunithmCatalog.Song song : songs) {
            if (!song.coverSongId().isBlank()
                    && store.isCached(song.coverSongId())) {
                coveredSongs++;
            }
        }
        boolean complete = unresolvedSongs == 0 && coveredSongs == songs.size();
        return new GameResult(
                complete,
                songs.size(),
                coverIds.size(),
                downloads.availableBefore(),
                downloads.downloaded(),
                downloads.remainingIds().size(),
                coveredSongs,
                unresolvedSongs,
                downloads.failures(),
                complete ? null : "Some CHUNITHM covers remain unavailable");
    }

    static DownloadResult downloadMissing(
            String label,
            Set<String> wantedIds,
            CoverStore store,
            int attempts,
            int concurrency,
            RetryPause pause) throws IOException, InterruptedException {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(wantedIds, "wantedIds");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(pause, "pause");
        if (attempts < 1 || attempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("attempts is out of range");
        }
        if (concurrency < 1 || concurrency > MAX_CONCURRENCY) {
            throw new IllegalArgumentException("concurrency is out of range");
        }

        List<String> ordered = wantedIds.stream()
                .sorted(Comparator.comparingLong(SongCoverSync::numericId))
                .toList();
        Set<String> missing = new LinkedHashSet<>();
        for (String id : ordered) {
            if (!store.isCached(id)) {
                missing.add(id);
            }
        }
        int availableBefore = wantedIds.size() - missing.size();
        Set<String> initiallyMissing = Set.copyOf(missing);
        Map<String, String> lastErrors = new ConcurrentHashMap<>();

        for (int attempt = 1; attempt <= attempts && !missing.isEmpty(); attempt++) {
            List<String> round = List.copyOf(missing);
            System.err.println(
                    label + "封面：第 " + attempt + "/" + attempts
                            + " 轮，待补全 " + round.size() + " 张");
            AtomicInteger completed = new AtomicInteger();
            try (ExecutorService executor = Executors.newFixedThreadPool(
                    concurrency,
                    Thread.ofVirtual().name("cover-sync-", 0).factory())) {
                List<Callable<AttemptResult>> tasks = new ArrayList<>(round.size());
                for (String id : round) {
                    tasks.add(() -> {
                        try {
                            store.ensure(id);
                            lastErrors.remove(id);
                            return new AttemptResult(id, null);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw error;
                        } catch (IOException | RuntimeException error) {
                            String message = safeMessage(error);
                            lastErrors.put(id, message);
                            return new AttemptResult(id, message);
                        } finally {
                            int count = completed.incrementAndGet();
                            if (count % 50 == 0 || count == round.size()) {
                                synchronized (PROGRESS_LOCK) {
                                    System.err.println(
                                            label + "封面：本轮进度 " + count
                                                    + "/" + round.size());
                                }
                            }
                        }
                    });
                }
                List<Future<AttemptResult>> futures = executor.invokeAll(tasks);
                for (Future<AttemptResult> future : futures) {
                    try {
                        future.get();
                    } catch (java.util.concurrent.ExecutionException error) {
                        Throwable cause = error.getCause();
                        if (cause instanceof InterruptedException interrupted) {
                            throw interrupted;
                        }
                        throw new IOException("Cover worker failed", cause);
                    }
                }
            }

            missing = new LinkedHashSet<>();
            for (String id : round) {
                if (!store.isCached(id)) {
                    missing.add(id);
                }
            }
            if (!missing.isEmpty() && attempt < attempts) {
                long delay = Math.min(4_000L, 500L << Math.min(attempt - 1, 3));
                pause.pause(delay);
            }
        }

        List<Failure> failures = new ArrayList<>();
        int sampled = 0;
        for (String id : missing) {
            if (sampled++ >= MAX_FAILURE_SAMPLES) {
                break;
            }
            failures.add(new Failure(
                    id,
                    lastErrors.getOrDefault(id, "Cover remains unavailable")));
        }
        int downloaded = initiallyMissing.size() - missing.size();
        return new DownloadResult(
                availableBefore,
                downloaded,
                Set.copyOf(missing),
                List.copyOf(failures));
    }

    private static long numericId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Cover ID must be numeric", error);
        }
    }

    private static String localMaimaiCoverFileName(String coverUrl) {
        if (coverUrl == null || !coverUrl.startsWith(LOCAL_MAIMAI_COVER_PREFIX)) {
            return null;
        }
        String fileName = coverUrl.substring(LOCAL_MAIMAI_COVER_PREFIX.length());
        if (fileName.isEmpty()
                || fileName.indexOf('/') >= 0
                || fileName.indexOf('\\') >= 0
                || fileName.equals(".")
                || fileName.equals("..")) {
            return null;
        }
        return fileName;
    }

    private static boolean isValidLocalPng(Path directory, String fileName) {
        try {
            Path root = directory.toAbsolutePath().normalize().toRealPath();
            Path candidate = root.resolve(fileName).normalize();
            if (!candidate.getParent().equals(root)
                    || !Files.isRegularFile(candidate)
                    || !candidate.toRealPath().startsWith(root)) {
                return false;
            }
            long size = Files.size(candidate);
            if (size < PNG_SIGNATURE.length || size > MAX_LOCAL_COVER_BYTES) {
                return false;
            }
            byte[] signature = new byte[PNG_SIGNATURE.length];
            try (InputStream input = Files.newInputStream(candidate)) {
                if (input.readNBytes(signature, 0, signature.length)
                        != signature.length) {
                    return false;
                }
            }
            return java.util.Arrays.equals(PNG_SIGNATURE, signature);
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static GameResult failedGame(String game, String error) {
        return new GameResult(
                false, 0, 0, 0, 0, 0, 0, 0, List.of(), error);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        message = message.replaceAll("[\\p{Cntrl}]+", " ").trim();
        return message.length() <= 180 ? message : message.substring(0, 180);
    }

    interface CoverStore {
        boolean isCached(String id) throws IOException;

        void ensure(String id) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface RetryPause {
        void pause(long milliseconds) throws InterruptedException;
    }

    record Failure(String songId, String message) {
        Failure {
            Objects.requireNonNull(songId, "songId");
            Objects.requireNonNull(message, "message");
        }

        Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("songId", songId);
            value.put("message", message);
            return value;
        }
    }

    record DownloadResult(
            int availableBefore,
            int downloaded,
            Set<String> remainingIds,
            List<Failure> failures) {
        DownloadResult {
            remainingIds = Set.copyOf(remainingIds);
            failures = List.copyOf(failures);
        }
    }

    record GameResult(
            boolean success,
            int songCount,
            int expectedFiles,
            int availableBefore,
            int downloaded,
            int remainingFiles,
            int coveredSongs,
            int unresolvedSongs,
            List<Failure> failures,
            String error) {
        GameResult {
            failures = List.copyOf(failures);
        }

        Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("songCount", songCount);
            value.put("expectedFiles", expectedFiles);
            value.put("availableBefore", availableBefore);
            value.put("downloaded", downloaded);
            value.put("remainingFiles", remainingFiles);
            value.put("coveredSongs", coveredSongs);
            value.put("unresolvedSongs", unresolvedSongs);
            value.put("failures", failures.stream()
                    .map(Failure::toJsonValue)
                    .toList());
            value.put("error", error);
            return value;
        }
    }

    record SyncResult(
            boolean success,
            Instant completedAt,
            GameResult maimai,
            GameResult chunithm) {
        SyncResult {
            Objects.requireNonNull(completedAt, "completedAt");
            Objects.requireNonNull(maimai, "maimai");
            Objects.requireNonNull(chunithm, "chunithm");
        }

        String toJson() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("success", success);
            value.put("completedAt", completedAt.toString());
            value.put("maimai", maimai.toJsonValue());
            value.put("chunithm", chunithm.toJsonValue());
            return Json.stringify(value);
        }
    }

    private record AttemptResult(String songId, String error) {
    }
}
