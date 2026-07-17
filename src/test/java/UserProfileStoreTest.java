import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.imageio.ImageIO;

/** Dependency-free tests for private user profile metadata and media. */
public final class UserProfileStoreTest {
    private static int tests;

    private UserProfileStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("b50-user-profile-test-");
        try {
            Path profiles = temporary.resolve("profiles");
            UserProfileStore store = new UserProfileStore(profiles);
            String firstId = UUID.randomUUID().toString();
            String secondId = UUID.randomUUID().toString();

            testLegacyFallback(store, profiles, firstId);
            testDisplayNames(store, profiles, firstId, secondId);
            testAvatar(store, profiles, firstId, secondId);
            testBackground(store, profiles, firstId, secondId);
            testUploadValidation(store, firstId);
            testStoredDataValidation(store, profiles);
            testDeleteUserData(store, profiles, firstId, secondId);
            testPathSafety(store);

            System.out.println(
                    "UserProfileStoreTest: all " + tests + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testLegacyFallback(
            UserProfileStore store, Path profiles, String userId) throws Exception {
        UserProfileStore.Profile profile = store.loadProfile(userId, "LegacyPlayer");
        expect(userId, profile.userId(), "profile identifies its owner");
        expect("LegacyPlayer", profile.username(), "profile preserves login username");
        expect("LegacyPlayer", profile.displayName(),
                "missing profile falls back to username");
        expect(false, profile.hasAvatar(), "old user starts without avatar");
        expect(false, profile.hasBackground(), "old user starts without background");
        expect(false, profile.hasMaimaiBackground(),
                "old user starts without a maimai background");
        expect(false, profile.hasChunithmBackground(),
                "old user starts without a CHUNITHM background");
        expect(false, Files.exists(profiles.resolve(userId)),
                "read-only fallback does not create storage");
    }

    private static void testDisplayNames(
            UserProfileStore store,
            Path profiles,
            String firstId,
            String secondId) throws Exception {
        UserProfileStore.Profile updated = store.updateDisplayName(
                firstId, "LegacyPlayer", "  Ｐｌａｙｅｒ  ");
        expect("Player", updated.displayName(), "display name is NFKC normalized and stripped");

        Path profileFile = profiles.resolve(firstId).resolve("profile.json");
        Map<String, Object> persisted = object(Json.parse(
                Files.readString(profileFile, StandardCharsets.UTF_8)));
        expect(2, persisted.size(), "profile metadata has a strict compact schema");
        expect("Player", persisted.get("displayName"), "display name is persisted");
        expect(1, ((java.math.BigDecimal) persisted.get("version")).intValueExact(),
                "profile format is versioned");

        UserProfileStore restarted = new UserProfileStore(profiles);
        expect("Player", restarted.loadProfile(firstId, "LegacyPlayer").displayName(),
                "display name survives restart");
        expect("OtherPlayer", restarted.loadProfile(secondId, "OtherPlayer").displayName(),
                "profiles are isolated by canonical user id");

        String thirtyTwoCodePoints = "音".repeat(32);
        expect(thirtyTwoCodePoints,
                store.updateDisplayName(firstId, "LegacyPlayer", thirtyTwoCodePoints)
                        .displayName(),
                "32-code-point display name is accepted");
        store.updateDisplayName(firstId, "LegacyPlayer", "Player");

        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.updateDisplayName(firstId, "LegacyPlayer", "   "),
                "blank display name is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.updateDisplayName(firstId, "LegacyPlayer", "\u00a0"),
                "compatibility-normalized blank display name is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.updateDisplayName(
                        firstId, "LegacyPlayer", "音".repeat(33)),
                "33-code-point display name is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.updateDisplayName(
                        firstId, "LegacyPlayer", "bad\u0000name"),
                "control characters are rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.updateDisplayName(firstId, "LegacyPlayer", null),
                "missing display name is rejected");
    }

    private static void testAvatar(
            UserProfileStore store,
            Path profiles,
            String firstId,
            String secondId) throws Exception {
        byte[] jpeg = image("jpeg", 800, 400, false);
        store.saveAvatar(firstId, "image/jpeg; charset=binary", jpeg);
        UserProfileStore.Profile profile = store.loadProfile(firstId, "LegacyPlayer");
        expect(true, profile.hasAvatar(), "saved avatar is reported in profile");
        expect(false, store.loadProfile(secondId, "OtherPlayer").hasAvatar(),
                "avatar does not leak to another user");

        UserProfileStore.ImageData avatar = store.loadAvatar(firstId).orElseThrow();
        expect("image/png", avatar.contentType(), "avatar is re-encoded as PNG");
        BufferedImage decoded = ImageIO.read(new java.io.ByteArrayInputStream(avatar.bytes()));
        expect(512, decoded.getWidth(), "avatar width is normalized");
        expect(512, decoded.getHeight(), "avatar height is normalized");
        expect(true, Files.isRegularFile(profiles.resolve(firstId).resolve("avatar.png")),
                "avatar uses the deterministic private filename");

        byte[] exposed = avatar.bytes();
        byte original = exposed[0];
        exposed[0] ^= 0x7f;
        expect(original, avatar.bytes()[0], "image response bytes are defensively copied");

        store.deleteAvatar(firstId);
        store.deleteAvatar(firstId);
        expect(Optional.empty(), store.loadAvatar(firstId),
                "avatar deletion is idempotent");
        expect(false, store.loadProfile(firstId, "LegacyPlayer").hasAvatar(),
                "deleted avatar is removed from profile state");
    }

    private static void testBackground(
            UserProfileStore store,
            Path profiles,
            String firstId,
            String secondId) throws Exception {
        byte[] widePng = image("png", 3000, 1000, true);
        store.saveBackground(
                firstId,
                UserProfileStore.BackgroundGame.MAIMAI,
                "IMAGE/PNG",
                widePng);
        UserProfileStore.ImageData background =
                store.loadBackground(
                        firstId, UserProfileStore.BackgroundGame.MAIMAI).orElseThrow();
        expect("image/jpeg", background.contentType(),
                "background is re-encoded as JPEG");
        BufferedImage decoded = ImageIO.read(
                new java.io.ByteArrayInputStream(background.bytes()));
        expect(2560, decoded.getWidth(), "background width is bounded");
        expect(853, decoded.getHeight(), "background aspect ratio is preserved");
        expect(true, store.loadProfile(firstId, "LegacyPlayer").hasBackground(),
                "legacy profile accessor maps to the maimai background");
        expect(true,
                store.loadProfile(firstId, "LegacyPlayer").hasMaimaiBackground(),
                "saved maimai background is reported in profile");
        expect(false,
                store.loadProfile(firstId, "LegacyPlayer").hasChunithmBackground(),
                "maimai background does not affect CHUNITHM state");
        expect(false,
                store.loadProfile(secondId, "OtherPlayer").hasMaimaiBackground(),
                "maimai background does not leak to another user");
        expect(true,
                Files.isRegularFile(
                        profiles.resolve(firstId).resolve("background-maimai.jpg")),
                "maimai background uses its deterministic private filename");

        byte[] tallPng = image("png", 720, 1280, true);
        store.saveBackground(
                firstId,
                UserProfileStore.BackgroundGame.CHUNITHM,
                "image/png",
                tallPng);
        expect(true,
                Files.isRegularFile(
                        profiles.resolve(firstId).resolve("background-chunithm.jpg")),
                "CHUNITHM background uses its deterministic private filename");
        expect(true,
                store.loadProfile(firstId, "LegacyPlayer").hasChunithmBackground(),
                "saved CHUNITHM background is reported independently");
        BufferedImage chunithm = ImageIO.read(new java.io.ByteArrayInputStream(
                store.loadBackground(firstId, UserProfileStore.BackgroundGame.CHUNITHM)
                        .orElseThrow()
                        .bytes()));
        expect(720, chunithm.getWidth(), "CHUNITHM background keeps its own width");
        expect(1280, chunithm.getHeight(), "CHUNITHM background keeps its own height");

        store.deleteBackground(firstId, UserProfileStore.BackgroundGame.MAIMAI);
        store.deleteBackground(firstId, UserProfileStore.BackgroundGame.MAIMAI);
        expect(Optional.empty(),
                store.loadBackground(firstId, UserProfileStore.BackgroundGame.MAIMAI),
                "maimai background deletion is idempotent");
        expect(true,
                store.loadBackground(firstId, UserProfileStore.BackgroundGame.CHUNITHM)
                        .isPresent(),
                "deleting maimai leaves CHUNITHM background intact");

        Path legacyFile = profiles.resolve(firstId).resolve("background.jpg");
        byte[] legacyJpeg = image("jpeg", 320, 180, false);
        Files.write(legacyFile, legacyJpeg);
        expect(true, store.loadProfile(firstId, "LegacyPlayer").hasMaimaiBackground(),
                "legacy background.jpg is reported as a maimai background");
        expect(true, java.util.Arrays.equals(
                        legacyJpeg,
                        store.loadBackground(firstId).orElseThrow().bytes()),
                "legacy background.jpg remains readable through the old API");

        store.saveBackground(firstId, "image/png", widePng);
        expect(true,
                Files.isRegularFile(
                        profiles.resolve(firstId).resolve("background-maimai.jpg")),
                "legacy save overload now writes the maimai-specific filename");
        expect(false,
                java.util.Arrays.equals(
                        legacyJpeg,
                        store.loadBackground(firstId).orElseThrow().bytes()),
                "maimai-specific file takes precedence over legacy background.jpg");

        store.deleteBackground(firstId);
        store.deleteBackground(firstId);
        expect(Optional.empty(), store.loadBackground(firstId),
                "legacy deletion removes maimai-specific and background.jpg files");
        expect(false, Files.exists(legacyFile, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "legacy maimai background is cleaned up during deletion");
        expect(true,
                store.loadBackground(firstId, UserProfileStore.BackgroundGame.CHUNITHM)
                        .isPresent(),
                "legacy maimai deletion still leaves CHUNITHM intact");

        store.deleteBackground(firstId, UserProfileStore.BackgroundGame.CHUNITHM);
        expect(Optional.empty(),
                store.loadBackground(firstId, UserProfileStore.BackgroundGame.CHUNITHM),
                "CHUNITHM background can be deleted independently");
    }

    private static void testUploadValidation(
            UserProfileStore store, String userId) throws Exception {
        byte[] png = image("png", 32, 32, true);
        byte[] jpeg = image("jpeg", 32, 32, false);

        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(userId, null, png),
                "missing image content type is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(userId, "image/svg+xml",
                        "<svg/>".getBytes(StandardCharsets.US_ASCII)),
                "SVG upload is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(userId, "image/png", jpeg),
                "MIME and file signature must agree");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveBackground(userId, "image/jpeg", png),
                "background MIME spoofing is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(userId, "image/png", new byte[0]),
                "empty image is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(
                        userId,
                        "image/png",
                        new byte[UserProfileStore.MAX_AVATAR_UPLOAD_BYTES + 1]),
                "avatar upload byte limit is enforced");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveBackground(
                        userId,
                        "image/png",
                        new byte[UserProfileStore.MAX_BACKGROUND_UPLOAD_BYTES + 1]),
                "background upload byte limit is enforced");

        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(
                        userId, "image/png", withPngDimensions(png, 4097, 1)),
                "avatar source edge limit is enforced before decode");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(
                        userId, "image/png", withPngDimensions(png, 3000, 3000)),
                "avatar source pixel limit is enforced before decode");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveBackground(
                        userId, "image/png", withPngDimensions(png, 8193, 1)),
                "background source edge limit is enforced before decode");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveBackground(
                        userId, "image/png", withPngDimensions(png, 5000, 5000)),
                "background source pixel limit is enforced before decode");

        byte[] damaged = corruptFirstIdatByte(png);
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.saveAvatar(userId, "image/png", damaged),
                "damaged image must complete decoding before storage");
    }

    private static void testStoredDataValidation(
            UserProfileStore store, Path profiles) throws Exception {
        String corruptId = UUID.randomUUID().toString();
        Path directory = profiles.resolve(corruptId);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("profile.json"),
                "{\"version\":1,\"displayName\":\"Player\",\"extra\":true}",
                StandardCharsets.UTF_8);
        expectThrows(IOException.class,
                () -> store.loadProfile(corruptId, "Player"),
                "unknown persisted profile fields are rejected");

        String corruptImageId = UUID.randomUUID().toString();
        Path imageDirectory = profiles.resolve(corruptImageId);
        Files.createDirectories(imageDirectory);
        Files.write(imageDirectory.resolve("avatar.png"), new byte[]{1, 2, 3});
        expectThrows(IOException.class,
                () -> store.loadAvatar(corruptImageId),
                "corrupt stored image is rejected");

        try (java.util.stream.Stream<Path> paths = Files.walk(profiles)) {
            expect(false,
                    paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")),
                    "successful atomic writes leave no temporary files");
        }
    }

    private static void testDeleteUserData(
            UserProfileStore store,
            Path profiles,
            String firstId,
            String secondId) throws Exception {
        store.updateDisplayName(firstId, "LegacyPlayer", "Delete Me");
        store.saveAvatar(firstId, "image/png", image("png", 64, 64, true));
        store.saveBackground(
                firstId,
                UserProfileStore.BackgroundGame.MAIMAI,
                "image/jpeg",
                image("jpeg", 320, 180, false));
        store.saveBackground(
                firstId,
                UserProfileStore.BackgroundGame.CHUNITHM,
                "image/jpeg",
                image("jpeg", 180, 320, false));
        Path firstDirectory = profiles.resolve(firstId);
        Path nested = firstDirectory.resolve("future-data");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("safe.txt"), "private");

        store.updateDisplayName(secondId, "OtherPlayer", "Keep Me");
        Path secondProfile = profiles.resolve(secondId).resolve("profile.json");
        expect(true, Files.isRegularFile(secondProfile),
                "control user's profile exists before account-data deletion");

        store.deleteUserData(firstId);
        expect(false,
                Files.exists(firstDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "account-data deletion removes the complete safe profile tree");
        expect(true, Files.isRegularFile(secondProfile),
                "account-data deletion is isolated to the selected user");
        store.deleteUserData(firstId);
        expect(false,
                Files.exists(firstDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "account-data deletion is idempotent");

        String unsafeFileId = UUID.randomUUID().toString();
        Path unsafeFile = profiles.resolve(unsafeFileId);
        Files.writeString(unsafeFile, "not a profile directory");
        expectThrows(IOException.class,
                () -> store.deleteUserData(unsafeFileId),
                "account-data deletion rejects a non-directory profile path");
        expect(true, Files.isRegularFile(unsafeFile),
                "rejected non-directory profile data is not removed");

        testLinkedTreeDeletionIsRejected(store, profiles);
    }

    private static void testLinkedTreeDeletionIsRejected(
            UserProfileStore store, Path profiles) throws Exception {
        String linkedId = UUID.randomUUID().toString();
        Path linkedDirectory = profiles.resolve(linkedId);
        Files.createDirectories(linkedDirectory);
        Path outside = profiles.getParent().resolve("outside-profile-data.txt");
        Files.writeString(outside, "must survive");
        Path link = linkedDirectory.resolve("escape-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            Files.delete(linkedDirectory);
            Files.delete(outside);
            return;
        }

        expectThrows(IOException.class,
                () -> store.deleteUserData(linkedId),
                "account-data deletion strictly rejects symbolic links");
        expect("must survive", Files.readString(outside),
                "rejected symbolic link cannot delete outside data");
        expect(true,
                Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "unsafe profile tree is left intact after validation fails");
    }

    private static void testPathSafety(UserProfileStore store) {
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.loadProfile("../outside", "Player"),
                "path traversal user id is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.loadProfile(UUID.randomUUID().toString().toUpperCase(), "Player"),
                "non-canonical UUID is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.loadProfile(null, "Player"),
                "missing user id is rejected");
        expectThrows(UserProfileStore.ValidationException.class,
                () -> store.deleteUserData("../outside"),
                "account-data deletion rejects path traversal user ids");
        expectThrows(NullPointerException.class,
                () -> store.loadBackground(UUID.randomUUID().toString(), null),
                "background APIs reject a missing game enum");
    }

    private static byte[] image(
            String format, int width, int height, boolean transparent) throws IOException {
        int type = transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(27, 77, 141));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new Color(245, 196, 66, transparent ? 170 : 255));
            graphics.fillOval(width / 4, height / 4, width / 2, height / 2);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Test image encoder is unavailable: " + format);
        }
        return output.toByteArray();
    }

    private static byte[] withPngDimensions(byte[] source, int width, int height) {
        byte[] patched = source.clone();
        if (patched.length < 33
                || patched[12] != 'I'
                || patched[13] != 'H'
                || patched[14] != 'D'
                || patched[15] != 'R') {
            throw new IllegalArgumentException("Test PNG has no leading IHDR");
        }
        putInt(patched, 16, width);
        putInt(patched, 20, height);
        CRC32 crc = new CRC32();
        crc.update(patched, 12, 17);
        putInt(patched, 29, (int) crc.getValue());
        return patched;
    }

    private static byte[] corruptFirstIdatByte(byte[] source) {
        byte[] corrupted = source.clone();
        int offset = 8;
        while (offset + 12 <= corrupted.length) {
            int length = readInt(corrupted, offset);
            int type = offset + 4;
            int data = offset + 8;
            if (length < 0 || data + length + 4 > corrupted.length) {
                break;
            }
            if (corrupted[type] == 'I'
                    && corrupted[type + 1] == 'D'
                    && corrupted[type + 2] == 'A'
                    && corrupted[type + 3] == 'T'
                    && length > 0) {
                corrupted[data] ^= 0x40;
                return corrupted;
            }
            offset = data + length + 4;
        }
        throw new IllegalArgumentException("Test PNG has no IDAT data");
    }

    private static int readInt(byte[] value, int offset) {
        return (value[offset] & 0xff) << 24
                | (value[offset + 1] & 0xff) << 16
                | (value[offset + 2] & 0xff) << 8
                | value[offset + 3] & 0xff;
    }

    private static void putInt(byte[] value, int offset, int number) {
        value[offset] = (byte) (number >>> 24);
        value[offset + 1] = (byte) (number >>> 16);
        value[offset + 2] = (byte) (number >>> 8);
        value[offset + 3] = (byte) number;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> type, ThrowingAction action, String label) {
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
        throw new AssertionError(
                label + ": expected " + type.getSimpleName() + " to be thrown");
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
