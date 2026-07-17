import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free HTTP boundary tests for song catalogue endpoints. */
public final class WebServerSongCatalogTest {
    private static final String LOCAL_JSON = """
            [{"title":"Alpha Song","artist":"Artist","category":"舞萌",
              "image_file":"alpha.png","lev_bas":"4","lev_adv":"7",
              "lev_exp":"10","lev_mas":"13","version":"舞萌DX 2026"}]
            """;
    private static final String SNAPSHOT_JSON = """
            [{"id":"10","title":"Alpha Song","type":"SD",
              "ds":[4.0,7.0,10.0,13.0],"level":["4","7","10","13"],
              "basic_info":{"artist":"Artist","genre":"舞萌"}}]
            """;
    private static final byte[] PNG = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00
    };
    private static int tests;

    private WebServerSongCatalogTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger networkCalls = new AtomicInteger();
        SongCatalog catalog = SongCatalog.fromJson(
                LOCAL_JSON,
                SNAPSHOT_JSON,
                endpoint -> {
                    networkCalls.incrementAndGet();
                    throw new IOException("test offline");
                },
                Duration.ofMinutes(5),
                () -> 100L);
        HttpHandler catalogHandler = handler(
                "WebServer$SongCatalogHandler",
                new Class<?>[]{SongCatalog.class},
                catalog);
        HttpHandler searchHandler = handler(
                "WebServer$SongSearchHandler",
                new Class<?>[]{SongCatalog.class},
                catalog);

        FakeExchange response = request(
                catalogHandler, "GET", "/api/songs/catalog");
        expect(200, response.status, "catalog GET succeeds");
        expect(true, response.body().contains("\"source\":\"snapshot\""),
                "catalog reports snapshot source");
        expect(true, response.body().contains("\"count\":1"),
                "catalog contains its songs");
        expect("application/json; charset=utf-8",
                response.responseHeaders.getFirst("Content-Type"),
                "catalogue response is JSON");
        expect(0, networkCalls.get(), "catalog GET never accesses the network");

        response = request(catalogHandler, "POST", "/api/songs/catalog");
        expect(405, response.status, "catalog rejects POST");
        expect("GET", response.responseHeaders.getFirst("Allow"),
                "catalog Allow header");
        expectStatus(catalogHandler, 404, "/api/songs/catalog/extra",
                "catalog requires exact path");
        expectStatus(catalogHandler, 400, "/api/songs/catalog?online=true",
                "catalog rejects query fields");

        response = request(searchHandler, "GET", "/api/songs/search?q=Alpha+Song");
        expect(200, response.status, "search GET succeeds");
        expect(true, response.body().contains("\"songId\":\"10\""),
                "search returns official Song ID");
        expect(0, networkCalls.get(), "search defaults to offline snapshot");
        response = request(
                searchHandler, "GET", "/api/songs/search?q=Alpha&limit=1");
        expect(200, response.status, "explicit bounded limit succeeds");

        expectStatus(searchHandler, 400, "/api/songs/search", "q is required");
        expectStatus(searchHandler, 400, "/api/songs/search?q=+", "blank q rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&q=b",
                "duplicate q rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&limit=0",
                "zero limit rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&limit=101",
                "oversized limit rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&limit=lots",
                "non-numeric limit rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&online=maybe",
                "invalid online flag rejected");
        expectStatus(searchHandler, 400, "/api/songs/search?q=a&other=x",
                "unknown parameter rejected");
        expectStatus(searchHandler, 400,
                "/api/songs/search?q=" + "a".repeat(121),
                "oversized q rejected");
        expectStatus(searchHandler, 404, "/api/songs/search/extra?q=a",
                "search requires exact path");
        response = request(searchHandler, "POST", "/api/songs/search?q=a");
        expect(405, response.status, "search rejects POST");

        response = request(
                searchHandler, "GET", "/api/songs/search?q=Alpha&online=true");
        expect(200, response.status, "online failure is transparent");
        expect(true, response.body().contains("\"source\":\"snapshot\""),
                "online failure uses snapshot");
        expect(true, response.body().contains("test offline"),
                "online failure carries a warning");
        expect(1, networkCalls.get(), "online=true performs one bounded attempt");

        SongCatalog noBundledCover = SongCatalog.fromJson(
                "[]",
                SNAPSHOT_JSON,
                endpoint -> {
                    throw new IOException("network must not run");
                },
                Duration.ofMinutes(5),
                () -> 100L);
        expect(true,
                noBundledCover.catalog().toJson().contains(
                        "\"coverUrl\":\"/api/maimai/covers/10.png\""),
                "catalog fallback uses the same-origin SongID cover endpoint");

        testCover();

        System.out.println(
                "WebServerSongCatalogTest: all " + tests + " tests passed.");
    }

    private static void testCover() throws Exception {
        Path cache = Files.createTempDirectory("web-maimai-cover-");
        try {
            AtomicInteger calls = new AtomicInteger();
            MaimaiCoverService service = new MaimaiCoverService(cache, uri -> {
                calls.incrementAndGet();
                if (uri.toString().endsWith("/7.png")) {
                    throw new IOException("remote unavailable");
                }
                expect(URI.create("https://assets2.lxns.net/maimai/jacket/10.png"),
                        uri, "cover proxy uses fixed CDN and canonical SongID");
                return PNG;
            });
            HttpHandler handler = handler(
                    "WebServer$MaimaiCoverHandler",
                    new Class<?>[]{MaimaiCoverService.class},
                    service);

            FakeExchange response = request(
                    handler, "GET", "/api/maimai/covers/10010.png");
            expect(200, response.status, "cover GET succeeds");
            expect("image/png", response.responseHeaders.getFirst("Content-Type"),
                    "cover MIME type");
            expect("public, max-age=31536000, immutable",
                    response.responseHeaders.getFirst("Cache-Control"),
                    "cover receives immutable browser cache policy");
            expect(true, java.util.Arrays.equals(PNG, response.bodyBytes()),
                    "cover bytes are returned");
            expect(1, calls.get(), "first cover request reaches CDN seam");
            expect(true, Files.isRegularFile(cache.resolve("10.png")),
                    "cover is cached under the canonical SongID");

            response = request(handler, "HEAD", "/api/maimai/covers/10.png");
            expect(200, response.status, "cover HEAD succeeds");
            expect("12", response.responseHeaders.getFirst("Content-Length"),
                    "cover HEAD exposes byte length");
            expect(0, response.bodyBytes().length, "cover HEAD has no body");
            expect(1, calls.get(), "cached cover avoids another remote request");

            expectStatus(handler, 400, "/api/maimai/covers/../10.png",
                    "cover path traversal rejected");
            expectStatus(handler, 404, "/api/maimai/covers/10.jpg",
                    "wrong cover extension not found");
            expectStatus(handler, 404, "/api/maimai/covers/",
                    "missing cover ID not found");
            expectStatus(handler, 400, "/api/maimai/covers/10.png?x=1",
                    "cover query rejected");
            response = request(handler, "POST", "/api/maimai/covers/10.png");
            expect(405, response.status, "cover rejects POST");
            expect("GET, HEAD", response.responseHeaders.getFirst("Allow"),
                    "cover Allow header");
            expectStatus(handler, 502, "/api/maimai/covers/7.png",
                    "remote cover failure maps to 502");
        } finally {
            deleteRecursively(cache);
        }
    }

    private static void expectStatus(
            HttpHandler handler, int status, String path, String label)
            throws IOException {
        expect(status, request(handler, "GET", path).status, label);
    }

    private static FakeExchange request(
            HttpHandler handler, String method, String path) throws IOException {
        FakeExchange exchange = new FakeExchange(method, URI.create(path));
        handler.handle(exchange);
        return exchange;
    }

    private static HttpHandler handler(
            String className, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return (HttpHandler) constructor.newInstance(arguments);
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private InputStream requestBody = new ByteArrayInputStream(new byte[0]);
        private OutputStream responseBody = new ByteArrayOutputStream();
        private int status = -1;

        private FakeExchange(String method, URI uri) {
            this.method = method;
            this.uri = uri;
        }

        private String body() {
            return ((ByteArrayOutputStream) responseBody)
                    .toString(StandardCharsets.UTF_8);
        }

        private byte[] bodyBytes() {
            return ((ByteArrayOutputStream) responseBody).toByteArray();
        }

        @Override
        public Headers getRequestHeaders() {
            return requestHeaders;
        }

        @Override
        public Headers getResponseHeaders() {
            return responseHeaders;
        }

        @Override
        public URI getRequestURI() {
            return uri;
        }

        @Override
        public String getRequestMethod() {
            return method;
        }

        @Override
        public HttpContext getHttpContext() {
            return null;
        }

        @Override
        public void close() {
        }

        @Override
        public InputStream getRequestBody() {
            return requestBody;
        }

        @Override
        public OutputStream getResponseBody() {
            return responseBody;
        }

        @Override
        public void sendResponseHeaders(int responseCode, long responseLength) {
            status = responseCode;
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return new InetSocketAddress(12345);
        }

        @Override
        public int getResponseCode() {
            return status;
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return new InetSocketAddress(8080);
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
        }

        @Override
        public void setStreams(InputStream input, OutputStream output) {
            requestBody = input;
            responseBody = output;
        }

        @Override
        public HttpPrincipal getPrincipal() {
            return null;
        }
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
}
