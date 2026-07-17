import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free tests for the bounded maimai DX cover cache. */
public final class MaimaiCoverServiceTest {
    private static int tests;

    private MaimaiCoverServiceTest() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("maimai-cover-test-");
        try {
            AtomicInteger calls = new AtomicInteger();
            byte[] png = pngBytes(64);
            MaimaiCoverService service = new MaimaiCoverService(
                    root,
                    uri -> {
                        expect(
                                URI.create("https://assets2.lxns.net/maimai/jacket/3.png"),
                                uri,
                                "fixed remote cover URI");
                        calls.incrementAndGet();
                        return png;
                    });

            expect(false, service.isCached("3"), "uncached ID is reported locally");
            expect(png, service.cover("10003"), "DX offset resolves to base SongID");
            expect(png, service.cover("0003"), "canonical ID reuses cache");
            expect(1, calls.get(), "cache avoids a second remote request");
            expect(true, Files.isRegularFile(root.resolve("3.png")), "cover is cached");
            expect(true, service.isCached("10003"), "valid canonical cache is detected");

            expect(
                    URI.create("https://assets2.lxns.net/maimai/jacket/1848.png"),
                    MaimaiCoverService.remoteUriFor("1848"),
                    "remote URI uses the canonical SongID");
            expect(HttpClient.Redirect.NEVER,
                    MaimaiCoverService.redirectPolicy(),
                    "cover client rejects redirects away from the fixed CDN");
            expect("0", MaimaiCoverService.canonicalSongId(" 000 "),
                    "zero is canonicalized safely");
            expect("3", MaimaiCoverService.canonicalSongId("10003"),
                    "Diving-Fish DX offset is folded before caching");

            expectThrows(IllegalArgumentException.class,
                    () -> MaimaiCoverService.canonicalSongId("../3"));
            expectThrows(IllegalArgumentException.class,
                    () -> MaimaiCoverService.canonicalSongId("3.png"));
            expectThrows(IllegalArgumentException.class,
                    () -> MaimaiCoverService.canonicalSongId("-1"));

            MaimaiCoverService invalid = new MaimaiCoverService(
                    root.resolve("invalid"), uri -> "not a png".getBytes());
            expectThrows(IOException.class, () -> invalid.cover("7"));
            expect(false, invalid.isCached("7"), "invalid response is not cached");

            byte[] oversized = pngBytes(MaimaiCoverService.MAX_COVER_BYTES + 1);
            MaimaiCoverService tooLarge = new MaimaiCoverService(
                    root.resolve("too-large"), uri -> oversized);
            expectThrows(IOException.class, () -> tooLarge.cover("8"));

            System.out.println(
                    "MaimaiCoverServiceTest: all " + tests + " tests passed.");
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
