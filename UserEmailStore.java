import java.io.IOException;
import java.math.BigDecimal;
import java.net.IDN;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Durable one-to-one mapping between local user ids and verified email addresses.
 *
 * <p>This deliberately lives outside {@code users.json}, so existing credential
 * records keep their version-1 shape. A missing mapping represents a legacy
 * account which still needs to bind an email address.</p>
 */
public final class UserEmailStore {
    private static final int FILE_VERSION = 1;
    private static final int MAX_BINDINGS = 100_000;
    private static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_LOCAL_PART_LENGTH = 64;
    private static final int MAX_DOMAIN_LENGTH = 253;
    private static final Set<String> DOCUMENT_KEYS = Set.of("version", "emails");

    private final Path root;
    private final Path emailFile;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final Map<String, String> emailByUserId = new LinkedHashMap<>();
    private final Map<String, String> userIdByEmail = new LinkedHashMap<>();

    /** Uses {@code userDataRoot/user_emails.json}. */
    public UserEmailStore(Path userDataRoot) throws IOException {
        root = Objects.requireNonNull(userDataRoot, "userDataRoot must not be null")
                .toAbsolutePath()
                .normalize();
        emailFile = safeChild(root, "user_emails.json");
        ensureSafeRoot();
        load();
    }

    public Path root() {
        return root;
    }

    public Path file() {
        return emailFile;
    }

