import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Dependency-free tests for per-user append-only play history. */
public final class PlayHistoryStoreTest {
    private static final String PLAYED_AT = "2026-07-16T01:23:00Z";
    private static final String IMPORTED_AT = "2026-07-16T02:00:00Z";
    private static int testsRun;

    private PlayHistoryStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("play-history-store-test-");
        try {
            testMissingAndPathSafety(temporary.resolve("missing"));
            testUserIsolationAndPersistence(temporary.resolve("users"));
            testDeleteUserData(temporary.resolve("delete-user-data"));
            testOfficialAndFingerprintDeduplication(temporary.resolve("dedup"));
            testJudgmentDetailEnrichment(temporary.resolve("detail-enrichment"));
            testPlayDetailEnrichment(temporary.resolve("play-detail-enrichment"));
            testImportParserAndValidation(temporary.resolve("parser"));
            testConcurrentAppends(temporary.resolve("concurrent"));
            testCorruptUnsafeAndOversizedFiles(temporary.resolve("bad-files"));
            testLimitsAndImmutability(temporary.resolve("limits"));
            System.out.println(
                    "PlayHistoryStoreTest: all " + testsRun + " tests passed.");
        } finally {
            deleteTree(temporary);
        }
    }

    private static void testMissingAndPathSafety(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String userId = uuid();
        assertEquals(List.of(), store.load(userId), "missing history is empty");
        assertEquals(root.toAbsolutePath().normalize(), store.root(),
                "root is absolute and normalized");

        expectThrows(IllegalArgumentException.class,
                () -> store.load("../victim"),
                "path traversal is rejected as a user id");
        expectThrows(IllegalArgumentException.class,
                () -> store.load(userId.toUpperCase(java.util.Locale.ROOT)),
                "non-canonical UUID is rejected");

        Path unsafeRoot = temporarySibling(root, "unsafe-root");
        Files.writeString(unsafeRoot, "not a directory", StandardCharsets.UTF_8);
        expectThrows(IOException.class,
                () -> new PlayHistoryStore(unsafeRoot),
                "a regular file cannot be used as the root");
    }

    private static void testUserIsolationAndPersistence(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String firstUser = uuid();
        String secondUser = uuid();
        JudgmentDetails details = JudgmentDetails.maimai(Map.of(
                "tap", maimaiCounts(280, 20, 2, 1, 0),
                "break", maimaiCounts(8, 2, 0, 0, 0)));
        PlayDetails playDetails = PlayDetails.parseMaimai(Map.of(
                "fast", 12,
                "late", 7,
                "maxCombo", Map.of("current", 400, "maximum", 423),
                "dxScore", Map.of("current", 2_900, "maximum", 3_000),
                "rating", Map.of(
                        "value", 15_600,
                        "playerTotal", 15_620,
                        "delta", 9,
                        "frame", "yellow",
                        "frameImageUrl", officialRatingFrameUrl("yellow"))));
        PlayHistoryStore.PlayRecord first = new PlayHistoryStore.PlayRecord(
                "wechat", "official-1", "100", "First Song", "dx", "MASTER",
                100.1234, 2_900, "sss", "fc", "fs", details, playDetails,
                PLAYED_AT, IMPORTED_AT, "batch-a");
        PlayHistoryStore.PlayRecord second = record(
                "wechat", "official-2", "200", "Second Song", "standard",
                "EXPERT", 99.5, 2_000, "ss", null, null,
                "2026-07-16T01:24:00Z", IMPORTED_AT, "batch-a");

        PlayHistoryStore.AppendResult firstResult = store.append(
                firstUser, List.of(first));
        PlayHistoryStore.AppendResult secondResult = store.append(
                secondUser, List.of(second));
        assertEquals(new PlayHistoryStore.AppendResult(1, 0, 1), firstResult,
                "first append counts");
        assertEquals(new PlayHistoryStore.AppendResult(1, 0, 1), secondResult,
                "second append counts");
        assertEquals(List.of(first), store.load(firstUser), "first user is isolated");
        assertEquals(List.of(second), store.load(secondUser), "second user is isolated");

        Path firstFile = root.resolve(firstUser + ".json");
        Map<String, Object> document = object(Json.parse(Files.readString(firstFile)));
        assertEquals(Set.of("version", "records"), document.keySet(),
                "stored document has exact keys");
        assertEquals(new BigDecimal("1"), document.get("version"),
                "stored format has an explicit version");
        Map<String, Object> storedRecord = object(list(document.get("records")).getFirst());
        assertEquals(Set.of(
                "source", "sourceRecordId", "songId", "title", "chartType",
                "difficulty", "achievement", "dxScore", "rank", "comboStatus",
                "syncStatus", "judgmentDetails", "playDetails", "playedAt",
                "importedAt", "importBatchId"),
                storedRecord.keySet(), "all core and import fields persist");
        assertEquals(
                new BigDecimal("20"),
                object(object(
                        object(storedRecord.get("judgmentDetails"))
                                .get("byNoteType")).get("tap"))
                        .get("perfect"),
                "player TAP perfect count persists");
        assertEquals(
                new BigDecimal("400"),
                object(object(storedRecord.get("playDetails")).get("maxCombo"))
                        .get("current"),
                "official MAX COMBO persists");
        assertEquals(
                new BigDecimal("15620"),
                object(object(storedRecord.get("playDetails")).get("rating"))
                        .get("playerTotal"),
                "official player rating persists");
        assertEquals(
                "yellow",
                object(object(storedRecord.get("playDetails")).get("rating"))
                        .get("frame"),
                "DX Rating frame tier persists");
        assertEquals(
                officialRatingFrameUrl("yellow"),
                object(object(storedRecord.get("playDetails")).get("rating"))
                        .get("frameImageUrl"),
                "DX Rating frame image persists");

        PlayHistoryStore restarted = new PlayHistoryStore(root);
        assertEquals(List.of(first), restarted.load(firstUser),
                "first history survives restart");
        assertEquals(List.of(second), restarted.load(secondUser),
                "second history survives restart");
    }

    private static void testDeleteUserData(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String deletedUser = uuid();
        String retainedUser = uuid();
        PlayHistoryStore.PlayRecord deletedRecord = record(
                "wechat", "delete-1", "100", "Delete", "dx", "MASTER",
                100.0, 2_900, "sss", "fc", "fs", PLAYED_AT,
                IMPORTED_AT, "delete-batch");
        PlayHistoryStore.PlayRecord retainedRecord = record(
                "wechat", "retain-1", "200", "Retain", "standard", "EXPERT",
                99.5, 2_000, "ss", null, null,
                "2026-07-16T01:24:00Z", IMPORTED_AT, "retain-batch");
        store.append(deletedUser, List.of(deletedRecord));
        store.append(retainedUser, List.of(retainedRecord));

        Path deletedFile = root.resolve(deletedUser + ".json");
        Path retainedFile = root.resolve(retainedUser + ".json");
        assertEquals(true, Files.isRegularFile(deletedFile),
                "deleted user's play-history file exists before cleanup");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "retained user's play-history file exists before cleanup");
        expectThrows(IllegalArgumentException.class,
                () -> store.deleteUserData("not-a-uuid"),
                "play-history deletion rejects an invalid UUID");

        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "play-history deletion removes the selected user's file");
        assertEquals(List.of(), store.load(deletedUser),
                "deleted play history reloads as empty");
        assertEquals(List.of(retainedRecord), store.load(retainedUser),
                "play-history deletion does not affect another user");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "another user's play-history file remains present");
        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "repeated play-history deletion is idempotent");

        String nonRegularUser = uuid();
        Path nonRegularEntry = root.resolve(nonRegularUser + ".json");
        Files.createDirectory(nonRegularEntry);
        expectThrows(IOException.class,
                () -> store.deleteUserData(nonRegularUser),
                "play-history deletion rejects a non-regular entry");
        assertEquals(true, Files.isDirectory(nonRegularEntry),
                "rejected non-regular play-history entry is not deleted");
        Files.delete(nonRegularEntry);

        String linkedUser = uuid();
        Path link = root.resolve(linkedUser + ".json");
        Path target = root.resolve("play-history-link-target.json");
        Files.writeString(target, "target", StandardCharsets.UTF_8);
        boolean linkCreated = false;
        try {
            Files.createSymbolicLink(link, target);
            linkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows may require Developer Mode or elevated symbolic-link rights.
        }
        if (linkCreated) {
            expectThrows(IOException.class,
                    () -> store.deleteUserData(linkedUser),
                    "play-history deletion rejects a symbolic link");
            assertEquals(true,
                    Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    "rejected play-history link itself remains present");
            assertEquals(true, Files.isRegularFile(target),
                    "rejected play-history link target remains present");
            Files.delete(link);
        }
        Files.delete(target);
    }

    private static void testOfficialAndFingerprintDeduplication(Path root)
            throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String userId = uuid();
        PlayHistoryStore.PlayRecord official = record(
                "WeChat", "id-1", "1", "Song", "DX", "MASTER",
                100.0, 3_000, "sss", "fc", "fs", PLAYED_AT,
                IMPORTED_AT, "first-batch");
        PlayHistoryStore.PlayRecord sameOfficialIdChangedData = record(
                "wechat", "id-1", "different", "Changed", "standard", "EXPERT",
                1.0, 1, null, null, null, "2026-07-17T01:23:00Z",
                "2026-07-17T02:00:00Z", "second-batch");
        PlayHistoryStore.PlayRecord sameIdOtherSource = record(
                "manual", "id-1", "1", "Song", "dx", "MASTER",
                100.0, 3_000, "sss", "fc", "fs", PLAYED_AT,
                IMPORTED_AT, "first-batch");

        PlayHistoryStore.AppendResult first = store.append(
                userId, List.of(official, sameOfficialIdChangedData, sameIdOtherSource));
        assertEquals(new PlayHistoryStore.AppendResult(2, 1, 2), first,
                "semantic play identity ignores its import source");
        assertEquals(official, store.load(userId).getFirst(),
                "a conflicting duplicate id never overwrites the first record");

        PlayHistoryStore.PlayRecord withoutId = record(
                "wechat", null, "2", "No ID", "dx", "Re:MASTER",
                100.5, 3_100, "sssp", "ap", "fsd", PLAYED_AT,
                IMPORTED_AT, "first-batch");
        PlayHistoryStore.PlayRecord reimportedWithoutId = record(
                "wechat", null, "2", "No ID", "dx", "Re:MASTER",
                100.5, 3_100, "sssp", "ap", "fsd", PLAYED_AT,
                "2026-07-18T02:00:00Z", "later-batch");
        PlayHistoryStore.PlayRecord differentPlay = record(
                "wechat", null, "2", "No ID", "dx", "Re:MASTER",
                100.5, 3_100, "sssp", "ap", "fsd",
                "2026-07-16T01:24:00Z", "2026-07-18T02:00:00Z", "later-batch");

        PlayHistoryStore.AppendResult fallback = store.append(
                userId, List.of(withoutId, reimportedWithoutId, differentPlay));
        assertEquals(new PlayHistoryStore.AppendResult(2, 1, 4), fallback,
                "fingerprint ignores import metadata but keeps a different play");
        assertEquals(withoutId, store.load(userId).get(2),
                "fingerprint duplicate retains the original import metadata");

        PlayHistoryStore.AppendResult repeated = store.append(
                userId, List.of(official, withoutId));
        assertEquals(new PlayHistoryStore.AppendResult(0, 2, 4), repeated,
                "a fully repeated batch is idempotent");
    }

    private static void testImportParserAndValidation(Path root) throws Exception {
        Files.createDirectories(root);
        PlayHistoryStore.PlayRecord complete = record(
                "WECHAT", null, "3", "Parser", "DX", "MASTER",
                100.75, 3_333, "sss+", null, null, PLAYED_AT,
                IMPORTED_AT, "batch-parser");
        Map<String, Object> importValue = new LinkedHashMap<>(
                PlayHistoryStore.recordsToJsonValues(List.of(complete)).getFirst());
        importValue.remove("importedAt");
        importValue.remove("importBatchId");
        importValue.put("judgmentDetails", Map.of(
                "byNoteType", Map.of(
                        "slide", maimaiCounts(20, 5, 1, 0, 2))));
        importValue.put("playDetails", Map.of(
                "fast", 2,
                "maxCombo", Map.of("current", 500, "maximum", 500),
                "dxScore", Map.of("current", 3_333),
                "rating", Map.of(
                        "value", 15_600,
                        "delta", -9,
                        "frame", "yellow",
                        "frameImageUrl", officialRatingFrameUrl("yellow"))));

        List<PlayHistoryStore.PlayRecord> parsed =
                PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(importValue)), 10);
        assertEquals(1, parsed.size(), "one import record is parsed");
        assertEquals(false, parsed.getFirst().hasImportMetadata(),
                "import metadata may be absent during parsing");
        assertEquals("wechat", parsed.getFirst().source(),
                "source is canonicalized");
        assertEquals("dx", parsed.getFirst().chartType(),
                "chart type is canonicalized");
        assertEquals(
                5,
                parsed.getFirst().judgmentDetails().byNoteType()
                        .get("slide").get("perfect"),
                "per-note player judgments are parsed");
        assertEquals(500, parsed.getFirst().playDetails().maxCombo().current(),
                "official play-page values are parsed");
        assertEquals("yellow", parsed.getFirst().playDetails().rating().frame(),
                "DX Rating frame fields are parsed with the record");

        PlayHistoryStore.PlayRecord filled = parsed.getFirst().withImportMetadata(
                IMPORTED_AT, "batch-filled");
        assertEquals(true, filled.hasImportMetadata(),
                "backend can fill trusted import metadata");
        PlayHistoryStore store = new PlayHistoryStore(root);
        expectThrows(IllegalArgumentException.class,
                () -> store.append(uuid(), parsed),
                "append rejects records whose import metadata is absent");

        Map<String, Object> unknown = new LinkedHashMap<>(importValue);
        unknown.put("rating", BigDecimal.ONE);
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(unknown)), 10),
                "unknown import fields are rejected");

        Map<String, Object> missing = new LinkedHashMap<>(importValue);
        missing.remove("songId");
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(missing)), 10),
                "missing core fields are rejected");

        Map<String, Object> fractionalDx = new LinkedHashMap<>(importValue);
        fractionalDx.put("dxScore", new BigDecimal("100.5"));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(fractionalDx)), 10),
                "fractional DX score is rejected");

        Map<String, Object> mismatchedDetailDx = new LinkedHashMap<>(importValue);
        mismatchedDetailDx.put("playDetails", Map.of(
                "dxScore", Map.of("current", 3_334)));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(mismatchedDetailDx)), 10),
                "play-page DX score must match the core record");

        Map<String, Object> invalidTime = new LinkedHashMap<>(importValue);
        invalidTime.put("playedAt", "2026-07-16 01:23");
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(invalidTime)), 10),
                "ambiguous timestamps are rejected");

        Map<String, Object> invalidAchievement = new LinkedHashMap<>(importValue);
        invalidAchievement.put("achievement", new BigDecimal("101.0001"));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(invalidAchievement)), 10),
                "achievement above 101 is rejected");

        Map<String, Object> missingJudgment = new LinkedHashMap<>(importValue);
        missingJudgment.put("judgmentDetails", Map.of(
                "byNoteType", Map.of(
                        "tap", Map.of(
                                "perfect", 1, "great", 0, "good", 0))));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(missingJudgment)), 10),
                "incomplete judgment rows are rejected");

        Map<String, Object> wrongGameJudgment = new LinkedHashMap<>(importValue);
        wrongGameJudgment.put("judgmentDetails", Map.of(
                "judgments", Map.of(
                        "justiceCritical", 1, "justice", 0,
                        "attack", 0, "miss", 0),
                "noteCounts", Map.of(
                        "tap", 1, "hold", 0, "slide", 0,
                        "air", 0, "flick", 0),
                "maxCombo", 1));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(wrongGameJudgment)), 10),
                "CHUNITHM judgment fields are rejected for maimai");

        Map<String, Object> fractionalJudgment = new LinkedHashMap<>(importValue);
        fractionalJudgment.put("judgmentDetails", Map.of(
                "byNoteType", Map.of(
                        "tap", Map.of(
                                "criticalPerfect", 10,
                                "perfect", new BigDecimal("1.5"),
                                "great", 0, "good", 0, "miss", 0))));
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(
                        parsedJson(List.of(fractionalJudgment)), 10),
                "fractional judgment counts are rejected");

        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(List.of(), -1),
                "negative parser limit is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords("not an array", 10),
                "non-array imports are rejected");
    }

    private static void testJudgmentDetailEnrichment(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String userId = uuid();
        PlayHistoryStore.PlayRecord withoutDetails = record(
                "manual", null, "8", "Enrichment", "dx", "MASTER",
                100.0, 3_000, "sss", "fc", null, PLAYED_AT,
                IMPORTED_AT, "initial-batch");
        JudgmentDetails captured = JudgmentDetails.maimai(Map.of(
                "tap", maimaiCounts(280, 20, 2, 1, 0)));
        PlayHistoryStore.PlayRecord withDetails =
                withoutDetails.withJudgmentDetails(captured);

        assertEquals(
                new PlayHistoryStore.AppendResult(1, 0, 1),
                store.append(userId, List.of(withoutDetails)),
                "initial detail-free play is stored");
        assertEquals(
                new PlayHistoryStore.AppendResult(0, 1, 0, 1),
                store.append(userId, List.of(withDetails)),
                "detail enrichment remains a semantic duplicate");
        PlayHistoryStore.PlayRecord enriched = store.load(userId).getFirst();
        assertEquals(captured, enriched.judgmentDetails(),
                "later player judgments fill an empty stored breakdown");
        assertEquals("initial-batch", enriched.importBatchId(),
                "detail enrichment preserves original import metadata");

        JudgmentDetails conflicting = JudgmentDetails.maimai(Map.of(
                "tap", maimaiCounts(1, 2, 3, 4, 5)));
        store.append(
                userId,
                List.of(withoutDetails.withJudgmentDetails(conflicting)));
        assertEquals(captured, store.load(userId).getFirst().judgmentDetails(),
                "later conflicting judgments never overwrite stored details");
    }

    private static void testPlayDetailEnrichment(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String userId = uuid();
        PlayHistoryStore.PlayRecord original = record(
                "manual", null, "9", "Play Details", "dx", "MASTER",
                100.0, 3_000, "sss", "fc", null, PLAYED_AT,
                IMPORTED_AT, "initial-batch");
        PlayDetails firstCapture = PlayDetails.parseMaimai(Map.of(
                "fast", 12,
                "maxCombo", Map.of("current", 420),
                "dxScore", Map.of("current", 3_000),
                "rating", Map.of("value", 15_600)));
        PlayDetails laterCapture = PlayDetails.parseMaimai(Map.of(
                "fast", 99,
                "late", 7,
                "maxCombo", Map.of("current", 419, "maximum", 423),
                "maxSync", Map.of("current", 8, "maximum", 1_139),
                "dxScore", Map.of("current", 3_000, "maximum", 3_100),
                "rating", Map.of(
                        "value", 99,
                        "playerTotal", 15_620,
                        "delta", 9,
                        "frame", "yellow",
                        "frameImageUrl", officialRatingFrameUrl("yellow")),
                "partners", List.of(Map.of(
                        "stars", 3,
                        "level", 156,
                        "imageUrl", "https://maimai.wahlap.com/maimai-mobile/"
                                + "img/Chara/UI_Chara_000001.png"))));

        assertEquals(
                new PlayHistoryStore.AppendResult(1, 0, 1),
                store.append(userId, List.of(original)),
                "detail-free play is stored first");
        assertEquals(
                new PlayHistoryStore.AppendResult(0, 1, 0, 1),
                store.append(userId, List.of(
                        original.withPlayDetails(firstCapture))),
                "first official detail capture enriches in place");
        assertEquals(
                new PlayHistoryStore.AppendResult(0, 1, 0, 1),
                store.append(userId, List.of(
                        original.withPlayDetails(laterCapture))),
                "a later partial capture fills remaining fields in place");

        PlayHistoryStore.PlayRecord enriched = store.load(userId).getFirst();
        assertEquals(12, enriched.playDetails().fast(),
                "stored FAST is not overwritten by a conflict");
        assertEquals(7, enriched.playDetails().late(),
                "missing LATE is added");
        assertEquals(420, enriched.playDetails().maxCombo().current(),
                "stored MAX COMBO current is preserved");
        assertEquals(423, enriched.playDetails().maxCombo().maximum(),
                "MAX COMBO maximum is added");
        assertEquals(1_139, enriched.playDetails().maxSync().maximum(),
                "MAX SYNC is added");
        assertEquals(9, enriched.playDetails().rating().delta(),
                "rating change is kept under rating rather than DX score");
        assertEquals(15_600, enriched.playDetails().rating().value(),
                "stored post-play DX Rating is not overwritten");
        assertEquals(15_620, enriched.playDetails().rating().playerTotal(),
                "missing synchronized total Rating is enriched");
        assertEquals("yellow", enriched.playDetails().rating().frame(),
                "missing DX Rating frame tier is enriched");
        assertEquals(
                officialRatingFrameUrl("yellow"),
                enriched.playDetails().rating().frameImageUrl(),
                "missing DX Rating frame image is enriched");
        assertEquals(156, enriched.playDetails().partners().getFirst().level(),
                "travel partner details are added");
        assertEquals("initial-batch", enriched.importBatchId(),
                "enrichment keeps the original import metadata");

        assertEquals(
                new PlayHistoryStore.AppendResult(0, 0, 1, 1),
                store.append(userId, List.of(
                        original.withPlayDetails(laterCapture))),
                "a fully repeated detail capture is ignored");
    }

    private static void testConcurrentAppends(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        String userId = uuid();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger added = new AtomicInteger();
        AtomicInteger ignored = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        PlayHistoryStore.PlayRecord same = record(
                "wechat", "concurrent-id", "4", "Concurrent", "dx", "MASTER",
                100.0, 3_000, "sss", null, null, PLAYED_AT,
                IMPORTED_AT, "batch-concurrent");
        Thread first = appendThread(
                store, userId, same, ready, start, done, added, ignored, failure,
                "history-writer-1");
        Thread second = appendThread(
                store, userId, same, ready, start, done, added, ignored, failure,
                "history-writer-2");
        first.start();
        second.start();
        ready.await();
        start.countDown();
        done.await();
        first.join();
        second.join();

        if (failure.get() != null) {
            throw new AssertionError("concurrent append failed", failure.get());
        }
        assertEquals(1, added.get(), "one concurrent duplicate is added");
        assertEquals(1, ignored.get(), "one concurrent duplicate is ignored");
        assertEquals(List.of(same), store.load(userId),
                "concurrent duplicate append stores one complete record");
    }

    private static void testCorruptUnsafeAndOversizedFiles(Path root)
            throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);

        String invalidJsonUser = uuid();
        Files.writeString(root.resolve(invalidJsonUser + ".json"), "{broken");
        IOException invalidJson = expectThrows(IOException.class,
                () -> store.load(invalidJsonUser),
                "invalid stored JSON fails closed");
        assertContains(invalidJson.getMessage(), "invalid JSON",
                "invalid JSON error is explicit");

        String invalidShapeUser = uuid();
        Files.writeString(
                root.resolve(invalidShapeUser + ".json"),
                "{\"version\":1,\"records\":[],\"extra\":true}",
                StandardCharsets.UTF_8);
        expectThrows(IOException.class,
                () -> store.load(invalidShapeUser),
                "unknown stored fields fail closed");

        String unsupportedVersionUser = uuid();
        Files.writeString(
                root.resolve(unsupportedVersionUser + ".json"),
                "{\"version\":2,\"records\":[]}",
                StandardCharsets.UTF_8);
        expectThrows(IOException.class,
                () -> store.load(unsupportedVersionUser),
                "unsupported stored versions fail closed");

        PlayHistoryStore.PlayRecord duplicate = record(
                "wechat", "stored-duplicate", "5", "Duplicate", "dx", "MASTER",
                100.0, 3_000, "sss", null, null, PLAYED_AT,
                IMPORTED_AT, "batch-duplicate");
        String duplicateUser = uuid();
        Map<String, Object> duplicateDocument = new LinkedHashMap<>();
        duplicateDocument.put("version", 1);
        duplicateDocument.put("records",
                PlayHistoryStore.recordsToJsonValues(List.of(duplicate, duplicate)));
        Files.writeString(
                root.resolve(duplicateUser + ".json"),
                Json.stringify(duplicateDocument), StandardCharsets.UTF_8);
        assertEquals(1, store.load(duplicateUser).size(),
                "legacy duplicate stored identities are collapsed compatibly");

        String legacyUser = uuid();
        Map<String, Object> legacyRecord = new LinkedHashMap<>(
                PlayHistoryStore.recordsToJsonValues(List.of(duplicate)).getFirst());
        legacyRecord.remove("judgmentDetails");
        legacyRecord.remove("playDetails");
        Map<String, Object> legacyDocument = new LinkedHashMap<>();
        legacyDocument.put("version", 1);
        legacyDocument.put("records", List.of(legacyRecord));
        Files.writeString(
                root.resolve(legacyUser + ".json"),
                Json.stringify(legacyDocument), StandardCharsets.UTF_8);
        assertEquals(null, store.load(legacyUser).getFirst().judgmentDetails(),
                "version-1 records without judgment details remain readable");
        assertEquals(null, store.load(legacyUser).getFirst().playDetails(),
                "version-1 records without official play details remain readable");

        String directoryUser = uuid();
        Files.createDirectory(root.resolve(directoryUser + ".json"));
        expectThrows(IOException.class,
                () -> store.load(directoryUser),
                "a directory cannot masquerade as a history file");

        String oversizedUser = uuid();
        Files.write(
                root.resolve(oversizedUser + ".json"),
                new byte[PlayHistoryStore.MAX_FILE_BYTES + 1]);
        IOException oversized = expectThrows(IOException.class,
                () -> store.load(oversizedUser),
                "oversized stored files are rejected before parsing");
        assertContains(oversized.getMessage(), "too large",
                "oversized error is explicit");
    }

    private static void testLimitsAndImmutability(Path root) throws Exception {
        PlayHistoryStore store = new PlayHistoryStore(root);
        PlayHistoryStore.PlayRecord one = record(
                "wechat", "limit", "6", "Limit", "dx", "MASTER",
                100.0, 3_000, "sss", null, null, PLAYED_AT,
                IMPORTED_AT, "batch-limit");
        List<PlayHistoryStore.PlayRecord> overBatch = new ArrayList<>(
                PlayHistoryStore.MAX_APPEND_RECORDS + 1);
        for (int index = 0; index <= PlayHistoryStore.MAX_APPEND_RECORDS; index++) {
            overBatch.add(one);
        }
        expectThrows(IllegalArgumentException.class,
                () -> store.append(uuid(), overBatch),
                "append batch count is bounded before file access");

        List<Object> overParserLimit = new ArrayList<>();
        overParserLimit.add(Map.of());
        overParserLimit.add(Map.of());
        expectThrows(IllegalArgumentException.class,
                () -> PlayHistoryStore.parseImportRecords(overParserLimit, 1),
                "parser count limit is enforced before row parsing");

        String userId = uuid();
        store.append(userId, List.of(one));
        List<PlayHistoryStore.PlayRecord> loaded = store.load(userId);
        expectThrows(UnsupportedOperationException.class,
                () -> loaded.clear(), "loaded history is immutable");
        List<Map<String, Object>> json = PlayHistoryStore.recordsToJsonValues(loaded);
        expectThrows(UnsupportedOperationException.class,
                () -> json.clear(), "JSON record list is immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> json.getFirst().put("songId", "forged"),
                "JSON records are immutable");

        try (var files = Files.list(root)) {
            long temporaries = files
                    .filter(path -> path.getFileName().toString().endsWith(".tmp"))
                    .count();
            assertEquals(0L, temporaries,
                    "atomic writes leave no temporary files behind");
        }
    }

    private static Thread appendThread(
            PlayHistoryStore store,
            String userId,
            PlayHistoryStore.PlayRecord record,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch done,
            AtomicInteger added,
            AtomicInteger ignored,
            AtomicReference<Throwable> failure,
            String name) {
        return new Thread(() -> {
            ready.countDown();
            try {
                start.await();
                PlayHistoryStore.AppendResult result = store.append(
                        userId, List.of(record));
                added.addAndGet(result.added());
                ignored.addAndGet(result.ignored());
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            } finally {
                done.countDown();
            }
        }, name);
    }

    private static PlayHistoryStore.PlayRecord record(
            String source,
            String sourceRecordId,
            String songId,
            String title,
            String chartType,
            String difficulty,
            double achievement,
            int dxScore,
            String rank,
            String comboStatus,
            String syncStatus,
            String playedAt,
            String importedAt,
            String importBatchId) {
        return new PlayHistoryStore.PlayRecord(
                source, sourceRecordId, songId, title, chartType, difficulty,
                achievement, dxScore, rank, comboStatus, syncStatus, playedAt,
                importedAt, importBatchId);
    }

    private static Map<String, Integer> maimaiCounts(
            int criticalPerfect, int perfect, int great, int good, int miss) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("criticalPerfect", criticalPerfect);
        result.put("perfect", perfect);
        result.put("great", great);
        result.put("good", good);
        result.put("miss", miss);
        return result;
    }

    private static String officialRatingFrameUrl(String frame) {
        return "https://maimai.wahlap.com/maimai-mobile/img/rating_base_"
                + frame + ".png";
    }

    private static Object parsedJson(Object value) {
        return Json.parse(Json.stringify(value));
    }

    private static String uuid() {
        return UUID.randomUUID().toString();
    }

    private static Path temporarySibling(Path root, String name) {
        return root.getParent().resolve(name + "-" + UUID.randomUUID());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertContains(String actual, String expected, String message) {
        testsRun++;
        if (actual == null || !actual.contains(expected)) {
            throw new AssertionError(
                    message + ": expected <" + actual + "> to contain <" + expected + ">");
        }
    }

    private static <T extends Throwable> T expectThrows(
            Class<T> expectedType, ThrowingAction action, String message) {
        testsRun++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expectedType.isInstance(error)) {
                return expectedType.cast(error);
            }
            throw new AssertionError(
                    message + ": expected " + expectedType.getSimpleName()
                            + " but caught " + error.getClass().getSimpleName(),
                    error);
        }
        throw new AssertionError(
                message + ": expected " + expectedType.getSimpleName()
                        + " to be thrown");
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

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
