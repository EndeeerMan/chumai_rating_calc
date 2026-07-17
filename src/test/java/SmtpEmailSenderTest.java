import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free configuration and local SMTP protocol tests. */
public final class SmtpEmailSenderTest {
    private static int tests;

    private SmtpEmailSenderTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-smtp-test-");
        try {
            testOptionalConfiguration(temporary.resolve("optional"));
            testConfigurationParsing(temporary.resolve("valid"));
            testInvalidConfigurations(temporary.resolve("invalid"));
            testPlainSmtpDelivery(temporary.resolve("smtp"));
            System.out.println("SmtpEmailSenderTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testOptionalConfiguration(Path root) throws Exception {
        Files.createDirectories(root);
        expect(Optional.empty(), EmailVerificationConfig.loadIfConfigured(root),
                "missing env file disables email without blocking startup");

        Files.writeString(
                root.resolve(EmailVerificationConfig.FILE_NAME),
                "# Fill these values to enable verification\n"
                        + "SMTP_HOST=\n"
                        + "SMTP_PORT=587\n"
                        + "SMTP_USERNAME=\n"
                        + "SMTP_PASSWORD=\n"
                        + "SMTP_FROM=\n"
                        + "SMTP_FROM_NAME=B50 工作台\n"
                        + "SMTP_SECURITY=STARTTLS\n",
                StandardCharsets.UTF_8);
        expect(Optional.empty(), EmailVerificationConfig.loadIfConfigured(root),
                "all-empty required template disables email even with a display name");
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "required loader clearly reports an unconfigured template");

        Files.writeString(
                root.resolve(EmailVerificationConfig.FILE_NAME),
                "SMTP_HSOT=\nSMTP_PORT=587\nSMTP_SECURITY=STARTTLS\n",
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.loadIfConfigured(root),
                "unknown key is not hidden by disabled-template detection");

        Files.delete(root.resolve(EmailVerificationConfig.FILE_NAME));
        Files.writeString(root.resolve("verification_email.env"), "SMTP_HOST=wrong-name");
        expect(Optional.empty(), EmailVerificationConfig.loadIfConfigured(root),
                "only the exact verifycaton_email.env filename is read");
    }

    private static void testConfigurationParsing(Path root) throws Exception {
        Files.createDirectories(root);
        Files.writeString(
                root.resolve(EmailVerificationConfig.FILE_NAME),
                "\ufeff# Third-party SMTP\n"
                        + "export SMTP_HOST=smtp.example.com\n"
                        + "SMTP_PORT=587\n"
                        + "SMTP_USERNAME=mail-user\n"
                        + "SMTP_PASSWORD='very secret # = value'\n"
                        + "SMTP_FROM=Sender@EXAMPLE.COM\n"
                        + "SMTP_FROM_NAME= Ｂ５０ 工作台 \n"
                        + "SMTP_SECURITY=starttls\n"
                        + "SMTP_CONNECT_TIMEOUT_MS=3456\n"
                        + "SMTP_READ_TIMEOUT_MS=7890\n"
                        + "SMTP_EHLO_DOMAIN=b50.local\n",
                StandardCharsets.UTF_8);
        EmailVerificationConfig config = EmailVerificationConfig.load(root);
        try {
            expect("smtp.example.com", config.host(), "SMTP host is loaded");
            expect(587, config.port(), "SMTP port is loaded");
            expect("mail-user", config.username(), "SMTP username is loaded");
            expect("sender@example.com", config.from(),
                    "envelope sender is normalized case-insensitively");
            expect("B50 工作台", config.fromName(),
                    "Unicode MIME display name is normalized");
            expect(EmailVerificationConfig.Security.STARTTLS, config.security(),
                    "security mode is case-insensitive");
            expect(3456, config.connectTimeoutMillis(),
                    "custom connection timeout is loaded");
            expect(7890, config.readTimeoutMillis(),
                    "custom read timeout is loaded");
            expect("b50.local", config.ehloDomain(), "custom EHLO domain is loaded");
            expect(true, Arrays.equals(
                            "very secret # = value".toCharArray(),
                            config.passwordCopy()),
                    "quoted password preserves spaces, hash, and equals signs");
            expect(false, config.toString().contains("very secret"),
                    "configuration string never exposes the SMTP password");
            expect(true, config.toString().contains("<redacted>"),
                    "configuration string marks password as redacted");
        } finally {
            config.close();
        }
        char[] erased = config.passwordCopy();
        try {
            expect(true, allZero(erased), "closing configuration erases retained password");
        } finally {
            Arrays.fill(erased, '\0');
        }
    }