    /** Returns the normalized email bound to a user, if any. */
    public Optional<String> findEmail(String userId) {
        String safeUserId = canonicalUserId(userId);
        lock.readLock().lock();
        try {
            return Optional.ofNullable(emailByUserId.get(safeUserId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Returns the user bound to an email, accepting an unnormalized input address. */
    public Optional<String> findUserId(String email) {
        String normalizedEmail = normalizeEmail(email);
        lock.readLock().lock();
        try {
            return Optional.ofNullable(userIdByEmail.get(normalizedEmail));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Binds a previously-unbound user. Repeating the exact same binding is
     * idempotent; changing an existing binding must use {@link #rebind}.
     */
    public void bind(String userId, String email) throws IOException {
        String safeUserId = canonicalUserId(userId);
        String normalizedEmail = normalizeEmail(email);
        lock.writeLock().lock();
        try {
            String existingEmail = emailByUserId.get(safeUserId);
            if (normalizedEmail.equals(existingEmail)) {
                return;
            }
            if (existingEmail != null) {
                throw new UserAlreadyBoundException();
            }
            requireEmailAvailable(normalizedEmail, safeUserId);
            if (emailByUserId.size() >= MAX_BINDINGS) {
                throw new IOException("Email binding storage limit has been reached");
            }
            emailByUserId.put(safeUserId, normalizedEmail);
            userIdByEmail.put(normalizedEmail, safeUserId);
            try {
                writeLocked();
            } catch (IOException | RuntimeException error) {
                emailByUserId.remove(safeUserId);
                userIdByEmail.remove(normalizedEmail);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Atomically replaces an existing binding without an unbound interval. */
    public void rebind(String userId, String newEmail) throws IOException {
        String safeUserId = canonicalUserId(userId);
        String normalizedEmail = normalizeEmail(newEmail);
        lock.writeLock().lock();
        try {
            String oldEmail = emailByUserId.get(safeUserId);
            if (oldEmail == null) {
                throw new EmailNotBoundException();
            }
            if (oldEmail.equals(normalizedEmail)) {
                return;
            }
            requireEmailAvailable(normalizedEmail, safeUserId);
            emailByUserId.put(safeUserId, normalizedEmail);
            userIdByEmail.remove(oldEmail);
            userIdByEmail.put(normalizedEmail, safeUserId);
            try {
                writeLocked();
            } catch (IOException | RuntimeException error) {
                userIdByEmail.remove(normalizedEmail);
                userIdByEmail.put(oldEmail, safeUserId);
                emailByUserId.put(safeUserId, oldEmail);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Removes the binding as part of permanent account deletion. */
    public void deleteUser(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        lock.writeLock().lock();
        try {
            String oldEmail = emailByUserId.remove(safeUserId);
            if (oldEmail == null) {
                return;
            }
            userIdByEmail.remove(oldEmail);
            try {
                writeLocked();
            } catch (IOException | RuntimeException error) {
                emailByUserId.put(safeUserId, oldEmail);
                userIdByEmail.put(oldEmail, safeUserId);
                throw error;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Normalizes a practical login address: NFKC input, ASCII dot-atom local
     * part, IDNA domain, and a lower-case full address for friendly uniqueness.
     */
    public static String normalizeEmail(String supplied) {
        if (supplied == null) {
            throw new ValidationException("email is required");
        }
        String value = Normalizer.normalize(supplied, Normalizer.Form.NFKC).strip();
        if (value.isEmpty() || value.length() > MAX_EMAIL_LENGTH) {
            throw new ValidationException("email must be between 1 and 254 characters");
        }
        int separator = value.lastIndexOf('@');
        if (separator <= 0 || separator != value.indexOf('@')
                || separator == value.length() - 1) {
            throw new ValidationException("email must contain one local part and domain");
        }
        String local = value.substring(0, separator);
        String suppliedDomain = value.substring(separator + 1);
        if (local.length() > MAX_LOCAL_PART_LENGTH || !isDotAtom(local)) {
            throw new ValidationException("email local part is invalid");
        }

        String domain;
        try {
            domain = IDN.toASCII(
                    suppliedDomain,
                    IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            throw new ValidationException("email domain is invalid", error);
        }
        if (domain.isEmpty() || domain.length() > MAX_DOMAIN_LENGTH
                || domain.endsWith(".") || !domain.contains(".")) {
            throw new ValidationException("email domain is invalid");
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                throw new ValidationException("email domain is invalid");
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!(character >= 'a' && character <= 'z')
                        && !(character >= '0' && character <= '9')
                        && character != '-') {
                    throw new ValidationException("email domain is invalid");
                }
            }
        }
        String normalized = local.toLowerCase(Locale.ROOT) + '@' + domain;
        if (normalized.length() > MAX_EMAIL_LENGTH) {
            throw new ValidationException("email is longer than 254 characters");
        }
        return normalized;
    }

    private static boolean isDotAtom(String local) {
        if (local.isEmpty() || local.charAt(0) == '.'
                || local.charAt(local.length() - 1) == '.' || local.contains("..")) {
            return false;
        }
        for (int index = 0; index < local.length(); index++) {
            char character = local.charAt(index);
            boolean alphaNumeric = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9';
            boolean special = "!#$%&'*+-/=?^_`{|}~.".indexOf(character) >= 0;
            if (!alphaNumeric && !special) {
                return false;
            }
        }
        return true;
    }

    private void load() throws IOException {
        lock.writeLock().lock();
        try {
            ensureSafeRoot();
            if (!Files.exists(emailFile, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            requireRegularFile(emailFile);
            byte[] encoded = readLimited(emailFile, MAX_FILE_BYTES);
            String json;
            try {
                json = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(encoded))
                        .toString();
            } catch (CharacterCodingException error) {
                throw new IOException("Email binding database is not valid UTF-8", error);
            }
            try {
                Map<String, Object> document = requireObject(
                        Json.parse(json), "email binding database");
                requireExactKeys(document, DOCUMENT_KEYS, "email binding database");
                if (!(document.get("version") instanceof BigDecimal version)
                        || version.intValueExact() != FILE_VERSION) {
                    throw new IllegalArgumentException(
                            "Unsupported email binding database version");
                }
                Map<String, Object> emails = requireObject(
                        document.get("emails"), "emails");
                if (emails.size() > MAX_BINDINGS) {
                    throw new IllegalArgumentException("Too many email bindings");
                }
                for (Map.Entry<String, Object> entry : emails.entrySet()) {
                    String userId = canonicalUserId(entry.getKey());
                    if (!(entry.getValue() instanceof String storedEmail)) {
                        throw new IllegalArgumentException(
                                "Stored email must be a string");
                    }
                    String normalizedEmail = normalizeEmail(storedEmail);
                    if (!storedEmail.equals(normalizedEmail)) {
                        throw new IllegalArgumentException(
                                "Stored email is not normalized");
                    }
                    emailByUserId.put(userId, normalizedEmail);
                    if (userIdByEmail.putIfAbsent(normalizedEmail, userId) != null) {
                        throw new IllegalArgumentException(
                                "An email is bound to more than one user");
                    }
                }
            } catch (IllegalArgumentException | ArithmeticException error) {
                emailByUserId.clear();
                userIdByEmail.clear();
                throw new IOException(
                        "Email binding database is invalid: " + error.getMessage(), error);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void writeLocked() throws IOException {
        ensureSafeRoot();
        if (Files.exists(emailFile, LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(emailFile);
        }
        Map<String, Object> emails = new LinkedHashMap<>();
        emailByUserId.forEach(emails::put);
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("version", FILE_VERSION);
        document.put("emails", emails);
        byte[] encoded = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
        if (encoded.length > MAX_FILE_BYTES) {
            throw new IOException("Email binding database is too large");
        }
        atomicWrite(emailFile, encoded);
    }

    private void requireEmailAvailable(String email, String requestedUserId) {
        String owner = userIdByEmail.get(email);
        if (owner != null && !owner.equals(requestedUserId)) {
            throw new EmailAlreadyBoundException();
        }
    }

    private void ensureSafeRoot() throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Email storage root is not a safe directory");
        }
    }

    private static Path safeChild(Path parent, String child) {
        Path resolved = parent.resolve(child).normalize();
        if (!resolved.getParent().equals(parent)) {
            throw new IllegalArgumentException("Unsafe email storage path");
        }
        return resolved;
    }

    private static String canonicalUserId(String userId) {
        if (userId == null) {
            throw new ValidationException("userId is required");
        }
        try {
            String canonical = UUID.fromString(userId).toString();
            if (!canonical.equals(userId)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new ValidationException("userId is invalid", error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(Object value, String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(label + " must be a JSON object");
        }
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(label + " has a non-string field");
            }
        }
        return (Map<String, Object>) raw;
    }

    private static void requireExactKeys(
            Map<String, Object> object, Set<String> keys, String label) {
        if (!object.keySet().equals(keys)) {
            throw new IllegalArgumentException(label + " fields are invalid");
        }
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Email binding database is not a regular file");
        }
    }

    private static byte[] readLimited(Path file, int limit) throws IOException {
        long size = Files.size(file);
        if (size > limit) {
            throw new IOException("Email binding database is too large");
        }
        byte[] content = Files.readAllBytes(file);
        if (content.length > limit) {
            throw new IOException("Email binding database is too large");
        }
        return content;
    }

    private static void atomicWrite(Path destination, byte[] content) throws IOException {
        Path parent = destination.getParent();
        Path temporary = Files.createTempFile(
                parent, "." + destination.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ValidationException(String message) {
            super(message);
        }

        ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class EmailAlreadyBoundException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        EmailAlreadyBoundException() {
            super("Email is already bound to another user");
        }
    }

    public static final class UserAlreadyBoundException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        UserAlreadyBoundException() {
            super("User already has an email binding");
        }
    }

    public static final class EmailNotBoundException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        EmailNotBoundException() {
            super("User does not have an email binding");
        }
    }
}
