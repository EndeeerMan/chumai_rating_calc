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
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free HTTP boundary tests for the CHUNITHM public API. */
public final class WebServerChunithmTest {
    private static final String MUSIC_JSON = """
            [
              {
                "id":1,"title":"Old Song","ds":[4,7,10,14],
                "level":["4","7","10","14"],"cids":[11,12,13,14],
                "charts":[
                  {"combo":100,"charter":"A"},{"combo":200,"charter":"B"},
                  {"combo":300,"charter":"C"},{"combo":400,"charter":"D"}
                ],
                "basic_info":{"title":"Old Song","artist":"Old Artist",
                  "genre":"原创","bpm":180,"from":"OLD"}
              },
              {
                "id":2,"title":"New Song","ds":[3,6,9,13],
                "level":["3","6","9","13"],"cids":[21,22,23,24],
                "charts":[
                  {"combo":100,"charter":"A"},{"combo":200,"charter":"B"},
                  {"combo":300,"charter":"C"},{"combo":400,"charter":"D"}
                ],
                "basic_info":{"title":"New Song","artist":"New Artist",
                  "genre":"niconico","bpm":190,"from":"NEW"}
              },
              {
                "id":8000,"title":"[止]World End","ds":[0],
                "level":["止"],"cids":[80],
                "charts":[{"combo":500,"charter":"WE Maker"}],
                "basic_info":{"title":"[止]World End","artist":"WE Artist",
                  "genre":"原创","bpm":200,"from":"OLD"}
              }
            ]
            """;
    private static final String VERSION_JSON = "{\"version\":[\"NEW\"]}";
    private static final byte[] PNG = {
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00
    };
    private static int tests;

    private WebServerChunithmTest() {
    }

