import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

/** Durable per-user display names and private profile media. */
public final class UserProfileStore {
    public static final int MAX_AVATAR_UPLOAD_BYTES = 5 * 1024 * 1024;
    public static final int MAX_BACKGROUND_UPLOAD_BYTES = 12 * 1024 * 1024;

    private static final int PROFILE_VERSION = 1;
    private static final int MAX_PROFILE_BYTES = 16 * 1024;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 32;
    private static final int AVATAR_SIZE = 512;
    private static final int MAX_AVATAR_EDGE = 4_096;
    private static final long MAX_AVATAR_PIXELS = 8_000_000L;
    private static final int MAX_BACKGROUND_WIDTH = 2_560;
    private static final int MAX_BACKGROUND_HEIGHT = 1_440;
    private static final int MAX_BACKGROUND_SOURCE_EDGE = 8_192;
    private static final long MAX_BACKGROUND_PIXELS = 24_000_000L;
    private static final float BACKGROUND_JPEG_QUALITY = 0.88f;
    private static final Set<String> PROFILE_KEYS = Set.of("version", "displayName");
    private static final byte[] PNG_SIGNATURE = new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final byte[] PNG_IEND = new byte[]{
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4e, 0x44,
            (byte) 0xae, 0x42, 0x60, (byte) 0x82};

    private final Path root;
    private final ConcurrentHashMap<String, ReentrantReadWriteLock> userLocks =
            new ConcurrentHashMap<>();

    /** Games that may have independent user-selected page backgrounds. */
    public enum BackgroundGame {
        MAIMAI,
        CHUNITHM
    }

