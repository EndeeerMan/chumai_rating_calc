import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** Minimal dependency-free SMTP client used by email verification. */
public final class SmtpEmailSender
        implements EmailVerificationService.EmailSender, AutoCloseable {
    private static final int MAX_RESPONSE_LINE_BYTES = 2_048;
    private static final int MAX_RESPONSE_LINES = 100;

    private final EmailVerificationConfig config;

    public SmtpEmailSender(EmailVerificationConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    @Override
    public void sendVerificationCode(String recipient, String sixDigitCode)
            throws IOException {
        String safeRecipient = UserEmailStore.normalizeEmail(recipient);
        requireSixDigitCode(sixDigitCode);

        Socket connected = connect();
        try (SmtpSession session = new SmtpSession(connected)) {
            expect(session.readResponse(), "greeting", 220);
            SmtpResponse capabilities = ehlo(session);

            if (config.security() == EmailVerificationConfig.Security.STARTTLS) {
                if (!capabilities.supports("STARTTLS")) {
                    throw new IOException("SMTP server does not offer STARTTLS");
                }
                expect(session.command("STARTTLS"), "STARTTLS", 220);
                session.startTls(config.host(), config.port());
                capabilities = ehlo(session);
            }

            authenticate(session, capabilities);
            expect(
                    session.command("MAIL FROM:<" + config.from() + ">"),
                    "MAIL FROM",
                    250);
            expect(
                    session.command("RCPT TO:<" + safeRecipient + ">"),
                    "RCPT TO",
                    250,
                    251,
                    252);
            expect(session.command("DATA"), "DATA", 354);
            session.writeMessage(message(safeRecipient, sixDigitCode));
            expect(session.readResponse(), "message delivery", 250);
            try {
                expect(session.command("QUIT"), "QUIT", 221);
            } catch (IOException ignored) {
                // The message has already been accepted; QUIT is best-effort.
            }
        }
    }

    private Socket connect() throws IOException {
        Socket socket;
        if (config.security() == EmailVerificationConfig.Security.SSL) {
            socket = SSLSocketFactory.getDefault().createSocket();
        } else {
            socket = new Socket();
        }
        boolean ready = false;
        try {
            socket.connect(
                    new InetSocketAddress(config.host(), config.port()),
                    config.connectTimeoutMillis());
            socket.setSoTimeout(config.readTimeoutMillis());
            if (socket instanceof SSLSocket sslSocket) {
                configureAndHandshake(sslSocket);
            }
            ready = true;
            return socket;
        } finally {
            if (!ready) {
                socket.close();
            }
        }
    }

    private SmtpResponse ehlo(SmtpSession session) throws IOException {
        SmtpResponse response = session.command("EHLO " + config.ehloDomain());
        expect(response, "EHLO", 250);
        return response;
    }

    private void authenticate(SmtpSession session, SmtpResponse capabilities)
            throws IOException {
        if (capabilities.supportsAuth("PLAIN")) {
            authenticatePlain(session);
            return;
        }
        if (capabilities.supportsAuth("LOGIN")) {
            authenticateLogin(session);
            return;
        }
        throw new IOException("SMTP server does not offer AUTH PLAIN or AUTH LOGIN");
    }

    private void authenticatePlain(SmtpSession session) throws IOException {
        byte[] username = config.username().getBytes(StandardCharsets.UTF_8);
        char[] passwordCharacters = config.passwordCopy();
        byte[] password = utf8(passwordCharacters);
        byte[] authentication = new byte[username.length + password.length + 2];
        System.arraycopy(username, 0, authentication, 1, username.length);
        System.arraycopy(
                password,
                0,
                authentication,
                username.length + 2,
                password.length);
        String encoded = Base64.getEncoder().encodeToString(authentication);
        try {
            SmtpResponse response = session.command("AUTH PLAIN " + encoded);
            if (response.code() == 334) {
                response = session.command(encoded);
            }
            expect(response, "authentication", 235);
        } finally {
            Arrays.fill(username, (byte) 0);
            Arrays.fill(passwordCharacters, '\0');
            Arrays.fill(password, (byte) 0);
            Arrays.fill(authentication, (byte) 0);
        }
    }

    private void authenticateLogin(SmtpSession session) throws IOException {
        byte[] username = config.username().getBytes(StandardCharsets.UTF_8);
        char[] passwordCharacters = config.passwordCopy();
        byte[] password = utf8(passwordCharacters);
        try {
            expect(session.command("AUTH LOGIN"), "AUTH LOGIN", 334);
            expect(
                    session.command(Base64.getEncoder().encodeToString(username)),
                    "SMTP username",
                    334);
            expect(
                    session.command(Base64.getEncoder().encodeToString(password)),
                    "SMTP password",
                    235);
        } finally {
            Arrays.fill(username, (byte) 0);
            Arrays.fill(passwordCharacters, '\0');
            Arrays.fill(password, (byte) 0);
        }
    }

    private String message(String recipient, String code) {
        String subject = encodedWord("舞萌/中二成绩管理站台邮箱验证码");
        String body = "您的验证码是：" + code + "\r\n\r\n"
                + "验证码 10 分钟内有效，请勿转发给他人。\r\n"
                + "如果这不是您的操作，请忽略此邮件。\r\n";
        String encodedBody = Base64.getMimeEncoder(
                        76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(body.getBytes(StandardCharsets.UTF_8));
        return "Date: "
                + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())
                + "\r\n"
                + "Message-ID: <" + UUID.randomUUID() + "@" + config.ehloDomain() + ">\r\n"
                + "From: " + (config.fromName().isEmpty()
                        ? ""
                        : encodedWord(config.fromName()) + " ")
                + "<" + config.from() + ">\r\n"
                + "To: <" + recipient + ">\r\n"
                + "Subject: " + subject + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "Auto-Submitted: auto-generated\r\n"
                + "\r\n"
                + encodedBody
                + "\r\n";
    }

    private static String encodedWord(String value) {
        return "=?UTF-8?B?"
                + Base64.getEncoder().encodeToString(
                        value.getBytes(StandardCharsets.UTF_8))
                + "?=";
    }

    private static byte[] utf8(char[] characters) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(characters));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return bytes;
    }

    private static void requireSixDigitCode(String code) {
        if (code == null || code.length() != EmailVerificationService.CODE_DIGITS) {
            throw new IllegalArgumentException("Verification code must be six digits");
        }
        for (int index = 0; index < code.length(); index++) {
            char character = code.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException("Verification code must be six digits");
            }
        }
    }

    private static void configureAndHandshake(SSLSocket socket) throws IOException {
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.startHandshake();
    }

    private static void expect(
            SmtpResponse response, String operation, int... acceptedCodes)
            throws IOException {
        for (int acceptedCode : acceptedCodes) {
            if (response.code() == acceptedCode) {
                return;
            }
        }
        throw new IOException(
                "SMTP " + operation + " failed with status " + response.code());
    }

    @Override
    public void close() {
        config.close();
    }

    private static final class SmtpSession implements Closeable {
        private Socket socket;
        private InputStream input;
        private OutputStream output;

        SmtpSession(Socket socket) throws IOException {
            this.socket = socket;
            resetStreams();
        }

        SmtpResponse command(String command) throws IOException {
            writeAscii(command + "\r\n");
            output.flush();
            return readResponse();
        }

        void writeMessage(String message) throws IOException {
            writeAscii(message);
            writeAscii(".\r\n");
            output.flush();
        }

        void startTls(String host, int port) throws IOException {
            SSLSocketFactory factory =
                    (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket sslSocket =
                    (SSLSocket) factory.createSocket(socket, host, port, true);
            configureAndHandshake(sslSocket);
            socket = sslSocket;
            resetStreams();
        }

        SmtpResponse readResponse() throws IOException {
            List<String> lines = new ArrayList<>();
            String first = readAsciiLine();
            int code = parseStatus(first);
            lines.add(first);
            boolean continued = first.length() >= 4 && first.charAt(3) == '-';
            while (continued) {
                if (lines.size() >= MAX_RESPONSE_LINES) {
                    throw new IOException("SMTP response has too many lines");
                }
                String line = readAsciiLine();
                if (parseStatus(line) != code) {
                    throw new IOException("SMTP response status changed mid-response");
                }
                lines.add(line);
                continued = line.length() >= 4 && line.charAt(3) == '-';
            }
            return new SmtpResponse(code, List.copyOf(lines));
        }

        private void resetStreams() throws IOException {
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
        }

        private String readAsciiLine() throws IOException {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            boolean carriageReturn = false;
            while (line.size() <= MAX_RESPONSE_LINE_BYTES) {
                int value = input.read();
                if (value < 0) {
                    throw new IOException("SMTP server closed the connection");
                }
                if (carriageReturn) {
                    if (value == '\n') {
                        return line.toString(StandardCharsets.US_ASCII);
                    }
                    line.write('\r');
                    carriageReturn = false;
                }
                if (value == '\r') {
                    carriageReturn = true;
                } else if (value == '\n') {
                    return line.toString(StandardCharsets.US_ASCII);
                } else if (value > 0x7f) {
                    throw new IOException("SMTP response is not ASCII");
                } else {
                    line.write(value);
                }
            }
            throw new IOException("SMTP response line is too long");
        }

        private void writeAscii(String value) throws IOException {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    private record SmtpResponse(int code, List<String> lines) {
        boolean supports(String capability) {
            String expected = capability.toUpperCase(Locale.ROOT);
            for (String line : lines) {
                String value = line.length() > 4
                        ? line.substring(4).strip().toUpperCase(Locale.ROOT)
                        : "";
                if (value.equals(expected) || value.startsWith(expected + " ")) {
                    return true;
                }
            }
            return false;
        }

        boolean supportsAuth(String mechanism) {
            String expected = mechanism.toUpperCase(Locale.ROOT);
            for (String line : lines) {
                String value = line.length() > 4
                        ? line.substring(4).strip().toUpperCase(Locale.ROOT)
                        : "";
                if (value.startsWith("AUTH=")) {
                    value = "AUTH " + value.substring("AUTH=".length());
                }
                if (!value.startsWith("AUTH ")) {
                    continue;
                }
                for (String token : value.substring(5).split("\\s+")) {
                    if (token.equals(expected)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static int parseStatus(String line) throws IOException {
        if (line.length() < 3
                || line.charAt(0) < '0' || line.charAt(0) > '9'
                || line.charAt(1) < '0' || line.charAt(1) > '9'
                || line.charAt(2) < '0' || line.charAt(2) > '9'
                || line.length() > 3
                        && line.charAt(3) != ' ' && line.charAt(3) != '-') {
            throw new IOException("SMTP response has an invalid status line");
        }
        return (line.charAt(0) - '0') * 100
                + (line.charAt(1) - '0') * 10
                + line.charAt(2) - '0';
    }
}
