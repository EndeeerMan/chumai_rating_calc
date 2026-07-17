import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dependency-free tests. Run with: {@code java ChunithmCalculatorTest}. */
public final class ChunithmCalculatorTest {
    private static final String OLD = "CHUNITHM SUN";
    private static final String NEW = "CHUNITHM VERSE";
    private static int testsRun;

    private ChunithmCalculatorTest() {
    }

    public static void main(String[] args) {
        testEveryOfficialScoreBoundary();
        testExactDecimalFlooring();
        testSelectsB30AndN20FromLatestVersions();
        testVersionNormalizationMatchesBrowser();
        testStableRatingTies();
        testSongDifficultyUniqueness();
        testPerDifficultyVersionsUseIndependentPools();
        testWorldsEndIsRetainedButNeverSelected();
        testDisabledSongIsRetainedButNeverSelected();
        testEmptyAndPartialPoolsUseFixedDivisor();
        testImmutableResults();
        testRejectsInvalidInput();
        testRejectsInvalidCollections();

        System.out.println(
                "ChunithmCalculatorTest: all " + testsRun + " tests passed.");
    }

    private static void testEveryOfficialScoreBoundary() {
        int[][] cases = {
                {0, 0},
                {499_999, 0},
                {500_000, 0},
                {650_000, 125},
                {799_999, 249},
                {800_000, 250},
                {899_999, 499},
                {900_000, 500},
                {924_999, 699},
                {925_000, 700},
                {974_999, 999},
                {975_000, 1_000},
                {999_999, 1_099},
                {1_000_000, 1_100},
                {1_004_999, 1_149},
                {1_005_000, 1_150},
                {1_007_499, 1_199},
                {1_007_500, 1_200},
                {1_008_999, 1_214},
                {1_009_000, 1_215},
                {1_010_000, 1_215}
        };

        for (int[] scoreCase : cases) {
            int score = scoreCase[0];
            double expected = scoreCase[1] / 100.0;
            assertDouble(expected,
                    ChunithmCalculator.calculateSingleRating(10.0, score),
                    "score boundary " + score);
        }
    }

    private static void testExactDecimalFlooring() {
        assertDouble(11.90,
                ChunithmCalculator.calculateSingleRating(10.0, 1_007_027),
                "matches Diving-Fish public test-data sample");
        assertDouble(10.82,
                ChunithmCalculator.calculateSingleRating(10.5, 983_027),
                "matches second Diving-Fish public test-data sample");
        assertDouble(2.55,
                ChunithmCalculator.calculateSingleRating(10.1, 800_000),
                "exact decimal endpoint must not lose 0.01 to binary floating point");
        assertDouble(10.09,
                ChunithmCalculator.calculateSingleRating(10.0, 977_499),
                "interpolation is floored, not rounded");
        assertDouble(10.10,
                ChunithmCalculator.calculateSingleRating(10.0, 977_500),
                "next exact hundredth boundary");
        assertDouble(0.0,
                ChunithmCalculator.calculateSingleRating(0.0, 974_999),
                "negative low-constant interpolation is clamped to zero");
        assertDouble(0.01,
                ChunithmCalculator.calculateSingleRating(0.0, 975_250),
                "positive contribution after 975000");
    }

