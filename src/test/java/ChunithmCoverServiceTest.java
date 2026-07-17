import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests for the bounded CHUNITHM cover cache. */
public final class ChunithmCoverServiceTest {
    private static int tests;

    private ChunithmCoverServiceTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("chunithm-cover-test-");
        try {
            AtomicInteger calls = new AtomicInteger();
            byte[] png = pngBytes(64);
            ChunithmCoverService service = new ChunithmCoverService(
                    root,
                    uri -> {
                        expect(
                                URI.create("https://assets2.lxns.net/chunithm/jacket/3.png"),
                                uri,
                                "fixed remote cover URI");
                        calls.incrementAndGet();
                        return png;
                    });

            expect(false, service.isCached("3"), "uncached ID is reported locally");
            expect(png, service.cover("3"), "first request returns PNG");
            expect(png, service.cover("0003"), "canonical ID reuses cache");
            expect(1, calls.get(), "cache avoids a second remote request");
            expect(true, Files.isRegularFile(root.resolve("3.png")), "cover is cached");
            expect(true, service.isCached("0003"), "valid canonical cache is detected");

            List<URI> fallbackCalls = new ArrayList<>();
            ChunithmCoverService fallback = new ChunithmCoverService(
                    root.resolve("fallback"),
                    uri -> {
                        fallbackCalls.add(uri);
                        if (uri.equals(ChunithmCoverService.remoteUriFor("1051"))) {
                            throw new IOException("HTTP 404");
                        }
                        expect(
                                ChunithmCoverService.fallbackUriFor("1051"),
                                uri,
                                "removed song uses only its audited fallback URI");
                        return png;
                    });
            expect(png, fallback.cover("1051"),
                    "missing LXNS jacket is fetched from the audited fallback");
            expect(2, fallbackCalls.size(),
                    "fallback runs once after the primary source fails");
            expect(true, fallback.isCached("1051"),
                    "fallback jacket is stored under the canonical SongID");
            expect(png, fallback.cover("01051"),
                    "fallback cache is reused without another network request");
            expect(2, fallbackCalls.size(),
                    "cached fallback avoids repeated provider requests");

            expect(
                    URI.create("https://assets2.lxns.net/chunithm/jacket/1557.png"),
                    ChunithmCoverService.remoteUriFor("1557"),
                    "remote URI uses the numeric song ID without padding");
            expect(HttpClient.Redirect.NEVER,
                    ChunithmCoverService.redirectPolicy(),
                    "cover client rejects redirects away from the fixed CDN");
            expect(
                    URI.create(
                            "https://silentblue.remywiki.com/images/1/1c/"
                                    + "Tadashii_itsuwari_kara_no_kishou.png"),
                    ChunithmCoverService.fallbackUriFor("1051"),
                    "removed song fallback is fixed and reviewable");
            expect(null, ChunithmCoverService.fallbackUriFor("3"),
                    "ordinary songs have no third-party fallback");
            expect(true,
                    ChunithmCoverService.isTrustedRemoteUri(
                            ChunithmCoverService.remoteUriFor("3")),
                    "numeric LXNS jacket URI is trusted");
            expect(true,
                    ChunithmCoverService.isTrustedRemoteUri(
                            ChunithmCoverService.fallbackUriFor("1051")),
                    "exact audited fallback URI is trusted");
            expect(false,
                    ChunithmCoverService.isTrustedRemoteUri(
                            URI.create("https://silentblue.remywiki.com/images/other.png")),
                    "other paths on the fallback host are rejected");
            expect(false,
                    ChunithmCoverService.isTrustedRemoteUri(
                            URI.create("https://assets2.lxns.net/chunithm/jacket/3.png?x=1")),
                    "LXNS jacket URI with a query is rejected");
            expect("0", ChunithmCoverService.canonicalSongId(" 000 "),
                    "zero is canonicalized safely");

            AtomicInteger ordinaryFailureCalls = new AtomicInteger();
            ChunithmCoverService noFallback = new ChunithmCoverService(
                    root.resolve("no-fallback"),
                    uri -> {
                        ordinaryFailureCalls.incrementAndGet();
                        throw new IOException("HTTP 404");
                    });
            expectThrows(IOException.class, () -> noFallback.cover("7"));
            expect(1, ordinaryFailureCalls.get(),
                    "an ordinary missing jacket does not try an unlisted URL");

            expectThrows(IllegalArgumentException.class,
                    () -> ChunithmCoverService.canonicalSongId("../3"));
            expectThrows(IllegalArgumentException.class,
                    () -> ChunithmCoverService.canonicalSongId("3.png"));
            expectThrows(IllegalArgumentException.class,
                    () -> ChunithmCoverService.canonicalSongId("-1"));
            expectThrows(IllegalArgumentException.class,
                    () -> ChunithmCoverService.canonicalSongId("2147483648"));

            ChunithmCoverService invalid = new ChunithmCoverService(
                    root.resolve("invalid"), uri -> "not a png".getBytes());
            expectThrows(IOException.class, () -> invalid.cover("7"));

            byte[] oversized = pngBytes(ChunithmCoverService.MAX_COVER_BYTES + 1);
            ChunithmCoverService tooLarge = new ChunithmCoverService(
                    root.resolve("too-large"), uri -> oversized);
            expectThrows(IOException.class, () -> tooLarge.cover("8"));

            System.out.println(
                    "ChunithmCoverServiceTest: all " + tests + " tests passed.");
        } finally {
            deleteRecursively(root);
        }
    }

    private static byte[] pngBytes(int size) {
        byte[] bytes = new byte[size];
        byte[] signature = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };
        System.arraycopy(signature, 0, bytes, 0, signature.length);
        return bytes;
    }

    private static void expect(Object expected, Object actual, String message) {
        tests++;
        if (expected instanceof byte[] expectedBytes && actual instanceof byte[] actualBytes) {
            if (java.util.Arrays.equals(expectedBytes, actualBytes)) {
                return;
            }
        } else if (java.util.Objects.equals(expected, actual)) {
            return;
        }
        throw new AssertionError(
                message + ": expected=" + expected + ", actual=" + actual);
    }

    private static void expectThrows(
            Class<? extends Throwable> expected, ThrowingAction action) throws Exception {
        tests++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
