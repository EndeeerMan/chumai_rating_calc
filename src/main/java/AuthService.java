import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Password authentication and short-lived in-memory browser sessions. */
public final class AuthService {
    public static final String SESSION_COOKIE_NAME = "b50_session";
    public static final int SESSION_MAX_AGE_SECONDS = 7 * 24 * 60 * 60;

    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;
    private static final int TOKEN_BYTES = 32;
    private static final Duration SESSION_LIFETIME =
            Duration.ofSeconds(SESSION_MAX_AGE_SECONDS);

    private final UserStore store;
    private final UserEmailStore emailStore;
    private final SecureRandom random;
    private final Clock clock;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock credentialLifecycleLock =
            new ReentrantReadWriteLock();
    private final byte[] dummySalt = new byte[SALT_BYTES];
    private final byte[] dummyHash = new byte[HASH_BITS / 8];

    public AuthService(UserStore store) {
        this(store, null, new SecureRandom(), Clock.systemUTC());
    }

    /** Enables mandatory verified-email binding for web-server sessions. */
    public AuthService(UserStore store, UserEmailStore emailStore) {
        this(store, emailStore, new SecureRandom(), Clock.systemUTC());
    }

    AuthService(UserStore store, SecureRandom random, Clock clock) {
        this(store, null, random, clock);
    }

