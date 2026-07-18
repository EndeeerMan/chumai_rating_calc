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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP contract tests for sync sessions, imports, and per-user play history. */
public final class WebServerSyncTest {
    private static int tests;

    private WebServerSyncTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-web-sync-test-");
        try {
            UserStore users = new UserStore(temporary.resolve("users"));
            AuthService auth = new AuthService(users);
            PlayHistoryStore history = new PlayHistoryStore(
                    temporary.resolve("history"));
            ChunithmScoreStore chunithmScores = new ChunithmScoreStore(
                    temporary.resolve("chunithm-scores"));
            ChunithmHistoryStore chunithmHistory = new ChunithmHistoryStore(
                    temporary.resolve("chunithm-history"));
            ChunithmCatalog chunithmCatalog = chunithmCatalog();
            SyncSessionStore sessions = new SyncSessionStore();
            AuthService.SessionHandle first = auth.register(
                    "SyncPlayer", "CorrectHorse#2026");
            AuthService.SessionHandle second = auth.register(
                    "OtherPlayer", "AnotherSecure#2026");
            String firstCookie = cookie(first.token());
            String secondCookie = cookie(second.token());

            HttpHandler sessionHandler = handler(
                    "WebServer$SyncSessionsHandler",
                    new Class<?>[]{AuthService.class, SyncSessionStore.class},
                    auth,
                    sessions);
            HttpHandler importHandler = handler(
                    "WebServer$SyncImportHandler",
                    new Class<?>[]{
                            AuthService.class,
                            UserStore.class,
                            PlayHistoryStore.class,
                            ChunithmScoreStore.class,
                            ChunithmHistoryStore.class,
                            SyncSessionStore.class,
                            MaimaiScoreCanonicalizer.class,
                            ChunithmScoreCanonicalizer.class,
                            ChunithmCatalog.class},
                    auth,
                    users,
                    history,
                    chunithmScores,
                    chunithmHistory,
                    sessions,
                    new MaimaiScoreCanonicalizer(
                            SongCatalog.empty("test catalogue is empty")),
                    new ChunithmScoreCanonicalizer(chunithmCatalog),
                    chunithmCatalog);
            HttpHandler historyHandler = handler(
                    "WebServer$PlayHistoryHandler",
                    new Class<?>[]{
                            AuthService.class,
                            PlayHistoryStore.class,
                            ChunithmHistoryStore.class},
                    auth,
                    history,
                    chunithmHistory);
            HttpHandler clashConfigHandler = handler(
                    "WebServer$ClashConfigHandler",
                    new Class<?>[]{});
            HttpHandler helperHostHandler = handler(
                    "WebServer$HelperHostHandler",
                    new Class<?>[]{});

            expect(
                    "192.168.1.12",
                    WebServer.preferredLanHelperHost(List.of(
                            "8.8.8.8", "127.0.0.1", "192.168.001.012")),
                    "Helper host selection skips public and loopback addresses");
            expect(
                    "",
                    WebServer.preferredLanHelperHost(List.of(
                            "localhost", "8.8.8.8", "127.0.0.1")),
                    "Helper host selection returns empty without a private address");

            FakeExchange helperHost = request(
                    helperHostHandler,
                    "GET",
                    "/api/sync/helper-host",
                    null,
                    null,
                    null);
            expect(200, helperHost.status,
                    "preferred Helper host is available without login");
            expect("no-store",
                    helperHost.responseHeaders.getFirst("Cache-Control"),
                    "preferred Helper host is never cached");
            Map<String, Object> helperHostDocument = object(
                    Json.parse(helperHost.body()));
            expect(Set.of("helperHost"), helperHostDocument.keySet(),
                    "preferred Helper host response exposes one field only");
            String advertisedHelperHost = string(
                    helperHostDocument.get("helperHost"));
            expect(
                    advertisedHelperHost,
                    WebServer.preferredLanHelperHost(List.of(advertisedHelperHost)),
                    "preferred Helper host response is empty or strictly private");

            helperHost = request(
                    helperHostHandler,
                    "GET",
                    "/api/sync/helper-host?interfaces=true",
                    null,
                    null,
                    null);
            expect(400, helperHost.status,
                    "preferred Helper host rejects interface-detail queries");
            helperHost = request(
                    helperHostHandler,
                    "POST",
                    "/api/sync/helper-host",
                    "{}",
                    null,
                    null);
            expect(405, helperHost.status,
                    "preferred Helper host rejects state-changing methods");

            FakeExchange clashConfig = request(
                    clashConfigHandler,
                    "GET",
                    "/api/sync/clash-config?helperHost=192.168.001.012",
                    null,
                    null,
                    null);
            expect(200, clashConfig.status,
                    "Clash config is available without a login cookie");
            expect("text/yaml; charset=utf-8",
                    clashConfig.responseHeaders.getFirst("Content-Type"),
                    "Clash config has a YAML content type");
            expect("no-store",
                    clashConfig.responseHeaders.getFirst("Cache-Control"),
                    "Clash config is never cached");
            expect("nosniff",
                    clashConfig.responseHeaders.getFirst("X-Content-Type-Options"),
                    "Clash config disables MIME sniffing");
            String contentSecurityPolicy = clashConfig.responseHeaders.getFirst(
                    "Content-Security-Policy");
            expect(true,
                    contentSecurityPolicy != null
                            && contentSecurityPolicy.contains(
                            "connect-src 'self' http://*:8081"),
                    "site CSP permits the fixed local Helper port");
            expect(true, clashConfig.body().contains("server: 192.168.1.12"),
                    "Clash config canonicalizes the private Helper IPv4");
            expect(true, clashConfig.body().contains(
                            "AND,((DOMAIN,tgk-wcaime.wahlap.com),(DST-PORT,80))"),
                    "Clash config routes only the audited Wahlap callback");
            expect(true, clashConfig.body().contains("- MATCH,DIRECT"),
                    "Clash config sends all other traffic directly");

            clashConfig = request(
                    clashConfigHandler,
                    "HEAD",
                    "/api/sync/clash-config?helperHost=127.0.0.1",
                    null,
                    null,
                    null);
            expect(200, clashConfig.status,
                    "Clash config supports a metadata-only HEAD request");
            expect("", clashConfig.body(),
                    "Clash config HEAD response has no body");

            for (String invalidPath : List.of(
                    "/api/sync/clash-config?helperHost=8.8.8.8",
                    "/api/sync/clash-config?helperHost=localhost",
                    "/api/sync/clash-config?helperHost=192.168.1.2%0Arules%3A",
                    "/api/sync/clash-config?helperHost=192.168.1.2&extra=true",
                    "/api/sync/clash-config?helperHost=192.168.1.2&helperHost=10.0.0.2")) {
                clashConfig = request(
                        clashConfigHandler,
                        "GET",
                        invalidPath,
                        null,
                        null,
                        null);
                expect(400, clashConfig.status,
                        "Clash config rejects unsafe or ambiguous Helper hosts");
            }
            clashConfig = request(
                    clashConfigHandler,
                    "POST",
                    "/api/sync/clash-config?helperHost=127.0.0.1",
                    "{}",
                    null,
                    null);
            expect(405, clashConfig.status,
                    "Clash config rejects state-changing methods");
            expect("GET, HEAD", clashConfig.responseHeaders.getFirst("Allow"),
                    "Clash config advertises only safe methods");

            FakeExchange response = request(
                    sessionHandler,
                    "POST",
                    "/api/sync/sessions",
                    "{\"game\":\"maimai\"}",
                    null,
                    null);
            expect(401, response.status, "sync sessions require login");

            response = request(
                    sessionHandler,
                    "POST",
                    "/api/sync/sessions",
                    "{\"game\":\"chunithm\"}",
                    firstCookie,
                    null);
            expect(201, response.status, "CHUNITHM sync session is created");
            Map<String, Object> chunithmCreated = object(Json.parse(response.body()));
            expect(
                    "chunithm",
                    object(chunithmCreated.get("session")).get("game"),
                    "CHUNITHM session retains game");

            response = request(
                    sessionHandler,
                    "POST",
                    "/api/sync/sessions",
                    "{\"game\":\"maimai\"}",
                    firstCookie,
                    null);
            expect(201, response.status, "maimai sync session is created");
            Map<String, Object> created = object(Json.parse(response.body()));
            Map<String, Object> session = object(created.get("session"));
            String sessionId = string(session.get("id"));
            String helperToken = string(created.get("helperToken"));
            expect("waiting", session.get("status"), "new session waits for WeChat");
            expect(true, string(created.get("helperPath")).contains(sessionId),
                    "helper path identifies session");
            expect(false, Json.stringify(session).contains(helperToken),
                    "public session snapshot omits helper secret");
            expect(null, session.get("progress"),
                    "new sessions have no detail progress");

            response = request(
                    sessionHandler,
                    "GET",
                    "/api/sync/sessions/" + sessionId,
                    null,
                    secondCookie,
                    null);
            expect(404, response.status, "other users cannot inspect a sync session");

            String eventPath = "/api/sync/sessions/" + sessionId + "/events";
            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    eventDocument("waiting_auth", null),
                    null,
                    null);
            expect(401, response.status, "helper events require bearer authorization");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    "x".repeat(43),
                    "waiting_auth",
                    null);
            expect(409, response.status, "helper events reject a wrong token");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "unknown_state",
                    null);
            expect(400, response.status, "unknown helper event is rejected");
            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"status\":\"waiting_auth\",\"extra\":true}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status, "unknown helper event fields are rejected");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "callback_received",
                    null);
            expect(409, response.status, "callback cannot skip waiting_auth");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "waiting_auth",
                    null);
            expect(200, response.status, "valid helper token starts OAuth waiting");
            expect(
                    "waiting_auth",
                    object(object(Json.parse(response.body())).get("session")).get("status"),
                    "waiting_auth status is visible");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "waiting_auth",
                    null);
            expect(200, response.status, "same helper progress event may be retried");

            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"callback_received\","
                            + "\"stage\":\"play_details\",\"completed\":0,"
                            + "\"total\":50,\"succeeded\":0}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status,
                    "detail progress is accepted only during fetching");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "fetching",
                    null);
            expect(409, response.status, "fetching cannot skip the callback");

            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"status\":\"callback_received\"}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status, "later helper events require game");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "chunithm",
                    "callback_received",
                    null);
            expect(409, response.status, "helper event game must match session");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "callback_received",
                    null);
            expect(200, response.status, "OAuth callback advances the session");
            expect(
                    "callback_received",
                    object(object(Json.parse(response.body())).get("session")).get("status"),
                    "callback status is visible");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "waiting_auth",
                    null);
            expect(409, response.status, "helper events cannot move state backward");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "fetching",
                    null);
            expect(200, response.status, "score fetching advances the session");
            expect(
                    "fetching",
                    object(object(Json.parse(response.body())).get("session")).get("status"),
                    "fetching status is visible");

            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    0,
                    50,
                    0);
            expect(200, response.status, "detail progress can start at zero");
            Map<String, Object> progress = object(object(
                    object(Json.parse(response.body())).get("session"))
                    .get("progress"));
            expect("play_details", progress.get("stage"),
                    "progress exposes its bounded stage");
            expect(0L, integer(progress.get("completed")),
                    "progress exposes completed details");
            expect(50L, integer(progress.get("total")),
                    "progress exposes the stable detail total");
            expect(0L, integer(progress.get("succeeded")),
                    "progress exposes successful details");

            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"fetching\"," 
                            + "\"stage\":\"play_details\",\"completed\":10,"
                            + "\"total\":50,\"succeeded\":9,\"skipped\":1,"
                            + "\"failureReasons\":{\"request-timeout\":1}}",
                    null,
                    "Bearer " + helperToken);
            expect(200, response.status, "detail progress advances");
            progress = object(object(
                    object(Json.parse(response.body())).get("session"))
                    .get("progress"));
            expect(1L, integer(progress.get("skipped")),
                    "progress exposes skipped details");
            expect(1L, integer(object(progress.get("failureReasons"))
                            .get("request-timeout")),
                    "progress exposes only controlled failure counts");

            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"fetching\"," 
                            + "\"stage\":\"play_details\",\"completed\":11,"
                            + "\"total\":50,\"succeeded\":9,\"skipped\":2,"
                            + "\"failureReasons\":{\"request-timeout\":1,"
                            + "\"private-idx\":1}}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status,
                    "unknown detail failure reasons are rejected");
            expect(false, response.body().contains("private-idx"),
                    "rejected failure reason text is never reflected");

            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"fetching\"," 
                            + "\"idx\":\"private-detail-id\"}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status,
                    "secret-bearing progress fields are rejected");
            expect(false, response.body().contains("idx"),
                    "rejected progress field names are never reflected");
            expect(false, response.body().contains("private-detail-id"),
                    "rejected progress values are never reflected");

            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    9,
                    50,
                    9);
            expect(409, response.status, "detail progress cannot move backward");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    10,
                    51,
                    9);
            expect(409, response.status, "detail progress total cannot change");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    10,
                    50,
                    11);
            expect(400, response.status,
                    "successful detail count cannot exceed completed count");
            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"fetching\","
                            + "\"completed\":10}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status,
                    "partial progress field groups are rejected");
            response = request(
                    sessionHandler,
                    "POST",
                    eventPath,
                    "{\"game\":\"maimai\",\"status\":\"fetching\","
                            + "\"stage\":\"play_details\",\"completed\":10.5,"
                            + "\"total\":50,\"succeeded\":9}",
                    null,
                    "Bearer " + helperToken);
            expect(400, response.status, "progress counters must be integers");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "score_list",
                    10,
                    50,
                    9);
            expect(400, response.status, "unknown progress stages are rejected");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    10,
                    101,
                    9);
            expect(400, response.status,
                    "detail progress rejects totals above the Helper maximum");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "maimai",
                    "play_details",
                    0,
                    0,
                    0);
            expect(400, response.status,
                    "detail progress requires a positive total");
            response = postProgressEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "chunithm",
                    "play_details",
                    10,
                    50,
                    9);
            expect(409, response.status,
                    "progress cannot be attached to another game session");

            response = request(
                    sessionHandler,
                    "GET",
                    "/api/sync/sessions/" + sessionId,
                    null,
                    firstCookie,
                    null);
            expect(200, response.status, "owner can poll detail progress");
            progress = object(object(
                    object(Json.parse(response.body())).get("session"))
                    .get("progress"));
            expect(10L, integer(progress.get("completed")),
                    "polling returns the last accepted detail progress");

            String importBody = importDocument(null, "2026-07-16T10:00:00Z");
            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    importBody.replace("\"game\":\"maimai\"", "\"game\":\"maimai\",\"sessionId\":\""
                            + sessionId + "\""),
                    null,
                    "Bearer " + helperToken);
            expect(200, response.status, "helper import succeeds");
            Map<String, Object> imported = object(Json.parse(response.body()));
            expect(1L, integer(imported.get("chartsAdded")), "one best chart added");
            expect(1L, integer(imported.get("recordsAdded")), "one play added");
            expect(0L, integer(imported.get("recordsEnriched")),
                    "first import has no history enrichment");
            expect("completed", object(imported.get("session")).get("status"),
                    "session completes only after durable import");
            expect(10L, integer(object(
                    object(imported.get("session")).get("progress"))
                    .get("completed")),
                    "completed sessions retain final detail progress");
            expect(1, users.loadCharts(first.user().id()).size(),
                    "best chart belongs to session owner");
            expect(1, history.load(first.user().id()).size(),
                    "play history belongs to session owner");
            expect(0, history.load(second.user().id()).size(),
                    "other user's history stays isolated");

            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    importBody.replace("\"game\":\"maimai\"", "\"game\":\"maimai\",\"sessionId\":\""
                            + sessionId + "\""),
                    null,
                    "Bearer " + helperToken);
            expect(401, response.status, "helper token cannot be replayed");

            response = postEvent(
                    sessionHandler,
                    sessionId,
                    helperToken,
                    "fetching",
                    null);
            expect(409, response.status, "completed session rejects event replay");

            Map<String, Object> secondSession = createSession(
                    sessionHandler, firstCookie);
            String secondSessionId = string(object(secondSession.get("session")).get("id"));
            String secondHelperToken = string(secondSession.get("helperToken"));
            advanceToFetching(
                    sessionHandler, secondSessionId, secondHelperToken);
            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    importDocument(
                            "official-reissued", "2026-07-16T10:00:00Z")
                            .replace(
                                    "\"game\":\"maimai\"",
                                    "\"game\":\"maimai\",\"sessionId\":\""
                                            + secondSessionId
                                            + "\""),
                    null,
                    "Bearer " + secondHelperToken);
            expect(200, response.status,
                    "old/new official IDs share the semantic play fingerprint");
            imported = object(Json.parse(response.body()));
            expect(0L, integer(imported.get("chartsAdded")), "same chart not added twice");
            expect(0L, integer(imported.get("chartsUpdated")), "same chart not updated twice");
            expect(0L, integer(imported.get("recordsAdded")), "same play not added twice");
            expect(1L, integer(imported.get("recordsIgnored")), "same play is counted ignored");

            Map<String, Object> failedSession = createSession(
                    sessionHandler, firstCookie);
            String failedSessionId = string(object(failedSession.get("session")).get("id"));
            String failedHelperToken = string(failedSession.get("helperToken"));
            response = postEvent(
                    sessionHandler,
                    failedSessionId,
                    failedHelperToken,
                    "waiting_auth",
                    null);
            expect(200, response.status, "failed-flow session accepts waiting_auth");
            response = postEvent(
                    sessionHandler,
                    failedSessionId,
                    failedHelperToken,
                    "failed",
                    "WeChat callback failed\nwithout leaking a token");
            expect(200, response.status, "helper can report a terminal failure");
            Map<String, Object> failedSnapshot = object(
                    object(Json.parse(response.body())).get("session"));
            expect("failed", failedSnapshot.get("status"), "failure status is visible");
            expect(
                    false,
                    string(failedSnapshot.get("message")).contains("\n"),
                    "failure message is sanitized");

            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    importBody.replace(
                            "\"game\":\"maimai\"",
                            "\"game\":\"maimai\",\"sessionId\":\""
                                    + failedSessionId
                                    + "\""),
                    null,
                    "Bearer " + failedHelperToken);
            expect(401, response.status, "failed event consumes the helper token");
            response = postEvent(
                    sessionHandler,
                    failedSessionId,
                    failedHelperToken,
                    "failed",
                    "retry");
            expect(409, response.status, "failed event cannot be replayed");

            response = request(
                    historyHandler,
                    "GET",
                    "/api/history?game=maimai&songId=123&chartType=dx&difficulty=MASTER",
                    null,
                    firstCookie,
                    null);
            expect(200, response.status, "history filter succeeds");
            Map<String, Object> historyPayload = object(Json.parse(response.body()));
            expect(1L, integer(historyPayload.get("count")),
                    "history filter finds the imported play");
            Map<String, Object> play = object(
                    ((List<?>) historyPayload.get("records")).getFirst());
            expect(null, play.get("sourceRecordId"),
                    "legacy null official record id is retained");
            expect(true, play.get("importedAt") instanceof String,
                    "backend supplies trusted import timestamp");
            Map<String, Object> maimaiJudgments = object(
                    play.get("judgmentDetails"));
            expect(
                    10L,
                    integer(object(object(
                            maimaiJudgments.get("byNoteType")).get("tap"))
                            .get("perfect")),
                    "maimai history API returns player judgment details");
            Map<String, Object> maimaiPlayDetails = object(
                    play.get("playDetails"));
            expect(
                    408L,
                    integer(object(maimaiPlayDetails.get("maxCombo"))
                            .get("current")),
                    "maimai history API returns MAX COMBO details");
            expect(
                    -9L,
                    integer(object(maimaiPlayDetails.get("rating"))
                            .get("delta")),
                    "maimai history API preserves signed rating changes");
            expect(
                    15_600L,
                    integer(object(maimaiPlayDetails.get("rating"))
                            .get("value")),
                    "maimai history API returns post-play DX Rating");
            expect(
                    "yellow",
                    object(maimaiPlayDetails.get("rating")).get("frame"),
                    "maimai history API returns the DX Rating frame tier");
            expect(
                    officialRatingFrameUrl("yellow"),
                    object(maimaiPlayDetails.get("rating")).get("frameImageUrl"),
                    "maimai history API returns the official Rating frame image");
            Map<String, Object> firstPartner = object(
                    ((List<?>) maimaiPlayDetails.get("partners")).getFirst());
            expect(156L, integer(firstPartner.get("level")),
                    "maimai history API returns travel partner details");

            response = request(
                    historyHandler,
                    "GET",
                    "/api/history?game=maimai",
                    null,
                    secondCookie,
                    null);
            expect(0L, integer(object(Json.parse(response.body())).get("count")),
                    "second user cannot see first user's plays");

            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    importDocument("official-2", "2026-07-16T11:00:00Z"),
                    firstCookie,
                    null);
            expect(200, response.status, "signed-in JSON import works without helper");
            expect(2, history.load(first.user().id()).size(),
                    "manual import appends another distinct play");

            String chunithmSessionId = string(
                    object(chunithmCreated.get("session")).get("id"));
            String chunithmHelperToken = string(
                    chunithmCreated.get("helperToken"));
            advanceToFetching(
                    sessionHandler,
                    chunithmSessionId,
                    chunithmHelperToken,
                    "chunithm");
            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    chunithmImportDocument()
                            .replace(
                                    "\"game\":\"chunithm\"",
                                    "\"game\":\"chunithm\",\"sessionId\":\""
                                            + chunithmSessionId
                                            + "\""),
                    null,
                    "Bearer " + chunithmHelperToken);
            expect(200, response.status, "CHUNITHM helper import succeeds");
            Map<String, Object> chunithmImported = object(Json.parse(response.body()));
            expect(1L, integer(chunithmImported.get("chartsAdded")),
                    "first CHUNITHM row adds chart");
            expect(0L, integer(chunithmImported.get("chartsUpdated")),
                    "same-batch CHUNITHM duplicates collapse before persistence");
            expect(1L, integer(chunithmImported.get("recordsAdded")),
                    "one semantic CHUNITHM play added");
            expect(1L, integer(chunithmImported.get("recordsEnriched")),
                    "same CHUNITHM play receives missing percentage details");
            expect(0L, integer(chunithmImported.get("recordsIgnored")),
                    "a useful duplicate is enriched rather than ignored");
            ChunithmScoreStore.Snapshot storedChunithm =
                    chunithmScores.loadSnapshot(
                            first.user().id(), List.of("CHUNITHM VERSE"));
            expect(1, storedChunithm.charts().size(),
                    "CHUNITHM duplicate charts collapse by SongID+difficulty");
            ChunithmChartInput storedChart = storedChunithm.charts().getFirst();
            expect(1_009_000, storedChart.score(), "highest CHUNITHM score stored");
            expect("Ｂ．Ｂ．Ｋ．Ｋ．Ｂ．Ｋ．Ｋ．", storedChart.title(),
                    "catalog title overwrites helper title");
            expect(12.5, storedChart.constant(),
                    "catalog constant overwrites helper constant");
            expect("CHUNITHM VERSE", storedChart.version(),
                    "catalog version overwrites helper version");
            expect(1, chunithmHistory.load(first.user().id()).size(),
                    "CHUNITHM history is stored separately");

            response = request(
                    historyHandler,
                    "GET",
                    "/api/history?game=chunithm&songId=3&difficulty=master",
                    null,
                    firstCookie,
                    null);
            expect(200, response.status, "CHUNITHM history query succeeds");
            Map<String, Object> chunithmHistoryPayload = object(
                    Json.parse(response.body()));
            expect("chunithm", chunithmHistoryPayload.get("game"),
                    "history response identifies CHUNITHM");
            expect(1L, integer(chunithmHistoryPayload.get("count")),
                    "CHUNITHM history filter finds play");
            Map<String, Object> chunithmPlay = object(
                    ((List<?>) chunithmHistoryPayload.get("records")).getFirst());
            expect("sssp", chunithmPlay.get("rank"),
                    "CHUNITHM rank is derived from score");
            expect(2L, integer(chunithmPlay.get("track")),
                    "CHUNITHM track is retained");
            Map<String, Object> chunithmJudgments = object(
                    chunithmPlay.get("judgmentDetails"));
            expect(
                    2L,
                    integer(object(chunithmJudgments.get("judgments"))
                            .get("justice")),
                    "CHUNITHM history API returns global judgments");
            expect(
                    40L,
                    integer(object(chunithmJudgments.get("noteCounts"))
                            .get("flick")),
                    "CHUNITHM history API keeps legacy note counts readable");
            expect(
                    new BigDecimal("96.93"),
                    object(chunithmJudgments.get("noteAchievements"))
                            .get("flick"),
                    "CHUNITHM history API returns note achievements as percentages");
            expect(
                    642L,
                    integer(chunithmJudgments.get("maxCombo")),
                    "CHUNITHM history API returns max combo");

            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    chunithmImportDocument().replace(
                            "B.B.K.K.B.K.K.", "Wrong Song For ID"),
                    firstCookie,
                    null);
            expect(400, response.status,
                    "CHUNITHM title must match the supplied catalog SongID");

            response = request(
                    importHandler,
                    "POST",
                    "/api/sync/import",
                    "{\"game\":\"maimai\",\"records\":[],\"extra\":true}",
                    firstCookie,
                    null);
            expect(400, response.status, "unknown import fields are rejected");

            response = request(
                    historyHandler,
                    "GET",
                    "/api/history?unknown=true",
                    null,
                    firstCookie,
                    null);
            expect(400, response.status, "unknown history query is rejected");

            System.out.println("WebServerSyncTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static Map<String, Object> createSession(
            HttpHandler handler,
            String cookie) throws IOException {
        FakeExchange response = request(
                handler,
                "POST",
                "/api/sync/sessions",
                "{\"game\":\"maimai\"}",
                cookie,
                null);
        expect(201, response.status, "another session can be created");
        return object(Json.parse(response.body()));
    }

    private static void advanceToFetching(
            HttpHandler handler,
            String sessionId,
            String helperToken) throws IOException {
        advanceToFetching(handler, sessionId, helperToken, "maimai");
    }

    private static void advanceToFetching(
            HttpHandler handler,
            String sessionId,
            String helperToken,
            String game) throws IOException {
        for (String status : List.of(
                "waiting_auth", "callback_received", "fetching")) {
            FakeExchange response = postEvent(
                    handler, sessionId, helperToken, game, status, null);
            expect(200, response.status, "helper advances through " + status);
        }
    }

    private static FakeExchange postEvent(
            HttpHandler handler,
            String sessionId,
            String helperToken,
            String status,
            String message) throws IOException {
        return postEvent(
                handler, sessionId, helperToken, "maimai", status, message);
    }

    private static FakeExchange postProgressEvent(
            HttpHandler handler,
            String sessionId,
            String helperToken,
            String game,
            String stage,
            int completed,
            int total,
            int succeeded) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("game", game);
        document.put("status", "fetching");
        document.put("stage", stage);
        document.put("completed", completed);
        document.put("total", total);
        document.put("succeeded", succeeded);
        return request(
                handler,
                "POST",
                "/api/sync/sessions/" + sessionId + "/events",
                Json.stringify(document),
                null,
                "Bearer " + helperToken);
    }

    private static FakeExchange postEvent(
            HttpHandler handler,
            String sessionId,
            String helperToken,
            String game,
            String status,
            String message) throws IOException {
        return request(
                handler,
                "POST",
                "/api/sync/sessions/" + sessionId + "/events",
                eventDocument(game, status, message),
                null,
                "Bearer " + helperToken);
    }

    private static String eventDocument(String status, String message) {
        return eventDocument("maimai", status, message);
    }

    private static String eventDocument(
            String game,
            String status,
            String message) {
        Map<String, Object> document = new LinkedHashMap<>();
        if (!"waiting_auth".equals(status)) {
            document.put("game", game);
        }
        document.put("status", status);
        if (message != null) {
            document.put("message", message);
        }
        return Json.stringify(document);
    }

    private static String importDocument(String recordId, String playedAt) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("songId", "123");
        chart.put("title", "Sync Song");
        chart.put("chartType", "dx");
        chart.put("difficulty", "MASTER");
        chart.put("level", new BigDecimal("14.0"));
        chart.put("achievement", new BigDecimal("100.5000"));
        chart.put("version", "current");
        chart.put("comboStatus", "ap");
        chart.put("syncStatus", "fsd");

        Map<String, Object> record = new LinkedHashMap<>();
        record.put("source", "maimai-wechat");
        record.put("sourceRecordId", recordId);
        record.put("songId", "123");
        record.put("title", "Sync Song");
        record.put("chartType", "dx");
        record.put("difficulty", "MASTER");
        record.put("achievement", new BigDecimal("100.5000"));
        record.put("dxScore", 2879);
        record.put("rank", "sssp");
        record.put("comboStatus", "ap");
        record.put("syncStatus", "fsd");
        record.put("judgmentDetails", Map.of(
                "byNoteType", Map.of(
                        "tap", maimaiJudgments(240, 10, 2, 0, 0),
                        "break", maimaiJudgments(9, 1, 0, 0, 0))));
        record.put("playDetails", Map.of(
                "fast", 137,
                "late", 63,
                "maxCombo", Map.of("current", 408, "maximum", 423),
                "maxSync", Map.of("current", 8, "maximum", 1_139),
                "dxScore", Map.of("current", 2_879, "maximum", 3_000),
                "rating", Map.of(
                        "value", 15_600,
                        "playerTotal", 15_620,
                        "delta", -9,
                        "frame", "yellow",
                        "frameImageUrl", officialRatingFrameUrl("yellow")),
                "partners", List.of(Map.of(
                        "stars", 3,
                        "level", 156,
                        "imageUrl", "https://maimai.wahlap.com/maimai-mobile/"
                                + "img/Chara/UI_Chara_000001.png"))));
        record.put("playedAt", playedAt);

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("game", "maimai");
        document.put("charts", List.of(chart));
        document.put("records", List.of(record));
        return Json.stringify(document);
    }

    private static String chunithmImportDocument() {
        Map<String, Object> lower = new LinkedHashMap<>();
        lower.put("songId", "003");
        lower.put("title", "B.B.K.K.B.K.K.");
        lower.put("difficulty", "master");
        lower.put("constant", new BigDecimal("1.0"));
        lower.put("score", 1_000_000);
        lower.put("version", "FORGED VERSION");

        Map<String, Object> higher = new LinkedHashMap<>(lower);
        higher.put("score", 1_009_000);

        Map<String, Object> firstRecord = new LinkedHashMap<>();
        firstRecord.put("source", "chunithm-wechat");
        firstRecord.put("sourceRecordId", null);
        firstRecord.put("songId", "3");
        firstRecord.put("title", "B.B.K.K.B.K.K.");
        firstRecord.put("difficulty", "MASTER");
        firstRecord.put("score", 1_009_000);
        firstRecord.put("rank", null);
        firstRecord.put("clearStatus", "clear");
        firstRecord.put("comboStatus", "aj");
        firstRecord.put("playedAt", "2026-07-16T12:00:00Z");
        firstRecord.put("track", 2);
        firstRecord.put("judgmentDetails", Map.of(
                "judgments", chunithmJudgments(640, 2, 0, 0),
                "noteCounts", chunithmNoteCounts(200, 120, 140, 142, 40),
                "maxCombo", 642));

        Map<String, Object> duplicateSource = new LinkedHashMap<>(firstRecord);
        duplicateSource.put("source", "manual-test");
        duplicateSource.put("sourceRecordId", "new-official-id");
        duplicateSource.put("judgmentDetails", Map.of(
                "judgments", chunithmJudgments(640, 2, 0, 0),
                "noteAchievements", chunithmAchievements(
                        "97.05", "100.98", "100.35", "99.52",
                        "96.93"),
                "maxCombo", 642));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("game", "chunithm");
        document.put("charts", List.of(lower, higher));
        document.put("records", List.of(firstRecord, duplicateSource));
        return Json.stringify(document);
    }

    private static Map<String, Integer> maimaiJudgments(
            int criticalPerfect, int perfect, int great, int good, int miss) {
        Map<String, Integer> value = new LinkedHashMap<>();
        value.put("criticalPerfect", criticalPerfect);
        value.put("perfect", perfect);
        value.put("great", great);
        value.put("good", good);
        value.put("miss", miss);
        return value;
    }

    private static Map<String, Integer> chunithmJudgments(
            int justiceCritical, int justice, int attack, int miss) {
        Map<String, Integer> value = new LinkedHashMap<>();
        value.put("justiceCritical", justiceCritical);
        value.put("justice", justice);
        value.put("attack", attack);
        value.put("miss", miss);
        return value;
    }

    private static Map<String, Integer> chunithmNoteCounts(
            int tap, int hold, int slide, int air, int flick) {
        Map<String, Integer> value = new LinkedHashMap<>();
        value.put("tap", tap);
        value.put("hold", hold);
        value.put("slide", slide);
        value.put("air", air);
        value.put("flick", flick);
        return value;
    }

    private static Map<String, BigDecimal> chunithmAchievements(
            String tap, String hold, String slide, String air, String flick) {
        Map<String, BigDecimal> value = new LinkedHashMap<>();
        value.put("tap", new BigDecimal(tap));
        value.put("hold", new BigDecimal(hold));
        value.put("slide", new BigDecimal(slide));
        value.put("air", new BigDecimal(air));
        value.put("flick", new BigDecimal(flick));
        return value;
    }

    private static String officialRatingFrameUrl(String frame) {
        return "https://maimai.wahlap.com/maimai-mobile/img/rating_base_"
                + frame + ".png";
    }

    private static ChunithmCatalog chunithmCatalog() {
        String music = """
                [{
                  "id":3,
                  "title":"Ｂ．Ｂ．Ｋ．Ｋ．Ｂ．Ｋ．Ｋ．",
                  "ds":[3.0,5.0,10.0,12.5],
                  "level":["3","5","10","12+"],
                  "cids":[1,2,3,4],
                  "charts":[
                    {"combo":333,"charter":"A"},
                    {"combo":541,"charter":"B"},
                    {"combo":1051,"charter":"C"},
                    {"combo":960,"charter":"D"}
                  ],
                  "basic_info":{
                    "title":"Ｂ．Ｂ．Ｋ．Ｋ．Ｂ．Ｋ．Ｋ．",
                    "artist":"nora2r",
                    "genre":"其他游戏",
                    "bpm":170,
                    "from":"CHUNITHM VERSE"
                  }
                }]
                """;
        return ChunithmCatalog.fromJson(
                music,
                "{\"version\":[\"CHUNITHM VERSE\"]}",
                endpoint -> {
                    throw new AssertionError("test catalogue must stay offline");
                },
                Duration.ofMinutes(1),
                new AtomicLong(1)::get);
    }

    private static String cookie(String token) {
        return AuthService.SESSION_COOKIE_NAME + "=" + token;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static String string(Object value) {
        return (String) value;
    }

    private static long integer(Object value) {
        return ((BigDecimal) value).longValueExact();
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
            String body,
            String cookie,
            String authorization) throws IOException {
        byte[] bytes = body == null
                ? new byte[0]
                : body.getBytes(StandardCharsets.UTF_8);
        FakeExchange exchange = new FakeExchange(method, URI.create(path), bytes);
        if (body != null) {
            exchange.requestHeaders.set("Content-Type", "application/json");
        }
        if (cookie != null) {
            exchange.requestHeaders.set("Cookie", cookie);
        }
        if (authorization != null) {
            exchange.requestHeaders.set("Authorization", authorization);
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