    public static void main(String[] args) throws Exception {
        AtomicInteger catalogNetworkCalls = new AtomicInteger();
        ChunithmCatalog catalog = ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                endpoint -> {
                    catalogNetworkCalls.incrementAndGet();
                    throw new IOException("offline fixture");
                },
                Duration.ofMinutes(5),
                () -> 100L);
        Path cache = Files.createTempDirectory("web-chunithm-cover-");
        try {
            testCatalogAndSearch(catalog, catalogNetworkCalls);
            testCalculate(catalog);
            testCalculateUsesActiveCatalogMetadata();
            testCover(cache);
            testSafeLoader();
            System.out.println(
                    "WebServerChunithmTest: all " + tests + " tests passed.");
        } finally {
            deleteRecursively(cache);
        }
    }

    private static void testCatalogAndSearch(
            ChunithmCatalog catalog, AtomicInteger networkCalls)
            throws Exception {
        HttpHandler catalogHandler = handler(
                "WebServer$ChunithmCatalogHandler",
                new Class<?>[]{ChunithmCatalog.class},
                catalog);
        HttpHandler searchHandler = handler(
                "WebServer$ChunithmSearchHandler",
                new Class<?>[]{ChunithmCatalog.class},
                catalog);

        FakeExchange response = request(
                catalogHandler, "GET", "/api/chunithm/songs/catalog");
        expect(200, response.status, "catalog GET succeeds");
        Map<?, ?> body = jsonObject(response.body());
        expect(new BigDecimal("3"), body.get("count"),
                "catalog returns every fixture song");
        expect(List.of("NEW"), body.get("latestVersions"),
                "catalog exposes latest version metadata");
        expect(0, networkCalls.get(), "catalog defaults to bundled snapshot");

        response = request(catalogHandler, "GET",
                "/api/chunithm/songs/catalog?online=false");
        expect(200, response.status, "catalog accepts online=false");
        expect(0, networkCalls.get(), "online=false never accesses network");
        response = request(catalogHandler, "GET",
                "/api/chunithm/songs/catalog?online=true");
        expect(200, response.status, "online failure transparently falls back");
        expect(true, response.body().contains("offline fixture"),
                "online failure includes fallback warning");
        expect(1, networkCalls.get(), "online refresh is attempted once");

        expectStatus(catalogHandler, "GET",
                "/api/chunithm/songs/catalog?online=maybe", 400,
                "invalid catalog online flag rejected");
        expectStatus(catalogHandler, "GET",
                "/api/chunithm/songs/catalog?online=true&online=false", 400,
                "duplicate catalog online flag rejected");
        expectStatus(catalogHandler, "GET",
                "/api/chunithm/songs/catalog?other=x", 400,
                "unknown catalog query rejected");
        response = request(catalogHandler, "POST",
                "/api/chunithm/songs/catalog");
        expect(405, response.status, "catalog rejects POST");
        expect("GET", response.responseHeaders.getFirst("Allow"),
                "catalog Allow header");
        expectStatus(catalogHandler, "GET",
                "/api/chunithm/songs/catalog/extra", 404,
                "catalog requires exact path");

        response = request(searchHandler, "GET",
                "/api/chunithm/songs/search?q=Old+Song&limit=1");
        expect(200, response.status, "search GET succeeds");
        body = jsonObject(response.body());
        List<?> songs = (List<?>) body.get("songs");
        expect("1", ((Map<?, ?>) songs.getFirst()).get("songId"),
                "search returns matching Song ID");
        expectStatus(searchHandler, "GET",
                "/api/chunithm/songs/search", 400,
                "search requires q");
        expectStatus(searchHandler, "GET",
                "/api/chunithm/songs/search?q=a&unknown=x", 400,
                "search rejects unknown query");
        response = request(searchHandler, "HEAD",
                "/api/chunithm/songs/search?q=a");
        expect(405, response.status, "search rejects HEAD");
    }

    private static void testCalculate(ChunithmCatalog catalog) throws Exception {
        HttpHandler handler = handler(
                "WebServer$ChunithmCalculateHandler",
                new Class<?>[]{ChunithmCatalog.class},
                catalog);
        String requestJson = """
                {"charts":[
                  {"songId":"1","title":"Old Song","difficulty":"MASTER",
                   "constant":14.0,"score":1010000,"version":"OLD"},
                  {"songId":"2","title":"New Song","difficulty":"MASTER",
                   "constant":13.0,"score":1010000,"version":"NEW"},
                  {"songId":"8000","title":"[止]World End",
                   "difficulty":"WORLD'S END","constant":0.0,
                   "score":1010000,"version":"OLD"}
                ]}
                """;
        FakeExchange response = jsonRequest(
                handler, "POST", "/api/chunithm/calculate", requestJson);
        expect(200, response.status, "calculate POST succeeds");
        expect("application/json; charset=utf-8",
                response.responseHeaders.getFirst("Content-Type"),
                "calculate response is JSON");
        Map<?, ?> body = jsonObject(response.body());
        expectDecimal("0.626", body.get("rating"), "combined fixed-divisor Rating");
        expectDecimal("0.323", body.get("b30Rating"),
                "B30 Rating equals B30 sum divided by 50");
        expectDecimal("0.303", body.get("n20Rating"),
                "N20 Rating equals N20 sum divided by 50");
        expectDecimal("16.15", body.get("b30Sum"), "B30 contribution sum");
        expectDecimal("15.15", body.get("n20Sum"), "N20 contribution sum");
        expect(new BigDecimal("2"), body.get("selectedCount"),
                "only eligible pools contribute");
        expect(new BigDecimal("1"), body.get("b30Count"), "one B30 chart");
        expect(new BigDecimal("1"), body.get("n20Count"), "one N20 chart");

        List<?> charts = (List<?>) body.get("charts");
        expect(3, charts.size(), "response retains every input chart");
        Map<?, ?> worldEnd = findChart(charts, "8000");
        expect(false, worldEnd.get("ratingEligible"),
                "World's End is Rating-ineligible");
        expect(false, worldEnd.get("selected"),
                "World's End is never selected");
        expect(new BigDecimal("0"), worldEnd.get("poolRank"),
                "World's End has no pool rank");
        expectDecimal("2.15", worldEnd.get("rating"),
                "World's End display Rating remains available");
        expect(true, ((Map<?, ?>) charts.get(1)).containsKey("newChart"),
                "chart output contains selection metadata");

        response = request(handler, "OPTIONS", "/api/chunithm/calculate");
        expect(204, response.status, "calculate OPTIONS succeeds");
        expect("POST, OPTIONS", response.responseHeaders.getFirst("Allow"),
                "calculate OPTIONS Allow header");
        response = request(handler, "GET", "/api/chunithm/calculate");
        expect(405, response.status, "calculate rejects GET");
        response = request(handler, "POST", "/api/chunithm/calculate");
        expect(415, response.status, "calculate requires JSON Content-Type");
        response = jsonRequest(handler, "POST",
                "/api/chunithm/calculate?unexpected=true", requestJson);
        expect(400, response.status, "calculate rejects query parameters");

        expectJsonStatus(handler, "[]", 400, "root array rejected");
        expectJsonStatus(handler, "{\"charts\":[],\"extra\":1}", 400,
                "unknown root field rejected");
        expectJsonStatus(handler, "{\"charts\":[]}", 400,
                "empty chart list rejected");
        expectJsonStatus(handler, """
                {"charts":[{"songId":"1","title":"A","difficulty":"MASTER",
                 "constant":14,"score":1000000,"version":"OLD","extra":1}]}
                """, 400, "unknown chart field rejected");
        expectJsonStatus(handler, """
                {"charts":[{"songId":"1","title":"A","difficulty":"MASTER",
                 "constant":"14","score":1000000,"version":"OLD"}]}
                """, 400, "string constant rejected");
        expectJsonStatus(handler, """
                {"charts":[{"songId":"1","title":"A","difficulty":"MASTER",
                 "constant":14,"score":1000000.5,"version":"OLD"}]}
                """, 400, "fractional score rejected");
        expectJsonStatus(handler, """
                {"charts":[{"songId":"1","title":"A","difficulty":"UNKNOWN",
                 "constant":14,"score":1000000,"version":"OLD"}]}
                """, 400, "unknown difficulty rejected");
        expectJsonStatus(handler, """
                {"charts":[
                 {"songId":"1","title":"A","difficulty":"MASTER",
                  "constant":14,"score":1000000,"version":"OLD"},
                 {"songId":"1","title":"A","difficulty":"MASTER",
                  "constant":14,"score":1000000,"version":"OLD"}]}
                """, 400, "duplicate chart rejected");

        StringBuilder tooMany = new StringBuilder("{\"charts\":[");
        for (int index = 0; index <= 3_000; index++) {
            if (index > 0) {
                tooMany.append(',');
            }
            tooMany.append("{\"songId\":\"").append(index)
                    .append("\",\"title\":\"A\",\"difficulty\":\"MASTER\",")
                    .append("\"constant\":14,\"score\":0,\"version\":\"OLD\"}");
        }
        tooMany.append("]}");
        expectJsonStatus(handler, tooMany.toString(), 413,
                "chart count is bounded");
        expectJsonStatus(handler, " ".repeat(2 * 1024 * 1024 + 1), 413,
                "calculate request bytes are bounded");
    }

    private static void testCalculateUsesActiveCatalogMetadata() throws Exception {
        String lxnsSongs = """
                {"songs":[{"id":1,"disabled":true,"difficulties":[
                  {"difficulty":0,"version":100},
                  {"difficulty":1,"version":100},
                  {"difficulty":2,"version":100},
                  {"difficulty":3,"version":100}
                ]}],"genres":[],"versions":[
                  {"id":1,"title":"OLD","version":100},
                  {"id":2,"title":"NEW","version":200}
                ]}
                """;
        ChunithmCatalog catalog = ChunithmCatalog.fromJson(
                MUSIC_JSON,
                VERSION_JSON,
                endpoint -> {
                    if (endpoint.equals(ChunithmCatalog.DIVING_FISH_MUSIC_ENDPOINT)) {
                        return MUSIC_JSON.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.DIVING_FISH_VERSION_ENDPOINT)) {
                        return VERSION_JSON.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.LXNS_SONG_ENDPOINT)) {
                        return lxnsSongs.getBytes(StandardCharsets.UTF_8);
                    }
                    if (endpoint.equals(ChunithmCatalog.LXNS_ALIAS_ENDPOINT)) {
                        return "{\"aliases\":[]}".getBytes(StandardCharsets.UTF_8);
                    }
                    throw new AssertionError("unexpected endpoint: " + endpoint);
                },
                Duration.ofMinutes(5),
                () -> 200L);
        expect("online", catalog.catalog(true).source(),
                "active metadata fixture refreshes successfully");

        HttpHandler handler = handler(
                "WebServer$ChunithmCalculateHandler",
                new Class<?>[]{ChunithmCatalog.class},
                catalog);
        FakeExchange response = jsonRequest(handler, "POST",
                "/api/chunithm/calculate", """
                {"charts":[{"songId":"1","title":"Old Song",
                  "difficulty":"MASTER","constant":14.0,
                  "score":1010000,"version":"OLD"}]}
                """);
        expect(200, response.status,
                "calculate accepts a disabled historical score");
        Map<?, ?> body = jsonObject(response.body());
        expect(new BigDecimal("0"), body.get("selectedCount"),
                "active LXNS disabled flag excludes the song");
        Map<?, ?> chart = (Map<?, ?>) ((List<?>) body.get("charts")).getFirst();
        expect(false, chart.get("ratingEligible"),
                "handler passes active disabled IDs to the calculator");
    }

    private static void testCover(Path cache) throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ChunithmCoverService service = new ChunithmCoverService(cache, uri -> {
            calls.incrementAndGet();
            if (uri.toString().endsWith("/7.png")) {
                throw new IOException("remote unavailable");
            }
            expect(URI.create(
                            "https://assets2.lxns.net/chunithm/jacket/1.png"),
                    uri, "cover proxy uses fixed CDN and canonical ID");
            return PNG;
        });
        HttpHandler handler = handler(
                "WebServer$ChunithmCoverHandler",
                new Class<?>[]{ChunithmCoverService.class},
                service);

        FakeExchange response = request(handler, "GET",
                "/api/chunithm/covers/0001.png");
        expect(200, response.status, "cover GET succeeds");
        expect("image/png", response.responseHeaders.getFirst("Content-Type"),
                "cover MIME type");
        expect("public, max-age=31536000, immutable",
                response.responseHeaders.getFirst("Cache-Control"),
                "cover receives immutable browser cache policy");
        expect(PNG, response.bodyBytes(), "cover bytes are returned");
        expect(1, calls.get(), "first cover request reaches CDN seam");
        expect(true, Files.isRegularFile(cache.resolve("1.png")),
                "cover is cached on disk");

        response = request(handler, "GET", "/api/chunithm/covers/1.png");
        expect(200, response.status, "cached cover GET succeeds");
        expect(1, calls.get(), "cached cover avoids another remote request");
        response = request(handler, "HEAD", "/api/chunithm/covers/1.png");
        expect(200, response.status, "cover HEAD succeeds");
        expect("12", response.responseHeaders.getFirst("Content-Length"),
                "cover HEAD exposes byte length");
        expect(0, response.bodyBytes().length, "cover HEAD has no body");
        expect(1, calls.get(), "cover HEAD uses cache");

        expectStatus(handler, "GET", "/api/chunithm/covers/../1.png", 400,
                "cover path traversal rejected");
        expectStatus(handler, "GET", "/api/chunithm/covers/1.jpg", 404,
                "wrong cover extension not found");
        expectStatus(handler, "GET", "/api/chunithm/covers/", 404,
                "missing cover ID not found");
        expectStatus(handler, "GET", "/api/chunithm/covers/1.png?x=1", 400,
                "cover query rejected");
        response = request(handler, "POST", "/api/chunithm/covers/1.png");
        expect(405, response.status, "cover rejects POST");
        expect("GET, HEAD", response.responseHeaders.getFirst("Allow"),
                "cover Allow header");
        expectStatus(handler, "GET", "/api/chunithm/covers/7.png", 502,
                "remote cover failure maps to 502");
    }

    private static void testSafeLoader() throws IOException {
        Path missing = Files.createTempDirectory("missing-chunithm-catalog-");
        try {
            ChunithmCatalog catalog = WebServer.loadChunithmCatalogSafely(missing);
            expect(0, catalog.catalog().songs().size(),
                    "missing snapshot yields operational empty catalogue");
            expect(true, catalog.catalog().warning() != null,
                    "missing snapshot exposes startup warning");
        } finally {
            Files.deleteIfExists(missing);
        }
    }

    private static Map<?, ?> findChart(List<?> charts, String songId) {
        for (Object value : charts) {
            Map<?, ?> chart = (Map<?, ?>) value;
            if (songId.equals(chart.get("songId"))) {
                return chart;
            }
        }
        throw new AssertionError("missing chart: " + songId);
    }

    private static void expectJsonStatus(
            HttpHandler handler, String body, int status, String label)
            throws IOException {
        expect(status, jsonRequest(
                handler, "POST", "/api/chunithm/calculate", body).status, label);
    }

    private static void expectStatus(
            HttpHandler handler, String method, String path, int status, String label)
            throws IOException {
        expect(status, request(handler, method, path).status, label);
    }

    private static FakeExchange jsonRequest(
            HttpHandler handler, String method, String path, String body)
            throws IOException {
        FakeExchange exchange = new FakeExchange(
                method,
                URI.create(path),
                body.getBytes(StandardCharsets.UTF_8));
        exchange.requestHeaders.set("Content-Type", "application/json; charset=utf-8");
        handler.handle(exchange);
        return exchange;
    }

    private static FakeExchange request(
            HttpHandler handler, String method, String path) throws IOException {
        FakeExchange exchange = new FakeExchange(
                method, URI.create(path), new byte[0]);
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

    private static Map<?, ?> jsonObject(String json) {
        Object value = Json.parse(json);
        if (!(value instanceof Map<?, ?> object)) {
            throw new AssertionError("expected JSON object: " + json);
        }
        return object;
    }

    private static void expectDecimal(
            String expected, Object actual, String label) {
        if (!(actual instanceof BigDecimal decimal)) {
            throw new AssertionError(label + ": expected decimal, got " + actual);
        }
        expect(new BigDecimal(expected), decimal, label);
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        boolean equal;
        if (expected instanceof byte[] expectedBytes
                && actual instanceof byte[] actualBytes) {
            equal = java.util.Arrays.equals(expectedBytes, actualBytes);
        } else if (expected instanceof BigDecimal expectedDecimal
                && actual instanceof BigDecimal actualDecimal) {
            equal = expectedDecimal.compareTo(actualDecimal) == 0;
        } else {
            equal = java.util.Objects.equals(expected, actual);
        }
        if (!equal) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
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

    private static final class FakeExchange extends HttpExchange {
        private final Headers requestHeaders = new Headers();
        private final Headers responseHeaders = new Headers();
        private final String method;
        private final URI uri;
        private final Map<String, Object> attributes = new HashMap<>();
        private InputStream requestBody;
        private OutputStream responseBody = new ByteArrayOutputStream();
        private int status = -1;

        private FakeExchange(String method, URI uri, byte[] requestBody) {
            this.method = method;
            this.uri = uri;
            this.requestBody = new ByteArrayInputStream(requestBody);
        }

        private String body() {
            return new String(bodyBytes(), StandardCharsets.UTF_8);
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
}
