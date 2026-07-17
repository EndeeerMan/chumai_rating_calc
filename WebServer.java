import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dependency-free HTTP entry point for the B50 calculator web interface.
 *
 * <p>Run with {@code java WebServer} or {@code java WebServer 8090}. Static
 * files are served from the {@code web} directory.</p>
 */
public final class WebServer {
    private static final int DEFAULT_PORT = 8080;
    private static final String IPV4_WILDCARD = "0.0.0.0";
    private static final int MAX_REQUEST_BYTES = 2 * 1024 * 1024;
    private static final int MAX_AUTH_REQUEST_BYTES = 16 * 1024;
    private static final int MAX_CHUNITHM_CALCULATE_BYTES =
            ChunithmScoreStore.MAX_FILE_BYTES;
    private static final int MAX_SYNC_REQUEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_QUERY_FIELDS = 16;
    private static final int MAX_CHARTS = 2_000;
    private static final int MAX_CHUNITHM_CHARTS = ChunithmScoreStore.MAX_CHARTS;
    private static final int FORM_FIELDS_PER_CHART = 7;
    private static final int MAX_FORM_FIELDS = MAX_CHARTS * FORM_FIELDS_PER_CHART;
    private static final String CALCULATE_PATH = "/api/b50/calculate";
    private static final String HEALTH_PATH = "/api/health";
    private static final String AUTH_STATUS_PATH = "/api/auth/status";
    private static final String AUTH_REGISTER_PATH = "/api/auth/register";
    private static final String AUTH_LOGIN_PATH = "/api/auth/login";
    private static final String AUTH_LOGOUT_PATH = "/api/auth/logout";
    private static final String AUTH_EMAIL_CODE_PATH = "/api/auth/email/code";
    private static final String USER_PROFILE_PATH = "/api/user/profile";
    private static final String USER_AVATAR_PATH = "/api/user/profile/avatar";
    private static final String USER_BACKGROUND_PATH = "/api/user/profile/background";
    private static final String USER_PASSWORD_PATH = "/api/user/password";
    private static final String USER_EMAIL_PATH = "/api/user/email";
    private static final String USER_ACCOUNT_PATH = "/api/user/account";
    private static final String USER_CHARTS_PATH = "/api/user/charts";
    private static final String SONG_CATALOG_PATH = "/api/songs/catalog";
    private static final String SONG_SEARCH_PATH = "/api/songs/search";
    private static final String MAIMAI_COVER_PREFIX = "/api/maimai/covers/";
    private static final String CHUNITHM_CATALOG_PATH =
            "/api/chunithm/songs/catalog";
    private static final String CHUNITHM_SEARCH_PATH =
            "/api/chunithm/songs/search";
    private static final String CHUNITHM_COVER_PREFIX =
            "/api/chunithm/covers/";
    private static final String CHUNITHM_CALCULATE_PATH =
            "/api/chunithm/calculate";
    private static final String CHUNITHM_USER_CHARTS_PATH =
            "/api/chunithm/user/charts";
    private static final String PLAY_HISTORY_PATH = "/api/history";
    private static final String CLASH_CONFIG_PATH = "/api/sync/clash-config";
    private static final String HELPER_HOST_PATH = "/api/sync/helper-host";
    private static final String SYNC_SESSIONS_PATH = "/api/sync/sessions";
    private static final String SYNC_IMPORT_PATH = "/api/sync/import";
    private static final Set<String> SYNC_SESSION_CREATE_KEYS = Set.of("game");
    private static final Set<String> SYNC_HELPER_EVENT_KEYS =
            Set.of(
                    "game", "status", "message", "stage",
                    "completed", "total", "succeeded", "skipped",
                    "failureReasons");
    private static final Set<String> SYNC_IMPORT_REQUIRED_KEYS = Set.of("game");
    private static final Set<String> SYNC_IMPORT_KEYS =
            Set.of("game", "sessionId", "charts", "records");
    private static final Set<String> CREDENTIAL_KEYS = Set.of("username", "password");
    private static final Set<String> REGISTER_KEYS = Set.of(
            "username", "password", "email", "verificationCode",
            "verificationFlowId");
    private static final Set<String> EMAIL_CODE_KEYS = Set.of("email", "purpose");
    private static final Set<String> EMAIL_UPDATE_KEYS = Set.of(
            "email", "verificationCode", "currentPassword");
    private static final Set<String> ACCOUNT_DELETE_KEYS = Set.of("currentPassword");
    private static final Set<String> PROFILE_UPDATE_KEYS = Set.of("displayName");
    private static final Set<String> PASSWORD_UPDATE_KEYS =
            Set.of("currentPassword", "newPassword");
    private static final Set<String> CHUNITHM_CALCULATE_KEYS = Set.of("charts");
    private static final Set<String> CHUNITHM_CHART_KEYS = Set.of(
            "songId", "title", "difficulty", "constant", "score", "version");

