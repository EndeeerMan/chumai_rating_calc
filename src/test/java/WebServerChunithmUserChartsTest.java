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
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** HTTP contract tests for authenticated CHUNITHM chart persistence. */
public final class WebServerChunithmUserChartsTest {
    private static final String PATH = "/api/chunithm/user/charts";
    private static int testsRun;

    private WebServerChunithmUserChartsTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("chunithm-user-http-test-");
        try {
            UserStore users = new UserStore(temporary.resolve("accounts"));
            AuthService auth = new AuthService(users);
            ChunithmScoreStore scores = new ChunithmScoreStore(
                    temporary.resolve("chunithm-charts"));
            ChunithmCatalog catalog = ChunithmCatalog.load(
                    Path.of("web", "chunithm-catalog"));
            HttpHandler handler = handler(auth, scores, catalog);

            testUnauthorizedAndMethodContract(handler);

            AuthService.SessionHandle firstSession = auth.register(
                    "ChuniPlayerOne", "CorrectHorseOne#2026");
            AuthService.SessionHandle secondSession = auth.register(
                    "ChuniPlayerTwo", "CorrectHorseTwo#2026");
            String firstCookie = cookie(firstSession.token());
            String secondCookie = cookie(secondSession.token());
            String firstUserId = firstSession.user().id();
            String secondUserId = secondSession.user().id();

            ChunithmCatalog.CatalogResult catalogResult = catalog.catalog();
            assertEquals(false, catalogResult.latestVersions().isEmpty(),
                    "real latest_version snapshot is available");
            ChartFixture oldChart = fixture(catalogResult, false);
            ChartFixture newChart = fixture(catalogResult, true);

            FakeExchange response = request(
                    handler, "GET", PATH, null, null, firstCookie);
            assertEquals(200, response.status, "authenticated GET succeeds");
            Map<String, Object> firstEmpty = object(Json.parse(response.body()));
            assertEquals(firstUserId, firstEmpty.get("userId"),
                    "GET snapshot identifies authenticated user");
            assertEquals(0L, integer(firstEmpty.get("revision")),
                    "new user starts at revision zero");
            assertEquals(List.of(), firstEmpty.get("charts"),
                    "new user starts with no CHUNITHM charts");

            List<ChunithmChartInput> firstCharts = List.of(
                    oldChart.toInput(), newChart.toInput());
            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(firstUserId, 0, firstCharts),
                    "application/json; charset=utf-8",
                    firstCookie);
            assertEquals(200, response.status, "first user can save charts");
            Map<String, Object> firstSaved = object(Json.parse(response.body()));
            assertEquals(firstUserId, firstSaved.get("userId"),
                    "save response identifies owner");
            assertEquals(1L, integer(firstSaved.get("revision")),
                    "successful PUT increments revision");
            assertEquals(2, list(firstSaved.get("charts")).size(),
                    "save response contains committed charts");

            ChunithmResult realClassification = ChunithmCalculator.calculate(
                    scores.loadSnapshot(
                            firstUserId, catalogResult.latestVersions()).charts(),
                    catalogResult.latestVersions());
            assertEquals(1, realClassification.b30().size(),
                    "real old version enters B30");
            assertEquals(1, realClassification.n20().size(),
                    "real latest_version enters N20");

