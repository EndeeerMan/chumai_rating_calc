import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Fetches and caches CHUNITHM jacket images from audited public sources. */
public final class ChunithmCoverService {
    static final int MAX_COVER_BYTES = LxnsCoverService.MAX_COVER_BYTES;
    private static final URI REMOTE_BASE =
            URI.create("https://assets2.lxns.net/chunithm/jacket/");
    private static final Map<String, URI> FALLBACK_REMOTES = Map.of(
            // This removed song is still present in Diving-Fish, but its
            // numeric jacket is absent from the current LXNS asset set.  The
            // fixed RemyWiki file page identifies this 512 px PNG as the
            // jacket used by Nouriue no cracker.  Keep the exact URI audited:
            // never derive an arbitrary third-party URL from catalogue data.
            "1051",
            URI.create(
                    "https://silentblue.remywiki.com/images/1/1c/"
                            + "Tadashii_itsuwari_kara_no_kishou.png"));

    private final LxnsCoverService delegate;

    public ChunithmCoverService(Path cacheDirectory) throws IOException {
        this(cacheDirectory, new TrustedHttpFetcher());
    }

    ChunithmCoverService(Path cacheDirectory, RemoteFetcher fetcher) throws IOException {
        Objects.requireNonNull(fetcher, "fetcher must not be null");
        delegate = new LxnsCoverService(
                cacheDirectory,
                REMOTE_BASE,
                "CHUNITHM",
                uri -> fetchWithAuditedFallback(uri, fetcher));
    }

    /** Returns a validated PNG, using the local cache after the first request. */
    public byte[] cover(String suppliedSongId) throws IOException, InterruptedException {
        return delegate.cover(canonicalSongId(suppliedSongId));
    }

    /** Reports whether a valid local PNG exists without performing network I/O. */
    boolean isCached(String suppliedSongId) throws IOException {
        return delegate.isCached(canonicalSongId(suppliedSongId));
    }

    static URI remoteUriFor(String suppliedSongId) {
        String songId = canonicalSongId(suppliedSongId);
        return REMOTE_BASE.resolve(songId + ".png");
    }

    static URI fallbackUriFor(String suppliedSongId) {
        return FALLBACK_REMOTES.get(canonicalSongId(suppliedSongId));
    }

    static HttpClient buildHttpClient() {
        return LxnsCoverService.buildHttpClient();
    }

    static HttpClient.Redirect redirectPolicy() {
        return LxnsCoverService.redirectPolicy();
    }

    private static byte[] fetchWithAuditedFallback(
            URI primaryUri,
            RemoteFetcher fetcher) throws IOException, InterruptedException {
        try {
            return fetcher.fetch(primaryUri);
        } catch (IOException primaryError) {
            URI fallbackUri = fallbackForPrimaryUri(primaryUri);
            if (fallbackUri == null) {
                throw primaryError;
            }
            try {
                return fetcher.fetch(fallbackUri);
            } catch (IOException fallbackError) {
                fallbackError.addSuppressed(primaryError);
                throw fallbackError;
            }
        }
    }

    private static URI fallbackForPrimaryUri(URI primaryUri) {
        for (Map.Entry<String, URI> entry : FALLBACK_REMOTES.entrySet()) {
            if (remoteUriFor(entry.getKey()).equals(primaryUri)) {
                return entry.getValue();
            }
        }
        return null;
    }

    static boolean isTrustedRemoteUri(URI uri) {
        if (uri == null) {
            return false;
        }
        if (FALLBACK_REMOTES.containsValue(uri)) {
            return true;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !REMOTE_BASE.getHost().equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getPath() == null
                || !uri.getPath().startsWith(REMOTE_BASE.getPath())
                || !uri.getPath().endsWith(".png")) {
            return false;
        }
        String id = uri.getPath().substring(
                REMOTE_BASE.getPath().length(),
                uri.getPath().length() - ".png".length());
        try {
            return remoteUriFor(id).equals(uri);
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static String canonicalSongId(String suppliedSongId) {
        if (suppliedSongId == null) {
            throw new IllegalArgumentException("CHUNITHM song ID is required");
        }
        String value = suppliedSongId.strip();
        if (value.isEmpty() || value.length() > 10) {
            throw new IllegalArgumentException("Invalid CHUNITHM song ID");
        }
        for (int index = 0; index < value.length(); index++) {
            if (!Character.isDigit(value.charAt(index))) {
                throw new IllegalArgumentException("Invalid CHUNITHM song ID");
            }
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0 || parsed > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid CHUNITHM song ID");
            }
            return Long.toString(parsed);
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Invalid CHUNITHM song ID", error);
        }
    }

    @FunctionalInterface
    interface RemoteFetcher {
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }

    private static final class TrustedHttpFetcher implements RemoteFetcher {
        private final HttpClient client = buildHttpClient();

        @Override
        public byte[] fetch(URI uri) throws IOException, InterruptedException {
            if (!isTrustedRemoteUri(uri)) {
                throw new IOException("Refusing an unexpected CHUNITHM cover host");
            }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "image/png")
                    .GET()
                    .build();
            HttpResponse<byte[]> response = client.send(
                    request,
                    HttpResponse.BodyHandlers.limiting(
                            HttpResponse.BodyHandlers.ofByteArray(),
                            MAX_COVER_BYTES));
            if (response.statusCode() != 200) {
                throw new IOException(
                        "CHUNITHM cover provider returned HTTP "
                                + response.statusCode());
            }
            String contentType = response.headers()
                    .firstValue("Content-Type")
                    .orElse("")
                    .toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("image/png")) {
                throw new IOException(
                        "CHUNITHM cover provider returned a non-PNG response");
            }
            return response.body();
        }
    }
}