    private WebServer() {
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        Path webRoot = Path.of("web").toAbsolutePath().normalize();
        HttpServer server = HttpServer.create(
                ipv4WildcardAddress(port), 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Path userDataRoot = Path.of("user_data");
        UserStore userStore = new UserStore(userDataRoot);
        UserEmailStore emailStore = new UserEmailStore(userDataRoot);
        AuthService authService = new AuthService(userStore, emailStore);
        EmailVerificationService emailVerificationService =
                loadEmailVerificationServiceSafely(Path.of("."));
        UserProfileStore profileStore = new UserProfileStore(
                userDataRoot.resolve("profiles"));
        ChunithmScoreStore chunithmScoreStore = new ChunithmScoreStore(
                userDataRoot.resolve("chunithm_charts"));
        PlayHistoryStore playHistoryStore = new PlayHistoryStore(
                userDataRoot.resolve("play_history"));
        ChunithmHistoryStore chunithmHistoryStore = new ChunithmHistoryStore(
                userDataRoot.resolve("chunithm_play_history"));
        SyncSessionStore syncSessionStore = new SyncSessionStore();
        SongCatalog songCatalog = loadSongCatalogSafely(
                webRoot.resolve("song-catalog"));
        MaimaiScoreCanonicalizer maimaiCanonicalizer =
                new MaimaiScoreCanonicalizer(songCatalog);
        MaimaiCatalogScheduler maimaiCatalogScheduler =
                new MaimaiCatalogScheduler(songCatalog);
        maimaiCatalogScheduler.start();
        ChunithmCatalog chunithmCatalog = loadChunithmCatalogSafely(
                webRoot.resolve("chunithm-catalog"));
        ChunithmCatalogScheduler chunithmCatalogScheduler =
                new ChunithmCatalogScheduler(chunithmCatalog);
        chunithmCatalogScheduler.start();
        ChunithmScoreCanonicalizer chunithmCanonicalizer =
                new ChunithmScoreCanonicalizer(chunithmCatalog);
        MaimaiCoverService maimaiCoverService = new MaimaiCoverService(
                Path.of("cache", "maimai-covers"));
        ChunithmCoverService chunithmCoverService = new ChunithmCoverService(
                Path.of("cache", "chunithm-covers"));

        server.setExecutor(executor);
        server.createContext(CALCULATE_PATH, new CalculateHandler());
        server.createContext(HEALTH_PATH, new HealthHandler());
        server.createContext(AUTH_STATUS_PATH, new AuthStatusHandler(authService));
        server.createContext(
                AUTH_REGISTER_PATH,
                new RegisterHandler(
                        authService, emailStore, emailVerificationService));
        server.createContext(AUTH_LOGIN_PATH, new LoginHandler(authService));
        server.createContext(AUTH_LOGOUT_PATH, new LogoutHandler(authService));
        server.createContext(
                AUTH_EMAIL_CODE_PATH,
                new EmailCodeHandler(
                        authService, emailStore, emailVerificationService));
        server.createContext(
                USER_PROFILE_PATH,
                new UserProfileHandler(authService, profileStore));
        server.createContext(
                USER_AVATAR_PATH,
                new UserProfileImageHandler(
                        authService, profileStore, ProfileImageKind.AVATAR));
        server.createContext(
                USER_BACKGROUND_PATH,
                new UserProfileImageHandler(
                        authService, profileStore, ProfileImageKind.BACKGROUND));
        server.createContext(
                USER_PASSWORD_PATH,
                new UserPasswordHandler(authService));
        server.createContext(
                USER_EMAIL_PATH,
                new UserEmailHandler(
                        authService, emailStore, emailVerificationService));
        server.createContext(
                USER_ACCOUNT_PATH,
                new UserAccountHandler(
                        authService,
                        emailStore,
                        profileStore,
                        chunithmScoreStore,
                        playHistoryStore,
                        chunithmHistoryStore,
                        syncSessionStore));
        server.createContext(
                USER_CHARTS_PATH,
                new UserChartsHandler(
                        authService, userStore, maimaiCanonicalizer));
        server.createContext(
                SONG_CATALOG_PATH,
                new SongCatalogHandler(songCatalog));
        server.createContext(
                SONG_SEARCH_PATH,
                new SongSearchHandler(songCatalog));
        server.createContext(
                MAIMAI_COVER_PREFIX,
                new MaimaiCoverHandler(maimaiCoverService));
        server.createContext(
                CHUNITHM_CATALOG_PATH,
                new ChunithmCatalogHandler(chunithmCatalog));
        server.createContext(
                CHUNITHM_SEARCH_PATH,
                new ChunithmSearchHandler(chunithmCatalog));
        server.createContext(
                CHUNITHM_COVER_PREFIX,
                new ChunithmCoverHandler(chunithmCoverService));
        server.createContext(
                CHUNITHM_CALCULATE_PATH,
                new ChunithmCalculateHandler(chunithmCatalog));
        server.createContext(
                CHUNITHM_USER_CHARTS_PATH,
                new ChunithmUserChartsHandler(
                        authService, chunithmScoreStore, chunithmCatalog));
        server.createContext(
                PLAY_HISTORY_PATH,
                new PlayHistoryHandler(
                        authService, playHistoryStore, chunithmHistoryStore));
        server.createContext(CLASH_CONFIG_PATH, new ClashConfigHandler());
        server.createContext(HELPER_HOST_PATH, new HelperHostHandler());
        server.createContext(
                SYNC_SESSIONS_PATH,
                new SyncSessionsHandler(authService, syncSessionStore));
        server.createContext(
                SYNC_IMPORT_PATH,
                new SyncImportHandler(
                        authService,
                        userStore,
                        playHistoryStore,
                        chunithmScoreStore,
                        chunithmHistoryStore,
                        syncSessionStore,
                        maimaiCanonicalizer,
                        chunithmCanonicalizer,
                        chunithmCatalog));
        server.createContext("/api", new ApiNotFoundHandler());
        server.createContext("/", new StaticHandler(webRoot));

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            maimaiCatalogScheduler.close();
            chunithmCatalogScheduler.close();
            if (emailVerificationService != null) {
                emailVerificationService.close();
            }
            server.stop(1);
            executor.shutdown();
        }, "web-server-shutdown"));

        server.start();
        printStartupAddresses(port);
        if (!Files.isDirectory(webRoot)) {
            System.out.println("Warning: static directory not found: " + webRoot);
        }
    }

    static SongCatalog loadSongCatalogSafely(Path songCatalogDirectory) {
        try {
            return SongCatalog.load(songCatalogDirectory);
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            System.err.println(
                    "Warning: song catalogue is unavailable: " + message);
            return SongCatalog.empty(
                    "Song catalogue could not be loaded from bundled data");
        }
    }

    static ChunithmCatalog loadChunithmCatalogSafely(Path catalogDirectory) {
        try {
            return ChunithmCatalog.load(catalogDirectory);
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            System.err.println(
                    "Warning: CHUNITHM catalogue is unavailable: " + message);
            return ChunithmCatalog.empty(
                    "CHUNITHM catalogue could not be loaded from bundled data",
                    catalogDirectory);
        }
    }

    static EmailVerificationService loadEmailVerificationServiceSafely(
            Path projectRoot) {
        try {
            Optional<EmailVerificationConfig> configured =
                    EmailVerificationConfig.loadIfConfigured(projectRoot);
            if (configured.isEmpty()) {
                System.err.println(
                        "Warning: email verification is disabled until "
                                + EmailVerificationConfig.FILE_NAME
                                + " is configured");
                return null;
            }
            return new EmailVerificationService(
                    new SmtpEmailSender(configured.orElseThrow()));
        } catch (IOException | RuntimeException error) {
            String message = error.getMessage() == null
                    ? error.getClass().getSimpleName()
                    : error.getMessage();
            System.err.println(
                    "Warning: email verification configuration is unavailable: "
                            + message);
            return null;
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("Usage: java WebServer [port]");
        }

        String value = args.length == 1 ? args[0] : System.getenv("PORT");
        if (value == null || value.isBlank()) {
            return DEFAULT_PORT;
        }

        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("port must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("port must be an integer", error);
        }
    }

    static InetSocketAddress ipv4WildcardAddress(int port) {
        return new InetSocketAddress(IPV4_WILDCARD, port);
    }

    static List<String> localAccessUrls(int port) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("port must be between 1 and 65535");
        }

        List<String> urls = new ArrayList<>();
        urls.add("http://localhost:" + port + "/");
        for (String address : discoverLanIpv4Addresses()) {
            urls.add("http://" + address + ":" + port + "/");
        }
        return List.copyOf(urls);
    }

    static List<String> discoverLanIpv4Addresses() {
        Set<String> preferredAddresses = new TreeSet<>();
        Set<String> fallbackAddresses = new TreeSet<>();
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return List.of();
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                        continue;
                    }
                } catch (SocketException error) {
                    continue;
                }

                Set<String> target = isLikelyVirtualInterface(networkInterface)
                        ? fallbackAddresses
                        : preferredAddresses;
                var interfaceAddresses = networkInterface.getInetAddresses();
                while (interfaceAddresses.hasMoreElements()) {
                    var address = interfaceAddresses.nextElement();
                    if (address instanceof Inet4Address
                            && address.isSiteLocalAddress()
                            && !address.isLinkLocalAddress()) {
                        target.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException error) {
            return List.of();
        }
        Set<String> addresses = preferredAddresses.isEmpty()
                ? fallbackAddresses
                : preferredAddresses;
        return List.copyOf(addresses);
    }

    static String preferredLanHelperHost() {
        return preferredLanHelperHost(discoverLanIpv4Addresses());
    }

    static String preferredLanHelperHost(List<String> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            try {
                String canonical = requireHelperIpv4(candidate);
                if (!"127.0.0.1".equals(canonical)) {
                    return canonical;
                }
            } catch (ClientError ignored) {
                // Network enumeration may include an address outside RFC 1918.
            }
        }
        return "";
    }

    private static boolean isLikelyVirtualInterface(
            NetworkInterface networkInterface) {
        if (networkInterface.isVirtual()) {
            return true;
        }
        String identity = (networkInterface.getName() + " "
                + Objects.toString(networkInterface.getDisplayName(), ""))
                .toLowerCase(Locale.ROOT);
        return identity.contains("vmware")
                || identity.contains("virtualbox")
                || identity.contains("hyper-v")
                || identity.contains("vethernet")
                || identity.contains("mihomo")
                || identity.contains("clash")
                || identity.contains("docker")
                || identity.contains("wsl")
                || identity.contains(" tun")
                || identity.contains(" tap");
    }

    private static void printStartupAddresses(int port) {
        List<String> urls = localAccessUrls(port);
        System.out.println();
        System.out.println("B50 web server is ready.");
        System.out.println("  Listening: " + IPV4_WILDCARD + ":" + port
                + " (all IPv4 interfaces)");
        System.out.println("  This computer: " + urls.getFirst());
        if (urls.size() == 1) {
            System.out.println("  LAN: no active private IPv4 address found");
        } else {
            System.out.println("  LAN (phone/computer on the same network): "
                    + urls.get(1));
            for (int index = 2; index < urls.size(); index++) {
                System.out.println("  Other LAN: " + urls.get(index));
            }
        }
        System.out.println("Keep this window open while using the site.");
        System.out.println();
    }

    private static final class CalculateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!CALCULATE_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendJsonError(exchange, 404, "API endpoint not found");
                    return;
                }
                if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "POST, OPTIONS");
                    sendNoContent(exchange);
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }

                String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
                String mediaType = contentType == null
                        ? ""
                        : contentType.split(";", 2)[0].trim();
                if (contentType == null
                        || !mediaType.equalsIgnoreCase(
                                "application/x-www-form-urlencoded")) {
                    throw new ClientError(
                            415, "Content-Type must be application/x-www-form-urlencoded");
                }

                Map<String, List<String>> form = parseForm(readRequestBody(exchange));
                List<ChartInput> charts = parseCharts(form);
                String response = calculateResponse(charts);
                sendJson(exchange, 200, response);
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!HEALTH_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendJsonError(exchange, 404, "API endpoint not found");
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class AuthStatusHandler implements HttpHandler {
        private final AuthService authService;

        private AuthStatusHandler(AuthService authService) {
            this.authService = authService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!AUTH_STATUS_PATH.equals(exchange.getRequestURI().getPath())) {
                    sendJsonError(exchange, 404, "API endpoint not found");
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                String token = sessionToken(exchange);
                Optional<AuthService.AuthenticatedUser> user =
                        authService.authenticateSession(token);
                if (token != null && user.isEmpty()) {
                    clearSessionCookie(exchange);
                }
                sendJson(exchange, 200, authResponse(authService, user.orElse(null)));
            } finally {
                exchange.close();
            }
        }
    }

    private static final class RegisterHandler implements HttpHandler {
        private final AuthService authService;
        private final UserEmailStore emailStore;
        private final EmailVerificationService verificationService;

        private RegisterHandler(AuthService authService) {
            this(authService, null, null);
        }

        private RegisterHandler(
                AuthService authService,
                UserEmailStore emailStore,
                EmailVerificationService verificationService) {
            this.authService = Objects.requireNonNull(
                    authService, "authService must not be null");
            this.emailStore = emailStore;
            this.verificationService = verificationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, AUTH_REGISTER_PATH);
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                AuthService.SessionHandle session;
                if (emailStore == null) {
                    Credentials credentials = parseCredentials(exchange);
                    session = authService.register(
                            credentials.username(), credentials.password());
                } else {
                    if (verificationService == null) {
                        throw new ClientError(
                                503, "Email verification is not configured");
                    }
                    Map<String, String> document = parseStringDocument(
                            exchange,
                            MAX_AUTH_REQUEST_BYTES,
                            REGISTER_KEYS,
                            256);
                    String email = UserEmailStore.normalizeEmail(
                            document.get("email"));
                    if (emailStore.findUserId(email).isPresent()) {
                        throw new ClientError(409, "Email is already bound");
                    }
                    requireVerifiedEmailCode(
                            verificationService.verifyAndConsume(
                                    EmailVerificationService.Purpose.REGISTER,
                                    requireVerificationFlowId(
                                            document.get("verificationFlowId")),
                                    email,
                                    document.get("verificationCode")));
                    session = authService.register(
                            document.get("username"), document.get("password"));
                    try {
                        emailStore.bind(session.user().id(), email);
                    } catch (IOException | RuntimeException error) {
                        try {
                            authService.deleteAccount(
                                    session.token(), document.get("password"));
                        } catch (IOException | RuntimeException rollbackError) {
                            error.addSuppressed(rollbackError);
                        }
                        throw error;
                    }
                }
                setSessionCookie(exchange, session.token());
                sendJson(exchange, 200, authResponse(authService, session.user()));
            } catch (UserStore.UsernameAlreadyExistsException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (UserEmailStore.EmailAlreadyBoundException
                    | UserEmailStore.UserAlreadyBoundException
                    | UserEmailStore.EmailNotBoundException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (UserEmailStore.ValidationException
                    | EmailVerificationService.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (AuthService.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to save user account");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class LoginHandler implements HttpHandler {
        private final AuthService authService;

        private LoginHandler(AuthService authService) {
            this.authService = authService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, AUTH_LOGIN_PATH);
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                Credentials credentials = parseCredentials(exchange);
                AuthService.SessionHandle session = authService.loginIdentifier(
                        credentials.username(), credentials.password());
                setSessionCookie(exchange, session.token());
                sendJson(exchange, 200, authResponse(authService, session.user()));
            } catch (AuthService.InvalidCredentialsException error) {
                sendJsonError(exchange, 401, error.getMessage());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class LogoutHandler implements HttpHandler {
        private final AuthService authService;

        private LogoutHandler(AuthService authService) {
            this.authService = authService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, AUTH_LOGOUT_PATH);
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                authService.logout(sessionToken(exchange));
                clearSessionCookie(exchange);
                sendJson(exchange, 200, authResponse(authService, null));
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } finally {
                exchange.close();
            }
        }
    }

    private static final class EmailCodeHandler implements HttpHandler {
        private final AuthService authService;
        private final UserEmailStore emailStore;
        private final EmailVerificationService verificationService;

        private EmailCodeHandler(
                AuthService authService,
                UserEmailStore emailStore,
                EmailVerificationService verificationService) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.emailStore = Objects.requireNonNull(emailStore, "emailStore");
            this.verificationService = verificationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, AUTH_EMAIL_CODE_PATH);
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The email code endpoint");
                if (verificationService == null) {
                    throw new ClientError(503, "Email verification is not configured");
                }
                Map<String, String> document = parseStringDocument(
                        exchange,
                        MAX_AUTH_REQUEST_BYTES,
                        EMAIL_CODE_KEYS,
                        256);
                String email = UserEmailStore.normalizeEmail(document.get("email"));
                String requestedPurpose = document.get("purpose")
                        .strip()
                        .toLowerCase(Locale.ROOT);
                EmailVerificationService.Purpose purpose;
                String context;
                String verificationFlowId = null;
                if ("register".equals(requestedPurpose)) {
                    if (emailStore.findUserId(email).isPresent()) {
                        throw new ClientError(409, "Email is already bound");
                    }
                    purpose = EmailVerificationService.Purpose.REGISTER;
                    verificationFlowId = UUID.randomUUID().toString();
                    context = verificationFlowId;
                } else if ("bind".equals(requestedPurpose)) {
                    AuthService.AuthenticatedUser user =
                            requireAuthenticatedUserAllowUnbound(exchange, authService);
                    Optional<String> owner = emailStore.findUserId(email);
                    if (owner.isPresent() && !owner.orElseThrow().equals(user.id())) {
                        throw new ClientError(409, "Email is already bound");
                    }
                    Optional<String> current = emailStore.findEmail(user.id());
                    if (current.filter(email::equals).isPresent()) {
                        throw new ClientError(400, "This email is already bound to the account");
                    }
                    purpose = current.isPresent()
                            ? EmailVerificationService.Purpose.REBIND
                            : EmailVerificationService.Purpose.BIND;
                    context = user.id();
                } else {
                    throw new ClientError(400, "purpose must be register or bind");
                }

                EmailVerificationService.SendResult result =
                        verificationService.sendCode(purpose, context, email);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("email", result.email());
                if (verificationFlowId != null) {
                    response.put("verificationFlowId", verificationFlowId);
                }
                response.put("expiresInSeconds", EmailVerificationService.CODE_TTL.toSeconds());
                response.put(
                        "resendAfterSeconds",
                        EmailVerificationService.RESEND_COOLDOWN.toSeconds());
                response.put("expiresAt", result.expiresAt().toString());
                response.put(
                        "resendAvailableAt", result.resendAvailableAt().toString());
                sendJson(exchange, 200, Json.stringify(response));
            } catch (EmailVerificationService.RateLimitException error) {
                exchange.getResponseHeaders().set(
                        "Retry-After", Long.toString(error.retryAfterSeconds()));
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("error", error.getMessage());
                response.put("retryAfterSeconds", error.retryAfterSeconds());
                sendJson(exchange, 429, Json.stringify(response));
            } catch (UserEmailStore.ValidationException
                    | EmailVerificationService.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 502, "Unable to deliver verification email");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class UserEmailHandler implements HttpHandler {
        private final AuthService authService;
        private final UserEmailStore emailStore;
        private final EmailVerificationService verificationService;

        private UserEmailHandler(
                AuthService authService,
                UserEmailStore emailStore,
                EmailVerificationService verificationService) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.emailStore = Objects.requireNonNull(emailStore, "emailStore");
            this.verificationService = verificationService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, USER_EMAIL_PATH);
                if (handleOptions(exchange, "GET, PUT, OPTIONS")) {
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(method)
                        && !"PUT".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, PUT, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The user email endpoint");
                String token = sessionToken(exchange);
                AuthService.AuthenticatedUser user =
                        requireAuthenticatedUserAllowUnbound(exchange, authService);
                if ("GET".equalsIgnoreCase(method)) {
                    sendJson(exchange, 200, emailBindingResponse(emailStore, user.id()));
                    return;
                }
                if (verificationService == null) {
                    throw new ClientError(503, "Email verification is not configured");
                }
                Map<String, String> document = parseStringDocument(
                        exchange,
                        MAX_AUTH_REQUEST_BYTES,
                        EMAIL_UPDATE_KEYS,
                        256);
                String email = UserEmailStore.normalizeEmail(document.get("email"));
                String updatedUserId = authService.withVerifiedCurrentPassword(
                        token,
                        document.get("currentPassword"),
                        verifiedUser -> {
                            Optional<String> owner = emailStore.findUserId(email);
                            if (owner.isPresent()
                                    && !owner.orElseThrow().equals(verifiedUser.id())) {
                                throw new ClientError(409, "Email is already bound");
                            }
                            Optional<String> current =
                                    emailStore.findEmail(verifiedUser.id());
                            if (current.filter(email::equals).isPresent()) {
                                throw new ClientError(
                                        400, "The new email must be different");
                            }
                            EmailVerificationService.Purpose purpose = current.isPresent()
                                    ? EmailVerificationService.Purpose.REBIND
                                    : EmailVerificationService.Purpose.BIND;
                            requireVerifiedEmailCode(
                                    verificationService.verifyAndConsume(
                                            purpose,
                                            verifiedUser.id(),
                                            email,
                                            document.get("verificationCode")));
                            if (current.isPresent()) {
                                emailStore.rebind(verifiedUser.id(), email);
                            } else {
                                emailStore.bind(verifiedUser.id(), email);
                            }
                            return verifiedUser.id();
                        });
                sendJson(
                        exchange,
                        200,
                        emailBindingResponse(emailStore, updatedUserId));
            } catch (AuthService.InvalidCurrentPasswordException error) {
                sendJsonError(exchange, 401, error.getMessage());
            } catch (UserEmailStore.EmailAlreadyBoundException
                    | UserEmailStore.UserAlreadyBoundException
                    | UserEmailStore.EmailNotBoundException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (UserEmailStore.ValidationException
                    | EmailVerificationService.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to update email binding");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class UserAccountHandler implements HttpHandler {
        private final AuthService authService;
        private final UserEmailStore emailStore;
        private final UserProfileStore profileStore;
        private final ChunithmScoreStore chunithmScoreStore;
        private final PlayHistoryStore playHistoryStore;
        private final ChunithmHistoryStore chunithmHistoryStore;
        private final SyncSessionStore syncSessionStore;

        private UserAccountHandler(
                AuthService authService,
                UserEmailStore emailStore,
                UserProfileStore profileStore,
                ChunithmScoreStore chunithmScoreStore,
                PlayHistoryStore playHistoryStore,
                ChunithmHistoryStore chunithmHistoryStore,
                SyncSessionStore syncSessionStore) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.emailStore = Objects.requireNonNull(emailStore, "emailStore");
            this.profileStore = Objects.requireNonNull(profileStore, "profileStore");
            this.chunithmScoreStore = Objects.requireNonNull(
                    chunithmScoreStore, "chunithmScoreStore");
            this.playHistoryStore = Objects.requireNonNull(
                    playHistoryStore, "playHistoryStore");
            this.chunithmHistoryStore = Objects.requireNonNull(
                    chunithmHistoryStore, "chunithmHistoryStore");
            this.syncSessionStore = Objects.requireNonNull(
                    syncSessionStore, "syncSessionStore");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, USER_ACCOUNT_PATH);
                if (handleOptions(exchange, "DELETE, OPTIONS")) {
                    return;
                }
                if (!"DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "DELETE, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The user account endpoint");
                String token = sessionToken(exchange);
                Map<String, String> document = parseStringDocument(
                        exchange,
                        MAX_AUTH_REQUEST_BYTES,
                        ACCOUNT_DELETE_KEYS,
                        256);
                AuthService.AuthenticatedUser deleted = authService.deleteAccount(
                        token,
                        document.get("currentPassword"),
                        user -> emailStore.deleteUser(user.id()));
                boolean cleanupComplete = cleanupDeletedUser(deleted.id());
                clearSessionCookie(exchange);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", true);
                response.put("cleanupComplete", cleanupComplete);
                sendJson(exchange, 200, Json.stringify(response));
            } catch (AuthService.InvalidCurrentPasswordException error) {
                sendJsonError(exchange, 401, error.getMessage());
            } catch (UserStore.CredentialConflictException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to delete account");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }

        private boolean cleanupDeletedUser(String userId) {
            List<IOException> failures = new ArrayList<>();
            try {
                emailStore.deleteUser(userId);
            } catch (IOException error) {
                failures.add(error);
            }
            try {
                profileStore.deleteUserData(userId);
            } catch (IOException error) {
                failures.add(error);
            }
            try {
                chunithmScoreStore.deleteUserData(userId);
            } catch (IOException error) {
                failures.add(error);
            }
            try {
                playHistoryStore.deleteUserData(userId);
            } catch (IOException error) {
                failures.add(error);
            }
            try {
                chunithmHistoryStore.deleteUserData(userId);
            } catch (IOException error) {
                failures.add(error);
            }
            syncSessionStore.deleteUserSessions(userId);
            for (IOException failure : failures) {
                failure.printStackTrace(System.err);
            }
            return failures.isEmpty();
        }
    }

    private static final class UserProfileHandler implements HttpHandler {
        private final AuthService authService;
        private final UserProfileStore profileStore;

        private UserProfileHandler(
                AuthService authService,
                UserProfileStore profileStore) {
            this.authService = Objects.requireNonNull(
                    authService, "authService must not be null");
            this.profileStore = Objects.requireNonNull(
                    profileStore, "profileStore must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, USER_PROFILE_PATH);
                if (handleOptions(exchange, "GET, PUT, OPTIONS")) {
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(method)
                        && !"PUT".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, PUT, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The user profile endpoint");
                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);
                UserProfileStore.Profile profile;
                if ("GET".equalsIgnoreCase(method)) {
                    profile = profileStore.loadProfile(
                            user.id(), user.username());
                } else {
                    Map<String, String> document = parseStringDocument(
                            exchange,
                            MAX_AUTH_REQUEST_BYTES,
                            PROFILE_UPDATE_KEYS,
                            256);
                    profile = profileStore.updateDisplayName(
                            user.id(),
                            user.username(),
                            document.get("displayName"));
                }
                sendJson(exchange, 200, profileResponse(profile));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (UserProfileStore.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to access user profile");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private enum ProfileImageKind {
        AVATAR(USER_AVATAR_PATH, UserProfileStore.MAX_AVATAR_UPLOAD_BYTES),
        BACKGROUND(
                USER_BACKGROUND_PATH,
                UserProfileStore.MAX_BACKGROUND_UPLOAD_BYTES);

        private final String path;
        private final int maxUploadBytes;

        ProfileImageKind(String path, int maxUploadBytes) {
            this.path = path;
            this.maxUploadBytes = maxUploadBytes;
        }

        Optional<UserProfileStore.ImageData> load(
                UserProfileStore store,
                String userId,
                UserProfileStore.BackgroundGame backgroundGame) throws IOException {
            return this == AVATAR
                    ? store.loadAvatar(userId)
                    : store.loadBackground(userId, backgroundGame);
        }

        void save(
                UserProfileStore store,
                String userId,
                UserProfileStore.BackgroundGame backgroundGame,
                String contentType,
                byte[] content) throws IOException {
            if (this == AVATAR) {
                store.saveAvatar(userId, contentType, content);
            } else {
                store.saveBackground(userId, backgroundGame, contentType, content);
            }
        }

        void delete(
                UserProfileStore store,
                String userId,
                UserProfileStore.BackgroundGame backgroundGame) throws IOException {
            if (this == AVATAR) {
                store.deleteAvatar(userId);
            } else {
                store.deleteBackground(userId, backgroundGame);
            }
        }
    }

    private static final class UserProfileImageHandler implements HttpHandler {
        private final AuthService authService;
        private final UserProfileStore profileStore;
        private final ProfileImageKind kind;

        private UserProfileImageHandler(
                AuthService authService,
                UserProfileStore profileStore,
                ProfileImageKind kind) {
            this.authService = Objects.requireNonNull(
                    authService, "authService must not be null");
            this.profileStore = Objects.requireNonNull(
                    profileStore, "profileStore must not be null");
            this.kind = Objects.requireNonNull(kind, "kind must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, kind.path);
                if (handleOptions(exchange, "GET, HEAD, PUT, DELETE, OPTIONS")) {
                    return;
                }
                String method = exchange.getRequestMethod();
                boolean headRequest = "HEAD".equalsIgnoreCase(method);
                if (!headRequest
                        && !"GET".equalsIgnoreCase(method)
                        && !"PUT".equalsIgnoreCase(method)
                        && !"DELETE".equalsIgnoreCase(method)) {
                    methodNotAllowed(
                            exchange, "GET, HEAD, PUT, DELETE, OPTIONS");
                    return;
                }
                UserProfileStore.BackgroundGame backgroundGame =
                        kind == ProfileImageKind.BACKGROUND
                                ? profileBackgroundGame(exchange)
                                : null;
                if (kind == ProfileImageKind.AVATAR) {
                    rejectQueryParameters(exchange, "The avatar endpoint");
                }
                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);

                if (headRequest || "GET".equalsIgnoreCase(method)) {
                    UserProfileStore.ImageData image = kind
                            .load(profileStore, user.id(), backgroundGame)
                            .orElseThrow(() -> new ClientError(
                                    404, "Profile image was not found"));
                    sendPrivateImage(exchange, image, headRequest);
                    return;
                }

                if ("PUT".equalsIgnoreCase(method)) {
                    String contentType = exchange.getRequestHeaders()
                            .getFirst("Content-Type");
                    String mediaType = contentType == null
                            ? ""
                            : contentType.split(";", 2)[0].trim();
                    if (!"image/png".equalsIgnoreCase(mediaType)
                            && !"image/jpeg".equalsIgnoreCase(mediaType)) {
                        throw new ClientError(
                                415,
                                "Content-Type must be image/png or image/jpeg");
                    }
                    byte[] content = readRequestBody(
                            exchange, kind.maxUploadBytes);
                    kind.save(
                            profileStore,
                            user.id(),
                            backgroundGame,
                            mediaType,
                            content);
                } else {
                    kind.delete(profileStore, user.id(), backgroundGame);
                }

                UserProfileStore.Profile profile = profileStore.loadProfile(
                        user.id(), user.username());
                sendJson(exchange, 200, profileResponse(profile));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (UserProfileStore.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to access profile image");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class UserPasswordHandler implements HttpHandler {
        private final AuthService authService;

        private UserPasswordHandler(AuthService authService) {
            this.authService = Objects.requireNonNull(
                    authService, "authService must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, USER_PASSWORD_PATH);
                if (handleOptions(exchange, "PUT, OPTIONS")) {
                    return;
                }
                if (!"PUT".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "PUT, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The password endpoint");
                String token = sessionToken(exchange);
                requireAuthenticatedUser(exchange, authService);
                Map<String, String> document = parseStringDocument(
                        exchange,
                        MAX_AUTH_REQUEST_BYTES,
                        PASSWORD_UPDATE_KEYS,
                        256);
                AuthService.SessionHandle session = authService.changePassword(
                        token,
                        document.get("currentPassword"),
                        document.get("newPassword"));
                setSessionCookie(exchange, session.token());
                sendJson(exchange, 200, authResponse(authService, session.user()));
            } catch (AuthService.InvalidCurrentPasswordException error) {
                sendJsonError(exchange, 401, error.getMessage());
            } catch (AuthService.ValidationException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (UserStore.CredentialConflictException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to change password");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class UserChartsHandler implements HttpHandler {
        private final AuthService authService;
        private final UserStore userStore;
        private final MaimaiScoreCanonicalizer canonicalizer;

        private UserChartsHandler(AuthService authService, UserStore userStore) {
            this(
                    authService,
                    userStore,
                    new MaimaiScoreCanonicalizer(
                            SongCatalog.empty("test catalogue is empty")));
        }

        private UserChartsHandler(
                AuthService authService,
                UserStore userStore,
                MaimaiScoreCanonicalizer canonicalizer) {
            this.authService = authService;
            this.userStore = userStore;
            this.canonicalizer = Objects.requireNonNull(
                    canonicalizer, "canonicalizer must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, USER_CHARTS_PATH);
                if (handleOptions(exchange, "GET, PUT, OPTIONS")) {
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, PUT, OPTIONS");
                    return;
                }
                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);

                if ("GET".equalsIgnoreCase(method)) {
                    UserStore.ChartSnapshot snapshot =
                            userStore.loadChartSnapshot(user.id());
                    List<ChartInput> canonical = canonicalizer.charts(
                            snapshot.charts());
                    if (!canonical.equals(snapshot.charts())) {
                        snapshot = userStore.saveCharts(
                                user.id(), user.id(), snapshot.revision(), canonical);
                    }
                    sendJson(exchange, 200, chartSnapshotResponse(snapshot));
                    return;
                }

                requireJsonContentType(exchange);
                Object document = parseJsonBody(exchange, MAX_REQUEST_BYTES);
                UserStore.ChartUpdate update = UserStore.parseChartUpdateDocument(
                        document, MAX_CHARTS);
                List<ChartInput> canonical = canonicalizer.charts(update.charts());
                UserStore.ChartSnapshot snapshot = userStore.saveCharts(
                        user.id(),
                        update.expectedUserId(),
                        update.revision(),
                        canonical);
                sendJson(exchange, 200, chartSnapshotResponse(snapshot));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (UserStore.ChartConflictException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to access saved chart data");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class SongCatalogHandler implements HttpHandler {
        private final SongCatalog songCatalog;

        private SongCatalogHandler(SongCatalog songCatalog) {
            this.songCatalog = songCatalog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, SONG_CATALOG_PATH);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    throw new ClientError(
                            400, "The catalog endpoint does not accept query parameters");
                }
                sendJson(exchange, 200, songCatalog.catalog().toJson());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to read song catalogue");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class SongSearchHandler implements HttpHandler {
        private final SongCatalog songCatalog;

        private SongSearchHandler(SongCatalog songCatalog) {
            this.songCatalog = songCatalog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, SONG_SEARCH_PATH);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                SongSearchOptions options = parseSongSearchOptions(
                        exchange.getRequestURI().getRawQuery());
                SongCatalog.CatalogResult result = songCatalog.search(
                        options.query(), options.limit(), options.online());
                sendJson(exchange, 200, result.toJson());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to search song catalogue");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class MaimaiCoverHandler implements HttpHandler {
        private final MaimaiCoverService coverService;

        private MaimaiCoverHandler(MaimaiCoverService coverService) {
            this.coverService = coverService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                boolean headRequest = "HEAD".equalsIgnoreCase(method);
                if (!headRequest && !"GET".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, HEAD");
                    return;
                }
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    throw new ClientError(
                            400, "The cover endpoint does not accept query parameters");
                }
                String songId = parseMaimaiCoverSongId(
                        exchange.getRequestURI().getPath());
                byte[] cover = coverService.cover(songId);
                sendPng(exchange, cover, headRequest);
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                sendJsonError(exchange, 502, "Unable to fetch Maimai cover");
            } catch (IOException error) {
                sendJsonError(exchange, 502, "Unable to fetch Maimai cover");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to read Maimai cover");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class ChunithmCatalogHandler implements HttpHandler {
        private final ChunithmCatalog catalog;

        private ChunithmCatalogHandler(ChunithmCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, CHUNITHM_CATALOG_PATH);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                boolean online = parseChunithmCatalogOnline(
                        exchange.getRequestURI().getRawQuery());
                sendJson(exchange, 200, catalog.catalog(online).toJson());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to read CHUNITHM catalogue");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class ChunithmSearchHandler implements HttpHandler {
        private final ChunithmCatalog catalog;

        private ChunithmSearchHandler(ChunithmCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, CHUNITHM_SEARCH_PATH);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                SongSearchOptions options = parseSongSearchOptions(
                        exchange.getRequestURI().getRawQuery());
                sendJson(exchange, 200, catalog.search(
                        options.query(), options.limit(), options.online()).toJson());
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to search CHUNITHM catalogue");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class ChunithmCoverHandler implements HttpHandler {
        private final ChunithmCoverService coverService;

        private ChunithmCoverHandler(ChunithmCoverService coverService) {
            this.coverService = coverService;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String method = exchange.getRequestMethod();
                boolean headRequest = "HEAD".equalsIgnoreCase(method);
                if (!headRequest && !"GET".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, HEAD");
                    return;
                }
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    throw new ClientError(
                            400, "The cover endpoint does not accept query parameters");
                }
                String songId = parseChunithmCoverSongId(
                        exchange.getRequestURI().getPath());
                byte[] cover = coverService.cover(songId);
                sendPng(exchange, cover, headRequest);
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                sendJsonError(exchange, 502, "Unable to fetch CHUNITHM cover");
            } catch (IOException error) {
                sendJsonError(exchange, 502, "Unable to fetch CHUNITHM cover");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to read CHUNITHM cover");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class ChunithmCalculateHandler implements HttpHandler {
        private final ChunithmCatalog catalog;

        private ChunithmCalculateHandler(ChunithmCatalog catalog) {
            this.catalog = catalog;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, CHUNITHM_CALCULATE_PATH);
                String rawQuery = exchange.getRequestURI().getRawQuery();
                if (rawQuery != null && !rawQuery.isEmpty()) {
                    throw new ClientError(
                            400, "The calculate endpoint does not accept query parameters");
                }
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                requireJsonContentType(exchange);
                Object document = parseJsonBody(
                        exchange, MAX_CHUNITHM_CALCULATE_BYTES);
                List<ChunithmChartInput> charts = parseChunithmCharts(document);
                ChunithmCatalog.CatalogResult activeCatalog =
                        catalog.currentCatalog();
                ChunithmResult result = ChunithmCalculator.calculate(
                        charts,
                        activeCatalog.latestVersions(),
                        activeCatalog.disabledSongIds());
                sendJson(exchange, 200, chunithmCalculateResponse(result));
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to calculate CHUNITHM Rating");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class ChunithmUserChartsHandler implements HttpHandler {
        private final AuthService authService;
        private final ChunithmScoreStore scoreStore;
        private final ChunithmCatalog catalog;

        private ChunithmUserChartsHandler(
                AuthService authService,
                ChunithmScoreStore scoreStore,
                ChunithmCatalog catalog) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.scoreStore = Objects.requireNonNull(scoreStore, "scoreStore");
            this.catalog = Objects.requireNonNull(catalog, "catalog");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, CHUNITHM_USER_CHARTS_PATH);
                if (handleOptions(exchange, "GET, PUT, OPTIONS")) {
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!"GET".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, PUT, OPTIONS");
                    return;
                }

                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);
                List<String> latestVersions = catalog.currentCatalog()
                        .latestVersions();

                if ("GET".equalsIgnoreCase(method)) {
                    ChunithmScoreStore.Snapshot snapshot = scoreStore.loadSnapshot(
                            user.id(), latestVersions);
                    sendJson(exchange, 200, Json.stringify(
                            ChunithmScoreStore.snapshotToJsonValue(snapshot)));
                    return;
                }

                requireJsonContentType(exchange);
                Object document = parseJsonBody(exchange, MAX_REQUEST_BYTES);
                ChunithmScoreStore.Update update =
                        ChunithmScoreStore.parseUpdateDocument(
                                document, latestVersions);
                ChunithmScoreStore.Snapshot snapshot = scoreStore.save(
                        user.id(), update, latestVersions);
                sendJson(exchange, 200, Json.stringify(
                        ChunithmScoreStore.snapshotToJsonValue(snapshot)));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (ChunithmScoreStore.ConflictException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to access saved CHUNITHM score data");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class PlayHistoryHandler implements HttpHandler {
        private final AuthService authService;
        private final PlayHistoryStore maimaiHistoryStore;
        private final ChunithmHistoryStore chunithmHistoryStore;

        private PlayHistoryHandler(
                AuthService authService,
                PlayHistoryStore maimaiHistoryStore,
                ChunithmHistoryStore chunithmHistoryStore) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.maimaiHistoryStore = Objects.requireNonNull(
                    maimaiHistoryStore, "maimaiHistoryStore");
            this.chunithmHistoryStore = Objects.requireNonNull(
                    chunithmHistoryStore, "chunithmHistoryStore");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, PLAY_HISTORY_PATH);
                if (handleOptions(exchange, "GET, OPTIONS")) {
                    return;
                }
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET, OPTIONS");
                    return;
                }
                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);
                HistoryQuery query = parseHistoryQuery(
                        exchange.getRequestURI().getRawQuery());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("game", query.game());
                if ("chunithm".equals(query.game())) {
                    List<ChunithmHistoryStore.PlayRecord> matching =
                            chunithmHistoryStore.load(user.id())
                                    .stream()
                                    .filter(query::matches)
                                    .sorted(Comparator.comparing(
                                            (ChunithmHistoryStore.PlayRecord record) ->
                                                    Instant.parse(record.playedAt()))
                                            .reversed())
                                    .limit(query.limit())
                                    .toList();
                    response.put("count", matching.size());
                    response.put(
                            "records",
                            ChunithmHistoryStore.recordsToJsonValues(matching));
                } else {
                    List<PlayHistoryStore.PlayRecord> matching =
                            maimaiHistoryStore.load(user.id())
                                    .stream()
                                    .filter(query::matches)
                                    .sorted(Comparator.comparing(
                                            (PlayHistoryStore.PlayRecord record) ->
                                                    Instant.parse(record.playedAt()))
                                            .reversed())
                                    .limit(query.limit())
                                    .toList();
                    response.put("count", matching.size());
                    response.put(
                            "records",
                            PlayHistoryStore.recordsToJsonValues(matching));
                }
                sendJson(exchange, 200, Json.stringify(response));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to access play history");
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class SyncSessionsHandler implements HttpHandler {
        private final AuthService authService;
        private final SyncSessionStore sessionStore;

        private SyncSessionsHandler(
                AuthService authService,
                SyncSessionStore sessionStore) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                if (handleOptions(exchange, "GET, POST, OPTIONS")) {
                    return;
                }
                if (isSyncHelperEventPath(path)) {
                    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        methodNotAllowed(exchange, "POST, OPTIONS");
                        return;
                    }
                    rejectQueryParameters(exchange, "The sync helper event endpoint");
                    String token = bearerToken(exchange);
                    if (token == null) {
                        throw new ClientError(401, "Invalid helper authorization");
                    }
                    requireJsonContentType(exchange);
                    Map<String, Object> document = parseSyncHelperEventDocument(
                            parseJsonBody(exchange, MAX_AUTH_REQUEST_BYTES));
                    String status = requireJsonString(
                            document.get("status"), "status", 40)
                            .toLowerCase(Locale.ROOT);
                    SyncSessionStore.HelperEvent event = switch (status) {
                        case "waiting_auth" ->
                                SyncSessionStore.HelperEvent.WAITING_AUTH;
                        case "callback_received" ->
                                SyncSessionStore.HelperEvent.CALLBACK_RECEIVED;
                        case "fetching" -> SyncSessionStore.HelperEvent.FETCHING;
                        case "failed" -> SyncSessionStore.HelperEvent.FAILED;
                        default -> throw new ClientError(
                                400, "Unsupported sync helper status");
                    };
                    String message = optionalJsonString(
                            document.get("message"), "message", 200);
                    SyncSessionStore.Progress progress = null;
                    SyncSessionStore.Session updated;
                    if (event == SyncSessionStore.HelperEvent.WAITING_AUTH) {
                        if (!document.keySet().equals(Set.of("status"))) {
                            throw new ClientError(
                                    400,
                                    "waiting_auth must contain only status");
                        }
                        updated = sessionStore.reportWaitingAuth(
                                syncSessionIdFromEventPath(path), token);
                    } else {
                        String game = requireJsonString(
                                document.get("game"), "game", 32)
                                .toLowerCase(Locale.ROOT);
                        Set<String> expectedFields;
                        if (event == SyncSessionStore.HelperEvent.FETCHING
                                && document.containsKey("stage")) {
                            boolean hasDiagnostics = document.containsKey("skipped")
                                    || document.containsKey("failureReasons");
                            expectedFields = hasDiagnostics
                                    ? Set.of(
                                            "game", "status", "stage", "completed",
                                            "total", "succeeded", "skipped",
                                            "failureReasons")
                                    : Set.of(
                                            "game", "status", "stage", "completed",
                                            "total", "succeeded");
                            if (document.keySet().equals(expectedFields)) {
                                String progressStage = requireJsonString(
                                        document.get("stage"), "stage", 40);
                                int progressCompleted = requireJsonInteger(
                                        document.get("completed"), "completed");
                                int progressTotal = requireJsonInteger(
                                        document.get("total"), "total");
                                int progressSucceeded = requireJsonInteger(
                                        document.get("succeeded"), "succeeded");
                                progress = hasDiagnostics
                                        ? new SyncSessionStore.Progress(
                                                progressStage,
                                                progressCompleted,
                                                progressTotal,
                                                progressSucceeded,
                                                requireJsonInteger(
                                                        document.get("skipped"),
                                                        "skipped"),
                                                requireProgressFailureReasons(
                                                        document.get("failureReasons")))
                                        : new SyncSessionStore.Progress(
                                                progressStage,
                                                progressCompleted,
                                                progressTotal,
                                                progressSucceeded);
                            }
                        } else if (event == SyncSessionStore.HelperEvent.FAILED
                                && message != null) {
                            expectedFields = Set.of("game", "status", "message");
                        } else {
                            expectedFields = Set.of("game", "status");
                        }
                        if (!document.keySet().equals(expectedFields)) {
                            throw new ClientError(
                                    400,
                                    "Sync helper event fields do not match status");
                        }
                        updated = sessionStore.reportHelperEvent(
                                syncSessionIdFromEventPath(path),
                                token,
                                game,
                                event,
                                message,
                                progress);
                    }
                    sendJson(exchange, 200, Json.stringify(Map.of(
                            "session", syncSessionValue(updated))));
                    return;
                }
                AuthService.AuthenticatedUser user = requireAuthenticatedUser(
                        exchange, authService);
                String method = exchange.getRequestMethod();
                if (SYNC_SESSIONS_PATH.equals(path)) {
                    if ("GET".equalsIgnoreCase(method)) {
                        rejectQueryParameters(exchange, "The sync session list");
                        List<Map<String, Object>> sessions = sessionStore
                                .listForUser(user.id())
                                .stream()
                                .map(WebServer::syncSessionValue)
                                .toList();
                        sendJson(exchange, 200, Json.stringify(Map.of(
                                "sessions", sessions)));
                        return;
                    }
                    if (!"POST".equalsIgnoreCase(method)) {
                        methodNotAllowed(exchange, "GET, POST, OPTIONS");
                        return;
                    }
                    rejectQueryParameters(exchange, "The sync session endpoint");
                    requireJsonContentType(exchange);
                    Map<String, Object> document = requireStrictJsonObject(
                            parseJsonBody(exchange, MAX_AUTH_REQUEST_BYTES),
                            "Request body");
                    requireExactFields(
                            document, SYNC_SESSION_CREATE_KEYS, "Request body");
                    String game = requireJsonString(
                            document.get("game"), "game", 32);
                    SyncSessionStore.CreatedSession created = sessionStore.create(
                            user.id(), game);
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("session", syncSessionValue(created.session()));
                    response.put("helperToken", created.helperToken());
                    response.put("helperPort", 8081);
                    response.put(
                            "helperPath",
                            "/start?sessionId="
                                    + urlEncode(created.session().id())
                                    + "&token="
                                    + urlEncode(created.helperToken()));
                    sendJson(exchange, 201, Json.stringify(response));
                    return;
                }

                String sessionId = syncSessionIdFromPath(path);
                if (!"GET".equalsIgnoreCase(method)) {
                    methodNotAllowed(exchange, "GET, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The sync session endpoint");
                SyncSessionStore.Session session = sessionStore.findForUser(
                        user.id(), sessionId)
                        .orElseThrow(() -> new ClientError(
                                404, "Sync session was not found"));
                sendJson(exchange, 200, Json.stringify(Map.of(
                        "session", syncSessionValue(session))));
            } catch (ClientError error) {
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (SyncSessionStore.InvalidSessionException error) {
                sendJsonError(exchange, 409, error.getMessage());
            } catch (IllegalArgumentException error) {
                sendJsonError(exchange, 400, error.getMessage());
            } catch (RuntimeException error) {
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to create sync session");
            } finally {
                exchange.close();
            }
        }
    }

    private static final class SyncImportHandler implements HttpHandler {
        private final AuthService authService;
        private final UserStore userStore;
        private final PlayHistoryStore maimaiHistoryStore;
        private final ChunithmScoreStore chunithmScoreStore;
        private final ChunithmHistoryStore chunithmHistoryStore;
        private final SyncSessionStore sessionStore;
        private final MaimaiScoreCanonicalizer maimaiCanonicalizer;
        private final ChunithmScoreCanonicalizer chunithmCanonicalizer;
        private final ChunithmCatalog chunithmCatalog;

        private SyncImportHandler(
                AuthService authService,
                UserStore userStore,
                PlayHistoryStore maimaiHistoryStore,
                ChunithmScoreStore chunithmScoreStore,
                ChunithmHistoryStore chunithmHistoryStore,
                SyncSessionStore sessionStore,
                MaimaiScoreCanonicalizer maimaiCanonicalizer,
                ChunithmScoreCanonicalizer chunithmCanonicalizer,
                ChunithmCatalog chunithmCatalog) {
            this.authService = Objects.requireNonNull(authService, "authService");
            this.userStore = Objects.requireNonNull(userStore, "userStore");
            this.maimaiHistoryStore = Objects.requireNonNull(
                    maimaiHistoryStore, "maimaiHistoryStore");
            this.chunithmScoreStore = Objects.requireNonNull(
                    chunithmScoreStore, "chunithmScoreStore");
            this.chunithmHistoryStore = Objects.requireNonNull(
                    chunithmHistoryStore, "chunithmHistoryStore");
            this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
            this.maimaiCanonicalizer = Objects.requireNonNull(
                    maimaiCanonicalizer, "maimaiCanonicalizer must not be null");
            this.chunithmCanonicalizer = Objects.requireNonNull(
                    chunithmCanonicalizer, "chunithmCanonicalizer must not be null");
            this.chunithmCatalog = Objects.requireNonNull(
                    chunithmCatalog, "chunithmCatalog must not be null");
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            SyncSessionStore.ImportAuthorization helperAuthorization = null;
            try {
                requireExactApiPath(exchange, SYNC_IMPORT_PATH);
                if (handleOptions(exchange, "POST, OPTIONS")) {
                    return;
                }
                if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "POST, OPTIONS");
                    return;
                }
                rejectQueryParameters(exchange, "The sync import endpoint");
                requireJsonContentType(exchange);
                Map<String, Object> document = parseSyncImportDocument(
                        parseJsonBody(exchange, MAX_SYNC_REQUEST_BYTES));
                String game = requireJsonString(document.get("game"), "game", 32)
                        .trim()
                        .toLowerCase(Locale.ROOT);
                if (!"maimai".equals(game) && !"chunithm".equals(game)) {
                    throw new ClientError(
                            400, "game must be maimai or chunithm");
                }

                String bearerToken = bearerToken(exchange);
                String sessionId = optionalJsonString(
                        document.get("sessionId"), "sessionId", 100);
                final String userId;
                final String batchId;
                if (bearerToken != null) {
                    if (sessionId == null) {
                        throw new ClientError(
                                400, "sessionId is required for helper imports");
                    }
                    helperAuthorization = sessionStore.beginImport(
                            sessionId, bearerToken, game);
                    userId = helperAuthorization.userId();
                    batchId = helperAuthorization.sessionId();
                } else {
                    if (sessionId != null) {
                        throw new ClientError(
                                400, "sessionId is only accepted from the sync helper");
                    }
                    userId = requireAuthenticatedUser(exchange, authService).id();
                    batchId = UUID.randomUUID().toString();
                }

                SyncImportResult imported = "chunithm".equals(game)
                        ? importChunithm(document, userId, batchId)
                        : importMaimai(document, userId, batchId);

                SyncSessionStore.Session completedSession = null;
                if (helperAuthorization != null) {
                    completedSession = sessionStore.completeImport(
                            helperAuthorization,
                            imported.chartsChanged(),
                            imported.recordsAdded(),
                            imported.recordsEnriched(),
                            imported.recordsIgnored());
                }
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("game", game);
                response.put("chartsAdded", imported.chartsAdded());
                response.put("chartsUpdated", imported.chartsUpdated());
                response.put("chartsUnchanged", imported.chartsUnchanged());
                response.put("chartRevision", imported.chartRevision());
                response.put("recordsAdded", imported.recordsAdded());
                response.put("recordsEnriched", imported.recordsEnriched());
                response.put("recordsIgnored", imported.recordsIgnored());
                response.put("recordsTotal", imported.recordsTotal());
                response.put(
                        "session",
                        completedSession == null
                                ? null
                                : syncSessionValue(completedSession));
                sendJson(exchange, 200, Json.stringify(response));
            } catch (ClientError error) {
                failHelperImport(sessionStore, helperAuthorization, error.getMessage());
                if (error.status() == 401) {
                    clearSessionCookie(exchange);
                }
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (SyncSessionStore.InvalidSessionException error) {
                failHelperImport(sessionStore, helperAuthorization, error.getMessage());
                sendJsonError(exchange, 401, error.getMessage());
            } catch (UserStore.ChartConflictException error) {
                failHelperImport(
                        sessionStore,
                        helperAuthorization,
                        "成绩在同步期间发生变化，请重新同步");
                sendJsonError(exchange, 409, error.getMessage());
            } catch (ChunithmScoreStore.ConflictException error) {
                failHelperImport(
                        sessionStore,
                        helperAuthorization,
                        "中二节奏成绩在同步期间发生变化，请重新同步");
                sendJsonError(exchange, 409, error.getMessage());
            } catch (IllegalArgumentException error) {
                failHelperImport(sessionStore, helperAuthorization, error.getMessage());
                sendJsonError(exchange, 400, error.getMessage());
            } catch (IOException error) {
                failHelperImport(
                        sessionStore,
                        helperAuthorization,
                        "本地存储写入失败，请检查磁盘后重试");
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Unable to save synchronized game data");
            } catch (RuntimeException error) {
                failHelperImport(
                        sessionStore,
                        helperAuthorization,
                        "同步导入失败，请检查辅助程序后重试");
                error.printStackTrace(System.err);
                sendJsonError(exchange, 500, "Internal server error");
            } finally {
                exchange.close();
            }
        }

        private SyncImportResult importMaimai(
                Map<String, Object> document,
                String userId,
                String batchId) throws IOException {
            List<ChartInput> importedCharts = maimaiCanonicalizer.charts(
                    parseImportedMaimaiCharts(document.get("charts")));
            List<PlayHistoryStore.PlayRecord> importedRecords =
                    document.containsKey("records")
                            ? maimaiCanonicalizer.records(
                                    PlayHistoryStore.parseImportRecords(
                                            document.get("records"),
                                            PlayHistoryStore.MAX_APPEND_RECORDS))
                            : List.of();
            requireNonEmptySyncImport(importedCharts, importedRecords);

            ChartImportResult charts = mergeImportedCharts(
                    userStore, userId, importedCharts, maimaiCanonicalizer);
            String importedAt = Instant.now().toString();
            PlayHistoryStore.AppendResult records = maimaiHistoryStore.append(
                    userId,
                    importedRecords.stream()
                            .map(record -> record.withImportMetadata(
                                    importedAt, batchId))
                            .toList());
            return new SyncImportResult(
                    charts.added(),
                    charts.updated(),
                    charts.unchanged(),
                    charts.revision(),
                    records.added(),
                    records.enriched(),
                    records.ignored(),
                    records.total());
        }

        private SyncImportResult importChunithm(
                Map<String, Object> document,
                String userId,
                String batchId) throws IOException {
            List<ChunithmChartInput> importedCharts =
                    chunithmCanonicalizer.charts(
                            parseImportedChunithmCharts(document.get("charts")));
            List<ChunithmHistoryStore.PlayRecord> importedRecords =
                    document.containsKey("records")
                            ? chunithmCanonicalizer.records(
                                    ChunithmHistoryStore.parseImportRecords(
                                            document.get("records"),
                                            ChunithmHistoryStore.MAX_APPEND_RECORDS))
                            : List.of();
            requireNonEmptySyncImport(importedCharts, importedRecords);

            ChunithmChartImportResult charts = mergeImportedChunithmCharts(
                    chunithmScoreStore,
                    userId,
                    importedCharts,
                    chunithmCatalog);
            String importedAt = Instant.now().toString();
            ChunithmHistoryStore.AppendResult records =
                    chunithmHistoryStore.append(
                            userId,
                            importedRecords.stream()
                                    .map(record -> record.withImportMetadata(
                                            importedAt, batchId))
                                    .toList());
            return new SyncImportResult(
                    charts.added(),
                    charts.updated(),
                    charts.unchanged(),
                    charts.revision(),
                    records.added(),
                    records.enriched(),
                    records.ignored(),
                    records.total());
        }
    }

    private static final class ApiNotFoundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                sendJsonError(exchange, 404, "API endpoint not found");
            } finally {
                exchange.close();
            }
        }
    }

    /** Serves a bounded standalone Clash profile for the local Helper proxy. */
    private static final class ClashConfigHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, CLASH_CONFIG_PATH);
                boolean headRequest = "HEAD".equalsIgnoreCase(
                        exchange.getRequestMethod());
                if (!headRequest
                        && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET, HEAD");
                    return;
                }

                Map<String, List<String>> query = parseQuery(
                        exchange.getRequestURI().getRawQuery());
                if (!query.keySet().equals(Set.of("helperHost"))) {
                    throw new ClientError(
                            400, "Only helperHost may be provided");
                }
                String helperHost = requireHelperIpv4(
                        requiredQueryValue(query, "helperHost"));
                sendYaml(
                        exchange,
                        clashConfigYaml(helperHost),
                        headRequest);
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } catch (RuntimeException error) {
                sendJsonError(exchange, 400, "Invalid Clash configuration request");
            } finally {
                exchange.close();
            }
        }
    }

    /** Returns one preferred RFC 1918 address for same-origin Helper setup. */
    private static final class HelperHostHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                requireExactApiPath(exchange, HELPER_HOST_PATH);
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET");
                    return;
                }
                rejectQueryParameters(exchange, "The Helper host endpoint");
                sendJson(exchange, 200, Json.stringify(Map.of(
                        "helperHost", preferredLanHelperHost())));
            } catch (ClientError error) {
                sendJsonError(exchange, error.status(), error.getMessage());
            } finally {
                exchange.close();
            }
        }
    }

    private static final class StaticHandler implements HttpHandler {
        private final Path webRoot;

        private StaticHandler(Path webRoot) {
            this.webRoot = webRoot;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                boolean headRequest = "HEAD".equalsIgnoreCase(exchange.getRequestMethod());
                if (!headRequest && !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    methodNotAllowed(exchange, "GET, HEAD");
                    return;
                }

                Path file = resolveStaticFile(exchange.getRequestURI().getPath());
                if (file == null) {
                    sendText(exchange, 404, "Not found", headRequest);
                    return;
                }

                byte[] content = Files.readAllBytes(file);
                exchange.getResponseHeaders().set("Content-Type", contentType(file));
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                addSecurityHeaders(exchange);
                if (headRequest) {
                    exchange.getResponseHeaders().set(
                            "Content-Length", Integer.toString(content.length));
                    exchange.sendResponseHeaders(200, -1);
                    return;
                }
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(content);
                }
            } catch (InvalidPathException error) {
                sendText(exchange, 400, "Invalid path", false);
            } finally {
                exchange.close();
            }
        }

        private Path resolveStaticFile(String requestPath) throws IOException {
            if (!Files.isDirectory(webRoot)) {
                return null;
            }

            Path realRoot = webRoot.toRealPath();
            String relativePath = requestPath.startsWith("/")
                    ? requestPath.substring(1)
                    : requestPath;
            if (relativePath.isBlank()) {
                relativePath = "index.html";
            }

            Path candidate = realRoot.resolve(relativePath).normalize();
            if (!candidate.startsWith(realRoot)) {
                return null;
            }
            if (Files.isDirectory(candidate)) {
                candidate = candidate.resolve("index.html");
            }
            if (!Files.isRegularFile(candidate)) {
                return null;
            }

            Path realFile = candidate.toRealPath();
            return realFile.startsWith(realRoot) ? realFile : null;
        }
    }

    private static void requireExactApiPath(HttpExchange exchange, String path) {
        if (!path.equals(exchange.getRequestURI().getPath())) {
            throw new ClientError(404, "API endpoint not found");
        }
    }

    private static boolean handleOptions(HttpExchange exchange, String allow)
            throws IOException {
        if (!"OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            return false;
        }
        exchange.getResponseHeaders().set("Allow", allow);
        sendNoContent(exchange);
        return true;
    }

    private static Credentials parseCredentials(HttpExchange exchange) throws IOException {
        requireJsonContentType(exchange);
        Object parsed = parseJsonBody(exchange, MAX_AUTH_REQUEST_BYTES);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new ClientError(400, "Request body must be a JSON object");
        }
        Map<String, Object> object = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ClientError(400, "Request body contains an invalid field");
            }
            object.put(key, entry.getValue());
        }
        for (String key : object.keySet()) {
            if (!CREDENTIAL_KEYS.contains(key)) {
                throw new ClientError(400, "Unknown request field: " + key);
            }
        }
        for (String key : CREDENTIAL_KEYS) {
            if (!object.containsKey(key)) {
                throw new ClientError(400, key + " is required");
            }
        }
        Object rawUsername = object.get("username");
        Object rawPassword = object.get("password");
        if (!(rawUsername instanceof String username)) {
            throw new ClientError(400, "username must be a string");
        }
        if (!(rawPassword instanceof String password)) {
            throw new ClientError(400, "password must be a string");
        }
        if (username.length() > 128) {
            throw new ClientError(400, "username is too long");
        }
        if (password.length() > 256) {
            throw new ClientError(400, "password is too long");
        }
        return new Credentials(username, password);
    }

    private static Map<String, String> parseStringDocument(
            HttpExchange exchange,
            int maxBytes,
            Set<String> expectedKeys,
            int maxStringLength) throws IOException {
        requireJsonContentType(exchange);
        Object parsed = parseJsonBody(exchange, maxBytes);
        if (!(parsed instanceof Map<?, ?> raw)) {
            throw new ClientError(400, "Request body must be a JSON object");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ClientError(
                        400, "Request body contains an invalid field");
            }
            if (!expectedKeys.contains(key)) {
                throw new ClientError(400, "Unknown request field: " + key);
            }
            if (!(entry.getValue() instanceof String value)) {
                throw new ClientError(400, key + " must be a string");
            }
            if (value.length() > maxStringLength) {
                throw new ClientError(400, key + " is too long");
            }
            result.put(key, value);
        }
        for (String key : expectedKeys) {
            if (!result.containsKey(key)) {
                throw new ClientError(400, key + " is required");
            }
        }
        return Map.copyOf(result);
    }

    private static void requireJsonContentType(HttpExchange exchange) {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        String mediaType = contentType == null
                ? ""
                : contentType.split(";", 2)[0].trim();
        if (!"application/json".equalsIgnoreCase(mediaType)) {
            throw new ClientError(415, "Content-Type must be application/json");
        }
    }

    private static Object parseJsonBody(HttpExchange exchange, int maxBytes)
            throws IOException {
        byte[] bytes = readRequestBody(exchange, maxBytes);
        if (bytes.length == 0) {
            throw new ClientError(400, "Request body must not be empty");
        }
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new ClientError(400, "Request body must be valid UTF-8");
        }
        try {
            return Json.parse(json);
        } catch (Json.JsonException error) {
            throw new ClientError(400, "Invalid JSON: " + error.getMessage());
        }
    }

    private static String sessionToken(HttpExchange exchange) {
        String found = null;
        List<String> cookieHeaders = exchange.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                int separator = part.indexOf('=');
                if (separator < 0) {
                    continue;
                }
                String name = part.substring(0, separator).trim();
                if (!AuthService.SESSION_COOKIE_NAME.equals(name)) {
                    continue;
                }
                String value = part.substring(separator + 1).trim();
                if (!value.matches("[A-Za-z0-9_-]{43}")) {
                    return null;
                }
                if (found != null && !MessageDigest.isEqual(
                        found.getBytes(StandardCharsets.US_ASCII),
                        value.getBytes(StandardCharsets.US_ASCII))) {
                    return null;
                }
                found = value;
            }
        }
        return found;
    }

    private static void setSessionCookie(HttpExchange exchange, String token) {
        exchange.getResponseHeaders().set(
                "Set-Cookie",
                AuthService.SESSION_COOKIE_NAME + "=" + token
                        + "; Path=/api; Max-Age=" + AuthService.SESSION_MAX_AGE_SECONDS
                        + "; HttpOnly; SameSite=Strict");
    }

    private static void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().set(
                "Set-Cookie",
                AuthService.SESSION_COOKIE_NAME
                        + "=; Path=/api; Max-Age=0; HttpOnly; SameSite=Strict");
    }

    private static String authResponse(
            AuthService authService,
            AuthService.AuthenticatedUser user) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticated", user != null);
        response.put("username", user == null ? null : user.username());
        boolean emailAware = authService.emailBindingRequired();
        String email = user == null
                ? null
                : authService.emailForUser(user.id()).orElse(null);
        boolean emailRequired = emailAware && user != null && email == null;
        if (emailAware) {
            response.put("email", email);
            response.put("emailRequired", emailRequired);
        }
        if (user == null) {
            response.put("user", null);
        } else {
            Map<String, Object> publicUser = new LinkedHashMap<>();
            publicUser.put("id", user.id());
            publicUser.put("username", user.username());
            publicUser.put("displayName", user.username());
            if (emailAware) {
                publicUser.put("email", email);
                publicUser.put("emailRequired", emailRequired);
            }
            response.put("user", publicUser);
        }
        return Json.stringify(response);
    }

    private static String emailBindingResponse(
            UserEmailStore emailStore, String userId) {
        String email = emailStore.findEmail(userId).orElse(null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("email", email);
        response.put("emailRequired", email == null);
        response.put("verificationCodeDigits", EmailVerificationService.CODE_DIGITS);
        response.put("verificationCodeTtlSeconds",
                EmailVerificationService.CODE_TTL.toSeconds());
        response.put("resendCooldownSeconds",
                EmailVerificationService.RESEND_COOLDOWN.toSeconds());
        return Json.stringify(response);
    }

    private static String profileResponse(UserProfileStore.Profile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("userId", profile.userId());
        value.put("username", profile.username());
        value.put("displayName", profile.displayName());
        value.put(
                "avatarUrl",
                profile.hasAvatar() ? USER_AVATAR_PATH : null);
        value.put(
                "backgroundUrl",
                profile.hasBackground() ? USER_BACKGROUND_PATH : null);
        Map<String, Object> backgroundUrls = new LinkedHashMap<>();
        backgroundUrls.put(
                "maimai",
                profile.hasMaimaiBackground()
                        ? USER_BACKGROUND_PATH + "?game=maimai"
                        : null);
        backgroundUrls.put(
                "chunithm",
                profile.hasChunithmBackground()
                        ? USER_BACKGROUND_PATH + "?game=chunithm"
                        : null);
        value.put("backgroundUrls", backgroundUrls);

        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put(
                "avatarBytes", UserProfileStore.MAX_AVATAR_UPLOAD_BYTES);
        limits.put(
                "backgroundBytes", UserProfileStore.MAX_BACKGROUND_UPLOAD_BYTES);
        limits.put("imageTypes", List.of("image/png", "image/jpeg"));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profile", value);
        response.put("limits", limits);
        return Json.stringify(response);
    }

    private static String chartSnapshotResponse(UserStore.ChartSnapshot snapshot) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", snapshot.userId());
        response.put("revision", snapshot.revision());
        response.put("charts", UserStore.chartsToJsonValues(snapshot.charts()));
        return Json.stringify(response);
    }

    private static AuthService.AuthenticatedUser requireAuthenticatedUser(
            HttpExchange exchange,
            AuthService authService) {
        AuthService.AuthenticatedUser user = requireAuthenticatedUserAllowUnbound(
                exchange, authService);
        if (authService.requiresEmailBinding(user.id())) {
            throw new ClientError(428, "Verified email binding is required");
        }
        return user;
    }

    private static AuthService.AuthenticatedUser requireAuthenticatedUserAllowUnbound(
            HttpExchange exchange,
            AuthService authService) {
        return authService.authenticateSession(sessionToken(exchange))
                .orElseThrow(() -> new ClientError(401, "Authentication required"));
    }

    private static void requireVerifiedEmailCode(
            EmailVerificationService.VerificationResult result) {
        switch (result) {
            case VERIFIED -> {
                return;
            }
            case INVALID_CODE, NOT_FOUND -> throw new ClientError(
                    400, "Email verification code is invalid");
            case EXPIRED -> throw new ClientError(
                    400, "Email verification code has expired");
            case ATTEMPTS_EXHAUSTED -> throw new ClientError(
                    429, "Too many incorrect verification code attempts");
        }
    }

    private static String requireVerificationFlowId(String supplied) {
        if (supplied == null) {
            throw new ClientError(400, "verificationFlowId is required");
        }
        String value = supplied.strip();
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) {
                throw new IllegalArgumentException("flow id is not canonical");
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new ClientError(400, "verificationFlowId is invalid");
        }
    }

    private static void rejectQueryParameters(
            HttpExchange exchange,
            String endpointName) {
        String rawQuery = exchange.getRequestURI().getRawQuery();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            throw new ClientError(
                    400, endpointName + " does not accept query parameters");
        }
    }

    private static UserProfileStore.BackgroundGame profileBackgroundGame(
            HttpExchange exchange) {
        Map<String, List<String>> query = parseQuery(
                exchange.getRequestURI().getRawQuery());
        if (query.isEmpty()) {
            return UserProfileStore.BackgroundGame.MAIMAI;
        }
        if (!query.keySet().equals(Set.of("game"))) {
            throw new ClientError(
                    400, "The background endpoint only accepts the game parameter");
        }
        String game = optionalQueryValue(query, "game");
        if (game == null) {
            throw new ClientError(400, "game must not be blank");
        }
        return switch (game.toLowerCase(Locale.ROOT)) {
            case "maimai" -> UserProfileStore.BackgroundGame.MAIMAI;
            case "chunithm" -> UserProfileStore.BackgroundGame.CHUNITHM;
            default -> throw new ClientError(
                    400, "game must be maimai or chunithm");
        };
    }

    private static HistoryQuery parseHistoryQuery(String rawQuery) {
        Map<String, List<String>> query = parseQuery(rawQuery);
        Set<String> allowed = Set.of(
                "game", "songId", "chartType", "difficulty", "limit");
        for (String name : query.keySet()) {
            if (!allowed.contains(name)) {
                throw new ClientError(400, "Unknown query parameter: " + name);
            }
        }
        String game = optionalQueryValue(query, "game");
        game = game == null ? "maimai" : game.trim().toLowerCase(Locale.ROOT);
        if (!"maimai".equals(game) && !"chunithm".equals(game)) {
            throw new ClientError(400, "game must be maimai or chunithm");
        }
        String songId = optionalQueryValue(query, "songId");
        String chartType = optionalQueryValue(query, "chartType");
        String difficulty = optionalQueryValue(query, "difficulty");
        if ("chunithm".equals(game) && chartType != null) {
            throw new ClientError(
                    400, "chartType is not supported for CHUNITHM history");
        }
        int limit = 200;
        String limitText = optionalQueryValue(query, "limit");
        if (limitText != null) {
            try {
                limit = Integer.parseInt(limitText);
            } catch (NumberFormatException error) {
                throw new ClientError(400, "limit must be an integer");
            }
            if (limit < 1 || limit > 500) {
                throw new ClientError(400, "limit must be between 1 and 500");
            }
        }
        return new HistoryQuery(game, songId, chartType, difficulty, limit);
    }

    private static Map<String, Object> syncSessionValue(
            SyncSessionStore.Session session) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", session.id());
        value.put("game", session.game());
        value.put("status", session.status().name().toLowerCase(Locale.ROOT));
        value.put("message", session.message());
        value.put("createdAt", session.createdAt().toString());
        value.put("expiresAt", session.expiresAt().toString());
        value.put(
                "completedAt",
                session.completedAt() == null
                        ? null
                        : session.completedAt().toString());
        value.put("chartsChanged", session.addedCharts());
        value.put("recordsAdded", session.addedRecords());
        value.put("recordsEnriched", session.enrichedRecords());
        value.put("recordsIgnored", session.ignoredRecords());
        SyncSessionStore.Progress progress = session.progress();
        value.put(
                "progress",
                progress == null
                        ? null
                        : Map.of(
                                "stage", progress.stage(),
                                "completed", progress.completed(),
                                "total", progress.total(),
                                "succeeded", progress.succeeded(),
                                "skipped", progress.skipped(),
                                "failureReasons", progress.failureReasons()));
        return value;
    }

    private static String syncSessionIdFromPath(String path) {
        String prefix = SYNC_SESSIONS_PATH + "/";
        if (path == null || !path.startsWith(prefix)) {
            throw new ClientError(404, "API endpoint not found");
        }
        String id = path.substring(prefix.length());
        if (id.isEmpty() || id.indexOf('/') >= 0) {
            throw new ClientError(404, "API endpoint not found");
        }
        try {
            String canonical = UUID.fromString(id).toString();
            if (!canonical.equals(id)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new ClientError(404, "Sync session was not found");
        }
    }

    private static boolean isSyncHelperEventPath(String path) {
        return path != null
                && path.startsWith(SYNC_SESSIONS_PATH + "/")
                && path.endsWith("/events");
    }

    private static String syncSessionIdFromEventPath(String path) {
        String suffix = "/events";
        if (!isSyncHelperEventPath(path)) {
            throw new ClientError(404, "API endpoint not found");
        }
        return syncSessionIdFromPath(path.substring(0, path.length() - suffix.length()));
    }

    private static Map<String, Object> parseSyncHelperEventDocument(Object value) {
        Map<String, Object> document = requireStrictJsonObject(value, "Request body");
        for (String field : document.keySet()) {
            if (!SYNC_HELPER_EVENT_KEYS.contains(field)) {
                throw new ClientError(
                        400, "Request body contains unsupported fields");
            }
        }
        if (!document.containsKey("status")) {
            throw new ClientError(
                    400, "Request body is missing field: status");
        }
        return document;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireJsonString(
            Object value,
            String field,
            int maxLength) {
        if (!(value instanceof String text)) {
            throw new ClientError(400, field + " must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new ClientError(400, field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new ClientError(400, field + " is too long");
        }
        return normalized;
    }

    private static String optionalJsonString(
            Object value,
            String field,
            int maxLength) {
        if (value == null) {
            return null;
        }
        return requireJsonString(value, field, maxLength);
    }

    private static Map<String, Object> parseSyncImportDocument(Object value) {
        Map<String, Object> document = requireStrictJsonObject(value, "Request body");
        for (String field : document.keySet()) {
            if (!SYNC_IMPORT_KEYS.contains(field)) {
                throw new ClientError(
                        400, "Request body has an unknown field: " + field);
            }
        }
        for (String field : SYNC_IMPORT_REQUIRED_KEYS) {
            if (!document.containsKey(field)) {
                throw new ClientError(
                        400, "Request body is missing field: " + field);
            }
        }
        return document;
    }

    private static String bearerToken(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new ClientError(401, "Invalid helper authorization");
        }
        String header = values.getFirst();
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ClientError(401, "Invalid helper authorization");
        }
        String token = header.substring("Bearer ".length()).trim();
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            throw new ClientError(401, "Invalid helper authorization");
        }
        return token;
    }

    private static List<ChartInput> parseImportedMaimaiCharts(Object value) {
        if (value == null) {
            return List.of();
        }
        Map<String, Object> chartDocument = new LinkedHashMap<>();
        chartDocument.put("charts", value);
        return UserStore.parseChartsDocument(chartDocument, MAX_CHARTS);
    }

    private static List<ChunithmChartInput> parseImportedChunithmCharts(
            Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values && values.isEmpty()) {
            return List.of();
        }
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("charts", value);
        return parseChunithmCharts(document);
    }

    private static void requireNonEmptySyncImport(
            List<?> charts,
            List<?> records) {
        if (charts.isEmpty() && records.isEmpty()) {
            throw new ClientError(
                    400, "Import must contain at least one chart or play record");
        }
    }

    private static ChartImportResult mergeImportedCharts(
            UserStore userStore,
            String userId,
            List<ChartInput> imported,
            MaimaiScoreCanonicalizer canonicalizer) throws IOException {
        for (int attempt = 0; attempt < 3; attempt++) {
            UserStore.ChartSnapshot current = userStore.loadChartSnapshot(userId);
            List<ChartInput> canonicalCurrent = canonicalizer.charts(
                    current.charts());
            MaimaiChartMerger.MergeResult merged = MaimaiChartMerger.merge(
                    canonicalCurrent, imported);
            if (merged.charts().equals(current.charts())) {
                return new ChartImportResult(
                        merged.added(),
                        merged.updated(),
                        merged.unchanged(),
                        current.revision());
            }
            try {
                UserStore.ChartSnapshot saved = userStore.saveCharts(
                        userId,
                        userId,
                        current.revision(),
                        merged.charts());
                return new ChartImportResult(
                        merged.added(),
                        merged.updated(),
                        merged.unchanged(),
                        saved.revision());
            } catch (UserStore.ChartConflictException error) {
                if (attempt == 2) {
                    throw error;
                }
            }
        }
        throw new AssertionError("Unreachable chart merge retry state");
    }

    private static ChunithmChartImportResult mergeImportedChunithmCharts(
            ChunithmScoreStore scoreStore,
            String userId,
            List<ChunithmChartInput> imported,
            ChunithmCatalog catalog) throws IOException {
        ChunithmCatalog.CatalogResult activeCatalog = catalog.currentCatalog();
        List<String> latestVersions = activeCatalog.latestVersions();
        for (int attempt = 0; attempt < 3; attempt++) {
            ChunithmScoreStore.Snapshot current = scoreStore.loadSnapshot(
                    userId, latestVersions);
            ChunithmChartMerger.MergeResult merged = ChunithmChartMerger.merge(
                    current.charts(), imported);
            if (merged.charts().equals(current.charts())) {
                return new ChunithmChartImportResult(
                        merged.added(),
                        merged.updated(),
                        merged.unchanged(),
                        current.revision());
            }
            try {
                ChunithmScoreStore.Snapshot saved = scoreStore.save(
                        userId,
                        userId,
                        current.revision(),
                        merged.charts(),
                        latestVersions);
                return new ChunithmChartImportResult(
                        merged.added(),
                        merged.updated(),
                        merged.unchanged(),
                        saved.revision());
            } catch (ChunithmScoreStore.ConflictException error) {
                if (attempt == 2) {
                    throw error;
                }
            }
        }
        throw new AssertionError("Unreachable CHUNITHM chart merge retry state");
    }

    private static void failHelperImport(
            SyncSessionStore sessionStore,
            SyncSessionStore.ImportAuthorization authorization,
            String message) {
        if (authorization == null) {
            return;
        }
        try {
            sessionStore.failImport(authorization, message);
        } catch (RuntimeException ignored) {
            // Preserve the original HTTP failure; the helper token was already consumed.
        }
    }

    private static SongSearchOptions parseSongSearchOptions(String rawQuery) {
        Map<String, List<String>> query = parseQuery(rawQuery);
        for (String name : query.keySet()) {
            if (!"q".equals(name) && !"limit".equals(name) && !"online".equals(name)) {
                throw new ClientError(400, "Unknown query parameter: " + name);
            }
        }

        String searchText = requiredQueryValue(query, "q");
        if (searchText.length() > 120) {
            throw new ClientError(400, "q is too long");
        }

        int limit = 30;
        if (query.containsKey("limit")) {
            String rawLimit = requiredQueryValue(query, "limit");
            try {
                limit = Integer.parseInt(rawLimit);
            } catch (NumberFormatException error) {
                throw new ClientError(400, "limit must be an integer");
            }
            if (limit < 1 || limit > 100) {
                throw new ClientError(400, "limit must be between 1 and 100");
            }
        }

        boolean online = false;
        if (query.containsKey("online")) {
            String value = requiredQueryValue(query, "online")
                    .toLowerCase(Locale.ROOT);
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new ClientError(400, "online must be true or false");
            }
            online = Boolean.parseBoolean(value);
        }
        return new SongSearchOptions(searchText, limit, online);
    }

    private static boolean parseChunithmCatalogOnline(String rawQuery) {
        Map<String, List<String>> query = parseQuery(rawQuery);
        for (String name : query.keySet()) {
            if (!"online".equals(name)) {
                throw new ClientError(400, "Unknown query parameter: " + name);
            }
        }
        if (!query.containsKey("online")) {
            return false;
        }
        String value = requiredQueryValue(query, "online")
                .toLowerCase(Locale.ROOT);
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new ClientError(400, "online must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    private static String parseMaimaiCoverSongId(String path) {
        if (path == null || !path.startsWith(MAIMAI_COVER_PREFIX)) {
            throw new ClientError(404, "API endpoint not found");
        }
        String fileName = path.substring(MAIMAI_COVER_PREFIX.length());
        if (!fileName.endsWith(".png") || fileName.length() == 4) {
            throw new ClientError(404, "Maimai cover not found");
        }
        String songId = fileName.substring(0, fileName.length() - 4);
        try {
            return MaimaiCoverService.canonicalSongId(songId);
        } catch (IllegalArgumentException error) {
            throw new ClientError(400, "Invalid Maimai cover song ID");
        }
    }

    private static String parseChunithmCoverSongId(String path) {
        if (path == null || !path.startsWith(CHUNITHM_COVER_PREFIX)) {
            throw new ClientError(404, "API endpoint not found");
        }
        String fileName = path.substring(CHUNITHM_COVER_PREFIX.length());
        if (!fileName.endsWith(".png") || fileName.length() == 4) {
            throw new ClientError(404, "CHUNITHM cover not found");
        }
        String songId = fileName.substring(0, fileName.length() - 4);
        try {
            return ChunithmCoverService.canonicalSongId(songId);
        } catch (IllegalArgumentException error) {
            throw new ClientError(400, "Invalid CHUNITHM cover song ID");
        }
    }

    private static List<ChunithmChartInput> parseChunithmCharts(
            Object document) {
        Map<String, Object> root = requireStrictJsonObject(
                document, "Request body");
        requireExactFields(root, CHUNITHM_CALCULATE_KEYS, "Request body");
        Object rawCharts = root.get("charts");
        if (!(rawCharts instanceof List<?> chartValues)) {
            throw new ClientError(400, "charts must be a JSON array");
        }
        if (chartValues.isEmpty()) {
            throw new ClientError(400, "At least one chart is required");
        }
        if (chartValues.size() > MAX_CHUNITHM_CHARTS) {
            throw new ClientError(413, "Too many charts");
        }

        List<ChunithmChartInput> charts = new ArrayList<>(chartValues.size());
        for (int index = 0; index < chartValues.size(); index++) {
            int rowNumber = index + 1;
            String context = "charts[" + rowNumber + "]";
            Map<String, Object> chart = requireStrictJsonObject(
                    chartValues.get(index), context);
            requireExactFields(chart, CHUNITHM_CHART_KEYS, context);
            String songId = requireJsonText(
                    chart.get("songId"), "songId", rowNumber, 100);
            String title = requireJsonText(
                    chart.get("title"), "title", rowNumber, 300);
            String difficulty = requireJsonText(
                    chart.get("difficulty"), "difficulty", rowNumber, 50);
            double constant = requireJsonDecimal(
                    chart.get("constant"), "constant", rowNumber, 20.0);
            int score = requireJsonInteger(
                    chart.get("score"), "score", rowNumber);
            String version = requireJsonText(
                    chart.get("version"), "version", rowNumber, 100);
            try {
                charts.add(new ChunithmChartInput(
                        songId, title, difficulty, constant, score, version));
            } catch (IllegalArgumentException | NullPointerException error) {
                throw new ClientError(400, context + ": " + error.getMessage());
            }
        }
        return List.copyOf(charts);
    }

    private static Map<String, Object> requireStrictJsonObject(
            Object value, String context) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ClientError(400, context + " must be a JSON object");
        }
        Map<String, Object> object = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ClientError(400, context + " has an invalid field name");
            }
            object.put(key, entry.getValue());
        }
        return object;
    }

    private static Map<String, Integer> requireProgressFailureReasons(Object value) {
        if (!(value instanceof Map<?, ?> raw)
                || raw.size() > SyncSessionStore.DETAIL_FAILURE_REASONS.size()) {
            throw new ClientError(400, "failureReasons must be a bounded JSON object");
        }
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String reason)
                    || !(entry.getValue() instanceof BigDecimal count)) {
                throw new ClientError(400, "failureReasons contains an invalid count");
            }
            try {
                reasons.put(reason, count.intValueExact());
            } catch (ArithmeticException error) {
                throw new ClientError(400, "failureReasons contains an invalid count");
            }
        }
        return reasons;
    }

    private static void requireExactFields(
            Map<String, Object> object, Set<String> allowed, String context) {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new ClientError(
                        400, context + " has an unknown field: " + field);
            }
        }
        for (String field : allowed) {
            if (!object.containsKey(field)) {
                throw new ClientError(
                        400, context + " is missing field: " + field);
            }
        }
    }

    private static String requireJsonText(
            Object value,
            String field,
            int rowNumber,
            int maxLength) {
        if (!(value instanceof String text)) {
            throw new ClientError(
                    400, field + "[" + rowNumber + "] must be a string");
        }
        return requireText(text, field, rowNumber, maxLength);
    }

    private static double requireJsonDecimal(
            Object value,
            String field,
            int rowNumber,
            double maximum) {
        if (!(value instanceof BigDecimal decimal)) {
            throw new ClientError(
                    400, field + "[" + rowNumber + "] must be a number");
        }
        double parsed = decimal.doubleValue();
        if (!Double.isFinite(parsed) || parsed < 0.0 || parsed > maximum) {
            throw new ClientError(
                    400, field + "[" + rowNumber + "] is out of range");
        }
        return parsed;
    }

    private static int requireJsonInteger(
            Object value, String field, int rowNumber) {
        if (!(value instanceof BigDecimal decimal)) {
            throw new ClientError(
                    400, field + "[" + rowNumber + "] must be an integer");
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException error) {
            throw new ClientError(
                    400, field + "[" + rowNumber + "] must be an integer");
        }
    }

    private static int requireJsonInteger(Object value, String field) {
        if (!(value instanceof BigDecimal decimal)) {
            throw new ClientError(400, field + " must be an integer");
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException error) {
            throw new ClientError(400, field + " must be an integer");
        }
    }

    private static Map<String, List<String>> parseQuery(String rawQuery) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return query;
        }
        if (rawQuery.length() > 4_096) {
            throw new ClientError(413, "Query string is too large");
        }

        int fieldCount = 0;
        for (String pair : rawQuery.split("&", -1)) {
            if (++fieldCount > MAX_QUERY_FIELDS) {
                throw new ClientError(413, "Too many query parameters");
            }
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            try {
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                query.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            } catch (IllegalArgumentException error) {
                throw new ClientError(400, "Malformed query encoding");
            }
        }
        return query;
    }

    private static String requireHelperIpv4(String value) {
        String[] fields = value.split("\\.", -1);
        if (fields.length != 4) {
            throw new ClientError(400, "helperHost must be a private IPv4 address");
        }
        int[] octets = new int[4];
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            if (field.isEmpty() || field.length() > 3) {
                throw new ClientError(
                        400, "helperHost must be a private IPv4 address");
            }
            for (int offset = 0; offset < field.length(); offset++) {
                if (!Character.isDigit(field.charAt(offset))) {
                    throw new ClientError(
                            400, "helperHost must be a private IPv4 address");
                }
            }
            try {
                octets[index] = Integer.parseInt(field);
            } catch (NumberFormatException error) {
                throw new ClientError(
                        400, "helperHost must be a private IPv4 address");
            }
            if (octets[index] > 255) {
                throw new ClientError(
                        400, "helperHost must be a private IPv4 address");
            }
        }
        boolean loopback = octets[0] == 127
                && octets[1] == 0
                && octets[2] == 0
                && octets[3] == 1;
        boolean privateAddress = octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
        if (!loopback && !privateAddress) {
            throw new ClientError(400, "helperHost must be a private IPv4 address");
        }
        return octets[0] + "." + octets[1] + "."
                + octets[2] + "." + octets[3];
    }

    private static String clashConfigYaml(String helperHost) {
        return """
                mixed-port: 7890
                allow-lan: false
                mode: rule
                log-level: warning

                proxies:
                  - name: wahlap-wechat-helper
                    type: http
                    server: %s
                    port: 8081

                rules:
                  - AND,((DOMAIN,tgk-wcaime.wahlap.com),(DST-PORT,80)),wahlap-wechat-helper
                  - MATCH,DIRECT
                """.formatted(helperHost);
    }

    private static String requiredQueryValue(
            Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            throw new ClientError(400, name + " is required");
        }
        if (values.size() != 1) {
            throw new ClientError(400, name + " must be provided once");
        }
        String value = values.get(0).trim();
        if (value.isEmpty()) {
            throw new ClientError(400, name + " must not be blank");
        }
        return value;
    }

    private static String optionalQueryValue(
            Map<String, List<String>> query, String name) {
        List<String> values = query.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 1) {
            throw new ClientError(400, name + " must be provided once");
        }
        String value = values.get(0).trim();
        return value.isEmpty() ? null : value;
    }

    private static byte[] readRequestBody(HttpExchange exchange) throws IOException {
        return readRequestBody(exchange, MAX_REQUEST_BYTES);
    }

    private static byte[] readRequestBody(HttpExchange exchange, int maxBytes)
            throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long declaredLength = Long.parseLong(contentLength);
                if (declaredLength < 0) {
                    throw new ClientError(400, "Invalid Content-Length header");
                }
                if (declaredLength > maxBytes) {
                    throw new ClientError(413, "Request body is too large");
                }
            } catch (NumberFormatException error) {
                throw new ClientError(400, "Invalid Content-Length header");
            }
        }

        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(maxBytes + 1);
            if (body.length > maxBytes) {
                throw new ClientError(413, "Request body is too large");
            }
            return body;
        }
    }

    private static Map<String, List<String>> parseForm(byte[] body) {
        Map<String, List<String>> form = new LinkedHashMap<>();
        String encoded = new String(body, StandardCharsets.UTF_8);
        if (encoded.isEmpty()) {
            return form;
        }

        int fieldCount = 0;
        for (String pair : encoded.split("&", -1)) {
            if (++fieldCount > MAX_FORM_FIELDS) {
                throw new ClientError(413, "Too many form fields");
            }
            int separator = pair.indexOf('=');
            String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
            String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
            try {
                String name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                form.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
            } catch (IllegalArgumentException error) {
                throw new ClientError(400, "Malformed form encoding");
            }
        }
        return form;
    }

    private static List<ChartInput> parseCharts(Map<String, List<String>> form) {
        List<String> songIds = form.get("songId");
        if (songIds == null || songIds.isEmpty()) {
            throw new ClientError(400, "At least one songId is required");
        }
        if (songIds.size() > MAX_CHARTS) {
            throw new ClientError(413, "Too many charts");
        }

        int count = songIds.size();
        List<String> titles = requiredColumn(form, "title", count);
        List<String> chartTypes = requiredColumn(form, "chartType", count);
        List<String> difficulties = requiredColumn(form, "difficulty", count);
        List<String> levels = requiredColumn(form, "level", count);
        List<String> achievements = requiredColumn(form, "achievement", count);
        List<String> versions = requiredColumn(form, "version", count);

        List<ChartInput> charts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int rowNumber = index + 1;
            String songId = requireText(songIds.get(index), "songId", rowNumber, 100);
            String title = requireText(titles.get(index), "title", rowNumber, 300);
            String chartTypeValue = requireText(
                    chartTypes.get(index), "chartType", rowNumber, 20)
                    .toLowerCase(Locale.ROOT);
            String difficulty = requireText(
                    difficulties.get(index), "difficulty", rowNumber, 50);
            double level = parseNumber(levels.get(index), "level", rowNumber);
            double achievement = parseNumber(
                    achievements.get(index), "achievement", rowNumber);
            String version = requireText(versions.get(index), "version", rowNumber, 20)
                    .toLowerCase(Locale.ROOT);

            Version chartVersion = switch (version) {
                case "legacy" -> Version.LEGACY;
                case "current" -> Version.CURRENT;
                default -> throw new ClientError(
                        400,
                        "version[" + rowNumber + "] must be legacy or current");
            };
            ChartType chartType = switch (chartTypeValue) {
                case "standard" -> ChartType.STANDARD;
                case "dx" -> ChartType.DX;
                default -> throw new ClientError(
                        400,
                        "chartType[" + rowNumber + "] must be standard or dx");
            };

            try {
                charts.add(new ChartInput(
                        songId, title, chartType, difficulty,
                        level, achievement, chartVersion));
            } catch (IllegalArgumentException error) {
                throw new ClientError(
                        400, "chart[" + rowNumber + "]: " + error.getMessage());
            }
        }
        return charts;
    }

    private static List<String> requiredColumn(
            Map<String, List<String>> form, String name, int expectedCount) {
        List<String> values = form.get(name);
        int actualCount = values == null ? 0 : values.size();
        if (actualCount != expectedCount) {
            throw new ClientError(
                    400,
                    name + " count must match songId count (expected "
                            + expectedCount + ", got " + actualCount + ")");
        }
        return values;
    }

    private static String requireText(
            String value, String field, int rowNumber, int maxLength) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new ClientError(400, field + "[" + rowNumber + "] must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new ClientError(400, field + "[" + rowNumber + "] is too long");
        }
        return normalized;
    }

    private static double parseNumber(String value, String field, int rowNumber) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException error) {
            throw new ClientError(400, field + "[" + rowNumber + "] must be a number");
        }
    }

    private static String calculateResponse(List<ChartInput> submittedCharts) {
        B50Result result = B50Calculator.calculate(submittedCharts);
        List<B50Item> rankedCharts = result.items();

        StringBuilder json = new StringBuilder(256 + rankedCharts.size() * 220);
        json.append('{')
                .append("\"total\":").append(result.total()).append(',')
                .append("\"oldTotal\":").append(result.legacyTotal()).append(',')
                .append("\"newTotal\":").append(result.currentTotal()).append(',')
                .append("\"oldCount\":").append(result.legacySelected().size()).append(',')
                .append("\"newCount\":").append(result.currentSelected().size()).append(',')
                .append("\"charts\":[");

        for (int index = 0; index < rankedCharts.size(); index++) {
            B50Item chart = rankedCharts.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append('{')
                    .append("\"songId\":").append(quoteJson(chart.id())).append(',')
                    .append("\"title\":").append(quoteJson(chart.title())).append(',')
                    .append("\"chartType\":")
                    .append(quoteJson(chart.chartType() == ChartType.STANDARD
                            ? "standard" : "dx")).append(',')
                    .append("\"difficulty\":")
                    .append(quoteJson(chart.difficulty())).append(',')
                    .append("\"level\":").append(Double.toString(chart.level())).append(',')
                    .append("\"achievement\":")
                    .append(Double.toString(chart.achievement())).append(',')
                    .append("\"version\":")
                    .append(quoteJson(chart.version() == Version.LEGACY
                            ? "legacy" : "current")).append(',')
                    .append("\"rating\":").append(chart.rating()).append(',')
                    .append("\"selected\":").append(chart.selected()).append(',')
                    .append("\"categoryRank\":").append(chart.categoryRank()).append(',')
                    .append("\"overallRank\":").append(chart.rank())
                    .append('}');
        }
        return json.append("]}").toString();
    }

    private static String chunithmCalculateResponse(ChunithmResult result) {
        BigDecimal divisor = BigDecimal.valueOf(50);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rating", result.ratingDecimal());
        response.put(
                "b30Rating", result.b30TotalDecimal().divide(divisor));
        response.put(
                "n20Rating", result.n20TotalDecimal().divide(divisor));
        response.put("b30Sum", result.b30TotalDecimal());
        response.put("n20Sum", result.n20TotalDecimal());
        response.put("selectedCount", result.selectedCount());
        response.put("b30Count", result.b30().size());
        response.put("n20Count", result.n20().size());

        List<Map<String, Object>> charts = new ArrayList<>(result.items().size());
        for (ChunithmItem item : result.items()) {
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("songId", item.songId());
            chart.put("title", item.title());
            chart.put("difficulty", item.difficulty());
            chart.put("constant", BigDecimal.valueOf(item.constant()));
            chart.put("score", item.score());
            chart.put("version", item.version());
            chart.put("newChart", item.newChart());
            chart.put("ratingEligible", item.ratingEligible());
            chart.put("rating", item.ratingDecimal());
            chart.put("selected", item.selected());
            chart.put("rank", item.rank());
            chart.put("poolRank", item.poolRank());
            charts.add(chart);
        }
        response.put("charts", charts);
        return Json.stringify(response);
    }

    private static String quoteJson(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    private static void methodNotAllowed(HttpExchange exchange, String allow)
            throws IOException {
        exchange.getResponseHeaders().set("Allow", allow);
        sendJsonError(exchange, 405, "Method not allowed");
    }

    private static void sendJsonError(HttpExchange exchange, int status, String message)
            throws IOException {
        String safeMessage = message == null || message.isBlank() ? "Invalid request" : message;
        sendJson(exchange, status, "{\"error\":" + quoteJson(safeMessage) + "}");
    }

    private static void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] content = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendYaml(
            HttpExchange exchange, String yaml, boolean headRequest)
            throws IOException {
        byte[] content = yaml.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/yaml; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set(
                "Content-Disposition",
                "inline; filename=wahlap-wechat-sync.yaml");
        addSecurityHeaders(exchange);
        if (headRequest) {
            exchange.getResponseHeaders().set(
                    "Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendPng(
            HttpExchange exchange, byte[] content, boolean headRequest)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.getResponseHeaders().set(
                "Cache-Control", "public, max-age=31536000, immutable");
        addSecurityHeaders(exchange);
        if (headRequest) {
            exchange.getResponseHeaders().set(
                    "Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendPrivateImage(
            HttpExchange exchange,
            UserProfileStore.ImageData image,
            boolean headRequest) throws IOException {
        byte[] content = image.bytes();
        exchange.getResponseHeaders().set("Content-Type", image.contentType());
        exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
        exchange.getResponseHeaders().set("Content-Disposition", "inline");
        addSecurityHeaders(exchange);
        if (headRequest) {
            exchange.getResponseHeaders().set(
                    "Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendText(
            HttpExchange exchange, int status, String text, boolean headRequest)
            throws IOException {
        byte[] content = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "text/plain; charset=utf-8");
        addSecurityHeaders(exchange);
        if (headRequest) {
            exchange.getResponseHeaders().set(
                    "Content-Length", Integer.toString(content.length));
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, content.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(content);
        }
    }

    private static void sendNoContent(HttpExchange exchange) throws IOException {
        addSecurityHeaders(exchange);
        exchange.sendResponseHeaders(204, -1);
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set(
                "Content-Security-Policy",
                "default-src 'self'; base-uri 'none'; frame-ancestors 'none'; "
                        + "form-action 'self'; img-src 'self' data: blob:; "
                        + "style-src 'self'; script-src 'self'; "
                        + "connect-src 'self' http://*:8081");
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".js") || name.endsWith(".mjs")) {
            return "text/javascript; charset=utf-8";
        }
        if (name.endsWith(".json") || name.endsWith(".map")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (name.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (name.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private static final class ClientError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final int status;

        private ClientError(int status, String message) {
            super(message);
            this.status = status;
        }

        private int status() {
            return status;
        }
    }

    private record SongSearchOptions(String query, int limit, boolean online) {
    }

    private record HistoryQuery(
            String game,
            String songId,
            String chartType,
            String difficulty,
            int limit) {
        private HistoryQuery {
            if (!"maimai".equals(game) && !"chunithm".equals(game)) {
                throw new IllegalArgumentException("Invalid history query game");
            }
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("Invalid history query limit");
            }
            chartType = chartType == null
                    ? null
                    : chartType.trim().toLowerCase(Locale.ROOT);
            songId = songId == null
                    ? null
                    : "chunithm".equals(game)
                            ? ChunithmCoverService.canonicalSongId(songId)
                            : songId.trim();
            difficulty = difficulty == null
                    ? null
                    : "chunithm".equals(game)
                            ? ChunithmHistoryStore.normalizeDifficulty(difficulty)
                            : normalizeHistoryDifficulty(difficulty);
        }

        private boolean matches(PlayHistoryStore.PlayRecord record) {
            return (songId == null || songId.equals(record.songId()))
                    && (chartType == null
                            || chartType.equals(record.chartType().toLowerCase(Locale.ROOT)))
                    && (difficulty == null
                            || difficulty.equals(normalizeHistoryDifficulty(
                                    record.difficulty())));
        }

        private boolean matches(ChunithmHistoryStore.PlayRecord record) {
            return (songId == null || songId.equals(record.songId()))
                    && (difficulty == null
                            || difficulty.equals(
                                    ChunithmHistoryStore.normalizeDifficulty(
                                            record.difficulty())));
        }

        private static String normalizeHistoryDifficulty(String value) {
            return value.trim()
                    .toUpperCase(Locale.ROOT)
                    .replace("：", ":")
                    .replaceAll("\\s+", "")
                    .replace("REMASTER", "RE:MASTER");
        }
    }

    private record ChartImportResult(
            int added,
            int updated,
            int unchanged,
            long revision) {
        private ChartImportResult {
            if (added < 0 || updated < 0 || unchanged < 0 || revision < 0) {
                throw new IllegalArgumentException("Invalid chart import result");
            }
        }

        private int changed() {
            return Math.addExact(added, updated);
        }
    }

    private record ChunithmChartImportResult(
            int added,
            int updated,
            int unchanged,
            long revision) {
        private ChunithmChartImportResult {
            if (added < 0 || updated < 0 || unchanged < 0 || revision < 0) {
                throw new IllegalArgumentException(
                        "Invalid CHUNITHM chart import result");
            }
        }
    }

    private record SyncImportResult(
            int chartsAdded,
            int chartsUpdated,
            int chartsUnchanged,
            long chartRevision,
            int recordsAdded,
            int recordsEnriched,
            int recordsIgnored,
            int recordsTotal) {
        private SyncImportResult {
            if (chartsAdded < 0
                    || chartsUpdated < 0
                    || chartsUnchanged < 0
                    || chartRevision < 0
                    || recordsAdded < 0
                    || recordsEnriched < 0
                    || recordsIgnored < 0
                    || recordsTotal < 0) {
                throw new IllegalArgumentException("Invalid sync import result");
            }
        }

        private int chartsChanged() {
            return Math.addExact(chartsAdded, chartsUpdated);
        }
    }

    private record Credentials(String username, String password) {
    }
}