            response = request(
                    handler, "GET", PATH, null, null, secondCookie);
            Map<String, Object> secondEmpty = object(Json.parse(response.body()));
            assertEquals(secondUserId, secondEmpty.get("userId"),
                    "second user sees own snapshot");
            assertEquals(0L, integer(secondEmpty.get("revision")),
                    "second user has independent revision");
            assertEquals(List.of(), secondEmpty.get("charts"),
                    "second user cannot see first user's charts");

            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(secondUserId, 0, List.of(newChart.toInput())),
                    "application/json",
                    firstCookie);
            assertEquals(409, response.status,
                    "forged expectedUserId conflicts with session owner");
            assertContains(response.body(), "expectedUserId",
                    "forged-user response explains ownership conflict");
            assertEquals(1L, scores.loadSnapshot(
                    firstUserId, catalogResult.latestVersions()).revision(),
                    "forged request does not alter authenticated user");
            assertEquals(0L, scores.loadSnapshot(
                    secondUserId, catalogResult.latestVersions()).revision(),
                    "forged request does not alter targeted user");

            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(secondUserId, 0, List.of(newChart.toInput())),
                    "application/json",
                    secondCookie);
            assertEquals(200, response.status, "second user saves independently");
            assertEquals(1L, integer(object(Json.parse(response.body())).get("revision")),
                    "second user's revision increments independently");

            response = request(handler, "GET", PATH, null, null, firstCookie);
            String firstBody = response.body();
            assertEquals(true, chartSongIds(firstBody).contains(oldChart.song().songId()),
                    "first user's old chart remains isolated");
            response = request(handler, "GET", PATH, null, null, secondCookie);
            String secondBody = response.body();
            assertEquals(false, chartSongIds(secondBody).contains(oldChart.song().songId()),
                    "second user cannot read first user's old chart");

            List<ChunithmChartInput> winningCharts = List.of(oldChart.toInput());
            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(firstUserId, 1, winningCharts),
                    "application/json",
                    firstCookie);
            assertEquals(200, response.status, "first update at revision one wins");
            assertEquals(2L, integer(object(Json.parse(response.body())).get("revision")),
                    "winning update advances revision");

            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(firstUserId, 1, List.of(newChart.toInput())),
                    "application/json",
                    firstCookie);
            assertEquals(409, response.status,
                    "second PUT at the same revision conflicts");
            ChunithmScoreStore.Snapshot afterConflict = scores.loadSnapshot(
                    firstUserId, catalogResult.latestVersions());
            assertEquals(2L, afterConflict.revision(),
                    "stale PUT leaves revision unchanged");
            assertEquals(winningCharts, afterConflict.charts(),
                    "stale PUT never overwrites winner");

            response = request(
                    handler,
                    "PUT",
                    PATH,
                    "{\"expectedUserId\":\"" + firstUserId
                            + "\",\"revision\":2,\"charts\":[],\"extra\":true}",
                    "application/json",
                    firstCookie);
            assertEquals(400, response.status,
                    "strict PUT parser rejects unknown fields");

            response = request(
                    handler,
                    "PUT",
                    PATH,
                    update(firstUserId, 2, winningCharts),
                    "text/plain",
                    firstCookie);
            assertEquals(415, response.status, "PUT requires JSON content type");

            testExpiredSessionClearsCookie(handler, auth, firstSession, firstCookie);
            testStorageFailureMapsTo500(
                    handler, scores, secondUserId, secondCookie);

            System.out.println(
                    "WebServerChunithmUserChartsTest: all "
                            + testsRun + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testUnauthorizedAndMethodContract(HttpHandler handler)
            throws IOException {
        FakeExchange response = request(handler, "GET", PATH, null, null, null);
        assertEquals(401, response.status, "GET requires authentication");
        assertContains(response.responseHeaders.getFirst("Set-Cookie"), "Max-Age=0",
                "401 clears session cookie");

        response = request(handler, "POST", PATH, null, null, null);
        assertEquals(405, response.status, "unsupported method is rejected");
        assertEquals("GET, PUT, OPTIONS", response.responseHeaders.getFirst("Allow"),
                "Allow advertises user chart methods");

        response = request(handler, "OPTIONS", PATH, null, null, null);
        assertEquals(204, response.status, "OPTIONS succeeds without login");
        assertEquals("GET, PUT, OPTIONS", response.responseHeaders.getFirst("Allow"),
                "OPTIONS advertises methods");

        response = request(
                handler, "GET", PATH + "/unexpected", null, null, null);
        assertEquals(404, response.status, "subpaths are rejected");
    }

    private static void testExpiredSessionClearsCookie(
            HttpHandler handler,
            AuthService auth,
            AuthService.SessionHandle session,
            String cookie) throws IOException {
        auth.logout(session.token());
        FakeExchange response = request(handler, "GET", PATH, null, null, cookie);
        assertEquals(401, response.status, "logged-out session is unauthorized");
        assertContains(response.responseHeaders.getFirst("Set-Cookie"), "Max-Age=0",
                "invalid session clears browser cookie");
    }

    private static void testStorageFailureMapsTo500(
            HttpHandler handler,
            ChunithmScoreStore scores,
            String userId,
            String cookie) throws IOException {
        Files.writeString(
                scores.root().resolve(userId + ".json"), "{broken",
                StandardCharsets.UTF_8);
        FakeExchange response;
        PrintStream originalError = System.err;
        try (PrintStream capturedError = new PrintStream(
                new ByteArrayOutputStream(), true, StandardCharsets.UTF_8)) {
            System.setErr(capturedError);
            response = request(handler, "GET", PATH, null, null, cookie);
        } finally {
            System.setErr(originalError);
        }
        assertEquals(500, response.status, "stored I/O failure maps to 500");
        assertContains(response.body(), "Unable to access saved CHUNITHM score data",
                "500 response does not expose corrupt file details");
    }

    private static ChartFixture fixture(
            ChunithmCatalog.CatalogResult catalog, boolean isNew) {
        for (ChunithmCatalog.Song song : catalog.songs()) {
            if (song.isNew() != isNew) {
                continue;
            }
            for (ChunithmCatalog.Chart chart : song.charts()) {
                if (!"WORLD'S END".equals(chart.difficulty())
                        && chart.constant().signum() > 0) {
                    return new ChartFixture(song, chart);
                }
            }
        }
        throw new AssertionError("catalog is missing an eligible isNew=" + isNew + " chart");
    }

    private static String update(
            String expectedUserId,
            long revision,
            List<ChunithmChartInput> charts) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("expectedUserId", expectedUserId);
        value.put("revision", revision);
        value.put("charts", ChunithmScoreStore.chartsToJsonValues(charts));
        return Json.stringify(value);
    }

    private static String cookie(String token) {
        return AuthService.SESSION_COOKIE_NAME + "=" + token;
    }

    private static HttpHandler handler(
            AuthService auth,
            ChunithmScoreStore scores,
            ChunithmCatalog catalog) throws ReflectiveOperationException {
        Class<?> type = Class.forName("WebServer$ChunithmUserChartsHandler");
        Constructor<?> constructor = type.getDeclaredConstructor(
                AuthService.class, ChunithmScoreStore.class, ChunithmCatalog.class);
        constructor.setAccessible(true);
        return (HttpHandler) constructor.newInstance(auth, scores, catalog);
    }

    private static FakeExchange request(
            HttpHandler handler,
            String method,
            String path,
            String body,
            String contentType,
            String cookie) throws IOException {
        byte[] bytes = body == null
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        FakeExchange exchange = new FakeExchange(method, URI.create(path), bytes);
        if (contentType != null) {
            exchange.requestHeaders.set("Content-Type", contentType);
        }
        if (cookie != null) {
            exchange.requestHeaders.set("Cookie", cookie);
        }
        handler.handle(exchange);
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static long integer(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    private static Set<String> chartSongIds(String snapshotJson) {
        Map<String, Object> snapshot = object(Json.parse(snapshotJson));
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (Object value : list(snapshot.get("charts"))) {
            ids.add((String) object(value).get("songId"));
        }
        return Set.copyOf(ids);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expected, String message) {
        testsRun++;
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(
                    message + ": expected <" + actual + "> to contain <" + expected + ">");
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record ChartFixture(
            ChunithmCatalog.Song song, ChunithmCatalog.Chart chart) {
        ChunithmChartInput toInput() {
            return new ChunithmChartInput(
                    song.songId(),
                    song.title(),
                    chart.difficulty(),
                    chart.constant().doubleValue(),
                    1_009_000,
                    song.version());
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

        private FakeExchange(String method, URI uri, byte[] body) {
            this.method = method;
            this.uri = uri;
            requestBody = new ByteArrayInputStream(body);
        }

        private String body() {
            return ((ByteArrayOutputStream) responseBody)
                    .toString(StandardCharsets.UTF_8);
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
