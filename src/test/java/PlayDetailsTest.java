import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free tests for verified maimai play-page values. */
public final class PlayDetailsTest {
    private static int testsRun;

    private PlayDetailsTest() {
    }

    public static void main(String[] args) {
        parsesAndSerializesCompleteDetails();
        parsesPartialDetailsAndMergesMissingValues();
        validatesRatingFrameAssets();
        rejectsInvalidShapesAndCounts();
        System.out.println(
                "PlayDetailsTest: all " + testsRun + " tests passed.");
    }

    private static void parsesAndSerializesCompleteDetails() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("fast", 137);
        value.put("late", 63);
        value.put("maxCombo", Map.of("current", 408, "maximum", 423));
        value.put("maxSync", Map.of("current", 8, "maximum", 1_139));
        value.put("dxScore", Map.of(
                "current", 7_622, "maximum", 8_000));
        value.put("rating", Map.of(
                "value", 15_600,
                "playerTotal", 15_620,
                "delta", 9,
                "frame", "yellow",
                "frameImageUrl", officialRatingFrameUrl("yellow")));
        value.put("partners", List.of(
                Map.of(
                        "stars", 3,
                        "level", 156,
                        "imageUrl", officialPartnerUrl("000001")),
                Map.of(
                        "stars", 1,
                        "level", 14,
                        "imageUrl", officialPartnerUrl("000002"))));

