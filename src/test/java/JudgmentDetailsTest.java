import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JudgmentDetailsTest {
    private static int tests;

    private JudgmentDetailsTest() {
    }

    public static void main(String[] args) {
        JudgmentDetails maimai = JudgmentDetails.parseMaimai(Map.of(
                "byNoteType", Map.of(
                        "touch", parsedCounts(
                                "criticalPerfect", 100, "perfect", 12,
                                "great", 1, "good", 0, "miss", 2))));
        expect(12, maimai.byNoteType().get("touch").get("perfect"),
                "maimai per-note-type judgment table parses");
        expect(null, maimai.judgments(),
                "maimai does not fabricate global judgments");
        expect(maimai, JudgmentDetails.parseMaimai(maimai.toJsonValue()),
                "maimai JSON value round trips");

        JudgmentDetails legacyChunithm = JudgmentDetails.parseChunithm(Map.of(
                "judgments", parsedCounts(
                        "justiceCritical", 30, "justice", 2,
                        "attack", 1, "miss", 0),
                "noteCounts", parsedCounts(
                        "tap", 10, "hold", 8, "slide", 7,
                        "air", 5, "flick", 3),
                "maxCombo", BigDecimal.valueOf(33)));
        expect(1, legacyChunithm.judgments().get("attack"),
                "CHUNITHM global judgments parse");
        expect(3, legacyChunithm.noteCounts().get("flick"),
                "legacy CHUNITHM note counts remain readable");
        expect(null, legacyChunithm.noteAchievements(),
                "legacy CHUNITHM records do not invent achievements");
        expect(33, legacyChunithm.maxCombo(), "CHUNITHM max combo parses");
        expect(null, legacyChunithm.byNoteType(),
                "CHUNITHM does not fabricate a judgment matrix");
        expect(
                legacyChunithm,
                JudgmentDetails.parseChunithm(
                        parsedJson(legacyChunithm.toJsonValue())),
                "legacy CHUNITHM JSON value round trips");

        JudgmentDetails achievements = JudgmentDetails.parseChunithm(
                parsedJson(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 598, "justice", 40,
                                "attack", 3, "miss", 1),
                        "noteAchievements", chunithmAchievements(
                                "97.05", "100.98", "100.35", "99.52",
                                "96.93"),
                        "maxCombo", 638)));
        expect(new BigDecimal("97.05"),
                achievements.noteAchievements().get("tap"),
                "CHUNITHM TAP achievement preserves two decimals");
        expect(new BigDecimal("100.98"),
                achievements.noteAchievements().get("hold"),
                "CHUNITHM achievements may exceed 100 up to 101");
        expect(new BigDecimal("96.93"),
                achievements.noteAchievements().get("flick"),
                "CHUNITHM FLICK achievement parses");
        expect(null, achievements.noteCounts(),
                "current CHUNITHM records do not invent note quantities");
        expect(
                achievements,
                JudgmentDetails.parseChunithm(
                        parsedJson(achievements.toJsonValue())),
                "CHUNITHM percentage JSON value round trips");

        JudgmentDetails enriched = legacyChunithm.mergeMissing(achievements);
        expect(3, enriched.noteCounts().get("flick"),
                "legacy note quantities survive enrichment");
        expect(new BigDecimal("96.93"),
                enriched.noteAchievements().get("flick"),
                "a later official sync adds missing percentages");
        expect(1, enriched.judgments().get("attack"),
                "later conflicting judgments do not rewrite history");
        expect(33, enriched.maxCombo(),
                "later conflicting max combo does not rewrite history");
        expect(
                enriched,
                JudgmentDetails.parseChunithm(parsedJson(enriched.toJsonValue())),
                "legacy and current CHUNITHM fields round trip together");

        expectThrows(
                () -> maimai.byNoteType().put("tap", Map.of()),
                "maimai table is immutable");
        expectThrows(
                () -> maimai.byNoteType().get("touch").put("miss", 99),
                "maimai judgment rows are immutable");
        expectThrows(
                () -> legacyChunithm.judgments().put("attack", 99),
                "CHUNITHM judgments are immutable");
        expectThrows(
                () -> legacyChunithm.noteCounts().put("tap", 99),
                "CHUNITHM note counts are immutable");
        expectThrows(
                () -> achievements.noteAchievements().put(
                        "tap", BigDecimal.ZERO),
                "CHUNITHM note achievements are immutable");
        expectThrows(
                () -> maimai.toJsonValue().put("forged", Map.of()),
                "public JSON value is immutable");

        expect(null, JudgmentDetails.parseMaimai(null),
                "absent maimai details remain absent");
        expect(null, JudgmentDetails.parseChunithm(null),
                "absent CHUNITHM details remain absent");
        expectThrows(
                () -> JudgmentDetails.parseMaimai(Map.of()),
                "maimai details require byNoteType");
        expectThrows(
                () -> JudgmentDetails.parseMaimai(Map.of(
                        "byNoteType", Map.of(
                                "tap", parsedCounts(
                                        "criticalPerfect", 10, "perfect", 1,
                                        "great", 0, "good", 0,
                                        "miss", 0, "extra", 0)))),
                "unknown maimai judgment fields are rejected");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "byNoteType", Map.of(
                                "tap", parsedCounts(
                                        "justiceCritical", 1, "justice", 0,
                                        "attack", 0, "miss", 0)))),
                "maimai matrix shape is rejected for CHUNITHM");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 1, "justice", 0,
                                "attack", 0, "miss", 0),
                        "maxCombo", BigDecimal.ONE)),
                "CHUNITHM details require note achievements or legacy counts");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 1, "justice", 0,
                                "attack", 0, "miss", 0),
                        "noteCounts", parsedCounts(
                                "tap", 1, "hold", 0, "slide", 0, "air", 0),
                        "maxCombo", BigDecimal.ONE)),
                "CHUNITHM noteCounts require all official note types");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 1, "justice", 0,
                                "attack", 0, "miss", 0),
                        "noteAchievements", Map.of(
                                "tap", new BigDecimal("97.05"),
                                "hold", new BigDecimal("100.98"),
                                "slide", new BigDecimal("100.35"),
                                "air", new BigDecimal("99.52")),
                        "maxCombo", BigDecimal.ONE)),
                "CHUNITHM achievements require all official note types");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 1, "justice", 0,
                                "attack", 0, "miss", 0),
                        "noteAchievements", chunithmAchievements(
                                "97.051", "100.98", "100.35", "99.52",
                                "96.93"),
                        "maxCombo", BigDecimal.ONE)),
                "CHUNITHM achievements reject more than two decimals");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", parsedCounts(
                                "justiceCritical", 1, "justice", 0,
                                "attack", 0, "miss", 0),
                        "noteAchievements", chunithmAchievements(
                                "97.05", "101.01", "100.35", "99.52",
                                "96.93"),
                        "maxCombo", BigDecimal.ONE)),
                "CHUNITHM achievements reject values above 101");
        expectThrows(
                () -> JudgmentDetails.parseChunithm(Map.of(
                        "judgments", Map.of(
                                "justiceCritical", BigDecimal.ONE,
                                "justice", BigDecimal.ZERO,
                                "attack", new BigDecimal("0.5"),
                                "miss", BigDecimal.ZERO),
                        "noteCounts", parsedCounts(
                                "tap", 1, "hold", 0, "slide", 0,
                                "air", 0, "flick", 0),
                        "maxCombo", BigDecimal.ONE)),
                "fractional CHUNITHM judgments are rejected");
        expectThrows(
                () -> JudgmentDetails.maimai(Map.of(
                        "tap", Map.of(
                                "criticalPerfect", 0,
                                "perfect", 10_000_001,
                                "great", 0,
                                "good", 0,
                                "miss", 0))),
                "unreasonably large counts are rejected");

        System.out.println(
                "JudgmentDetailsTest: all " + tests + " tests passed.");
    }

    private static Map<String, Object> parsedCounts(Object... pairs) {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object raw = pairs[index + 1];
            value.put(
                    (String) pairs[index],
                    raw instanceof Integer number
                            ? BigDecimal.valueOf(number)
                            : raw);
        }
        return value;
    }

    private static Map<String, BigDecimal> chunithmAchievements(
            String tap, String hold, String slide, String air, String flick) {
        Map<String, BigDecimal> value = new LinkedHashMap<>();
        value.put("tap", new BigDecimal(tap));
        value.put("hold", new BigDecimal(hold));
        value.put("slide", new BigDecimal(slide));
        value.put("air", new BigDecimal(air));
        value.put("flick", new BigDecimal(flick));
        return value;
    }

    private static Object parsedJson(Object value) {
        return Json.parse(Json.stringify(value));
    }

    private static void expect(Object expected, Object actual, String message) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expectThrows(ThrowingAction action, String message) {
        tests++;
        try {
            action.run();
        } catch (IllegalArgumentException | UnsupportedOperationException expected) {
            return;
        } catch (Exception error) {
            throw new AssertionError(message + ": wrong exception", error);
        }
        throw new AssertionError(message + ": expected an exception");
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }
}