    private static void testInvalidConfigurations(Path root) throws Exception {
        Files.createDirectories(root);
        Path file = root.resolve(EmailVerificationConfig.FILE_NAME);
        String base = "SMTP_HOST=smtp.example.com\n"
                + "SMTP_PORT=587\n"
                + "SMTP_USERNAME=user\n"
                + "SMTP_PASSWORD=secret\n"
                + "SMTP_FROM=sender@example.com\n"
                + "SMTP_SECURITY=STARTTLS\n";

        Files.writeString(file, base + "SMTP_UNKNOWN=value\n", StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "unknown key is rejected to catch configuration typos");
        Files.writeString(file, base + "SMTP_PORT=465\n", StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "duplicate keys are rejected");
        Files.writeString(file, base.replace("SMTP_PASSWORD=secret\n", ""),
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "missing password is rejected once configuration is enabled");
        Files.writeString(file, base.replace("SMTP_PORT=587", "SMTP_PORT=70000"),
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "out-of-range SMTP port is rejected");
        Files.writeString(file, base.replace("STARTTLS", "opportunistic"),
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "unknown transport security is rejected");
        Files.writeString(file, base.replace("STARTTLS", "PLAIN"),
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "unencrypted SMTP is rejected for a third-party host");
        Files.writeString(file,
                base + "SMTP_FROM_NAME=\"bad\\nheader\"\n",
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "header control character from escaped env value is rejected");
        Files.writeString(file,
                base + "SMTP_EHLO_DOMAIN=user@example.com\n",
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "EHLO domain cannot inject a Message-ID address separator");
        Files.writeString(file,
                base.replace("smtp.example.com", "smtp.example.com<bad>"),
                StandardCharsets.UTF_8);
        expectThrows(EmailVerificationConfig.ConfigurationException.class,
                () -> EmailVerificationConfig.load(root),
                "SMTP host accepts only an ASCII host name or IP address");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        expectThrows(IOException.class,
                () -> EmailVerificationConfig.load(root),
                "malformed env UTF-8 is rejected");
        Files.delete(file);
        Files.createDirectory(file);
        expectThrows(IOException.class,
                () -> EmailVerificationConfig.loadIfConfigured(root),
                "env path must be a regular file");
    }

    private static void testPlainSmtpDelivery(Path root) throws Exception {
        Files.createDirectories(root);
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        AtomicReference<String> message = new AtomicReference<>();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(5_000);
            Thread mock = Thread.ofPlatform().name("mock-smtp").start(() -> {
                try {
                    runMockServer(server, message);
                } catch (Throwable error) {
                    serverFailure.set(error);
                }
            });
            Files.writeString(
                    root.resolve(EmailVerificationConfig.FILE_NAME),
                    "SMTP_HOST=127.0.0.1\n"
                            + "SMTP_PORT=" + server.getLocalPort() + "\n"
                            + "SMTP_USERNAME=smtp-user\n"
                            + "SMTP_PASSWORD='s3cr#t='\n"
                            + "SMTP_FROM=sender@example.com\n"
                            + "SMTP_FROM_NAME=B50 工作台\n"
                            + "SMTP_SECURITY=PLAIN\n"
                            + "SMTP_CONNECT_TIMEOUT_MS=2000\n"
                            + "SMTP_READ_TIMEOUT_MS=2000\n"
                            + "SMTP_EHLO_DOMAIN=test.local\n",
                    StandardCharsets.UTF_8);
            try (SmtpEmailSender sender = new SmtpEmailSender(
                    EmailVerificationConfig.load(root))) {
                sender.sendVerificationCode("PLAYER@EXAMPLE.COM", "042731");
            }
            mock.join(5_000);
            expect(false, mock.isAlive(), "mock SMTP exchange completes without hanging");
            if (serverFailure.get() != null) {
                throw new AssertionError("mock SMTP server failed", serverFailure.get());
            }
        }

