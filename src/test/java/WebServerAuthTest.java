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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Dependency-free HTTP contract tests for authentication and user charts. */
public final class WebServerAuthTest {
    private static int tests;

    private WebServerAuthTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-web-auth-test-");
        try {
            UserStore store = new UserStore(temporary);
            AuthService auth = new AuthService(store);
            HttpHandler status = handler(
                    "WebServer$AuthStatusHandler",
                    new Class<?>[]{AuthService.class}, auth);
            HttpHandler register = handler(
                    "WebServer$RegisterHandler",
                    new Class<?>[]{AuthService.class}, auth);
            HttpHandler login = handler(
                    "WebServer$LoginHandler",
                    new Class<?>[]{AuthService.class}, auth);
            HttpHandler logout = handler(
                    "WebServer$LogoutHandler",
                    new Class<?>[]{AuthService.class}, auth);
            HttpHandler charts = handler(
                    "WebServer$UserChartsHandler",
                    new Class<?>[]{AuthService.class, UserStore.class}, auth, store);
            HttpHandler staticFiles = handler(
                    "WebServer$StaticHandler",
                    new Class<?>[]{Path.class},
                    Path.of("web").toAbsolutePath());

            verifyAuthenticationPageRoutes(staticFiles);

            FakeExchange response = request(
                    status, "GET", "/api/auth/status", null, null, null);
            expect(200, response.status, "anonymous status succeeds");
            expect("{\"authenticated\":false,\"username\":null,\"user\":null}",
                    response.body(), "anonymous status payload");

            response = request(register, "POST", "/api/auth/register",
                    "{}", "text/plain", null);
            expect(415, response.status, "register requires JSON");

            String credentials = "{\"username\":\"Player\","
                    + "\"password\":\"CorrectHorse#2026\"}";
            response = request(register, "POST", "/api/auth/register",
                    credentials, "application/json; charset=utf-8", null);
            expect(200, response.status, "registration succeeds");
            Map<String, Object> registration = object(Json.parse(response.body()));
            Map<String, Object> firstUser = object(registration.get("user"));
            String firstUserId = (String) firstUser.get("id");
            expect(true, registration.get("authenticated"),
                    "registration returns authenticated user");
            expect(firstUserId, UUID.fromString(firstUserId).toString(),
                    "registration returns a stable canonical user id");
            expect("Player", firstUser.get("username"),
                    "registration returns the username");
            String setCookie = response.responseHeaders.getFirst("Set-Cookie");
            expect(true, setCookie.contains("Path=/api;"),
                    "cookie is limited to API paths");
            expect(true, setCookie.contains("HttpOnly"), "cookie is HttpOnly");
            expect(true, setCookie.contains("SameSite=Strict"),
                    "cookie is SameSite Strict");
            expect(true, setCookie.contains("Max-Age=604800"),
                    "cookie has a bounded lifetime");
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));

            response = request(status, "GET", "/api/auth/status", null, null, cookie);
            expect(200, response.status, "authenticated status succeeds");
            Map<String, Object> statusPayload = object(Json.parse(response.body()));
            expect(firstUserId, object(statusPayload.get("user")).get("id"),
                    "status returns the same stable user id");
            expect("Player", object(statusPayload.get("user")).get("username"),
                    "status returns public username");

            response = request(charts, "GET", "/api/user/charts", null, null, null);
            expect(401, response.status, "charts require login");

            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(200, response.status, "new user's charts can be loaded");
            Map<String, Object> firstSnapshot = object(Json.parse(response.body()));
            expect(firstUserId, firstSnapshot.get("userId"),
                    "chart snapshot identifies its owner");
            expect(0L, integer(firstSnapshot.get("revision")),
                    "new chart snapshot starts at revision zero");
            expect(List.of(), firstSnapshot.get("charts"),
                    "new chart snapshot starts empty");

            String chartDocument = chartUpdate(firstUserId, 0, "42", "Song");
            response = request(charts, "PUT", "/api/user/charts",
                    chartDocument, "application/json", cookie);
            expect(200, response.status, "charts can be saved");
            Map<String, Object> firstSaved = object(Json.parse(response.body()));
            expect(firstUserId, firstSaved.get("userId"),
                    "save response identifies its owner");
            expect(1L, integer(firstSaved.get("revision")),
                    "successful save increments the revision");
            expect(true, response.body().contains("\"songId\":\"42\""),
                    "save response contains the committed charts");
            Map<String, Object> savedLegacyChart = object(
                    ((List<?>) firstSaved.get("charts")).getFirst());
            expect("", savedLegacyChart.get("comboStatus"),
                    "legacy chart request receives an empty combo status");
            expect("", savedLegacyChart.get("syncStatus"),
                    "legacy chart request receives an empty sync status");

            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(200, response.status, "charts can be loaded");
            expect(true, response.body().contains("\"songId\":\"42\""),
                    "saved chart belongs to logged-in user");
            expect(1L, integer(object(Json.parse(response.body())).get("revision")),
                    "loaded revision matches the committed revision");

            String secondCredentials = "{\"username\":\"SecondPlayer\","
                    + "\"password\":\"AnotherSecure#2026\"}";
            response = request(register, "POST", "/api/auth/register",
                    secondCredentials, "application/json", null);
            expect(200, response.status, "second registration succeeds");
            Map<String, Object> secondRegistration = object(Json.parse(response.body()));
            String secondUserId = (String) object(secondRegistration.get("user")).get("id");
            String secondSetCookie = response.responseHeaders.getFirst("Set-Cookie");
            String secondCookie = secondSetCookie.substring(0, secondSetCookie.indexOf(';'));
            expect(false, firstUserId.equals(secondUserId),
                    "different users receive different ids");

            response = request(charts, "GET", "/api/user/charts",
                    null, null, secondCookie);
            Map<String, Object> secondSnapshot = object(Json.parse(response.body()));
            expect(secondUserId, secondSnapshot.get("userId"),
                    "second user's chart snapshot has the second owner");
            expect(0L, integer(secondSnapshot.get("revision")),
                    "second user has an independent revision");
            expect(List.of(), secondSnapshot.get("charts"),
                    "second user starts with an isolated empty chart list");

            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdate(secondUserId, 0, "99", "Other Song"),
                    "application/json", secondCookie);
            expect(200, response.status, "second user can save independently");
            expect(1L, integer(object(Json.parse(response.body())).get("revision")),
                    "second user's revision increments independently");

            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(true, response.body().contains("\"songId\":\"42\""),
                    "first user's chart remains isolated");
            expect(false, response.body().contains("\"songId\":\"99\""),
                    "first user cannot see the second user's chart");
            response = request(charts, "GET", "/api/user/charts",
                    null, null, secondCookie);
            expect(true, response.body().contains("\"songId\":\"99\""),
                    "second user's chart remains isolated");
            expect(false, response.body().contains("\"songId\":\"42\""),
                    "second user cannot see the first user's chart");

            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdate(secondUserId, 1, "777", "Forged Owner"),
                    "application/json", cookie);
            expect(409, response.status,
                    "forged expectedUserId is rejected for the authenticated session");
            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(true, response.body().contains("\"songId\":\"42\""),
                    "forged owner request does not alter the authenticated user");
            response = request(charts, "GET", "/api/user/charts",
                    null, null, secondCookie);
            expect(true, response.body().contains("\"songId\":\"99\""),
                    "forged owner request does not alter the targeted user");

            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdate(firstUserId, 1, "43", "Winning Update"),
                    "application/json", cookie);
            expect(200, response.status, "first update at a revision succeeds");
            expect(2L, integer(object(Json.parse(response.body())).get("revision")),
                    "winning update increments the first user's revision");
            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdate(firstUserId, 1, "44", "Stale Update"),
                    "application/json", cookie);
            expect(409, response.status, "second update at the same revision conflicts");
            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(2L, integer(object(Json.parse(response.body())).get("revision")),
                    "revision is unchanged after a stale update");
            expect(true, response.body().contains("\"songId\":\"43\""),
                    "winning update remains committed");
            expect(false, response.body().contains("\"songId\":\"44\""),
                    "stale update never overwrites committed data");

            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdateWithStatuses(
                            firstUserId, 2, "45", "Completed Update", "APP", "FsDp"),
                    "application/json", cookie);
            expect(200, response.status, "completion statuses can be saved");
            Map<String, Object> completedSnapshot = object(Json.parse(response.body()));
            expect(3L, integer(completedSnapshot.get("revision")),
                    "completion status update increments the revision");
            Map<String, Object> completedChart = object(
                    ((List<?>) completedSnapshot.get("charts")).getFirst());
            expect("app", completedChart.get("comboStatus"),
                    "chart API returns canonical combo status");
            expect("fsdp", completedChart.get("syncStatus"),
                    "chart API returns canonical sync status");

            response = request(charts, "PUT", "/api/user/charts",
                    chartUpdateWithStatuses(
                            firstUserId, 3, "46", "Invalid Status", "fc+", "fs"),
                    "application/json", cookie);
            expect(400, response.status, "unknown completion status is rejected");
            response = request(charts, "GET", "/api/user/charts", null, null, cookie);
            expect(3L, integer(object(Json.parse(response.body())).get("revision")),
                    "invalid completion status does not change the revision");
            expect(true, response.body().contains("\"songId\":\"45\""),
                    "invalid completion status does not replace saved charts");

            response = request(charts, "PUT", "/api/user/charts",
                    "{\"expectedUserId\":\"" + firstUserId
                            + "\",\"revision\":3,\"charts\":[],\"extra\":true}",
                    "application/json", cookie);
            expect(400, response.status, "unknown chart document fields are rejected");

            response = request(logout, "POST", "/api/auth/logout", null, null, cookie);
            expect(200, response.status, "logout succeeds");
            expect(true, response.responseHeaders.getFirst("Set-Cookie")
                            .contains("Max-Age=0"),
                    "logout clears cookie");

            response = request(status, "GET", "/api/auth/status", null, null, cookie);
            expect(false, response.body().contains("\"authenticated\":true"),
                    "logged-out token is invalid");

            response = request(login, "POST", "/api/auth/login",
                    "{\"username\":\"Player\",\"password\":\"wrong password\"}",
                    "application/json", null);
            expect(401, response.status, "bad password gets generic unauthorized");

            response = request(login, "POST", "/api/auth/login",
                    credentials, "application/json", null);
            expect(200, response.status, "login succeeds");
            expect(true, response.responseHeaders.getFirst("Set-Cookie") != null,
                    "login issues a new session cookie");
            expect(firstUserId,
                    object(object(Json.parse(response.body())).get("user")).get("id"),
                    "login returns the original stable user id");

            response = request(register, "OPTIONS", "/api/auth/register",
                    null, null, null);
            expect(204, response.status, "auth OPTIONS succeeds");
            expect("POST, OPTIONS", response.responseHeaders.getFirst("Allow"),
                    "auth OPTIONS advertises methods");

            System.out.println("WebServerAuthTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void verifyAuthenticationPageRoutes(HttpHandler staticFiles)
            throws IOException {
        FakeExchange login = request(
                staticFiles, "GET", "/login.html", null, null, null);
        FakeExchange register = request(
                staticFiles, "GET", "/register.html", null, null, null);
        FakeExchange forgot = request(
                staticFiles, "GET", "/forgot-password.html", null, null, null);
        expect(200, login.status, "login page is served directly");
        expect(200, register.status, "register page route is served");
        expect(200, forgot.status, "forgot-password page route is served");
        expect(login.body(), register.body(),
                "register route uses the shared authentication document");
        expect(login.body(), forgot.body(),
                "forgot-password route uses the shared authentication document");
        expect("text/html; charset=utf-8",
                login.responseHeaders.getFirst("Content-Type"),
                "authentication document has the HTML content type");
    }

    private static String chartUpdate(
            String expectedUserId, long revision, String songId, String title) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("songId", songId);
        chart.put("title", title);
        chart.put("chartType", "dx");
        chart.put("difficulty", "MASTER");
        chart.put("level", new BigDecimal("14.7"));
        chart.put("achievement", new BigDecimal("100.5"));
        chart.put("version", "current");

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("expectedUserId", expectedUserId);
        document.put("revision", revision);
        document.put("charts", List.of(chart));
        return Json.stringify(document);
    }

    private static String chartUpdateWithStatuses(
            String expectedUserId,
            long revision,
            String songId,
            String title,
            String comboStatus,
            String syncStatus) {
        Map<String, Object> document = object(Json.parse(
                chartUpdate(expectedUserId, revision, songId, title)));
        Map<String, Object> chart = object(
                ((List<?>) document.get("charts")).getFirst());
        chart.put("comboStatus", comboStatus);
        chart.put("syncStatus", syncStatus);
        return Json.stringify(document);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static long integer(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    private static HttpHandler handler(
            String className, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return (HttpHandler) constructor.newInstance(arguments);
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

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
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