        PlayDetails details = PlayDetails.parseMaimai(parsedJson(value));
        assertEquals(137, details.fast(), "FAST count parses");
        assertEquals(63, details.late(), "LATE count parses");
        assertEquals(408, details.maxCombo().current(),
                "MAX COMBO current parses");
        assertEquals(423, details.maxCombo().maximum(),
                "MAX COMBO maximum parses");
        assertEquals(8, details.maxSync().current(),
                "MAX SYNC current parses");
        assertEquals(1_139, details.maxSync().maximum(),
                "MAX SYNC maximum parses");
        assertEquals(7_622, details.dxScore().current(),
                "DX score current parses");
        assertEquals(8_000, details.dxScore().maximum(),
                "DX score maximum parses");
        assertEquals(15_600, details.rating().value(),
                "post-play DX Rating parses");
        assertEquals(15_620, details.rating().playerTotal(),
                "synchronized player total rating parses");
        assertEquals(9, details.rating().delta(), "rating delta parses");
        assertEquals("yellow", details.rating().frame(),
                "DX Rating frame tier parses");
        assertEquals(officialRatingFrameUrl("yellow"),
                details.rating().frameImageUrl(),
                "official DX Rating frame image parses");
        assertEquals(2, details.partners().size(), "travel partners parse");
        assertEquals(156, details.partners().getFirst().level(),
                "travel partner level parses");
        assertEquals(officialPartnerUrl("000001"),
                details.partners().getFirst().imageUrl(),
                "official travel partner image URL parses");
        assertEquals(parsedJson(value), parsedJson(details.toJsonValue()),
                "public JSON shape round-trips exactly");
        expectThrows(UnsupportedOperationException.class,
                () -> details.toJsonValue().put("fast", 1),
                "serialized root is immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> object(details.toJsonValue().get("maxCombo"))
                        .put("current", 1),
                "serialized nested values are immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> details.partners().add(details.partners().getFirst()),
                "captured travel partner list is immutable");
    }

    private static void parsesPartialDetailsAndMergesMissingValues() {
        PlayDetails first = PlayDetails.parseMaimai(Map.of(
                "fast", 10,
                "maxCombo", Map.of("current", 400),
                "dxScore", Map.of("current", 7_622),
                "rating", Map.of("value", 15_600)));
        PlayDetails later = PlayDetails.parseMaimai(Map.of(
                "fast", 99,
                "late", 20,
                "maxCombo", Map.of("current", 399, "maximum", 423),
                "maxSync", Map.of("maximum", 1_139),
                "dxScore", Map.of("current", 7_622, "maximum", 8_000),
                "rating", Map.of(
                        "value", 99,
                        "playerTotal", 15_620,
                        "delta", 9,
                        "frame", "yellow",
                        "frameImageUrl", officialRatingFrameUrl("yellow")),
                "partners", List.of(Map.of(
                        "stars", 3,
                        "level", 156,
                        "imageUrl", officialPartnerUrl("000001")))));

        PlayDetails merged = first.mergeMissing(later);
        assertEquals(10, merged.fast(),
                "an existing FAST count wins a later conflict");
        assertEquals(20, merged.late(), "a missing LATE count is enriched");
        assertEquals(400, merged.maxCombo().current(),
                "an existing progress value wins");
        assertEquals(423, merged.maxCombo().maximum(),
                "a missing progress maximum is enriched");
        assertEquals(1_139, merged.maxSync().maximum(),
                "a whole missing progress object is enriched");
        assertEquals(8_000, merged.dxScore().maximum(),
                "a missing DX score maximum is enriched");
        assertEquals(9, merged.rating().delta(),
                "a missing rating delta is enriched");
        assertEquals(15_600, merged.rating().value(),
                "an existing post-play DX Rating wins a later conflict");
        assertEquals(15_620, merged.rating().playerTotal(),
                "a missing synchronized total rating is enriched");
        assertEquals("yellow", merged.rating().frame(),
                "a missing DX Rating frame tier is enriched");
        assertEquals(officialRatingFrameUrl("yellow"),
                merged.rating().frameImageUrl(),
                "a missing DX Rating frame image is enriched");
        assertEquals(156, merged.partners().getFirst().level(),
                "missing travel partners are enriched");

        PlayDetails conflict = PlayDetails.parseMaimai(Map.of(
                "maxCombo", Map.of("current", 399, "maximum", 399)));
        PlayDetails preserved = first.mergeMissing(conflict);
        assertEquals(null, preserved.maxCombo().maximum(),
                "an incompatible later maximum is ignored");
    }

    private static void validatesRatingFrameAssets() {
        PlayDetails normalized = PlayDetails.parseMaimai(Map.of(
                "rating", Map.of(
                        "frame", " YELLOW ",
                        "frameImageUrl", "https://maimai.wahlap.com:443/"
                                + "maimai-mobile/img/rating_base_yellow.png")));
        assertEquals("yellow", normalized.rating().frame(),
                "rating frame names are canonicalized through a whitelist");
        assertEquals(officialRatingFrameUrl("yellow"),
                normalized.rating().frameImageUrl(),
                "the explicit official HTTPS port is normalized");

        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of("frame", "black"))),
                "unknown DX Rating frame tiers are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of(
                                "frameImageUrl",
                                "https://example.com/rating_base_yellow.png"))),
                "non-official DX Rating frame images are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of(
                                "frameImageUrl",
                                officialRatingFrameUrl("yellow")
                                        + "?ver=123"))),
                "DX Rating frame image query strings are not persisted");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of(
                                "frameImageUrl",
                                "https://maimai.wahlap.com/maimai-mobile/"
                                        + "img/../secret.png"))),
                "non-normalized DX Rating frame paths are rejected");
    }

    private static void rejectsInvalidShapesAndCounts() {
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of()),
                "empty details are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of("unknown", 1)),
                "unknown root fields are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of("maxCombo", Map.of())),
                "empty nested progress is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "dxScore", Map.of("bonus", 1))),
                "unknown DX score fields are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "dxScore", Map.of("delta", 1))),
                "rating changes cannot be mislabeled as DX score deltas");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of("fast", -1)),
                "negative counts are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "late", new BigDecimal("1.5"))),
                "fractional counts are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "maxSync", Map.of("current", 2, "maximum", 1))),
                "current above maximum is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of("fast", 100_000_001)),
                "unreasonably large counts are rejected");
        assertEquals(
                -9,
                PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of("delta", -9))).rating().delta(),
                "a real negative rating change is accepted");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "rating", Map.of("delta", -100_001))),
                "an unreasonable negative rating change is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of("partners", List.of())),
                "an empty travel partner list is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156)))),
                "incomplete travel partner entries are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://example.com/chara.png")))),
                "non-official travel partner image URLs are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", officialPartnerUrl("000001")
                                        + "?token=secret")))),
                "travel partner image URL query strings are rejected");
        assertEquals(
                officialPartnerUrl("000001"),
                PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://maimai.wahlap.com:443/"
                                        + "maimai-mobile/img/Chara/"
                                        + "UI_Chara_000001.png"))))
                        .partners().getFirst().imageUrl(),
                "an explicit HTTPS port is normalized before persistence");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://maimai.wahlap.com/"
                                        + "maimai-mobile/img/../secret.png")))),
                "non-normalized travel partner paths are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://maimai.wahlap.com/"
                                        + "maimai-mobile/record/playlogDetail/"
                                        + "result.png")))),
                "non-static official endpoints are rejected as images");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://maimai.wahlap.com/"
                                        + "maimai-mobile/img/%2e%2e/secret.png")))),
                "encoded travel partner image paths are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(Map.of(
                                "stars", 3,
                                "level", 156,
                                "imageUrl", "https://maimai.wahlap.com/"
                                        + "maimai-mobile/img/partner.svg")))),
                "non-raster travel partner assets are rejected");
        expectThrows(IllegalArgumentException.class,
                () -> PlayDetails.parseMaimai(Map.of(
                        "partners", List.of(
                                partner(1), partner(2), partner(3),
                                partner(4), partner(5), partner(6)))),
                "more than five travel partners are rejected");
    }

    private static Map<String, Object> partner(int value) {
        return Map.of(
                "stars", value,
                "level", value,
                "imageUrl", officialPartnerUrl(String.format("%06d", value)));
    }

    private static String officialPartnerUrl(String id) {
        return "https://maimai.wahlap.com/maimai-mobile/img/Chara/UI_Chara_"
                + id + ".png";
    }

    private static String officialRatingFrameUrl(String frame) {
        return "https://maimai.wahlap.com/maimai-mobile/img/rating_base_"
                + frame + ".png";
    }

    private static Object parsedJson(Object value) {
        return Json.parse(Json.stringify(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return (Map<String, Object>) value;
    }

    private static void expectThrows(
            Class<? extends Throwable> type, Runnable action, String message) {
        testsRun++;
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
        testsRun++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected=" + expected + ", actual=" + actual);
        }
    }
}
