import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ChunithmHistoryStoreTest {
    private ChunithmHistoryStoreTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temporary = Files.createTempDirectory("chunithm-history-test-");
        try {
            String userId = UUID.randomUUID().toString();
            ChunithmHistoryStore store = new ChunithmHistoryStore(temporary);
            ChunithmHistoryStore.PlayRecord wechat = record(
                    "chunithm-wechat", null, "2026-07-16T01:00:00Z");
            ChunithmHistoryStore.PlayRecord manual = record(
                    "manual", "later-official-id", "2026-07-16T01:00:00Z");
            ChunithmHistoryStore.AppendResult appended = store.append(
                    userId,
                    List.of(
                            wechat.withImportMetadata(
                                    "2026-07-16T02:00:00Z", "batch-one"),
                            manual.withImportMetadata(
                                    "2026-07-16T03:00:00Z", "batch-two")));
            assertEquals(1, appended.added(), "cross-source semantic add count");
            assertEquals(1, appended.ignored(), "cross-source duplicate ignored");
            assertEquals(1, store.load(userId).size(), "one persisted play");

            Map<String, Object> parsed = new LinkedHashMap<>();
            parsed.put("source", "chunithm-wechat");
            parsed.put("sourceRecordId", null);
            parsed.put("songId", "3");
            parsed.put("title", "Song");
            parsed.put("difficulty", "MASTER");
            parsed.put("score", BigDecimal.valueOf(1_009_000));
            parsed.put("rank", null);
            parsed.put("clearStatus", "clear");
            parsed.put("comboStatus", "aj");
            parsed.put("playedAt", "2026-07-16T01:00:00Z");
            parsed.put("track", BigDecimal.valueOf(2));
            parsed.put("judgmentDetails", Map.of(
                    "judgments", chunithmCounts(540, 3, 1, 0),
                    "noteAchievements", chunithmAchievements(
                            "97.05", "100.98", "100.35", "99.52",
                            "96.93"),
                    "maxCombo", 544));
            List<ChunithmHistoryStore.PlayRecord> imported =
                    ChunithmHistoryStore.parseImportRecords(
                            List.of(parsed), 10);
            assertEquals("sssp", imported.getFirst().rank(), "rank derives from score");
            assertEquals(2, imported.getFirst().track(), "track retained");
            assertEquals(
                    3,
                    imported.getFirst().judgmentDetails().judgments()
                            .get("justice"),
                    "global player judgments are retained");
            assertEquals(
                    new BigDecimal("99.52"),
                    imported.getFirst().judgmentDetails().noteAchievements()
                            .get("air"),
                    "CHUNITHM note achievements remain separate percentages");
            assertEquals(
                    null,
                    imported.getFirst().judgmentDetails().noteCounts(),
                    "current CHUNITHM imports do not invent note quantities");
            assertEquals(
                    544,
                    imported.getFirst().judgmentDetails().maxCombo(),
                    "CHUNITHM max combo is retained");

            ChunithmHistoryStore.PlayRecord persisted = imported.getFirst()
                    .withImportMetadata(
                            "2026-07-16T02:00:00Z", "details-batch");
            String detailsUser = UUID.randomUUID().toString();
            store.append(detailsUser, List.of(persisted));
            assertEquals(
                    persisted.judgmentDetails(),
                    store.load(detailsUser).getFirst().judgmentDetails(),
                    "judgment details survive persistence");

            Map<String, Object> wrongRank = new LinkedHashMap<>(parsed);
            wrongRank.put("rank", "sss");
            assertThrows(
                    () -> ChunithmHistoryStore.parseImportRecords(
                            List.of(wrongRank), 10),
                    "rank mismatch rejected");

            Map<String, Object> withIdx = new LinkedHashMap<>(parsed);
            withIdx.put("idx", "secret-upstream-index");
            assertThrows(
                    () -> ChunithmHistoryStore.parseImportRecords(
                            List.of(withIdx), 10),
                    "unknown idx never enters storage");

            Map<String, Object> wrongGame = new LinkedHashMap<>(parsed);
            wrongGame.put("judgmentDetails", Map.of(
                    "byNoteType", Map.of(
                            "break", Map.of(
                                    "criticalPerfect", 1,
                                    "perfect", 1, "great", 0,
                                    "good", 0, "miss", 0))));
            assertThrows(
                    () -> ChunithmHistoryStore.parseImportRecords(
                            parsedJson(List.of(wrongGame)), 10),
                    "maimai judgment fields are rejected for CHUNITHM");

            Map<String, Object> negative = new LinkedHashMap<>(parsed);
            negative.put("judgmentDetails", Map.of(
                    "judgments", chunithmCounts(1, 0, 0, 0),
                    "noteAchievements", chunithmAchievements(
                            "1", "0", "-0.01", "0", "0"),
                    "maxCombo", 1));
            assertThrows(
                    () -> ChunithmHistoryStore.parseImportRecords(
                            parsedJson(List.of(negative)), 10),
                    "negative note achievements are rejected");

            Map<String, Object> excessivePrecision = new LinkedHashMap<>(parsed);
            excessivePrecision.put("judgmentDetails", Map.of(
                    "judgments", chunithmCounts(1, 0, 0, 0),
                    "noteAchievements", chunithmAchievements(
                            "97.051", "100.98", "100.35", "99.52",
                            "96.93"),
                    "maxCombo", 1));
            assertThrows(
                    () -> ChunithmHistoryStore.parseImportRecords(
                            parsedJson(List.of(excessivePrecision)), 10),
                    "note achievements reject more than two decimals");

            String legacyUser = UUID.randomUUID().toString();
            Map<String, Object> legacyRecord = new LinkedHashMap<>(
                    ChunithmHistoryStore.recordsToJsonValues(
                            List.of(persisted)).getFirst());
            legacyRecord.remove("judgmentDetails");
            Files.writeString(
                    temporary.resolve(legacyUser + ".json"),
                    Json.stringify(Map.of(
                            "version", 1,
                            "records", List.of(legacyRecord))));
            assertEquals(
                    null,
                    store.load(legacyUser).getFirst().judgmentDetails(),
                    "old records without judgment details remain readable");

            String enrichmentUser = UUID.randomUUID().toString();
            ChunithmHistoryStore.PlayRecord empty = record(
                    "manual", null, "2026-07-16T05:00:00Z")
                    .withImportMetadata(
                            "2026-07-16T06:00:00Z", "original-batch");
            JudgmentDetails legacyCaptured = JudgmentDetails.chunithm(
                    chunithmCounts(500, 3, 1, 0),
                    chunithmNoteCounts(200, 100, 100, 80, 24),
                    504);
            JudgmentDetails captured = JudgmentDetails.chunithmAchievements(
                    chunithmCounts(500, 3, 1, 0),
                    chunithmAchievements(
                            "97.05", "100.98", "100.35", "99.52",
                            "96.93"),
                    504);
            store.append(enrichmentUser, List.of(empty));
            assertEquals(
                    new ChunithmHistoryStore.AppendResult(0, 1, 0, 1),
                    store.append(
                            enrichmentUser,
                            List.of(empty.withJudgmentDetails(legacyCaptured))),
                    "legacy detail enrichment remains a duplicate");
            assertEquals(
                    new ChunithmHistoryStore.AppendResult(0, 1, 0, 1),
                    store.append(
                            enrichmentUser,
                            List.of(empty.withJudgmentDetails(captured))),
                    "a later percentage capture enriches the same record");
            ChunithmHistoryStore.PlayRecord enriched =
                    store.load(enrichmentUser).getFirst();
            assertEquals(
                    24,
                    enriched.judgmentDetails().noteCounts().get("flick"),
                    "legacy CHUNITHM note quantities remain readable");
            assertEquals(
                    new BigDecimal("96.93"),
                    enriched.judgmentDetails().noteAchievements().get("flick"),
                    "later CHUNITHM percentages fill the old record in place");
            assertEquals("original-batch", enriched.importBatchId(),
                    "CHUNITHM enrichment preserves original metadata");
            assertEquals(1, store.load(enrichmentUser).size(),
                    "detail enrichment never creates a duplicate play");

            JudgmentDetails conflict = JudgmentDetails.chunithmAchievements(
                    chunithmCounts(1, 2, 3, 4),
                    chunithmAchievements("1", "2", "3", "4", "5"),
                    5);
            assertEquals(
                    new ChunithmHistoryStore.AppendResult(0, 0, 1, 1),
                    store.append(
                            enrichmentUser,
                            List.of(empty.withJudgmentDetails(conflict))),
                    "fully conflicting later details are ignored");
            assertEquals(
                    new BigDecimal("96.93"),
                    store.load(enrichmentUser).getFirst().judgmentDetails()
                            .noteAchievements().get("flick"),
                    "later conflicting CHUNITHM percentages do not overwrite");
            testDeleteUserData(temporary.resolve("delete-user-data"));
            System.out.println("ChunithmHistoryStoreTest: all checks passed");
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static void testDeleteUserData(Path root) throws Exception {
        ChunithmHistoryStore store = new ChunithmHistoryStore(root);
        String deletedUser = UUID.randomUUID().toString();
        String retainedUser = UUID.randomUUID().toString();
        ChunithmHistoryStore.PlayRecord deletedRecord = record(
                "chunithm-wechat", "delete-1", "2026-07-16T04:00:00Z")
                .withImportMetadata("2026-07-16T05:00:00Z", "delete-batch");
        ChunithmHistoryStore.PlayRecord retainedRecord = record(
                "chunithm-wechat", "retain-1", "2026-07-16T06:00:00Z")
                .withImportMetadata("2026-07-16T07:00:00Z", "retain-batch");
        store.append(deletedUser, List.of(deletedRecord));
        store.append(retainedUser, List.of(retainedRecord));

        Path deletedFile = root.resolve(deletedUser + ".json");
        Path retainedFile = root.resolve(retainedUser + ".json");
        assertEquals(true, Files.isRegularFile(deletedFile),
                "deleted user's CHUNITHM history file exists before cleanup");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "retained user's CHUNITHM history file exists before cleanup");
        assertThrows(IllegalArgumentException.class,
                () -> store.deleteUserData("not-a-uuid"),
                "CHUNITHM history deletion rejects an invalid UUID");

        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "CHUNITHM history deletion removes the selected user's file");
        assertEquals(List.of(), store.load(deletedUser),
                "deleted CHUNITHM history reloads as empty");
        assertEquals(List.of(retainedRecord), store.load(retainedUser),
                "CHUNITHM history deletion does not affect another user");
        assertEquals(true, Files.isRegularFile(retainedFile),
                "another user's CHUNITHM history file remains present");
        store.deleteUserData(deletedUser);
        assertEquals(false, Files.exists(deletedFile),
                "repeated CHUNITHM history deletion is idempotent");

        String nonRegularUser = UUID.randomUUID().toString();
        Path nonRegularEntry = root.resolve(nonRegularUser + ".json");
        Files.createDirectory(nonRegularEntry);
        assertThrows(IOException.class,
                () -> store.deleteUserData(nonRegularUser),
                "CHUNITHM history deletion rejects a non-regular entry");
        assertEquals(true, Files.isDirectory(nonRegularEntry),
                "rejected non-regular CHUNITHM history entry is not deleted");
        Files.delete(nonRegularEntry);

        String linkedUser = UUID.randomUUID().toString();
        Path link = root.resolve(linkedUser + ".json");
        Path target = root.resolve("chunithm-history-link-target.json");
        Files.writeString(target, "target", StandardCharsets.UTF_8);
        boolean linkCreated = false;
        try {
            Files.createSymbolicLink(link, target);
            linkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows may require Developer Mode or elevated symbolic-link rights.
        }
        if (linkCreated) {
            assertThrows(IOException.class,
                    () -> store.deleteUserData(linkedUser),
                    "CHUNITHM history deletion rejects a symbolic link");
            assertEquals(true,
                    Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                    "rejected CHUNITHM history link itself remains present");
            assertEquals(true, Files.isRegularFile(target),
                    "rejected CHUNITHM history link target remains present");
            Files.delete(link);
        }
        Files.delete(target);
    }

    private static ChunithmHistoryStore.PlayRecord record(
            String source,
            String sourceRecordId,
            String playedAt) {
        return new ChunithmHistoryStore.PlayRecord(
                source,
                sourceRecordId,
                "3",
                "Song",
                "MASTER",
                1_009_000,
                "sssp",
                "clear",
                "aj",
                playedAt,
                2,
                null,
                null);
    }

    private static Map<String, Integer> chunithmCounts(
            int justiceCritical, int justice, int attack, int miss) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("justiceCritical", justiceCritical);
        result.put("justice", justice);
        result.put("attack", attack);
        result.put("miss", miss);
        return result;
    }

    private static Map<String, Integer> chunithmNoteCounts(
            int tap, int hold, int slide, int air, int flick) {
        Map<String, Integer> result = new LinkedHashMap<>();
        result.put("tap", tap);
        result.put("hold", hold);
        result.put("slide", slide);
        result.put("air", air);
        result.put("flick", flick);
        return result;
    }

    private static Map<String, BigDecimal> chunithmAchievements(
            String tap, String hold, String slide, String air, String flick) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        result.put("tap", new BigDecimal(tap));
        result.put("hold", new BigDecimal(hold));
        result.put("slide", new BigDecimal(slide));
        result.put("air", new BigDecimal(air));
        result.put("flick", new BigDecimal(flick));
        return result;
    }

    private static Object parsedJson(Object value) {
        return Json.parse(Json.stringify(value));
    }

    private static void assertThrows(ThrowingAction action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        } catch (Exception error) {
            throw new AssertionError(message + ": wrong exception", error);
        }
        throw new AssertionError(message + ": expected IllegalArgumentException");
    }

    private static void assertThrows(
            Class<? extends Throwable> type,
            ThrowingAction action,
            String message) {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return;
            }
            throw new AssertionError(message + ": wrong exception", error);
        }
        throw new AssertionError(message + ": expected " + type.getSimpleName());
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + expected + ", got " + actual);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
