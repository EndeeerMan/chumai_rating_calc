import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Shared bounded cache for jacket images hosted by the fixed LXNS CDN. */
final class LxnsCoverService {
    static final int MAX_COVER_BYTES = 2 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private final Path cacheDirectory;
    private final URI remoteBase;
    private final String gameName;
    private final RemoteFetcher fetcher;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    LxnsCoverService(Path cacheDirectory, URI remoteBase, String gameName)
            throws IOException {
        this(
                cacheDirectory,
                remoteBase,
                gameName,
                new HttpRemoteFetcher(remoteBase, gameName));
    }

    LxnsCoverService(
            Path cacheDirectory,
            URI remoteBase,
            String gameName,
            RemoteFetcher fetcher) throws IOException {
        this.cacheDirectory = Objects.requireNonNull(
                cacheDirectory, "cacheDirectory must not be null")
                .toAbsolutePath()
                .normalize();
        this.remoteBase = requireTrustedBase(remoteBase);
        this.gameName = Objects.requireNonNull(gameName, "gameName must not be null");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher must not be null");
        Files.createDirectories(this.cacheDirectory);
    }

    /** Returns a validated PNG, using the local cache after the first request. */
    byte[] cover(String canonicalSongId) throws IOException, InterruptedException {
        Path cacheFile = cacheFile(canonicalSongId);
        byte[] cached = readValidCache(cacheFile);
        if (cached != null) {
            return cached;
        }

        Object lock = locks.computeIfAbsent(canonicalSongId, ignored -> new Object());
        synchronized (lock) {
            try {
                cached = readValidCache(cacheFile);
                if (cached != null) {
                    return cached;
                }
                byte[] downloaded = fetcher.fetch(remoteUriFor(canonicalSongId));
                requireValidPng(downloaded);
                atomicWrite(cacheFile, downloaded);
                return downloaded.clone();
            } finally {
                locks.remove(canonicalSongId, lock);
            }
        }
    }

    /** Reports whether a valid local PNG is already cached without network I/O. */
    boolean isCached(String canonicalSongId) throws IOException {
        return readValidCache(cacheFile(canonicalSongId)) != null;
    }

    URI remoteUriFor(String canonicalSongId) {
        requireNumericId(canonicalSongId);
        return remoteBase.resolve(canonicalSongId + ".png");
    }

    static HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(redirectPolicy())
                .build();
    }

    static HttpClient.Redirect redirectPolicy() {
        return HttpClient.Redirect.NEVER;
    }

    private Path cacheFile(String canonicalSongId) {
        requireNumericId(canonicalSongId);
        Path cacheFile = cacheDirectory.resolve(canonicalSongId + ".png").normalize();
        if (!cacheFile.getParent().equals(cacheDirectory)) {
            throw new IllegalArgumentException("Invalid " + gameName + " song ID");
        }
        return cacheFile;
    }

    private static URI requireTrustedBase(URI value) {
        URI base = Objects.requireNonNull(value, "remoteBase must not be null");
        if (!"https".equalsIgnoreCase(base.getScheme())
                || !"assets2.lxns.net".equalsIgnoreCase(base.getHost())
                || base.getPort() != -1
                || base.getUserInfo() != null
                || base.getQuery() != null
                || base.getFragment() != null
                || base.getPath() == null
                || !base.getPath().endsWith("/")) {
            throw new IllegalArgumentException("Invalid LXNS cover base URI");
        }
        return base;
    }

    private static void requireNumericId(String value) {
        if (value == null || value.isEmpty() || value.length() > 12) {
            throw new IllegalArgumentException("Invalid canonical song ID");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException("Invalid canonical song ID");
            }
        }
    }

    private static byte[] readValidCache(Path cacheFile) throws IOException {
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        long size = Files.size(cacheFile);
        if (size < PNG_SIGNATURE.length || size > MAX_COVER_BYTES) {
            return null;
        }
        byte[] bytes = Files.readAllBytes(cacheFile);
        return isValidPng(bytes) ? bytes : null;
    }

    private void requireValidPng(byte[] bytes) throws IOException {
        if (!isValidPng(bytes)) {
            throw new IOException(gameName + " cover provider returned an invalid PNG");
        }
    }

    private static boolean isValidPng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_SIGNATURE.length
                || bytes.length > MAX_COVER_BYTES) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static void atomicWrite(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".cover-", ".tmp");
        boolean moved = false;
        try {
            Files.write(
                    temporary,
                    content,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    @FunctionalInterface
    interface RemoteFetcher {
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }

    private static final class HttpRemoteFetcher implements RemoteFetcher {
        private final HttpClient client = buildHttpClient();
        private final URI remoteBase;
        private final String gameName;

        private HttpRemoteFetcher(URI remoteBase, String gameName) {
            this.remoteBase = requireTrustedBase(remoteBase);
            this.gameName = gameName;
        }

        @Override
        public byte[] fetch(URI uri) throws IOException, InterruptedException {
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !remoteBase.getHost().equalsIgnoreCase(uri.getHost())
                    || uri.getPort() != -1
                    || uri.getUserInfo() != null
                    || uri.getQuery() != null
                    || uri.getFragment() != null
                    || uri.getPath() == null
                    || !uri.getPath().startsWith(remoteBase.getPath())) {
                throw new IOException("Refusing an unexpected " + gameName + " cover host");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.limiting(
                            HttpResponse.BodyHandlers.ofByteArray(), MAX_COVER_BYTES));
            if (response.statusCode() != 200) {
                throw new IOException(
                        gameName + " cover provider returned HTTP "
                                + response.statusCode());
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/png")) {
                throw new IOException(
                        gameName + " cover provider returned a non-PNG response");
            }
            return response.body();
        }
    }
}