    public UserProfileStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root must not be null")
                .toAbsolutePath().normalize();
        Files.createDirectories(this.root);
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Profile storage root is not a directory");
        }
    }

    public Path root() {
        return root;
    }

    /** Loads profile metadata, falling back to the account username for old users. */
    public Profile loadProfile(String userId, String username) throws IOException {
        String safeUserId = canonicalUserId(userId);
        String safeUsername = requireUsername(username);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.readLock().lock();
        try {
            Path directory = profileDirectory(safeUserId);
            requireSafeDirectoryIfPresent(directory);
            String displayName = loadDisplayNameLocked(directory, safeUsername);
            boolean hasMaimaiBackground = hasMaimaiBackground(directory);
            return new Profile(
                    safeUserId,
                    safeUsername,
                    displayName,
                    regularFileExists(avatarFile(directory)),
                    hasMaimaiBackground,
                    regularFileExists(backgroundFile(directory, BackgroundGame.CHUNITHM)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Updates only the mutable display name; the login username stays unchanged. */
    public Profile updateDisplayName(
            String userId, String username, String suppliedDisplayName) throws IOException {
        String safeUserId = canonicalUserId(userId);
        String safeUsername = requireUsername(username);
        String displayName = normalizeDisplayName(suppliedDisplayName);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Path directory = ensureProfileDirectory(safeUserId);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("version", PROFILE_VERSION);
            document.put("displayName", displayName);
            byte[] encoded = Json.stringify(document).getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_PROFILE_BYTES) {
                throw new IOException("Profile metadata is too large");
            }
            atomicWrite(profileFile(directory), encoded);
            boolean hasMaimaiBackground = hasMaimaiBackground(directory);
            return new Profile(
                    safeUserId,
                    safeUsername,
                    displayName,
                    regularFileExists(avatarFile(directory)),
                    hasMaimaiBackground,
                    regularFileExists(backgroundFile(directory, BackgroundGame.CHUNITHM)));
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<ImageData> loadAvatar(String userId) throws IOException {
        return loadImage(userId, MediaSlot.AVATAR);
    }

    /** Compatibility overload: the former single background is the maimai background. */
    public Optional<ImageData> loadBackground(String userId) throws IOException {
        return loadBackground(userId, BackgroundGame.MAIMAI);
    }

    public Optional<ImageData> loadBackground(String userId, BackgroundGame game)
            throws IOException {
        BackgroundGame safeGame = Objects.requireNonNull(game, "game must not be null");
        MediaSlot primary = backgroundSlot(safeGame);
        return safeGame == BackgroundGame.MAIMAI
                ? loadImage(userId, primary, MediaSlot.LEGACY_MAIMAI_BACKGROUND)
                : loadImage(userId, primary);
    }

    /** Validates and stores a center-cropped 512 by 512 PNG avatar. */
    public void saveAvatar(String userId, String contentType, byte[] content)
            throws IOException {
        String safeUserId = canonicalUserId(userId);
        byte[] normalized = normalizeUpload(
                contentType,
                content,
                MAX_AVATAR_UPLOAD_BYTES,
                MAX_AVATAR_EDGE,
                MAX_AVATAR_PIXELS,
                MediaSlot.AVATAR);
        saveImage(safeUserId, MediaSlot.AVATAR, normalized);
    }

    /** Compatibility overload: stores the former single background for maimai. */
    public void saveBackground(String userId, String contentType, byte[] content)
            throws IOException {
        saveBackground(userId, BackgroundGame.MAIMAI, contentType, content);
    }

    /** Validates and stores a bounded RGB JPEG background for one game. */
    public void saveBackground(
            String userId,
            BackgroundGame game,
            String contentType,
            byte[] content) throws IOException {
        String safeUserId = canonicalUserId(userId);
        MediaSlot slot = backgroundSlot(
                Objects.requireNonNull(game, "game must not be null"));
        byte[] normalized = normalizeUpload(
                contentType,
                content,
                MAX_BACKGROUND_UPLOAD_BYTES,
                MAX_BACKGROUND_SOURCE_EDGE,
                MAX_BACKGROUND_PIXELS,
                slot);
        saveImage(safeUserId, slot, normalized);
    }

    /** Removes an avatar when present. Repeated calls are intentionally harmless. */
    public void deleteAvatar(String userId) throws IOException {
        deleteImage(userId, MediaSlot.AVATAR);
    }

    /** Compatibility overload: removes the former single/maimai background. */
    public void deleteBackground(String userId) throws IOException {
        deleteBackground(userId, BackgroundGame.MAIMAI);
    }

    /** Removes one game's background. Repeated calls are intentionally harmless. */
    public void deleteBackground(String userId, BackgroundGame game) throws IOException {
        BackgroundGame safeGame = Objects.requireNonNull(game, "game must not be null");
        MediaSlot primary = backgroundSlot(safeGame);
        if (safeGame == BackgroundGame.MAIMAI) {
            deleteImages(userId, primary, MediaSlot.LEGACY_MAIMAI_BACKGROUND);
        } else {
            deleteImages(userId, primary);
        }
    }

    /**
     * Removes all private profile data for one canonical user id.
     *
     * <p>The complete tree is checked before deletion. Symbolic links, special files, and
     * any path that escapes the selected user's directory are rejected rather than followed.
     */
    public void deleteUserData(String userId) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Path directory = profileDirectory(safeUserId);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            requireSafeDirectoryIfPresent(directory);
            deleteSafeTree(directory);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public static String normalizeDisplayName(String supplied) {
        if (supplied == null) {
            throw new ValidationException("displayName is required");
        }
        String normalized = Normalizer.normalize(supplied, Normalizer.Form.NFKC).strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > MAX_DISPLAY_NAME_CODE_POINTS) {
            throw new ValidationException("displayName must be between 1 and 32 characters");
        }
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new ValidationException("displayName must not contain control characters");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    private Optional<ImageData> loadImage(String userId, MediaSlot... slots)
            throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.readLock().lock();
        try {
            Path directory = profileDirectory(safeUserId);
            requireSafeDirectoryIfPresent(directory);
            for (MediaSlot slot : slots) {
                Path file = slot.file(directory);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                requireRegularFile(file);
                byte[] content = readLimited(file, slot.maxStoredBytes());
                if (!slot.hasStoredSignature(content)) {
                    throw new IOException("Stored profile image is invalid");
                }
                return Optional.of(new ImageData(slot.storedContentType(), content));
            }
            return Optional.empty();
        } finally {
            lock.readLock().unlock();
        }
    }

    private void saveImage(String safeUserId, MediaSlot slot, byte[] normalized)
            throws IOException {
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Path directory = ensureProfileDirectory(safeUserId);
            atomicWrite(slot.file(directory), normalized);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void deleteImage(String userId, MediaSlot slot) throws IOException {
        deleteImages(userId, slot);
    }

    private void deleteImages(String userId, MediaSlot... slots) throws IOException {
        String safeUserId = canonicalUserId(userId);
        ReentrantReadWriteLock lock = lockFor(safeUserId);
        lock.writeLock().lock();
        try {
            Path directory = profileDirectory(safeUserId);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            requireSafeDirectoryIfPresent(directory);
            List<Path> present = new ArrayList<>();
            for (MediaSlot slot : slots) {
                Path file = slot.file(directory);
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    requireRegularFile(file);
                    present.add(file);
                }
            }
            for (Path file : present) {
                requireRegularFile(file);
                Files.delete(file);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private byte[] normalizeUpload(
            String contentType,
            byte[] supplied,
            int maxBytes,
            int maxEdge,
            long maxPixels,
            MediaSlot slot) throws IOException {
        SourceFormat sourceFormat = SourceFormat.fromContentType(contentType);
        if (supplied == null || supplied.length == 0) {
            throw new ValidationException("Image body must not be empty");
        }
        if (supplied.length > maxBytes) {
            throw new ValidationException("Image body is too large");
        }
        byte[] content = supplied.clone();
        try {
            sourceFormat.requireMatchingFile(content);
            BufferedImage decoded = decodeBoundedImage(
                    content, sourceFormat, maxEdge, maxPixels);
            return slot == MediaSlot.AVATAR
                    ? encodePng(normalizeAvatar(decoded))
                    : encodeJpeg(normalizeBackground(decoded));
        } finally {
            Arrays.fill(content, (byte) 0);
        }
    }

    private static BufferedImage decodeBoundedImage(
            byte[] content,
            SourceFormat sourceFormat,
            int maxEdge,
            long maxPixels) {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new ValidationException("Image data is invalid");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new ValidationException("Image data is invalid");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, false, true);
                String formatName = reader.getFormatName();
                if (!sourceFormat.matchesReader(formatName)) {
                    throw new ValidationException("Image media type does not match its data");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height, maxEdge, maxPixels);
                BufferedImage decoded = reader.read(0);
                if (decoded == null
                        || decoded.getWidth() != width
                        || decoded.getHeight() != height) {
                    throw new ValidationException("Image could not be decoded completely");
                }
                return decoded;
            } finally {
                reader.dispose();
            }
        } catch (ValidationException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ValidationException("Image data is invalid", error);
        }
    }

    private static void validateDimensions(
            int width, int height, int maxEdge, long maxPixels) {
        if (width < 1 || height < 1) {
            throw new ValidationException("Image dimensions are invalid");
        }
        long pixels = (long) width * (long) height;
        if (width > maxEdge || height > maxEdge || pixels > maxPixels) {
            throw new ValidationException("Image dimensions are too large");
        }
    }

    private static BufferedImage normalizeAvatar(BufferedImage source) {
        BufferedImage target = new BufferedImage(
                AVATAR_SIZE, AVATAR_SIZE, BufferedImage.TYPE_INT_ARGB);
        double scale = Math.max(
                (double) AVATAR_SIZE / source.getWidth(),
                (double) AVATAR_SIZE / source.getHeight());
        int scaledWidth = Math.max(
                AVATAR_SIZE, (int) Math.ceil(source.getWidth() * scale));
        int scaledHeight = Math.max(
                AVATAR_SIZE, (int) Math.ceil(source.getHeight() * scale));
        int x = (AVATAR_SIZE - scaledWidth) / 2;
        int y = (AVATAR_SIZE - scaledHeight) / 2;
        Graphics2D graphics = target.createGraphics();
        try {
            applyHighQualityRendering(graphics);
            graphics.drawImage(source, x, y, scaledWidth, scaledHeight, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static BufferedImage normalizeBackground(BufferedImage source) {
        double scale = Math.min(
                1.0,
                Math.min(
                        (double) MAX_BACKGROUND_WIDTH / source.getWidth(),
                        (double) MAX_BACKGROUND_HEIGHT / source.getHeight()));
        int width = Math.max(1, Math.min(
                MAX_BACKGROUND_WIDTH, (int) Math.round(source.getWidth() * scale)));
        int height = Math.max(1, Math.min(
                MAX_BACKGROUND_HEIGHT, (int) Math.round(source.getHeight() * scale)));
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            applyHighQualityRendering(graphics);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static void applyHighQualityRendering(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        graphics.setRenderingHint(
                RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) {
            throw new IOException("PNG encoder is unavailable");
        }
        return output.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("JPEG encoder is unavailable");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            if (imageOutput == null) {
                throw new IOException("Unable to create JPEG output");
            }
            writer.setOutput(imageOutput);
            ImageWriteParam parameters = writer.getDefaultWriteParam();
            if (parameters.canWriteCompressed()) {
                parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameters.setCompressionQuality(BACKGROUND_JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), parameters);
            imageOutput.flush();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private String loadDisplayNameLocked(Path directory, String fallback) throws IOException {
        Path file = profileFile(directory);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return fallback;
        }
        requireRegularFile(file);
        byte[] encoded = readLimited(file, MAX_PROFILE_BYTES);
        String json;
        try {
            json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encoded))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Profile metadata is not valid UTF-8", error);
        }
        try {
            Object parsed = Json.parse(json);
            if (!(parsed instanceof Map<?, ?> raw)) {
                throw new IllegalArgumentException("profile must be a JSON object");
            }
            Map<String, Object> document = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("profile has a non-string field");
                }
                document.put(key, entry.getValue());
            }
            if (!document.keySet().equals(PROFILE_KEYS)) {
                throw new IllegalArgumentException("profile fields are invalid");
            }
            if (!(document.get("version") instanceof BigDecimal version)
                    || version.intValueExact() != PROFILE_VERSION) {
                throw new IllegalArgumentException("profile version is invalid");
            }
            if (!(document.get("displayName") instanceof String storedName)) {
                throw new IllegalArgumentException("displayName must be a string");
            }
            String normalized = normalizeDisplayName(storedName);
            if (!normalized.equals(storedName)) {
                throw new IllegalArgumentException("displayName is not normalized");
            }
            return storedName;
        } catch (IllegalArgumentException | ArithmeticException error) {
            throw new IOException("Profile metadata is invalid: " + error.getMessage(), error);
        }
    }

    private ReentrantReadWriteLock lockFor(String userId) {
        return userLocks.computeIfAbsent(userId, ignored -> new ReentrantReadWriteLock());
    }

    private Path ensureProfileDirectory(String userId) throws IOException {
        Path directory = profileDirectory(userId);
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireSafeDirectoryIfPresent(directory);
        } else {
            Files.createDirectories(directory);
            requireSafeDirectoryIfPresent(directory);
        }
        return directory;
    }

    private Path profileDirectory(String userId) {
        Path directory = root.resolve(userId).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new ValidationException("Invalid user id");
        }
        return directory;
    }

    private static Path profileFile(Path directory) {
        return directory.resolve("profile.json");
    }

    private static Path avatarFile(Path directory) {
        return directory.resolve("avatar.png");
    }

    private static Path backgroundFile(Path directory, BackgroundGame game) {
        return directory.resolve(switch (game) {
            case MAIMAI -> "background-maimai.jpg";
            case CHUNITHM -> "background-chunithm.jpg";
        });
    }

    private static Path legacyMaimaiBackgroundFile(Path directory) {
        return directory.resolve("background.jpg");
    }

    private static boolean hasMaimaiBackground(Path directory) throws IOException {
        boolean current = regularFileExists(
                backgroundFile(directory, BackgroundGame.MAIMAI));
        boolean legacy = regularFileExists(legacyMaimaiBackgroundFile(directory));
        return current || legacy;
    }

    private static void requireSafeDirectoryIfPresent(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(directory)
                || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Profile path is not a safe directory");
        }
    }

    private static boolean regularFileExists(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireRegularFile(file);
        return true;
    }

    private static void requireRegularFile(Path file) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Profile path is not a regular file");
        }
    }

    private void deleteSafeTree(Path directory) throws IOException {
        List<Path> paths = new ArrayList<>();
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path visited, BasicFileAttributes attributes) throws IOException {
                requireDeletionPath(directory, visited);
                if (Files.isSymbolicLink(visited) || !attributes.isDirectory()) {
                    throw new IOException("Profile tree contains an unsafe directory");
                }
                paths.add(visited);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    Path visited, BasicFileAttributes attributes) throws IOException {
                requireDeletionPath(directory, visited);
                if (Files.isSymbolicLink(visited)
                        || attributes.isSymbolicLink()
                        || !attributes.isRegularFile()) {
                    throw new IOException("Profile tree contains an unsafe file");
                }
                paths.add(visited);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path visited, IOException error)
                    throws IOException {
                requireDeletionPath(directory, visited);
                throw error;
            }
        });

        paths.sort(Comparator.reverseOrder());
        for (Path path : paths) {
            requireDeletionPath(directory, path);
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (Files.isSymbolicLink(path)
                    || attributes.isSymbolicLink()
                    || (!attributes.isRegularFile() && !attributes.isDirectory())) {
                throw new IOException("Profile tree changed during deletion");
            }
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private void requireDeletionPath(Path directory, Path candidate) throws IOException {
        Path safeDirectory = directory.toAbsolutePath().normalize();
        Path safeCandidate = candidate.toAbsolutePath().normalize();
        if (!safeDirectory.startsWith(root)
                || safeDirectory.equals(root)
                || !safeCandidate.startsWith(safeDirectory)) {
            throw new IOException("Profile deletion path escapes its user directory");
        }
    }

    private static byte[] readLimited(Path file, int maxBytes) throws IOException {
        long size = Files.size(file);
        if (size > maxBytes) {
            throw new IOException("Stored profile file is too large");
        }
        byte[] content = Files.readAllBytes(file);
        if (content.length > maxBytes) {
            throw new IOException("Stored profile file is too large");
        }
        return content;
    }

    private static void atomicWrite(Path destination, byte[] content) throws IOException {
        Path directory = destination.getParent();
        requireSafeDirectoryIfPresent(directory);
        Path temporary = Files.createTempFile(
                directory, "." + destination.getFileName() + ".", ".tmp");
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

    private static String canonicalUserId(String userId) {
        if (userId == null) {
            throw new ValidationException("Invalid user id");
        }
        try {
            String canonical = UUID.fromString(userId).toString();
            if (!canonical.equals(userId)) {
                throw new IllegalArgumentException("user id is not canonical");
            }
            return canonical;
        } catch (IllegalArgumentException error) {
            throw new ValidationException("Invalid user id", error);
        }
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new ValidationException("username is required");
        }
        int length = username.codePointCount(0, username.length());
        if (length > 128) {
            throw new ValidationException("username is too long");
        }
        for (int offset = 0; offset < username.length();) {
            int codePoint = username.codePointAt(offset);
            if (Character.isISOControl(codePoint)) {
                throw new ValidationException("username must not contain control characters");
            }
            offset += Character.charCount(codePoint);
        }
        return username;
    }

    private static MediaSlot backgroundSlot(BackgroundGame game) {
        return switch (game) {
            case MAIMAI -> MediaSlot.MAIMAI_BACKGROUND;
            case CHUNITHM -> MediaSlot.CHUNITHM_BACKGROUND;
        };
    }

    private enum MediaSlot {
        AVATAR("avatar.png", "image/png", MAX_AVATAR_UPLOAD_BYTES),
        MAIMAI_BACKGROUND(
                "background-maimai.jpg", "image/jpeg", MAX_BACKGROUND_UPLOAD_BYTES),
        CHUNITHM_BACKGROUND(
                "background-chunithm.jpg", "image/jpeg", MAX_BACKGROUND_UPLOAD_BYTES),
        LEGACY_MAIMAI_BACKGROUND(
                "background.jpg", "image/jpeg", MAX_BACKGROUND_UPLOAD_BYTES);

        private final String fileName;
        private final String storedContentType;
        private final int maxStoredBytes;

        MediaSlot(String fileName, String storedContentType, int maxStoredBytes) {
            this.fileName = fileName;
            this.storedContentType = storedContentType;
            this.maxStoredBytes = maxStoredBytes;
        }

        Path file(Path directory) {
            return directory.resolve(fileName);
        }

        String storedContentType() {
            return storedContentType;
        }

        int maxStoredBytes() {
            return maxStoredBytes;
        }

        boolean hasStoredSignature(byte[] content) {
            return this == AVATAR
                    ? startsWith(content, PNG_SIGNATURE) && endsWith(content, PNG_IEND)
                    : SourceFormat.JPEG.hasMagic(content);
        }
    }

    private enum SourceFormat {
        PNG("image/png"),
        JPEG("image/jpeg");

        private final String contentType;

        SourceFormat(String contentType) {
            this.contentType = contentType;
        }

        static SourceFormat fromContentType(String supplied) {
            String mediaType = supplied == null
                    ? ""
                    : supplied.split(";", 2)[0].trim();
            for (SourceFormat format : values()) {
                if (format.contentType.equalsIgnoreCase(mediaType)) {
                    return format;
                }
            }
            throw new ValidationException("Content-Type must be image/png or image/jpeg");
        }

        void requireMatchingFile(byte[] content) {
            if (!hasMagic(content)) {
                throw new ValidationException("Image media type does not match its data");
            }
        }

        boolean hasMagic(byte[] content) {
            if (this == PNG) {
                return startsWith(content, PNG_SIGNATURE) && endsWith(content, PNG_IEND);
            }
            return content.length >= 5
                    && (content[0] & 0xff) == 0xff
                    && (content[1] & 0xff) == 0xd8
                    && (content[2] & 0xff) == 0xff
                    && (content[content.length - 2] & 0xff) == 0xff
                    && (content[content.length - 1] & 0xff) == 0xd9;
        }

        boolean matchesReader(String readerFormat) {
            if (readerFormat == null) {
                return false;
            }
            return this == PNG
                    ? "png".equalsIgnoreCase(readerFormat)
                    : "jpeg".equalsIgnoreCase(readerFormat)
                            || "jpg".equalsIgnoreCase(readerFormat);
        }
    }

    private static boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] content, byte[] suffix) {
        if (content.length < suffix.length) {
            return false;
        }
        int offset = content.length - suffix.length;
        for (int index = 0; index < suffix.length; index++) {
            if (content[offset + index] != suffix[index]) {
                return false;
            }
        }
        return true;
    }

    public record Profile(
            String userId,
            String username,
            String displayName,
            boolean hasAvatar,
            boolean hasMaimaiBackground,
            boolean hasChunithmBackground) {
        public Profile {
            Objects.requireNonNull(userId, "userId must not be null");
            Objects.requireNonNull(username, "username must not be null");
            Objects.requireNonNull(displayName, "displayName must not be null");
        }

        /** Source-compatible constructor for callers that only knew one background. */
        public Profile(
                String userId,
                String username,
                String displayName,
                boolean hasAvatar,
                boolean hasBackground) {
            this(userId, username, displayName, hasAvatar, hasBackground, false);
        }

        /** The legacy background API maps to maimai. */
        public boolean hasBackground() {
            return hasMaimaiBackground;
        }
    }

    public record ImageData(String contentType, byte[] bytes) {
        public ImageData {
            Objects.requireNonNull(contentType, "contentType must not be null");
            bytes = Objects.requireNonNull(bytes, "bytes must not be null").clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
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
}
