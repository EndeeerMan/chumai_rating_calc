import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.Objects;

/** Fetches and caches maimai DX jacket images from the fixed LXNS CDN. */
public final class MaimaiCoverService {
    static final int MAX_COVER_BYTES = LxnsCoverService.MAX_COVER_BYTES;
    private static final URI REMOTE_BASE =
            URI.create("https://assets2.lxns.net/maimai/jacket/");

    private final LxnsCoverService delegate;

    public MaimaiCoverService(Path cacheDirectory) throws IOException {
        delegate = new LxnsCoverService(cacheDirectory, REMOTE_BASE, "Maimai");
    }

    MaimaiCoverService(Path cacheDirectory, RemoteFetcher fetcher) throws IOException {
        Objects.requireNonNull(fetcher, "fetcher must not be null");
        delegate = new LxnsCoverService(
                cacheDirectory,
                REMOTE_BASE,
                "Maimai",
                fetcher::fetch);
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

    static HttpClient buildHttpClient() {
        return LxnsCoverService.buildHttpClient();
    }

    static HttpClient.Redirect redirectPolicy() {
        return LxnsCoverService.redirectPolicy();
    }

    static String canonicalSongId(String suppliedSongId) {
        return SongCatalog.canonicalSongId(suppliedSongId);
    }

    @FunctionalInterface
    interface RemoteFetcher {
        byte[] fetch(URI uri) throws IOException, InterruptedException;
    }
}
