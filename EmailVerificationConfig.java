import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Loads SMTP settings from the project-local {@code verifycaton_email.env}. */
public final class EmailVerificationConfig implements AutoCloseable {
    public static final String FILE_NAME = "verifycaton_email.env";

    private static final int MAX_FILE_BYTES = 64 * 1024;
    private static final Set<String> REQUIRED_KEYS = Set.of(
            "SMTP_HOST",
            "SMTP_PORT",
            "SMTP_USERNAME",
            "SMTP_PASSWORD",
            "SMTP_FROM",
            "SMTP_SECURITY");
    private static final Set<String> ACTIVATION_KEYS = Set.of(
            "SMTP_HOST", "SMTP_USERNAME", "SMTP_PASSWORD", "SMTP_FROM");
    private static final Set<String> OPTIONAL_KEYS = Set.of(
            "SMTP_CONNECT_TIMEOUT_MS",
            "SMTP_READ_TIMEOUT_MS",
            "SMTP_EHLO_DOMAIN",
            "SMTP_FROM_NAME");

    private final String host;
    private final int port;
    private final String username;
    private final char[] password;
    private final String from;
    private final String fromName;
    private final Security security;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final String ehloDomain;

    private EmailVerificationConfig(
            String host,
            int port,
            String username,
            char[] password,
            String from,
            String fromName,
            Security security,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            String ehloDomain) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password.clone();
        this.from = from;
        this.fromName = fromName;
        this.security = security;
        this.connectTimeoutMillis = connectTimeoutMillis;
        this.readTimeoutMillis = readTimeoutMillis;
        this.ehloDomain = ehloDomain;
    }

    public static EmailVerificationConfig load(Path projectRoot) throws IOException {
        return loadIfConfigured(projectRoot).orElseThrow(
                () -> new ConfigurationException(
                        FILE_NAME + " does not contain an SMTP configuration"));
    }

    /**
     * Returns empty when the file is absent or is still an all-empty template.
     * This lets the web application start while its email endpoint reports 503.
     */
    public static Optional<EmailVerificationConfig> loadIfConfigured(Path projectRoot)
            throws IOException {
        Path root = Objects.requireNonNull(projectRoot, "projectRoot must not be null")
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Project root is not a safe directory");
        }
        Path file = root.resolve(FILE_NAME).normalize();
        if (!file.getParent().equals(root)) {
            throw new IOException("Unsafe email configuration path");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(FILE_NAME + " is not a regular file");
        }
        byte[] bytes = readLimited(file);
        String contents;
        try {
            contents = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException(FILE_NAME + " is not valid UTF-8", error);
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
        if (!contents.isEmpty() && contents.charAt(0) == '\ufeff') {
            contents = contents.substring(1);
        }

        Map<String, String> values = parse(contents);
        for (String key : values.keySet()) {
            if (!REQUIRED_KEYS.contains(key) && !OPTIONAL_KEYS.contains(key)) {
                throw new ConfigurationException("Unknown email configuration key: " + key);
            }
        }
        boolean hasRequiredValue = ACTIVATION_KEYS.stream()
                .map(values::get)
                .anyMatch(value -> value != null && !value.isBlank());
        if (!hasRequiredValue) {
            values.clear();
            return Optional.empty();
        }
        for (String required : REQUIRED_KEYS) {
            if (!values.containsKey(required)) {
                throw new ConfigurationException(required + " is required");
            }
        }

        String host = normalizeSmtpHost(values.get("SMTP_HOST"));
        int port = parseInteger(values.get("SMTP_PORT"), "SMTP_PORT", 1, 65_535);
        String username = requireValue(
                values.get("SMTP_USERNAME"), "SMTP_USERNAME", 320);
        char[] password = requireValue(
                values.get("SMTP_PASSWORD"), "SMTP_PASSWORD", 4096).toCharArray();
        String from = UserEmailStore.normalizeEmail(values.get("SMTP_FROM"));
        String fromName = values.containsKey("SMTP_FROM_NAME")
                ? normalizeHeaderText(values.get("SMTP_FROM_NAME"), "SMTP_FROM_NAME", 128)
                : "B50 工作台";
        Security security = Security.parse(values.get("SMTP_SECURITY"));
        if (security == Security.PLAIN && !isLoopbackHost(host)) {
            throw new ConfigurationException(
                    "SMTP_SECURITY=PLAIN is allowed only for a local SMTP server; "
                            + "third-party SMTP must use STARTTLS or SSL");
        }
        int connectTimeout = optionalInteger(
                values,
                "SMTP_CONNECT_TIMEOUT_MS",
                10_000,
                1_000,
                60_000);
        int readTimeout = optionalInteger(
                values,
                "SMTP_READ_TIMEOUT_MS",
                20_000,
                1_000,
                120_000);
        String ehlo = values.containsKey("SMTP_EHLO_DOMAIN")
                ? normalizeEhloDomain(values.get("SMTP_EHLO_DOMAIN"))
                : "localhost";
        try {
            return Optional.of(new EmailVerificationConfig(
                    host, port, username, password, from, fromName, security,
                    connectTimeout, readTimeout, ehlo));
        } finally {
            Arrays.fill(password, '\0');
            values.clear();
        }
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
    }

    public String username() {
        return username;
    }

    public String from() {
        return from;
    }

    public String fromName() {
        return fromName;
    }

    public Security security() {
        return security;
    }

    public int connectTimeoutMillis() {
        return connectTimeoutMillis;
    }

    public int readTimeoutMillis() {
        return readTimeoutMillis;
    }

    public String ehloDomain() {
        return ehloDomain;
    }

    char[] passwordCopy() {
        return password.clone();
    }

    /** Explicitly erases the retained password when the mail sender is retired. */
    @Override
    public void close() {
        Arrays.fill(password, '\0');
    }

    @Override
    public String toString() {
        return "EmailVerificationConfig[host=" + host
                + ", port=" + port
                + ", username=" + username
                + ", password=<redacted>"
                + ", from=" + from
                + ", fromName=" + fromName
                + ", security=" + security
                + ", connectTimeoutMillis=" + connectTimeoutMillis
                + ", readTimeoutMillis=" + readTimeoutMillis
                + ", ehloDomain=" + ehloDomain + ']';
    }

    private static byte[] readLimited(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException(FILE_NAME + " is too large");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > MAX_FILE_BYTES) {
            Arrays.fill(bytes, (byte) 0);
            throw new IOException(FILE_NAME + " is too large");
        }
        return bytes;
    }

    private static Map<String, String> parse(String contents) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = contents.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index].strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).stripLeading();
            }
            int separator = line.indexOf('=');
            if (separator < 1) {
                throw new ConfigurationException(
                        "Invalid email configuration line " + (index + 1));
            }
            String key = line.substring(0, separator).strip();
            if (!key.matches("[A-Z][A-Z0-9_]*")) {
                throw new ConfigurationException(
                        "Invalid email configuration key on line " + (index + 1));
            }
            String value = decodeValue(
                    line.substring(separator + 1).strip(), index + 1);
            if (values.putIfAbsent(key, value) != null) {
                throw new ConfigurationException(
                        "Duplicate email configuration key: " + key);
            }
        }
        return values;
    }

    private static String decodeValue(String value, int lineNumber) {
        if (value.length() < 2) {
            return value;
        }
        char quote = value.charAt(0);
        if (quote != '\'' && quote != '"') {
            return value;
        }
        if (value.charAt(value.length() - 1) != quote) {
            throw new ConfigurationException(
                    "Unclosed quote on email configuration line " + lineNumber);
        }
        String body = value.substring(1, value.length() - 1);
        if (quote == '\'') {
            return body;
        }
        StringBuilder decoded = new StringBuilder(body.length());
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character != '\\') {
                decoded.append(character);
                continue;
            }
            if (++index >= body.length()) {
                throw new ConfigurationException(
                        "Invalid escape on email configuration line " + lineNumber);
            }
            char escaped = body.charAt(index);
            decoded.append(switch (escaped) {
                case '\\' -> '\\';
                case '"' -> '"';
                case 'n' -> '\n';
                case 'r' -> '\r';
                case 't' -> '\t';
                default -> throw new ConfigurationException(
                        "Invalid escape on email configuration line " + lineNumber);
            });
        }
        return decoded.toString();
    }

    private static String requireValue(String value, String key, int maxLength) {
        if (value == null || value.isEmpty() || value.length() > maxLength) {
            throw new ConfigurationException(
                    key + " must be between 1 and " + maxLength + " characters");
        }
        return value;
    }

    private static String requireToken(String value, String key, int maxLength) {
        String result = requireValue(value, key, maxLength);
        for (int index = 0; index < result.length(); index++) {
            char character = result.charAt(index);
            if (Character.isWhitespace(character) || Character.isISOControl(character)) {
                throw new ConfigurationException(key + " contains invalid characters");
            }
        }
        return result;
    }

    private static String normalizeHeaderText(
            String value, String key, int maxCodePoints) {
        if (value == null) {
            return "";
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).strip();
        if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
            throw new ConfigurationException(key + " is too long");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new ConfigurationException(key + " contains invalid characters");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private static String normalizeSmtpHost(String value) {
        String host = requireToken(value, "SMTP_HOST", 253);
        for (int index = 0; index < host.length(); index++) {
            char character = host.charAt(index);
            boolean allowed = character >= 'A' && character <= 'Z'
                    || character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || ".:-[]%".indexOf(character) >= 0;
            if (!allowed) {
                throw new ConfigurationException(
                        "SMTP_HOST must be an ASCII host name or IP address");
            }
        }
        return host;
    }

    private static String normalizeEhloDomain(String value) {
        String domain = requireToken(value, "SMTP_EHLO_DOMAIN", 253)
                .toLowerCase(Locale.ROOT);
        if (domain.startsWith("[") && domain.endsWith("]")) {
            String literal = domain.substring(1, domain.length() - 1);
            if (literal.startsWith("ipv6:")) {
                literal = literal.substring("ipv6:".length());
            }
            if (literal.isEmpty() || literal.indexOf(':') < 0
                    && literal.indexOf('.') < 0) {
                throw new ConfigurationException(
                        "SMTP_EHLO_DOMAIN address literal is invalid");
            }
            for (int index = 0; index < literal.length(); index++) {
                char character = literal.charAt(index);
                boolean allowed = character >= 'a' && character <= 'f'
                        || character >= '0' && character <= '9'
                        || character == ':' || character == '.';
                if (!allowed) {
                    throw new ConfigurationException(
                            "SMTP_EHLO_DOMAIN address literal is invalid");
                }
            }
            return domain;
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-'
                    || label.charAt(label.length() - 1) == '-') {
                throw new ConfigurationException(
                        "SMTP_EHLO_DOMAIN must be an ASCII DNS name");
            }
            for (int index = 0; index < label.length(); index++) {
                char character = label.charAt(index);
                if (!(character >= 'a' && character <= 'z')
                        && !(character >= '0' && character <= '9')
                        && character != '-') {
                    throw new ConfigurationException(
                            "SMTP_EHLO_DOMAIN must be an ASCII DNS name");
                }
            }
        }
        return domain;
    }

    private static boolean isLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static int optionalInteger(
            Map<String, String> values,
            String key,
            int fallback,
            int minimum,
            int maximum) {
        return values.containsKey(key)
                ? parseInteger(values.get(key), key, minimum, maximum)
                : fallback;
    }

    private static int parseInteger(
            String value, String key, int minimum, int maximum) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException("out of range");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new ConfigurationException(
                    key + " must be an integer between " + minimum
                            + " and " + maximum,
                    error);
        }
    }

    public enum Security {
        STARTTLS,
        SSL,
        PLAIN;

        static Security parse(String supplied) {
            if (supplied == null) {
                throw new ConfigurationException("SMTP_SECURITY is required");
            }
            try {
                return valueOf(supplied.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException error) {
                throw new ConfigurationException(
                        "SMTP_SECURITY must be STARTTLS, SSL, or PLAIN", error);
            }
        }
    }

    public static final class ConfigurationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        ConfigurationException(String message) {
            super(message);
        }

        ConfigurationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