    private static void testSelectsB30AndN20FromLatestVersions() {
        List<ChunithmChartInput> inputs = new ArrayList<>();
        for (int index = 0; index < 35; index++) {
            inputs.add(chart(id("old", index), "MASTER", OLD,
                    10.0 + index / 10.0, 1_009_000));
        }
        for (int index = 0; index < 25; index++) {
            inputs.add(chart(id("new", index), "MASTER", NEW,
                    10.0 + index / 10.0, 1_009_000));
        }

        ChunithmResult result = ChunithmCalculator.calculate(
                inputs, List.of(" CHUNITHM LUMINOUS PLUS ", NEW));

        assertEquals(60, result.items().size(), "all charts remain visible");
        assertEquals(30, result.b30().size(), "B30 limit");
        assertEquals(20, result.n20().size(), "N20 limit");
        assertEquals(50, result.selectedCount(), "selected total");
        assertEquals("old-34", result.b30().getFirst().songId(),
                "highest old chart first");
        assertEquals("old-05", result.b30().getLast().songId(),
                "lowest selected old chart");
        assertEquals("new-24", result.n20().getFirst().songId(),
                "highest new chart first");
        assertEquals("new-05", result.n20().getLast().songId(),
                "lowest selected new chart");

        ChunithmItem oldExcluded = find(result.items(), "old-04", "MASTER");
        ChunithmItem newExcluded = find(result.items(), "new-04", "MASTER");
        assertEquals(false, oldExcluded.selected(), "31st old chart excluded");
        assertEquals(31, oldExcluded.poolRank(), "old pool rank");
        assertEquals(false, newExcluded.selected(), "21st new chart excluded");
        assertEquals(21, newExcluded.poolRank(), "new pool rank");

        assertDouble(sum(result.b30()), result.b30Total(), "B30 subtotal");
        assertDouble(sum(result.n20()), result.n20Total(), "N20 subtotal");
        assertDouble((result.b30Total() + result.n20Total()) / 50.0,
                result.rating(), "official fixed-divisor Rating");
    }

    private static void testStableRatingTies() {
        List<ChunithmChartInput> inputs = List.of(
                chart("first", "MASTER", OLD, 10.0, 1_009_000),
                chart("second", "EXPERT", OLD, 10.0, 1_009_000),
                chart("third", "ULTIMA", OLD, 10.0, 1_009_000));

        ChunithmResult result = ChunithmCalculator.calculate(inputs, List.of(NEW));
        assertEquals("first", result.items().get(0).songId(),
                "overall ties retain input order");
        assertEquals("second", result.items().get(1).songId(),
                "overall ties retain input order");
        assertEquals("third", result.items().get(2).songId(),
                "overall ties retain input order");
        assertEquals("first", result.b30().get(0).songId(),
                "pool ties retain input order");
        assertEquals(1, result.b30().get(0).poolRank(), "first stable pool rank");
        assertEquals(3, result.b30().get(2).poolRank(), "last stable pool rank");
    }

    private static void testVersionNormalizationMatchesBrowser() {
        String fullWidthLatest =
                "\ufeff\u3000ＣＨＵＮＩＴＨＭ\u00a0\tＶＥＲＳＥ\u3000\ufeff";
        ChunithmResult normalized = ChunithmCalculator.calculate(List.of(
                chart("normalized", "MASTER", "  chunithm   verse  ",
                        10.0, 1_009_000)),
                List.of(fullWidthLatest));
        assertEquals(1, normalized.n20().size(),
                "NFKC, case, and whitespace variants match the latest version");
        assertEquals(true, normalized.items().getFirst().newChart(),
                "normalized version metadata marks the chart new");
        assertEquals("chunithm verse",
                ChunithmCalculator.normalizeVersion(fullWidthLatest),
                "version key mirrors browser normalization");

        ChunithmResult sameSong = ChunithmCalculator.calculate(List.of(
                chart("same-version", "EXPERT", "CHUNITHM VERSE",
                        10.0, 1_000_000),
                chart("same-version", "MASTER", "ｃｈｕｎｉｔｈｍ　ｖｅｒｓｅ",
                        11.0, 1_000_000)),
                List.of("chunithm verse"));
        assertEquals(2, sameSong.n20().size(),
                "normalized equivalent versions do not create a song conflict");
    }

