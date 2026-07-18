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
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Dependency-free HTTP contract tests for verified email and account deletion.
 *
 * <p>The handlers are private implementation details, so these tests use the
 * same reflection/FakeExchange boundary as the other WebServer contract tests.
 * The injected sender only captures codes in memory and never opens a network
 * connection.</p>
 */
public final class WebServerEmailAccountTest {
    private static final String LEGACY_PASSWORD =
            "LegacySecure#2026";
    private static final String TARGET_PASSWORD =
            "TargetSecure#2026";
    private static final String PLAYED_AT = "2026-07-17T02:00:00Z";
    private static final String IMPORTED_AT = "2026-07-17T02:01:00Z";

    private static int tests;

    private WebServerEmailAccountTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-web-email-account-test-");
        MutableClock clock = new MutableClock(
                Instant.parse("2026-07-17T01:00:00Z"), ZoneOffset.UTC);
        CapturingSender sender = new CapturingSender();
        EmailVerificationService verification = new EmailVerificationService(
                sender, clock, new SecureRandom());
        try {
            runContract(temporary, clock, sender, verification);
            System.out.println(
                    "WebServerEmailAccountTest: all " + tests + " tests passed.");
        } finally {
            verification.close();
            deleteTree(temporary);
        }
    }

    private static void runContract(
            Path temporary,
            MutableClock clock,
            CapturingSender sender,
            EmailVerificationService verification) throws Exception {
        Path accountRoot = temporary.resolve("accounts");
        Path profileRoot = temporary.resolve("profiles");
        Path chunithmScoreRoot = temporary.resolve("chunithm-scores");
        Path maimaiHistoryRoot = temporary.resolve("maimai-history");
        Path chunithmHistoryRoot = temporary.resolve("chunithm-history");

        UserStore users = new UserStore(accountRoot);
        UserEmailStore emails = new UserEmailStore(accountRoot);
        AuthService auth = new AuthService(users, emails);
        UserProfileStore profiles = new UserProfileStore(profileRoot);
        ChunithmScoreStore chunithmScores = new ChunithmScoreStore(
                chunithmScoreRoot);
        PlayHistoryStore maimaiHistory = new PlayHistoryStore(maimaiHistoryRoot);
        ChunithmHistoryStore chunithmHistory = new ChunithmHistoryStore(
                chunithmHistoryRoot);
        SyncSessionStore syncSessions = new SyncSessionStore();

        HttpHandler status = handler(
                "WebServer$AuthStatusHandler",
                new Class<?>[]{AuthService.class},
                auth);
        HttpHandler emailCode = handler(
                "WebServer$EmailCodeHandler",
                new Class<?>[]{
                        AuthService.class,
                        UserEmailStore.class,
                        EmailVerificationService.class},
                auth,
                emails,
                verification);
        HttpHandler register = handler(
                "WebServer$RegisterHandler",
                new Class<?>[]{
                        AuthService.class,
                        UserEmailStore.class,
                        EmailVerificationService.class},
                auth,
                emails,
                verification);
        HttpHandler login = handler(
                "WebServer$LoginHandler",
                new Class<?>[]{AuthService.class},
                auth);
        HttpHandler passwordReset = handler(
                "WebServer$PasswordResetHandler",
                new Class<?>[]{
                        AuthService.class,
                        UserEmailStore.class,
                        EmailVerificationService.class},
                auth,
                emails,
                verification);
        HttpHandler userEmail = handler(
                "WebServer$UserEmailHandler",
                new Class<?>[]{
                        AuthService.class,
                        UserEmailStore.class,
                        EmailVerificationService.class},
                auth,
                emails,
                verification);
        HttpHandler account = handler(
                "WebServer$UserAccountHandler",
                new Class<?>[]{
                        AuthService.class,
                        UserEmailStore.class,
                        UserProfileStore.class,
                        ChunithmScoreStore.class,
                        PlayHistoryStore.class,
                        ChunithmHistoryStore.class,
                        SyncSessionStore.class},
                auth,
                emails,
                profiles,
                chunithmScores,
                maimaiHistory,
                chunithmHistory,
                syncSessions);
        HttpHandler profile = handler(
                "WebServer$UserProfileHandler",
                new Class<?>[]{AuthService.class, UserProfileStore.class},
                auth,
                profiles);

        AuthService.SessionHandle legacy = auth.register(
                "LegacyPlayer", LEGACY_PASSWORD);
        String legacyCookie = sessionCookie(legacy.token());
        verifyLegacyAccountGate(status, userEmail, profile, legacyCookie);

        RegistrationFlow alpha = requestRegistrationCode(
                emailCode,
                sender,
                clock,
                "Alpha.Player@Example.COM");
        verifyResendCooldown(emailCode, sender, alpha);
        RegistrationFlow beta = requestRegistrationCode(
                emailCode,
                sender,
                clock,
                "beta.player@example.com");
        expect(false, alpha.flowId().equals(beta.flowId()),
                "separate registration requests receive random flow ids");

        verifyRegistrationIsolationAndRequirement(
                register,
                userEmail,
                legacyCookie,
                alpha,
                beta);

        FakeExchange registration = request(
                register,
                "POST",
                "/api/auth/register",
                registrationDocument(
                        "TargetPlayer",
                        TARGET_PASSWORD,
                        alpha.email(),
                        alpha.code(),
                        alpha.flowId()),
                "application/json",
                null);
        expect(200, registration.status,
                "registration succeeds only with its verified email challenge");
        Map<String, Object> registrationBody = object(Json.parse(registration.body()));
        Map<String, Object> targetPublicUser = object(registrationBody.get("user"));
        String targetUserId = (String) targetPublicUser.get("id");
        String targetCookie = cookiePair(
                registration.responseHeaders.getFirst("Set-Cookie"));
        expect(alpha.email(), registrationBody.get("email"),
                "registration response includes the normalized verified email");
        expect(false, registrationBody.get("emailRequired"),
                "new verified registration never enters the binding gate");

        verifyBoundStatusAndEmailLogin(
                status,
                profile,
                login,
                emailCode,
                registration,
                targetCookie,
                targetUserId,
                alpha);

        bindAndRebindLegacyEmail(
                status,
                emailCode,
                userEmail,
                profile,
                login,
                sender,
                auth,
                legacy,
                legacyCookie);

        String legacyEmail = emails.findEmail(legacy.user().id()).orElseThrow();
        FakeExchange conflictingBind = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(legacyEmail.toUpperCase(), "bind"),
                "application/json",
                targetCookie);
        expect(409, conflictingBind.status,
                "email uniqueness is case-insensitive across different users");

        verifyPasswordReset(
                emailCode,
                passwordReset,
                login,
                auth,
                emails,
                sender,
                legacy);

        seedDeletionData(
                users,
                profiles,
                chunithmScores,
                maimaiHistory,
                chunithmHistory,
                syncSessions,
                targetUserId,
                legacy.user().id());
        verifyAccountDeletion(
                status,
                login,
                account,
                auth,
                users,
                emails,
                profiles,
                chunithmScores,
                maimaiHistory,
                chunithmHistory,
                syncSessions,
                accountRoot,
                profileRoot,
                targetUserId,
                targetCookie,
                alpha.email(),
                legacy,
                legacyCookie,
                legacyEmail);
    }

    private static void verifyLegacyAccountGate(
            HttpHandler status,
            HttpHandler userEmail,
            HttpHandler profile,
            String cookie) throws IOException {
        FakeExchange response = request(
                status, "GET", "/api/auth/status", null, null, cookie);
        expect(200, response.status, "legacy account can inspect auth status");
        Map<String, Object> body = object(Json.parse(response.body()));
        expect(true, body.get("authenticated"),
                "legacy account remains authenticated while email is missing");
        expect(null, body.get("email"), "legacy status has no invented email");
        expect(true, body.get("emailRequired"),
                "legacy status explicitly requires verified email binding");
        expect(true, object(body.get("user")).get("emailRequired"),
                "public user repeats the mandatory binding state");

        response = request(
                userEmail, "GET", "/api/user/email", null, null, cookie);
        expect(200, response.status,
                "unbound account can access the email binding endpoint");
        Map<String, Object> emailBody = object(Json.parse(response.body()));
        expect(null, emailBody.get("email"),
                "email endpoint reports the missing legacy binding");
        expect(true, emailBody.get("emailRequired"),
                "email endpoint marks legacy binding as required");
        expect(6L, integer(emailBody.get("verificationCodeDigits")),
                "email endpoint advertises a six-digit code");
        expect(600L, integer(emailBody.get("verificationCodeTtlSeconds")),
                "email endpoint advertises a ten-minute code lifetime");
        expect(120L, integer(emailBody.get("resendCooldownSeconds")),
                "email endpoint advertises a two-minute resend cooldown");

        response = request(
                profile, "GET", "/api/user/profile", null, null, cookie);
        expect(428, response.status,
                "ordinary profile data is blocked until a legacy email is bound");
        expect(null, response.responseHeaders.getFirst("Set-Cookie"),
                "email-required response does not discard the valid session");
    }

    private static RegistrationFlow requestRegistrationCode(
            HttpHandler handler,
            CapturingSender sender,
            MutableClock clock,
            String suppliedEmail) throws IOException {
        int deliveriesBefore = sender.deliveries.size();
        Instant requestedAt = clock.instant();
        FakeExchange response = request(
                handler,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(suppliedEmail, "register"),
                "application/json; charset=utf-8",
                null);
        expect(200, response.status, "registration verification email is accepted");
        expect(deliveriesBefore + 1, sender.deliveries.size(),
                "verification endpoint invokes only the injected local sender");
        Delivery delivery = sender.deliveries.getLast();
        expect(true, delivery.code().matches("[0-9]{6}"),
                "delivered verification code is exactly six ASCII digits");

        Map<String, Object> body = object(Json.parse(response.body()));
        String email = (String) body.get("email");
        String flowId = (String) body.get("verificationFlowId");
        expect(email, delivery.recipient(),
                "delivery and response use the same normalized email");
        expect(flowId, UUID.fromString(flowId).toString(),
                "registration flow id is a canonical random UUID");
        expect(600L, integer(body.get("expiresInSeconds")),
                "code response promises a ten-minute validity window");
        expect(120L, integer(body.get("resendAfterSeconds")),
                "code response promises a two-minute resend wait");
        expect(requestedAt.plus(Duration.ofMinutes(10)),
                Instant.parse((String) body.get("expiresAt")),
                "absolute expiration is ten minutes after delivery");
        expect(requestedAt.plus(Duration.ofMinutes(2)),
                Instant.parse((String) body.get("resendAvailableAt")),
                "absolute resend time is two minutes after delivery");
        expect(false, response.body().contains(delivery.code()),
                "plaintext verification code is never returned by HTTP");
        return new RegistrationFlow(email, flowId, delivery.code());
    }

    private static void verifyResendCooldown(
            HttpHandler emailCode,
            CapturingSender sender,
            RegistrationFlow flow) throws IOException {
        int deliveriesBefore = sender.deliveries.size();
        FakeExchange response = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(flow.email().toUpperCase(), "register"),
                "application/json",
                null);
        expect(429, response.status,
                "immediate resend is rejected for the normalized email");
        expect("120", response.responseHeaders.getFirst("Retry-After"),
                "resend rejection includes an exact Retry-After header");
        expect(120L,
                integer(object(Json.parse(response.body())).get("retryAfterSeconds")),
                "resend rejection mirrors the remaining cooldown in JSON");
        expect(deliveriesBefore, sender.deliveries.size(),
                "rate-limited resend never calls the email sender");
    }

    private static void verifyRegistrationIsolationAndRequirement(
            HttpHandler register,
            HttpHandler userEmail,
            String legacyCookie,
            RegistrationFlow alpha,
            RegistrationFlow beta) throws IOException {
        FakeExchange response = request(
                register,
                "POST",
                "/api/auth/register",
                incompleteRegistrationDocument(
                        "UnverifiedPlayer", TARGET_PASSWORD, alpha.email(),
                        alpha.flowId()),
                "application/json",
                null);
        expect(400, response.status,
                "registration requires an explicit verification code");

        response = request(
                register,
                "POST",
                "/api/auth/register",
                registrationDocument(
                        "WrongFlowPlayer",
                        TARGET_PASSWORD,
                        alpha.email(),
                        alpha.code(),
                        beta.flowId()),
                "application/json",
                null);
        expect(400, response.status,
                "verification code cannot cross registration flow ids");

        response = request(
                register,
                "POST",
                "/api/auth/register",
                registrationDocument(
                        "Bad-Name",
                        TARGET_PASSWORD,
                        alpha.email(),
                        alpha.code(),
                        alpha.flowId()),
                "application/json",
                null);
        expect(400, response.status,
                "invalid registration credentials are rejected before consuming the code");

        response = request(
                register,
                "POST",
                "/api/auth/register",
                registrationDocument(
                        "LegacyPlayer",
                        TARGET_PASSWORD,
                        alpha.email(),
                        alpha.code(),
                        alpha.flowId()),
                "application/json",
                null);
        expect(409, response.status,
                "duplicate username failure returns the reserved registration code");

        response = request(
                userEmail,
                "PUT",
                "/api/user/email",
                emailUpdateDocument(alpha.email(), alpha.code(), LEGACY_PASSWORD),
                "application/json",
                legacyCookie);
        expect(400, response.status,
                "registration-purpose code cannot authorize an account binding");
    }

    private static void verifyBoundStatusAndEmailLogin(
            HttpHandler status,
            HttpHandler profile,
            HttpHandler login,
            HttpHandler emailCode,
            FakeExchange registration,
            String cookie,
            String userId,
            RegistrationFlow flow) throws IOException {
        FakeExchange response = request(
                status, "GET", "/api/auth/status", null, null, cookie);
        expect(200, response.status, "registered account can inspect status");
        Map<String, Object> body = object(Json.parse(response.body()));
        expect(flow.email(), body.get("email"),
                "status returns the normalized bound email");
        expect(false, body.get("emailRequired"),
                "status clears the binding requirement after registration");
        expect(userId, object(body.get("user")).get("id"),
                "status preserves the registered user identity");

        response = request(
                profile, "GET", "/api/user/profile", null, null, cookie);
        expect(200, response.status,
                "verified registration can immediately access the profile");

        response = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(flow.email().toUpperCase(), TARGET_PASSWORD),
                "application/json",
                null);
        expect(200, response.status, "email can be used as a login identifier");
        expect(userId,
                object(object(Json.parse(response.body())).get("user")).get("id"),
                "email login resolves to the original account");

        response = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(flow.email().toUpperCase(), "register"),
                "application/json",
                null);
        expect(409, response.status,
                "bound email cannot start another registration regardless of case");
        expect(true, registration.responseHeaders.getFirst("Set-Cookie") != null,
                "successful registration issued its authenticated cookie");
    }

    private static void bindAndRebindLegacyEmail(
            HttpHandler status,
            HttpHandler emailCode,
            HttpHandler userEmail,
            HttpHandler profile,
            HttpHandler login,
            CapturingSender sender,
            AuthService auth,
            AuthService.SessionHandle legacy,
            String legacyCookie) throws IOException {
        String firstEmail = "legacy.player@example.com";
        FakeExchange response = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(firstEmail, "bind"),
                "application/json",
                legacyCookie);
        expect(200, response.status,
                "legacy account can request its mandatory binding code");
        String firstCode = sender.lastCode(firstEmail);
        expect(true, firstCode.matches("[0-9]{6}"),
                "legacy binding uses the same six-digit format");
        expect(false,
                object(Json.parse(response.body())).containsKey("verificationFlowId"),
                "authenticated binding is isolated by user id, not a public flow id");

        response = request(
                userEmail,
                "PUT",
                "/api/user/email",
                emailUpdateDocument(firstEmail, firstCode, "wrong password"),
                "application/json",
                legacyCookie);
        expect(401, response.status,
                "binding rejects a correct code with the wrong current password");
        expect(null, response.responseHeaders.getFirst("Set-Cookie"),
                "wrong current password preserves the existing session cookie");
        expect(true, auth.authenticateSession(legacy.token()).isPresent(),
                "wrong current password keeps the legacy session alive");

        response = request(
                userEmail,
                "PUT",
                "/api/user/email",
                emailUpdateDocument(firstEmail, firstCode, LEGACY_PASSWORD),
                "application/json",
                legacyCookie);
        expect(200, response.status,
                "current password plus matching code binds the legacy email");
        expect(firstEmail, object(Json.parse(response.body())).get("email"),
                "binding response returns the canonical address");
        expect(false, object(Json.parse(response.body())).get("emailRequired"),
                "binding response clears the mandatory state");

        response = request(
                status, "GET", "/api/auth/status", null, null, legacyCookie);
        expect(false, object(Json.parse(response.body())).get("emailRequired"),
                "auth status observes the completed legacy binding");
        response = request(
                profile, "GET", "/api/user/profile", null, null, legacyCookie);
        expect(200, response.status,
                "profile gate opens as soon as legacy binding succeeds");

        String replacementEmail = "new.legacy@example.com";
        response = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(replacementEmail, "bind"),
                "application/json",
                legacyCookie);
        expect(200, response.status,
                "bound account can request a replacement-email code");
        String replacementCode = sender.lastCode(replacementEmail);

        response = request(
                userEmail,
                "PUT",
                "/api/user/email",
                emailUpdateDocument(
                        replacementEmail,
                        wrongCode(replacementCode),
                        LEGACY_PASSWORD),
                "application/json",
                legacyCookie);
        expect(400, response.status,
                "rebind requires the matching code as well as current password");

        response = request(
                userEmail,
                "PUT",
                "/api/user/email",
                emailUpdateDocument(
                        replacementEmail,
                        replacementCode,
                        LEGACY_PASSWORD),
                "application/json",
                legacyCookie);
        expect(200, response.status,
                "current password plus replacement code atomically rebinds email");
        expect(replacementEmail, object(Json.parse(response.body())).get("email"),
                "rebind response exposes only the new address");

        response = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(firstEmail, LEGACY_PASSWORD),
                "application/json",
                null);
        expect(401, response.status,
                "old email stops authenticating immediately after rebind");
        response = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(replacementEmail.toUpperCase(), LEGACY_PASSWORD),
                "application/json",
                null);
        expect(200, response.status,
                "new email authenticates case-insensitively after rebind");
        expect(legacy.user().id(),
                object(object(Json.parse(response.body())).get("user")).get("id"),
                "replacement email still resolves to the same user id");
    }

    private static void verifyPasswordReset(
            HttpHandler emailCode,
            HttpHandler passwordReset,
            HttpHandler login,
            AuthService auth,
            UserEmailStore emails,
            CapturingSender sender,
            AuthService.SessionHandle retainedUser) throws IOException {
        String email = "a".repeat(64) + "@"
                + "b".repeat(50) + "." + "c".repeat(50) + ".com";
        String unknownEmail = "missing.player@example.com";
        String oldPassword = "ResetOld#2026";
        String newPassword = "ResetNew#2026";
        AuthService.SessionHandle first = auth.register("ResetPlayer", oldPassword);
        AuthService.SessionHandle second = auth.login("resetplayer", oldPassword);
        emails.bind(first.user().id(), email);

        int deliveriesBefore = sender.deliveries.size();
        FakeExchange unknownRequest = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(unknownEmail, "reset-password"),
                "application/json",
                null);
        expect(200, unknownRequest.status,
                "unknown reset email receives the same accepted response");
        expect(deliveriesBefore, sender.deliveries.size(),
                "unknown reset email never invokes the mail sender");
        Map<String, Object> unknownBody = object(Json.parse(unknownRequest.body()));
        String unknownFlowId = (String) unknownBody.get("verificationFlowId");
        expect(unknownFlowId, UUID.fromString(unknownFlowId).toString(),
                "unknown reset response still uses an opaque flow id");

        FakeExchange knownRequest = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(email, "reset-password"),
                "application/json",
                null);
        expect(200, knownRequest.status,
                "bound email can request a password-reset code");
        String code = sender.awaitCode(email);
        expect(deliveriesBefore + 1, sender.deliveries.size(),
                "bound reset email invokes the sender exactly once");
        Map<String, Object> knownBody = object(Json.parse(knownRequest.body()));
        expect(unknownBody.keySet(), knownBody.keySet(),
                "known and unknown reset responses expose the same fields");
        expect(600L, integer(knownBody.get("expiresInSeconds")),
                "password-reset code is valid for ten minutes");
        expect(120L, integer(knownBody.get("resendAfterSeconds")),
                "password-reset resend waits two minutes");
        String flowId = (String) knownBody.get("verificationFlowId");

        FakeExchange unknownResend = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(unknownEmail, "reset-password"),
                "application/json",
                null);
        FakeExchange knownResend = request(
                emailCode,
                "POST",
                "/api/auth/email/code",
                emailCodeDocument(email, "reset-password"),
                "application/json",
                null);
        expect(429, unknownResend.status,
                "unknown reset email follows the normal resend cooldown");
        expect(429, knownResend.status,
                "known reset email follows the same resend cooldown");
        expect(deliveriesBefore + 1, sender.deliveries.size(),
                "rate-limited reset requests never send more mail");

        FakeExchange invalidPassword = request(
                passwordReset,
                "POST",
                "/api/auth/password/reset",
                passwordResetDocument(email, code, flowId, "Bad pass#1"),
                "application/json",
                null);
        expect(400, invalidPassword.status,
                "password reset rejects a password outside the ASCII policy");
        expect(true, auth.authenticateSession(first.token()).isPresent(),
                "invalid replacement password does not consume the old session");

        FakeExchange wrongCodeResponse = request(
                passwordReset,
                "POST",
                "/api/auth/password/reset",
                passwordResetDocument(
                        email, wrongCode(code), flowId, newPassword),
                "application/json",
                null);
        expect(400, wrongCodeResponse.status,
                "password reset rejects an incorrect verification code");
        expect(true, auth.authenticateSession(first.token()).isPresent(),
                "failed reset preserves the first old session");
        expect(true, auth.authenticateSession(second.token()).isPresent(),
                "failed reset preserves the second old session");

        FakeExchange reset = request(
                passwordReset,
                "POST",
                "/api/auth/password/reset",
                passwordResetDocument(email, code, flowId, newPassword),
                "application/json",
                null);
        expect(200, reset.status,
                "verified email challenge resets the password");
        expect(true, object(Json.parse(reset.body())).get("success"),
                "password reset reports success");
        expect(true,
                reset.responseHeaders.getFirst("Set-Cookie").contains("Max-Age=0"),
                "password reset clears any browser session cookie");
        expect(false, auth.authenticateSession(first.token()).isPresent(),
                "successful reset revokes the first old session");
        expect(false, auth.authenticateSession(second.token()).isPresent(),
                "successful reset revokes every old session for the account");
        expect(true, auth.authenticateSession(retainedUser.token()).isPresent(),
                "password reset leaves another user's session active");

        FakeExchange oldLogin = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(email, oldPassword),
                "application/json",
                null);
        expect(401, oldLogin.status,
                "old password no longer authenticates after reset");
        FakeExchange newLogin = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(email.toUpperCase(), newPassword),
                "application/json",
                null);
        expect(200, newLogin.status,
                "new password authenticates by email after reset");

        FakeExchange replay = request(
                passwordReset,
                "POST",
                "/api/auth/password/reset",
                passwordResetDocument(email, code, flowId, "ReplayNew#2026"),
                "application/json",
                null);
        expect(400, replay.status,
                "consumed password-reset code cannot be replayed");
    }

    private static void seedDeletionData(
            UserStore users,
            UserProfileStore profiles,
            ChunithmScoreStore chunithmScores,
            PlayHistoryStore maimaiHistory,
            ChunithmHistoryStore chunithmHistory,
            SyncSessionStore syncSessions,
            String targetUserId,
            String retainedUserId) throws IOException {
        users.saveCharts(targetUserId, List.of(maimaiChart("100", "Delete Song")));
        users.saveCharts(retainedUserId, List.of(maimaiChart("200", "Keep Song")));
        profiles.updateDisplayName(targetUserId, "TargetPlayer", "Delete Profile");
        profiles.updateDisplayName(retainedUserId, "LegacyPlayer", "Keep Profile");

        chunithmScores.save(
                targetUserId,
                targetUserId,
                0,
                List.of(chunithmChart("delete", "Delete Chuni")),
                List.of("VERSE"));
        chunithmScores.save(
                retainedUserId,
                retainedUserId,
                0,
                List.of(chunithmChart("keep", "Keep Chuni")),
                List.of("VERSE"));

        maimaiHistory.append(targetUserId, List.of(maimaiPlay("delete-maimai")));
        maimaiHistory.append(retainedUserId, List.of(maimaiPlay("keep-maimai")));
        chunithmHistory.append(
                targetUserId, List.of(chunithmPlay("delete-chunithm")));
        chunithmHistory.append(
                retainedUserId, List.of(chunithmPlay("keep-chunithm")));

        syncSessions.create(targetUserId, "maimai");
        syncSessions.create(targetUserId, "chunithm");
        syncSessions.create(retainedUserId, "maimai");
    }

    private static void verifyAccountDeletion(
            HttpHandler status,
            HttpHandler login,
            HttpHandler account,
            AuthService auth,
            UserStore users,
            UserEmailStore emails,
            UserProfileStore profiles,
            ChunithmScoreStore chunithmScores,
            PlayHistoryStore maimaiHistory,
            ChunithmHistoryStore chunithmHistory,
            SyncSessionStore syncSessions,
            Path accountRoot,
            Path profileRoot,
            String targetUserId,
            String targetCookie,
            String targetEmail,
            AuthService.SessionHandle retained,
            String retainedCookie,
            String retainedEmail) throws IOException {
        FakeExchange parallelLogin = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(targetEmail, TARGET_PASSWORD),
                "application/json",
                null);
        expect(200, parallelLogin.status,
                "target has a parallel session before account deletion");
        String parallelCookie = cookiePair(
                parallelLogin.responseHeaders.getFirst("Set-Cookie"));
        String parallelToken = tokenFromCookie(parallelCookie);

        FakeExchange response = request(
                account,
                "DELETE",
                "/api/user/account",
                accountDeletionDocument("wrong password"),
                "application/json",
                targetCookie);
        expect(401, response.status,
                "account deletion rejects an incorrect current password");
        expect(null, response.responseHeaders.getFirst("Set-Cookie"),
                "failed account deletion does not clear the session cookie");
        expect(true, auth.authenticateSession(tokenFromCookie(targetCookie)).isPresent(),
                "failed deletion preserves the calling session");
        expect(true, auth.authenticateSession(parallelToken).isPresent(),
                "failed deletion preserves parallel sessions");
        response = request(
                status, "GET", "/api/auth/status", null, null, targetCookie);
        expect(true, object(Json.parse(response.body())).get("authenticated"),
                "failed deletion leaves the account usable over HTTP");

        response = request(
                account,
                "DELETE",
                "/api/user/account",
                accountDeletionDocument(TARGET_PASSWORD),
                "application/json",
                targetCookie);
        expect(200, response.status,
                "correct current password permanently deletes the account");
        Map<String, Object> deletionBody = object(Json.parse(response.body()));
        expect(true, deletionBody.get("success"),
                "account deletion reports success");
        expect(true, deletionBody.get("cleanupComplete"),
                "account deletion reports complete cross-store cleanup");
        expect(true,
                response.responseHeaders.getFirst("Set-Cookie").contains("Max-Age=0"),
                "successful deletion expires the browser session cookie");

        expect(false, auth.authenticateSession(tokenFromCookie(targetCookie)).isPresent(),
                "successful deletion revokes the calling session");
        expect(false, auth.authenticateSession(parallelToken).isPresent(),
                "successful deletion revokes every parallel target session");
        expect(true, users.findById(targetUserId).isEmpty(),
                "successful deletion removes the credential row");
        expect(true, emails.findEmail(targetUserId).isEmpty(),
                "successful deletion removes the user-to-email mapping");
        expect(true, emails.findUserId(targetEmail).isEmpty(),
                "successful deletion releases the email-to-user mapping");
        expect(false, Files.exists(profileRoot.resolve(targetUserId)),
                "successful deletion removes the whole private profile directory");
        expect(false,
                Files.exists(accountRoot.resolve("charts")
                        .resolve(targetUserId + ".json")),
                "successful deletion removes the maimai score file");
        expect(List.of(), chunithmScores.loadSnapshot(targetUserId).charts(),
                "successful deletion removes CHUNITHM scores");
        expect(List.of(), maimaiHistory.load(targetUserId),
                "successful deletion removes maimai play history");
        expect(List.of(), chunithmHistory.load(targetUserId),
                "successful deletion removes CHUNITHM play history");
        expect(List.of(), syncSessions.listForUser(targetUserId),
                "successful deletion removes every pending sync session");

        response = request(
                login,
                "POST",
                "/api/auth/login",
                credentials(targetEmail, TARGET_PASSWORD),
                "application/json",
                null);
        expect(401, response.status,
                "deleted email and password can no longer authenticate");

        expect(true, users.findById(retained.user().id()).isPresent(),
                "deleting one account preserves another credential row");
        expect(retainedEmail, emails.findEmail(retained.user().id()).orElse(null),
                "deleting one account preserves another email binding");
        expect("Keep Profile",
                profiles.loadProfile(retained.user().id(), retained.user().username())
                        .displayName(),
                "deleting one account preserves another profile");
        expect(1, users.loadCharts(retained.user().id()).size(),
                "deleting one account preserves another maimai score list");
        expect(1, chunithmScores.loadSnapshot(retained.user().id()).charts().size(),
                "deleting one account preserves another CHUNITHM score list");
        expect(1, maimaiHistory.load(retained.user().id()).size(),
                "deleting one account preserves another maimai history");
        expect(1, chunithmHistory.load(retained.user().id()).size(),
                "deleting one account preserves another CHUNITHM history");
        expect(1, syncSessions.listForUser(retained.user().id()).size(),
                "deleting one account preserves another sync session");
        expect(true, auth.authenticateSession(retained.token()).isPresent(),
                "deleting one account preserves another in-memory session");
        response = request(
                status, "GET", "/api/auth/status", null, null, retainedCookie);
        expect(true, object(Json.parse(response.body())).get("authenticated"),
                "unrelated user's HTTP session remains usable");
    }

    private static ChartInput maimaiChart(String songId, String title) {
        return new ChartInput(
                songId,
                title,
                ChartType.DX,
                "MASTER",
                14.0,
                100.5,
                Version.CURRENT,
                "fc",
                "fs");
    }

    private static ChunithmChartInput chunithmChart(String songId, String title) {
        return new ChunithmChartInput(
                songId, title, "MASTER", 14.0, 1_009_000, "VERSE");
    }

    private static PlayHistoryStore.PlayRecord maimaiPlay(String recordId) {
        return new PlayHistoryStore.PlayRecord(
                "wechat",
                recordId,
                "100",
                "History Song",
                "dx",
                "MASTER",
                100.5,
                2_900,
                "sssp",
                "fc",
                "fs",
                PLAYED_AT,
                IMPORTED_AT,
                "maimai-batch");
    }

    private static ChunithmHistoryStore.PlayRecord chunithmPlay(String recordId) {
        return new ChunithmHistoryStore.PlayRecord(
                "chunithm-wechat",
                recordId,
                "200",
                "CHUNITHM History Song",
                "MASTER",
                1_009_000,
                "sssp",
                "clear",
                "aj",
                PLAYED_AT,
                1,
                IMPORTED_AT,
                "chunithm-batch");
    }

    private static String emailCodeDocument(String email, String purpose) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("email", email);
        value.put("purpose", purpose);
        return Json.stringify(value);
    }

    private static String registrationDocument(
            String username,
            String password,
            String email,
            String code,
            String flowId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("username", username);
        value.put("password", password);
        value.put("email", email);
        value.put("verificationCode", code);
        value.put("verificationFlowId", flowId);
        return Json.stringify(value);
    }

    private static String incompleteRegistrationDocument(
            String username,
            String password,
            String email,
            String flowId) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("username", username);
        value.put("password", password);
        value.put("email", email);
        value.put("verificationFlowId", flowId);
        return Json.stringify(value);
    }

    private static String credentials(String identifier, String password) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("username", identifier);
        value.put("password", password);
        return Json.stringify(value);
    }

    private static String emailUpdateDocument(
            String email, String code, String currentPassword) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("email", email);
        value.put("verificationCode", code);
        value.put("currentPassword", currentPassword);
        return Json.stringify(value);
    }

    private static String passwordResetDocument(
            String email, String code, String flowId, String newPassword) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("email", email);
        value.put("verificationCode", code);
        value.put("verificationFlowId", flowId);
        value.put("newPassword", newPassword);
        return Json.stringify(value);
    }

    private static String accountDeletionDocument(String currentPassword) {
        return Json.stringify(Map.of("currentPassword", currentPassword));
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static long integer(Object value) {
        return ((BigDecimal) value).longValueExact();
    }

    private static String wrongCode(String code) {
        return "000000".equals(code) ? "999999" : "000000";
    }

    private static String sessionCookie(String token) {
        return AuthService.SESSION_COOKIE_NAME + "=" + token;
    }

    private static String cookiePair(String setCookie) {
        Objects.requireNonNull(setCookie, "Set-Cookie must not be null");
        int separator = setCookie.indexOf(';');
        return separator < 0 ? setCookie : setCookie.substring(0, separator);
    }

    private static String tokenFromCookie(String cookie) {
        String prefix = AuthService.SESSION_COOKIE_NAME + "=";
        if (cookie == null || !cookie.startsWith(prefix)) {
            throw new AssertionError("session cookie is malformed: " + cookie);
        }
        return cookie.substring(prefix.length());
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!Objects.equals(expected, actual)) {
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

    private record RegistrationFlow(String email, String flowId, String code) {
    }

    private record Delivery(String recipient, String code) {
    }

    private static final class CapturingSender
            implements EmailVerificationService.EmailSender {
        private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();

        @Override
        public void sendVerificationCode(String recipient, String sixDigitCode) {
            deliveries.add(new Delivery(recipient, sixDigitCode));
        }

        private String lastCode(String email) {
            String normalized = UserEmailStore.normalizeEmail(email);
            for (int index = deliveries.size() - 1; index >= 0; index--) {
                Delivery delivery = deliveries.get(index);
                if (delivery.recipient().equals(normalized)) {
                    return delivery.code();
                }
            }
            throw new AssertionError("no captured verification code for " + normalized);
        }

        private String awaitCode(String email) throws IOException {
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    return lastCode(email);
                } catch (AssertionError pending) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "interrupted while awaiting verification email", error);
                    }
                }
            }
            throw new IOException("timed out awaiting verification email");
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = Objects.requireNonNull(instant, "instant");
            this.zone = Objects.requireNonNull(zone, "zone");
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new MutableClock(instant, requestedZone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @SuppressWarnings("unused")
        private void advance(Duration duration) {
            instant = instant.plus(duration);
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