        String delivered = message.get();
        expect(true, delivered.contains("To: <player@example.com>\r\n"),
                "SMTP message canonicalizes recipient case");
        expect(true, delivered.contains(
                        "From: =?UTF-8?B?QjUwIOW3peS9nOWPsA==?= <sender@example.com>\r\n"),
                "SMTP message MIME-encodes the configured Chinese display name");
        expect(true, delivered.contains("Subject: =?UTF-8?B?"),
                "SMTP message uses an encoded UTF-8 subject");
        String encodedBody = delivered.substring(delivered.indexOf("\r\n\r\n") + 4)
                .strip();
        String decodedBody = new String(
                Base64.getMimeDecoder().decode(encodedBody), StandardCharsets.UTF_8);
        expect(true, decodedBody.contains("042731"),
                "delivered MIME body contains the six-digit code");
        expect(true, decodedBody.contains("10 分钟"),
                "delivered body states the ten-minute validity");
    }

    private static void runMockServer(
            ServerSocket server, AtomicReference<String> message) throws Exception {
        try (Socket socket = server.accept();
             BufferedReader input = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.US_ASCII));
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.US_ASCII))) {
            socket.setSoTimeout(3_000);
            reply(output, "220 mock.local ESMTP ready\r\n");
            expect("EHLO test.local", input.readLine(), "client sends EHLO");
            reply(output,
                    "250-mock.local\r\n"
                            + "250-AUTH PLAIN LOGIN\r\n"
                            + "250 SIZE 100000\r\n");

            String auth = input.readLine();
            expect(true, auth != null && auth.startsWith("AUTH PLAIN "),
                    "client chooses advertised AUTH PLAIN");
            byte[] credentials = Base64.getDecoder().decode(auth.substring(11));
            try {
                expect("\0smtp-user\0s3cr#t=",
                        new String(credentials, StandardCharsets.UTF_8),
                        "SMTP credentials are correctly encoded");
            } finally {
                Arrays.fill(credentials, (byte) 0);
            }
            reply(output, "235 2.7.0 authentication successful\r\n");
            expect("MAIL FROM:<sender@example.com>", input.readLine(),
                    "client sends normalized envelope sender");
            reply(output, "250 sender accepted\r\n");
            expect("RCPT TO:<player@example.com>", input.readLine(),
                    "client sends normalized envelope recipient");
            reply(output, "250 recipient accepted\r\n");
            expect("DATA", input.readLine(), "client enters DATA mode");
            reply(output, "354 end with dot\r\n");
            StringBuilder data = new StringBuilder();
            while (true) {
                String line = input.readLine();
                if (line == null) {
                    throw new IOException("client disconnected during DATA");
                }
                if (line.equals(".")) {
                    break;
                }
                data.append(line).append("\r\n");
            }
            message.set(data.toString());
            reply(output, "250 message accepted\r\n");
            expect("QUIT", input.readLine(), "client cleanly quits SMTP session");
            reply(output, "221 bye\r\n");
        }
    }

    private static void reply(BufferedWriter output, String response) throws IOException {
        output.write(response);
        output.flush();
    }

    private static boolean allZero(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> type, ThrowingAction action, String label) {
        tests++;
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return type.cast(error);
            }
            throw new AssertionError(
                    label + ": expected " + type.getSimpleName() + ", got " + error,
                    error);
        }
        throw new AssertionError(label + ": expected " + type.getSimpleName());
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