    private static void testSongDifficultyUniqueness() {
        ChunithmResult valid = ChunithmCalculator.calculate(List.of(
                chart("same-song", "EXPERT", OLD, 10.0, 1_000_000),
                chart("same-song", "MASTER", OLD, 11.0, 1_000_000)),
                List.of(NEW));
        assertEquals(2, valid.items().size(),
                "different difficulties for one song are distinct");

        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(List.of(
                        chart("duplicate", "master", OLD, 10.0, 1_000_000),
                        chart("duplicate", "MASTER", OLD, 11.0, 1_000_000)),
                        List.of(NEW)),
                "difficulty aliases cannot bypass song+difficulty uniqueness");
    }

    private static void testPerDifficultyVersionsUseIndependentPools() {
        ChunithmResult result = ChunithmCalculator.calculate(List.of(
                chart("old-song", "MASTER", OLD, 13.0, 1_009_000),
                chart("old-song", "ULTIMA", NEW, 14.0, 1_009_000)),
                List.of(NEW));

        assertEquals(1, result.b30().size(),
                "an original difficulty remains in B30");
        assertEquals("MASTER", result.b30().getFirst().difficulty(),
                "the old MASTER uses its own chart version");
        assertEquals(1, result.n20().size(),
                "a later-added difficulty can independently enter N20");
        assertEquals("ULTIMA", result.n20().getFirst().difficulty(),
                "the added ULTIMA uses its own chart version");
    }

    private static void testWorldsEndIsRetainedButNeverSelected() {
        List<ChunithmChartInput> inputs = new ArrayList<>();
        for (int index = 0; index < 29; index++) {
            inputs.add(chart(id("normal", index), "MASTER", OLD,
                    1.0, 500_000));
        }
        inputs.add(chart("we-old", "World's End", OLD, 0.0, 1_010_000));
        inputs.add(chart("we-new", "World\u2019s End", NEW, 0.0, 1_010_000));

        ChunithmResult result = ChunithmCalculator.calculate(inputs, List.of(NEW));
        assertEquals(31, result.items().size(), "World's End remains visible");
        assertEquals(29, result.b30().size(), "World's End does not fill short B30");
        assertEquals(0, result.n20().size(), "World's End does not fill empty N20");

        ChunithmItem oldWe = find(result.items(), "we-old", "WORLD'S END");
        ChunithmItem newWe = find(result.items(), "we-new", "WORLD'S END");
        assertDouble(2.15, oldWe.rating(), "World's End raw formula is retained");
        assertEquals(false, oldWe.ratingEligible(), "old World's End is ineligible");
        assertEquals(false, oldWe.selected(), "old World's End is never selected");
        assertEquals(0, oldWe.poolRank(), "ineligible chart has no pool rank");
        assertEquals(true, newWe.newChart(), "latest version metadata is retained");
        assertEquals(false, newWe.ratingEligible(), "new World's End is ineligible");
        assertEquals(false, newWe.selected(), "new World's End is never selected");
    }

    private static void testDisabledSongIsRetainedButNeverSelected() {
        ChunithmResult result = ChunithmCalculator.calculate(List.of(
                chart("enabled", "MASTER", OLD, 10.0, 1_009_000),
                chart("921", "MASTER", OLD, 15.0, 1_010_000)),
                List.of(NEW),
                List.of("921"));

        assertEquals(2, result.items().size(),
                "disabled song remains available for history display");
        assertEquals(1, result.b30().size(),
                "disabled song does not fill B30");
        ChunithmItem disabled = find(result.items(), "921", "MASTER");
        assertEquals(false, disabled.ratingEligible(),
                "disabled song is Rating-ineligible");
        assertEquals(false, disabled.selected(),
                "disabled song is never selected");
        assertEquals(0, disabled.poolRank(),
                "disabled song has no pool rank");
        assertDouble(17.15, disabled.rating(),
                "disabled song still retains its raw single-chart Rating");
    }

    private static void testEmptyAndPartialPoolsUseFixedDivisor() {
        ChunithmResult empty = ChunithmCalculator.calculate(List.of(), List.of(NEW));
        assertDouble(0.0, empty.rating(), "empty Rating");
        assertEquals(0, empty.selectedCount(), "empty selected count");
        assertBigDecimal("0", empty.ratingDecimal(), "exact empty Rating");

        ChunithmResult one = ChunithmCalculator.calculate(List.of(
                chart("one", "MASTER", OLD, 10.0, 1_009_000)), List.of(NEW));
        assertDouble(12.15, one.totalContribution(), "one-chart contribution");
        assertDouble(0.243, one.rating(), "one chart is still divided by 50");
        assertBigDecimal("0.243", one.ratingDecimal(), "exact partial Rating");
        assertEquals(1, one.b30().size(), "partial B30");
        assertEquals(0, one.n20().size(), "empty N20");
    }

    private static void testImmutableResults() {
        ChunithmResult result = ChunithmCalculator.calculate(List.of(
                chart("one", "MASTER", OLD, 10.0, 1_009_000)), List.of(NEW));
        expectThrows(UnsupportedOperationException.class,
                () -> result.items().add(result.items().getFirst()),
                "items are immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> result.b30().clear(), "B30 is immutable");
        expectThrows(UnsupportedOperationException.class,
                () -> result.selectedItems().clear(), "selected list is immutable");
    }

    private static void testRejectsInvalidInput() {
        expectThrows(NullPointerException.class,
                () -> new ChunithmChartInput(
                        null, "Title", "MASTER", 10.0, 1_000_000, OLD),
                "null song ID");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        " ", "Title", "MASTER", 10.0, 1_000_000, OLD),
                "blank song ID");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", " ", "MASTER", 10.0, 1_000_000, OLD),
                "blank title");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "RE:MASTER", 10.0, 1_000_000, OLD),
                "unsupported difficulty");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", -0.1, 1_000_000, OLD),
                "negative constant");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", 10.25, 1_000_000, OLD),
                "constant precision above one decimal");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", Double.NaN, 1_000_000, OLD),
                "non-finite constant");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", 10.0, -1, OLD),
                "negative score");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", 10.0, 1_010_001, OLD),
                "score above official maximum");
        expectThrows(IllegalArgumentException.class,
                () -> new ChunithmChartInput(
                        "id", "Title", "MASTER", 10.0, 1_000_000, " "),
                "blank version");
    }

    private static void testRejectsInvalidCollections() {
        expectThrows(NullPointerException.class,
                () -> ChunithmCalculator.calculate(null, List.of(NEW)),
                "null inputs");
        expectThrows(NullPointerException.class,
                () -> ChunithmCalculator.calculate(List.of(), null),
                "null latest versions");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(
                        Arrays.asList(chart("one", "MASTER", OLD, 10.0, 1_000_000), null),
                        List.of(NEW)),
                "null chart entry");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(List.of(), Arrays.asList(NEW, null)),
                "null latest version entry");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(List.of(), List.of(" ")),
                "blank latest version entry");
        expectThrows(NullPointerException.class,
                () -> ChunithmCalculator.calculate(
                        List.of(), List.of(NEW), null),
                "null disabled song IDs");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(
                        List.of(), List.of(NEW), List.of(" ")),
                "blank disabled song ID");
        expectThrows(IllegalArgumentException.class,
                () -> ChunithmCalculator.calculate(
                        List.of(), List.of("\ufeff\u00a0\u3000\t")),
                "Unicode-blank latest version entry");
    }

    private static ChunithmChartInput chart(
            String songId,
            String difficulty,
            String version,
            double constant,
            int score) {
        return new ChunithmChartInput(
                songId, "Chart " + songId, difficulty, constant, score, version);
    }

    private static String id(String prefix, int index) {
        return prefix + "-" + String.format("%02d", index);
    }

    private static ChunithmItem find(
            List<ChunithmItem> items, String songId, String difficulty) {
        for (ChunithmItem item : items) {
            if (item.songId().equals(songId) && item.difficulty().equals(difficulty)) {
                return item;
            }
        }
        throw new AssertionError("missing item: " + songId + " / " + difficulty);
    }

    private static double sum(List<ChunithmItem> items) {
        double sum = 0.0;
        for (ChunithmItem item : items) {
            sum += item.rating();
        }
        return sum;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (!expected.equals(actual)) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertDouble(double expected, double actual, String message) {
        testsRun++;
        if (Math.abs(expected - actual) > 0.000_000_001) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertBigDecimal(
            String expected, BigDecimal actual, String message) {
        testsRun++;
        if (new BigDecimal(expected).compareTo(actual) != 0) {
            throw new AssertionError(
                    message + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expectedType, Runnable action, String message) {
        testsRun++;
        try {
            action.run();
        } catch (Throwable error) {
            if (expectedType.isInstance(error)) {
                return;
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
}
