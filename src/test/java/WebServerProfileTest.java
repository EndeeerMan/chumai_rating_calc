import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpPrincipal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

/** Dependency-free HTTP contract tests for profiles, private media, and passwords. */
public final class WebServerProfileTest {
    private static int tests;

    private WebServerProfileTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-web-profile-test-");
        try {
            UserStore userStore = new UserStore(temporary.resolve("users"));
            AuthService auth = new AuthService(userStore);
            UserProfileStore profileStore = new UserProfileStore(
                    temporary.resolve("profiles"));

            HttpHandler profile = handler(
                    "WebServer$UserProfileHandler",
                    new Class<?>[]{AuthService.class, UserProfileStore.class},
                    auth,
                    profileStore);
            HttpHandler avatar = imageHandler(auth, profileStore, "AVATAR");
            HttpHandler background = imageHandler(auth, profileStore, "BACKGROUND");
            HttpHandler password = handler(
                    "WebServer$UserPasswordHandler",
                    new Class<?>[]{AuthService.class},
                    auth);

            String originalPassword = "CorrectHorse#2026";
            String replacementPassword = "RotatedSecure#2026";
            AuthService.SessionHandle first = auth.register(
                    "PlayerOne", originalPassword);
            AuthService.SessionHandle firstParallel = auth.login(
                    "playerone", originalPassword);
            AuthService.SessionHandle second = auth.register(
                    "SecondPlayer", "DifferentSecure#2026");
            String firstCookie = sessionCookie(first.token());
            String firstParallelCookie = sessionCookie(firstParallel.token());
            String secondCookie = sessionCookie(second.token());

            testProfileContract(profile, firstCookie, secondCookie, first, second);
            testImageContract(
                    profile,
                    avatar,
                    background,
                    firstCookie,
                    secondCookie);
            testPasswordContract(
                    profile,
                    password,
                    auth,
                    first,
                    firstParallel,
                    second,
                    firstCookie,
                    firstParallelCookie,
                    secondCookie,
                    originalPassword,
                    replacementPassword);

            System.out.println(
                    "WebServerProfileTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testProfileContract(
            HttpHandler profile,
            String firstCookie,
            String secondCookie,
            AuthService.SessionHandle first,
            AuthService.SessionHandle second) throws IOException {
        FakeExchange response = request(
                profile, "GET", "/api/user/profile", null, null, null);
        expect(401, response.status, "profile requires authentication");

        response = request(
                profile, "GET", "/api/user/profile", null, null, firstCookie);
        expect(200, response.status, "default profile can be loaded");
        Map<String, Object> payload = object(Json.parse(response.body()));
        Map<String, Object> firstProfile = object(payload.get("profile"));
        expect(first.user().id(), firstProfile.get("userId"),
                "profile owner comes from the authenticated session");
        expect("PlayerOne", firstProfile.get("username"),
                "profile exposes the immutable login username");
        expect("PlayerOne", firstProfile.get("displayName"),
                "old users fall back to their username");
        expect(null, firstProfile.get("avatarUrl"),
                "new profile has no avatar URL");
        expect(null, firstProfile.get("backgroundUrl"),
                "new profile has no background URL");
        expect(false, response.body().contains("passwordHash"),
                "profile response never exposes credential hashes");

        response = request(
                profile,
                "PUT",
                "/api/user/profile",
                utf8("{\"displayName\":\"Player\"}"),
                "text/plain",
                firstCookie);
        expect(415, response.status, "profile update requires JSON");

        response = request(
                profile,
                "PUT",
                "/api/user/profile",
                utf8("{\"displayName\":\"Player\",\"userId\":\""
                        + second.user().id() + "\"}"),
                "application/json",
                firstCookie);
        expect(400, response.status,
                "profile update rejects forged owners and unknown fields");

        response = request(
                profile,
                "PUT",
                "/api/user/profile",
                utf8("{}"),
                "application/json",
                firstCookie);
        expect(400, response.status, "profile update requires displayName");

        response = request(
                profile,
                "PUT",
                "/api/user/profile",
                utf8("{\"displayName\":42}"),
                "application/json",
                firstCookie);
        expect(400, response.status, "profile displayName must be a string");

        response = request(
                profile,
                "PUT",
                "/api/user/profile",
                utf8("{\"displayName\":\"  Ｕｎｉ 玩家  \"}"),
                "application/json; charset=utf-8",
                firstCookie);
        expect(200, response.status, "profile display name can be updated");
        firstProfile = object(object(Json.parse(response.body())).get("profile"));
        expect("Uni 玩家", firstProfile.get("displayName"),
                "profile display name is normalized and trimmed");
        expect(first.user().id(), firstProfile.get("userId"),
                "profile update cannot change ownership");

        response = request(
                profile, "GET", "/api/user/profile", null, null, secondCookie);
        expect(200, response.status, "second user can load an isolated profile");
        Map<String, Object> secondProfile = object(
                object(Json.parse(response.body())).get("profile"));
        expect(second.user().id(), secondProfile.get("userId"),
                "second profile has the second owner");
        expect("SecondPlayer", secondProfile.get("displayName"),
                "first user's nickname does not leak to the second user");
        expect(null, secondProfile.get("avatarUrl"),
                "first user's avatar does not leak to the second user");
        expect(null, secondProfile.get("backgroundUrl"),
                "first user's background does not leak to the second user");
    }

    private static void testImageContract(
            HttpHandler profile,
            HttpHandler avatar,
            HttpHandler background,
            String firstCookie,
            String secondCookie) throws IOException {
        byte[] png = image("png", 3, 2);
        byte[] jpeg = image("jpeg", 4, 3);

        FakeExchange response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                png,
                "image/png",
                null);
        expect(401, response.status, "anonymous avatar upload is rejected");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                png,
                "image/gif",
                firstCookie);
        expect(415, response.status, "unsupported image media type is rejected");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                png,
                "image/jpeg",
                firstCookie);
        expect(400, response.status, "forged image media type is rejected");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47},
                "image/png",
                firstCookie);
        expect(400, response.status, "truncated image data is rejected");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                new byte[UserProfileStore.MAX_AVATAR_UPLOAD_BYTES + 1],
                "image/png",
                firstCookie);
        expect(413, response.status, "oversized avatar upload is rejected");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                png,
                "image/png",
                firstCookie);
        expect(200, response.status, "PNG avatar upload succeeds");
        Map<String, Object> firstProfile = object(
                object(Json.parse(response.body())).get("profile"));
        expect("/api/user/profile/avatar", firstProfile.get("avatarUrl"),
                "avatar upload publishes only a private API URL");

        response = request(
                avatar,
                "GET",
                "/api/user/profile/avatar",
                null,
                null,
                firstCookie);
        expect(200, response.status, "stored avatar can be loaded");
        expect("image/png", response.responseHeaders.getFirst("Content-Type"),
                "avatar is normalized to PNG");
        expect("private, no-store",
                response.responseHeaders.getFirst("Cache-Control"),
                "avatar response cannot be shared or cached");
        expect("nosniff",
                response.responseHeaders.getFirst("X-Content-Type-Options"),
                "avatar response disables content sniffing");
        expect(true, startsWithPng(response.bodyBytes()),
                "avatar response contains PNG bytes");

        response = request(
                avatar,
                "PUT",
                "/api/user/profile/avatar",
                jpeg,
                "image/jpeg",
                firstCookie);
        expect(200, response.status, "JPEG avatar upload succeeds");
        response = request(
                avatar,
                "GET",
                "/api/user/profile/avatar",
                null,
                null,
                firstCookie);
        expect("image/png", response.responseHeaders.getFirst("Content-Type"),
                "JPEG avatar is safely re-encoded as PNG");

        response = request(
                avatar,
                "GET",
                "/api/user/profile/avatar",
                null,
                null,
                secondCookie);
        expect(404, response.status, "avatar is isolated by authenticated user");

        response = request(
                background,
                "PUT",
                "/api/user/profile/background",
                jpeg,
                "image/jpeg",
                firstCookie);
        expect(200, response.status, "JPEG background upload succeeds");
        firstProfile = object(object(Json.parse(response.body())).get("profile"));
        expect("/api/user/profile/background", firstProfile.get("backgroundUrl"),
                "background upload publishes only a private API URL");
        Map<String, Object> backgroundUrls = object(
                firstProfile.get("backgroundUrls"));
        expect(
                "/api/user/profile/background?game=maimai",
                backgroundUrls.get("maimai"),
                "legacy background upload is the maimai-specific background");
        expect(null, backgroundUrls.get("chunithm"),
                "maimai upload does not create a CHUNITHM background");

        response = request(
                background,
                "GET",
                "/api/user/profile/background",
                null,
                null,
                firstCookie);
        expect(200, response.status, "stored background can be loaded");
        expect("image/jpeg", response.responseHeaders.getFirst("Content-Type"),
                "background is normalized to JPEG");
        expect("private, no-store",
                response.responseHeaders.getFirst("Cache-Control"),
                "background response cannot be shared or cached");
        expect("nosniff",
                response.responseHeaders.getFirst("X-Content-Type-Options"),
                "background response disables content sniffing");
        expect(true, startsWithJpeg(response.bodyBytes()),
                "background response contains JPEG bytes");

        response = request(
                background,
                "PUT",
                "/api/user/profile/background",
                png,
                "image/png",
                firstCookie);
        expect(200, response.status, "PNG background upload succeeds");
        response = request(
                background,
                "GET",
                "/api/user/profile/background",
                null,
                null,
                firstCookie);
        expect("image/jpeg", response.responseHeaders.getFirst("Content-Type"),
                "PNG background is safely re-encoded as JPEG");

        response = request(
                background,
                "PUT",
                "/api/user/profile/background?game=chunithm",
                jpeg,
                "image/jpeg",
                firstCookie);
        expect(200, response.status, "CHUNITHM background upload succeeds");
        firstProfile = object(object(Json.parse(response.body())).get("profile"));
        backgroundUrls = object(firstProfile.get("backgroundUrls"));
        expect(
                "/api/user/profile/background?game=maimai",
                backgroundUrls.get("maimai"),
                "CHUNITHM upload keeps the maimai background");
        expect(
                "/api/user/profile/background?game=chunithm",
                backgroundUrls.get("chunithm"),
                "CHUNITHM upload publishes its own private URL");

        response = request(
                background,
                "GET",
                "/api/user/profile/background?game=chunithm",
                null,
                null,
                firstCookie);
        expect(200, response.status, "CHUNITHM background can be loaded independently");
        expect(true, startsWithJpeg(response.bodyBytes()),
                "CHUNITHM background contains normalized JPEG bytes");

        response = request(
                background,
                "GET",
                "/api/user/profile/background?game=unknown",
                null,
                null,
                firstCookie);
        expect(400, response.status, "unknown background game is rejected");

        response = request(
                background,
                "DELETE",
                "/api/user/profile/background?game=chunithm",
                null,
                null,
                firstCookie);
        expect(200, response.status, "CHUNITHM background can be deleted alone");
        firstProfile = object(object(Json.parse(response.body())).get("profile"));
        backgroundUrls = object(firstProfile.get("backgroundUrls"));
        expect(
                "/api/user/profile/background?game=maimai",
                backgroundUrls.get("maimai"),
                "deleting CHUNITHM keeps maimai background");
        expect(null, backgroundUrls.get("chunithm"),
                "CHUNITHM deletion clears only its URL");

        response = request(
                background,
                "GET",
                "/api/user/profile/background",
                null,
                null,
                secondCookie);
        expect(404, response.status, "background is isolated by authenticated user");

        response = request(
                avatar,
                "DELETE",
                "/api/user/profile/avatar",
                null,
                null,
                firstCookie);
        expect(200, response.status, "avatar can be deleted");
        expect(null,
                object(object(Json.parse(response.body())).get("profile"))
                        .get("avatarUrl"),
                "avatar deletion clears its profile URL");
        response = request(
                avatar,
                "GET",
                "/api/user/profile/avatar",
                null,
                null,
                firstCookie);
        expect(404, response.status, "deleted avatar is no longer served");

        response = request(
                background,
                "DELETE",
                "/api/user/profile/background",
                null,
                null,
                firstCookie);
        expect(200, response.status, "background can be deleted");
        expect(null,
                object(object(Json.parse(response.body())).get("profile"))
                        .get("backgroundUrl"),
                "background deletion clears its profile URL");
        response = request(
                background,
                "GET",
                "/api/user/profile/background",
                null,
                null,
                firstCookie);
        expect(404, response.status, "deleted background is no longer served");

        response = request(
                profile, "GET", "/api/user/profile", null, null, firstCookie);
        firstProfile = object(object(Json.parse(response.body())).get("profile"));
        expect("Uni 玩家", firstProfile.get("displayName"),
                "media changes do not overwrite profile metadata");
    }

    private static void testPasswordContract(
            HttpHandler profile,
            HttpHandler password,
            AuthService auth,
            AuthService.SessionHandle first,
            AuthService.SessionHandle firstParallel,
            AuthService.SessionHandle second,
            String firstCookie,
            String firstParallelCookie,
            String secondCookie,
            String originalPassword,
            String replacementPassword) throws IOException {
        FakeExchange response = request(
                password,
                "PUT",
                "/api/user/password",
                utf8("{\"currentPassword\":\"wrong password\","
                        + "\"newPassword\":\"" + replacementPassword + "\"}"),
                "application/json",
                firstCookie);
        expect(401, response.status, "wrong current password is rejected");
        expect(null, response.responseHeaders.getFirst("Set-Cookie"),
                "wrong current password does not clear the valid session cookie");
        expect(true, auth.authenticateSession(first.token()).isPresent(),
                "primary session remains valid after a wrong password attempt");
        expect(true, auth.authenticateSession(firstParallel.token()).isPresent(),
                "parallel session remains valid after a wrong password attempt");

        response = request(
                password,
                "PUT",
                "/api/user/password",
                utf8("{\"currentPassword\":\"" + originalPassword + "\","
                        + "\"newPassword\":\"" + replacementPassword + "\"}"),
                "application/json; charset=utf-8",
                firstCookie);
        expect(200, response.status, "password change succeeds");
        String replacementSetCookie = response.responseHeaders.getFirst("Set-Cookie");
        expect(true, replacementSetCookie != null,
                "password change returns a replacement session cookie");
        expect(true, replacementSetCookie.contains("Path=/api;"),
                "replacement cookie remains scoped to API paths");
        expect(true, replacementSetCookie.contains("HttpOnly"),
                "replacement cookie remains HttpOnly");
        expect(true, replacementSetCookie.contains("SameSite=Strict"),
                "replacement cookie remains SameSite Strict");
        String replacementCookie = cookiePair(replacementSetCookie);
        expect(false, replacementCookie.equals(firstCookie),
                "password change rotates the session token");
        expect(false, auth.authenticateSession(first.token()).isPresent(),
                "password change invalidates the calling session");
        expect(false, auth.authenticateSession(firstParallel.token()).isPresent(),
                "password change invalidates all sessions for that user");
        expect(true, auth.authenticateSession(second.token()).isPresent(),
                "password change does not invalidate another user's session");

        response = request(
                profile,
                "GET",
                "/api/user/profile",
                null,
                null,
                firstParallelCookie);
        expect(401, response.status, "old HTTP sessions cannot access the profile");
        response = request(
                profile,
                "GET",
                "/api/user/profile",
                null,
                null,
                replacementCookie);
        expect(200, response.status, "replacement session can access the profile");
        expect("Uni 玩家",
                object(object(Json.parse(response.body())).get("profile"))
                        .get("displayName"),
                "password change preserves profile data");
        response = request(
                profile,
                "GET",
                "/api/user/profile",
                null,
                null,
                secondCookie);
        expect(200, response.status,
                "another user's HTTP session remains authenticated");

        expectThrows(
                AuthService.InvalidCredentialsException.class,
                () -> auth.login("PlayerOne", originalPassword),
                "old password no longer authenticates");
        AuthService.SessionHandle relogin = auth.login(
                "PlayerOne", replacementPassword);
        expect(first.user().id(), relogin.user().id(),
                "new password authenticates the original account");
    }

    private static HttpHandler imageHandler(
            AuthService auth,
            UserProfileStore profileStore,
            String kindName) throws ReflectiveOperationException {
        Class<?> kindType = Class.forName("WebServer$ProfileImageKind");
        Object kind = enumConstant(kindType, kindName);
        return handler(
                "WebServer$UserProfileImageHandler",
                new Class<?>[]{AuthService.class, UserProfileStore.class, kindType},
                auth,
                profileStore,
                kind);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object enumConstant(Class<?> enumType, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumType.asSubclass(Enum.class), name);
    }

    private static HttpHandler handler(
            String className,
            Class<?>[] parameterTypes,
            Object... arguments) throws ReflectiveOperationException {
        Class<?> type = Class.forName(className);
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return (HttpHandler) constructor.newInstance(arguments);
    }

    private static FakeExchange request(
            HttpHandler handler,
            String method,
            String path,
            byte[] body,
            String contentType,
            String cookie) throws IOException {
        FakeExchange exchange = new FakeExchange(
                method,
                URI.create(path),
                body == null ? new byte[0] : body);
        if (contentType != null) {
            exchange.requestHeaders.set("Content-Type", contentType);
        }
        if (cookie != null) {
            exchange.requestHeaders.set("Cookie", cookie);
        }
        handler.handle(exchange);
        return exchange;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] image(String format, int width, int height)
            throws IOException {
        boolean jpeg = "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format);
        BufferedImage image = new BufferedImage(
                width,
                height,
                jpeg ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(28, 172, 154));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(238, 111, 82));
            graphics.fillRect(0, 0, Math.max(1, width / 2), height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("No ImageIO writer for " + format);
        }
        return output.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static String sessionCookie(String token) {
        return AuthService.SESSION_COOKIE_NAME + "=" + token;
    }

    private static String cookiePair(String setCookie) {
        int separator = setCookie.indexOf(';');
        return separator < 0 ? setCookie : setCookie.substring(0, separator);
    }

    private static boolean startsWithPng(byte[] value) {
        return value.length >= 8
                && (value[0] & 0xff) == 0x89
                && value[1] == 0x50
                && value[2] == 0x4e
                && value[3] == 0x47;
    }

    private static boolean startsWithJpeg(byte[] value) {
        return value.length >= 3
                && (value[0] & 0xff) == 0xff
                && (value[1] & 0xff) == 0xd8
                && (value[2] & 0xff) == 0xff;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> type,
            ThrowingAction action,
            String label) {
        tests++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(
                    label + ": expected " + type.getSimpleName()
                            + ", got " + error,
                    error);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
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