    AuthService(
            UserStore store,
            UserEmailStore emailStore,
            SecureRandom random,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store must not be null");
        this.emailStore = emailStore;
        this.random = Objects.requireNonNull(random, "random must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        random.nextBytes(dummySalt);
        random.nextBytes(dummyHash);
    }

    /** Registers a user and immediately creates an authenticated session. */
    public SessionHandle register(String suppliedUsername, String password)
            throws IOException {
        credentialLifecycleLock.readLock().lock();
        try {
            Username username = validateNewUsername(suppliedUsername);
            char[] passwordCharacters = validatePassword(password);
            byte[] salt = new byte[SALT_BYTES];
            random.nextBytes(salt);
            byte[] hash = null;
            try {
                hash = derivePasswordHash(passwordCharacters, salt, PBKDF2_ITERATIONS);
                UserStore.StoredUser stored = store.createUser(
                        username.display(),
                        username.canonical(),
                        salt,
                        hash,
                        PBKDF2_ITERATIONS);
                return createSession(stored);
            } finally {
                Arrays.fill(passwordCharacters, '\0');
                Arrays.fill(salt, (byte) 0);
                if (hash != null) {
                    Arrays.fill(hash, (byte) 0);
                }
            }
        } finally {
            credentialLifecycleLock.readLock().unlock();
        }
    }

    /** Authenticates with a deliberately generic failure for all bad logins. */
    public SessionHandle login(String suppliedUsername, String password) {
        credentialLifecycleLock.readLock().lock();
        try {
            Username username;
            char[] passwordCharacters;
            try {
                username = validateUsername(suppliedUsername);
                passwordCharacters = password == null ? new char[0] : password.toCharArray();
            } catch (ValidationException error) {
                // Still spend one PBKDF2 operation so malformed and unknown usernames do
                // not have an obvious fast path.
                char[] candidate = password == null ? new char[0] : password.toCharArray();
                try {
                    constantTimePasswordCheck(
                            candidate, dummySalt, PBKDF2_ITERATIONS, dummyHash);
                } finally {
                    Arrays.fill(candidate, '\0');
                }
                throw new InvalidCredentialsException();
            }

            try {
                Optional<UserStore.StoredUser> found =
                        store.findByCanonicalUsername(username.canonical());
                byte[] salt = found.map(UserStore.StoredUser::salt)
                        .orElseGet(dummySalt::clone);
                byte[] expected = found.map(UserStore.StoredUser::passwordHash)
                        .orElseGet(dummyHash::clone);
                int iterations = found.map(UserStore.StoredUser::iterations)
                        .orElse(PBKDF2_ITERATIONS);
                boolean valid;
                try {
                    valid = constantTimePasswordCheck(
                            passwordCharacters, salt, iterations, expected);
                } finally {
                    Arrays.fill(salt, (byte) 0);
                    Arrays.fill(expected, (byte) 0);
                }
                if (found.isEmpty() || !valid) {
                    throw new InvalidCredentialsException();
                }
                return createSession(found.orElseThrow());
            } finally {
                Arrays.fill(passwordCharacters, '\0');
            }
        } finally {
            credentialLifecycleLock.readLock().unlock();
        }
    }

    /** Logs in with either the immutable username or its uniquely bound email. */
    public SessionHandle loginIdentifier(String suppliedIdentifier, String password) {
        if (emailStore == null || suppliedIdentifier == null
                || !suppliedIdentifier.contains("@")) {
            return login(suppliedIdentifier, password);
        }
        try {
            Optional<String> userId = emailStore.findUserId(suppliedIdentifier);
            Optional<UserStore.StoredUser> stored = userId.flatMap(store::findById);
            if (stored.isPresent()) {
                return login(stored.orElseThrow().username(), password);
            }
        } catch (UserEmailStore.ValidationException ignored) {
            // Use the same deliberately expensive generic failure path as bad usernames.
        }
        return login("\0", password);
    }

    public boolean emailBindingRequired() {
        return emailStore != null;
    }

    public Optional<String> emailForUser(String userId) {
        return emailStore == null ? Optional.empty() : emailStore.findEmail(userId);
    }

    public boolean requiresEmailBinding(String userId) {
        return emailStore != null && emailStore.findEmail(userId).isEmpty();
    }

    /** Returns a session only while it remains unexpired. */
    public Optional<AuthenticatedUser> authenticateSession(String token) {
        if (!isValidSessionToken(token)) {
            return Optional.empty();
        }
        String key = tokenKey(token);
        Session session = sessions.get(key);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (!session.expiresAt().isAfter(now)) {
            sessions.remove(key, session);
            return Optional.empty();
        }
        return Optional.of(session.user());
    }

    /** Invalidates the supplied token. Unknown tokens are intentionally harmless. */
    public void logout(String token) {
        if (!isValidSessionToken(token)) {
            return;
        }
        sessions.remove(tokenKey(token));
    }

    /** Changes one authenticated user's password and rotates every session. */
    public SessionHandle changePassword(
            String token,
            String currentPassword,
            String newPassword) throws IOException {
        credentialLifecycleLock.writeLock().lock();
        try {
            AuthenticatedUser authenticated = authenticateSession(token)
                    .orElseThrow(InvalidCurrentPasswordException::new);
            UserStore.StoredUser stored = store.findById(authenticated.id())
                    .orElseThrow(InvalidCurrentPasswordException::new);
            char[] currentCharacters = currentPassword == null
                    ? new char[0]
                    : currentPassword.toCharArray();
            char[] replacementCharacters = validatePassword(newPassword);
            byte[] expectedSalt = stored.salt();
            byte[] expectedHash = stored.passwordHash();
            byte[] replacementSalt = new byte[SALT_BYTES];
            byte[] replacementHash = null;
            try {
                boolean currentMatches = constantTimePasswordCheck(
                        currentCharacters,
                        expectedSalt,
                        stored.iterations(),
                        expectedHash);
                if (!currentMatches) {
                    throw new InvalidCurrentPasswordException();
                }
                if (constantTimePasswordCheck(
                        replacementCharacters,
                        expectedSalt,
                        stored.iterations(),
                        expectedHash)) {
                    throw new ValidationException(
                            "new password must be different from the current password");
                }

                random.nextBytes(replacementSalt);
                replacementHash = derivePasswordHash(
                        replacementCharacters,
                        replacementSalt,
                        PBKDF2_ITERATIONS);
                UserStore.StoredUser updated = store.updatePassword(
                        authenticated.id(),
                        expectedSalt,
                        expectedHash,
                        stored.iterations(),
                        replacementSalt,
                        replacementHash,
                        PBKDF2_ITERATIONS);
                invalidateUserSessions(authenticated.id());
                return createSession(updated);
            } finally {
                Arrays.fill(currentCharacters, '\0');
                Arrays.fill(replacementCharacters, '\0');
                Arrays.fill(expectedSalt, (byte) 0);
                Arrays.fill(expectedHash, (byte) 0);
                Arrays.fill(replacementSalt, (byte) 0);
                if (replacementHash != null) {
                    Arrays.fill(replacementHash, (byte) 0);
                }
            }
        } finally {
            credentialLifecycleLock.writeLock().unlock();
        }
    }

    /** Replaces credentials after a verified-email password-reset challenge. */
    public void resetPasswordByEmail(String suppliedEmail, String newPassword)
            throws IOException {
        if (emailStore == null) {
            throw new InvalidPasswordResetException();
        }
        String email;
        try {
            email = UserEmailStore.normalizeEmail(suppliedEmail);
        } catch (UserEmailStore.ValidationException error) {
            throw new InvalidPasswordResetException();
        }

        credentialLifecycleLock.writeLock().lock();
        try {
            String userId = emailStore.findUserId(email)
                    .orElseThrow(InvalidPasswordResetException::new);
            UserStore.StoredUser stored = store.findById(userId)
                    .orElseThrow(InvalidPasswordResetException::new);
            char[] replacementCharacters = validatePassword(newPassword);
            byte[] expectedSalt = stored.salt();
            byte[] expectedHash = stored.passwordHash();
            byte[] replacementSalt = new byte[SALT_BYTES];
            byte[] replacementHash = null;
            try {
                random.nextBytes(replacementSalt);
                replacementHash = derivePasswordHash(
                        replacementCharacters,
                        replacementSalt,
                        PBKDF2_ITERATIONS);
                store.updatePassword(
                        userId,
                        expectedSalt,
                        expectedHash,
                        stored.iterations(),
                        replacementSalt,
                        replacementHash,
                        PBKDF2_ITERATIONS);
                invalidateUserSessions(userId);
            } finally {
                Arrays.fill(replacementCharacters, '\0');
                Arrays.fill(expectedSalt, (byte) 0);
                Arrays.fill(expectedHash, (byte) 0);
                Arrays.fill(replacementSalt, (byte) 0);
                if (replacementHash != null) {
                    Arrays.fill(replacementHash, (byte) 0);
                }
            }
        } finally {
            credentialLifecycleLock.writeLock().unlock();
        }
    }

    /** Rechecks the current password without changing or rotating the session. */
    public AuthenticatedUser verifyCurrentPassword(
            String token, String currentPassword) {
        credentialLifecycleLock.readLock().lock();
        VerifiedPassword verified = null;
        try {
            verified = verifyCurrentPasswordLocked(token, currentPassword);
            return verified.user();
        } finally {
            if (verified != null) {
                verified.clear();
            }
            credentialLifecycleLock.readLock().unlock();
        }
    }

    /**
     * Runs one account mutation while the verified credential snapshot remains
     * protected from concurrent password changes or permanent deletion.
     */
    public <T> T withVerifiedCurrentPassword(
            String token,
            String currentPassword,
            VerifiedAccountAction<T> action) throws IOException {
        Objects.requireNonNull(action, "action must not be null");
        credentialLifecycleLock.readLock().lock();
        VerifiedPassword verified = null;
        try {
            verified = verifyCurrentPasswordLocked(token, currentPassword);
            return action.run(verified.user());
        } finally {
            if (verified != null) {
                verified.clear();
            }
            credentialLifecycleLock.readLock().unlock();
        }
    }

    /** Deletes the authenticated account and revokes all of its sessions. */
    public AuthenticatedUser deleteAccount(
            String token, String currentPassword) throws IOException {
        return deleteAccount(token, currentPassword, ignored -> {
        });
    }

    /**
     * Deletes an account after an in-lock cleanup step such as releasing its
     * unique email binding. Email changes use the matching lifecycle read lock,
     * so an old request cannot recreate that binding after deletion begins.
     */
    public AuthenticatedUser deleteAccount(
            String token,
            String currentPassword,
            AccountDeletionAction beforeCredentialDeletion) throws IOException {
        Objects.requireNonNull(
                beforeCredentialDeletion,
                "beforeCredentialDeletion must not be null");
        credentialLifecycleLock.writeLock().lock();
        VerifiedPassword verified = null;
        boolean accountDeleted = false;
        try {
            verified = verifyCurrentPasswordLocked(token, currentPassword);
            beforeCredentialDeletion.run(verified.user());
            store.deleteUser(
                    verified.user().id(),
                    verified.salt(),
                    verified.passwordHash(),
                    verified.iterations());
            accountDeleted = true;
            invalidateUserSessions(verified.user().id());
            store.deleteChartData(verified.user().id());
            return verified.user();
        } finally {
            if (accountDeleted) {
                // Keep sessions revoked even when a later per-user data cleanup
                // reports an I/O error after the credential row was removed.
                invalidateUserSessions(verified.user().id());
            }
            if (verified != null) {
                verified.clear();
            }
            credentialLifecycleLock.writeLock().unlock();
        }
    }

    private VerifiedPassword verifyCurrentPasswordLocked(
            String token, String currentPassword) {
        AuthenticatedUser authenticated = authenticateSession(token)
                .orElseThrow(InvalidCurrentPasswordException::new);
        UserStore.StoredUser stored = store.findById(authenticated.id())
                .orElseThrow(InvalidCurrentPasswordException::new);
        char[] passwordCharacters = currentPassword == null
                ? new char[0]
                : currentPassword.toCharArray();
        byte[] salt = stored.salt();
        byte[] passwordHash = stored.passwordHash();
        boolean verified = false;
        try {
            if (!constantTimePasswordCheck(
                    passwordCharacters, salt, stored.iterations(), passwordHash)) {
                throw new InvalidCurrentPasswordException();
            }
            verified = true;
            return new VerifiedPassword(
                    authenticated, salt, passwordHash, stored.iterations());
        } finally {
            Arrays.fill(passwordCharacters, '\0');
            if (!verified) {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(passwordHash, (byte) 0);
            }
        }
    }

    private static boolean isValidSessionToken(String token) {
        if (token == null || token.length() != 43) {
            return false;
        }
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_'
                    || character == '-';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    private SessionHandle createSession(UserStore.StoredUser stored) {
        purgeExpiredSessions();
        AuthenticatedUser user = new AuthenticatedUser(stored.id(), stored.username());
        Instant expiresAt = clock.instant().plus(SESSION_LIFETIME);
        while (true) {
            byte[] tokenBytes = new byte[TOKEN_BYTES];
            random.nextBytes(tokenBytes);
            String token;
            try {
                token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
            } finally {
                Arrays.fill(tokenBytes, (byte) 0);
            }
            String key = tokenKey(token);
            if (sessions.putIfAbsent(key, new Session(user, expiresAt)) == null) {
                return new SessionHandle(token, expiresAt, user);
            }
        }
    }

    private void purgeExpiredSessions() {
        Instant now = clock.instant();
        sessions.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private void invalidateUserSessions(String userId) {
        sessions.entrySet().removeIf(
                entry -> entry.getValue().user().id().equals(userId));
    }

    static Username validateUsername(String supplied) {
        if (supplied == null) {
            throw new ValidationException("username is required");
        }
        String display = Normalizer.normalize(supplied.strip(), Normalizer.Form.NFKC);
        int length = display.codePointCount(0, display.length());
        if (length < 3 || length > 32) {
            throw new ValidationException("username must be between 3 and 32 characters");
        }

        int position = 0;
        for (int offset = 0; offset < display.length();) {
            int codePoint = display.codePointAt(offset);
            boolean letterOrDigit = Character.isLetterOrDigit(codePoint);
            boolean punctuation = codePoint == '_' || codePoint == '-' || codePoint == '.';
            if ((position == 0 && !letterOrDigit)
                    || (position > 0 && !letterOrDigit && !punctuation)) {
                throw new ValidationException(
                        "username may contain letters, numbers, underscore, hyphen, and dot; "
                                + "it must start with a letter or number");
            }
            offset += Character.charCount(codePoint);
            position++;
        }
        String canonical = display.toLowerCase(Locale.ROOT);
        return new Username(display, canonical);
    }

    private static Username validateNewUsername(String supplied) {
        if (supplied == null) {
            throw new ValidationException("username is required");
        }
        String display = supplied.strip();
        if (display.length() < 3 || display.length() > 18) {
            throw new ValidationException("username must be between 3 and 18 characters");
        }
        for (int index = 0; index < display.length(); index++) {
            char character = display.charAt(index);
            boolean valid = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_';
            if (!valid) {
                throw new ValidationException(
                        "username may contain only ASCII letters, numbers, and underscore");
            }
        }
        return new Username(display, display.toLowerCase(Locale.ROOT));
    }

    static void validateNewCredentials(String username, String password) {
        validateNewUsername(username);
        validateNewPassword(password);
    }

    static void validateNewPassword(String password) {
        char[] characters = validatePassword(password);
        Arrays.fill(characters, '\0');
    }

    private static char[] validatePassword(String password) {
        if (password == null) {
            throw new ValidationException("password is required");
        }
        if (password.length() < 6 || password.length() > 32) {
            throw new ValidationException(
                    "password must be between 6 and 32 ASCII characters");
        }
        for (int index = 0; index < password.length(); index++) {
            char character = password.charAt(index);
            if (character < 33 || character > 126) {
                throw new ValidationException(
                        "password characters must have ASCII values from 33 through 126");
            }
        }
        return password.toCharArray();
    }

    private static boolean constantTimePasswordCheck(
            char[] password, byte[] salt, int iterations, byte[] expected) {
        byte[] actual = derivePasswordHash(password, salt, iterations);
        try {
            return MessageDigest.isEqual(actual, expected);
        } finally {
            Arrays.fill(actual, (byte) 0);
        }
    }

    private static byte[] derivePasswordHash(
            char[] password, byte[] salt, int iterations) {
        PBEKeySpec specification = new PBEKeySpec(
                password, salt, iterations, HASH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(specification)
                    .getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("PBKDF2-HMAC-SHA256 is unavailable", error);
        } finally {
            specification.clearPassword();
        }
    }

    private static String tokenKey(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII));
            try {
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } finally {
                Arrays.fill(digest, (byte) 0);
            }
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    public record AuthenticatedUser(String id, String username) {
        public AuthenticatedUser {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(username, "username must not be null");
        }
    }

    public record SessionHandle(
            String token, Instant expiresAt, AuthenticatedUser user) {
        public SessionHandle {
            Objects.requireNonNull(token, "token must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
            Objects.requireNonNull(user, "user must not be null");
        }
    }

    @FunctionalInterface
    public interface VerifiedAccountAction<T> {
        T run(AuthenticatedUser user) throws IOException;
    }

    @FunctionalInterface
    public interface AccountDeletionAction {
        void run(AuthenticatedUser user) throws IOException;
    }

    record Username(String display, String canonical) {
    }

    private record Session(AuthenticatedUser user, Instant expiresAt) {
    }

    private record VerifiedPassword(
            AuthenticatedUser user,
            byte[] salt,
            byte[] passwordHash,
            int iterations) {
        private void clear() {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(passwordHash, (byte) 0);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ValidationException(String message) {
            super(message);
        }
    }

    public static final class InvalidCredentialsException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidCredentialsException() {
            super("Invalid username or password");
        }
    }

    public static final class InvalidCurrentPasswordException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidCurrentPasswordException() {
            super("Current password is incorrect");
        }
    }

    public static final class InvalidPasswordResetException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        InvalidPasswordResetException() {
            super("Password reset verification is invalid");
        }
    }
}
