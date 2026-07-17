import java.util.ArrayList;
import java.util.List;

/**
 * Dependency-free tests. Compile the project, then run: java B50CalculatorTest
 */
public final class B50CalculatorTest {
    private static int testsRun;

    private B50CalculatorTest() {
    }

    public static void main(String[] args) {
        testRatingDelegatesToExistingFormula();
        testCompletionStatuses();
        testAllRatingCoefficientBoundaries();
        testExactDecimalFlooring();
        testSelectsBest35LegacyAnd15Current();
        testDeterministicTieBreakingAndRanks();
        testDifferentDifficultiesCanShareSongId();
        testChartTypesCanShareSongAndDifficulty();
        testPartialPoolsAndImmutableResult();
        testRejectsInvalidChartData();
        testAllowsChartSpecificVersionPools();
        testRejectsInvalidCollections();

        System.out.println("B50CalculatorTest: all " + testsRun + " tests passed.");
    }

    private static void testRatingDelegatesToExistingFormula() {
        ChartInput score = chart("rating", "MASTER", Version.LEGACY, 10.0, 100.5000);
        assertEquals(RatingCalc.calc(10.0, 100.5000), score.rating(),
                "chart rating must use RatingCalc");
        assertEquals(225, score.rating(), "100.5 coefficient boundary");
        assertEquals(223, RatingCalc.calc(10.0, 100.4999),
                "100.4999 coefficient boundary");
        assertEquals(216, RatingCalc.calc(10.0, 100.0000),
                "100.0 coefficient boundary");
    }

    private static void testCompletionStatuses() {
        ChartInput legacyConstructor = new ChartInput(
                "legacy-constructor", "Legacy", ChartType.DX, "MASTER",
                14.7, 100.5, Version.CURRENT);
        assertEquals("", legacyConstructor.comboStatus(),
                "seven-argument constructor leaves combo status empty");
        assertEquals("", legacyConstructor.syncStatus(),
                "seven-argument constructor leaves sync status empty");

        ChartInput completed = new ChartInput(
                "completed", "Completed", ChartType.DX, "MASTER",
                14.7, 100.5, Version.CURRENT, " APP ", " FsDp ");
        assertEquals("app", completed.comboStatus(),
                "combo status is trimmed and lower-cased");
        assertEquals("fsdp", completed.syncStatus(),
                "sync status is trimmed and lower-cased");
        assertEquals(legacyConstructor.rating(), completed.rating(),
                "completion statuses do not affect rating");

        ChartInput empty = new ChartInput(
                "empty", "Empty", ChartType.DX, "MASTER",
                14.7, 100.5, Version.CURRENT, null, "   ");
        assertEquals("", empty.comboStatus(), "null combo status becomes empty");
        assertEquals("", empty.syncStatus(), "blank sync status becomes empty");

        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput(
                        "bad-combo", "Bad", ChartType.DX, "MASTER",
                        14.7, 100.5, Version.CURRENT, "fc+", "fs"),
                "unknown combo status is rejected");
        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput(
                        "bad-sync", "Bad", ChartType.DX, "MASTER",
                        14.7, 100.5, Version.CURRENT, "fc", "fdx"),
                "unknown sync status is rejected");
    }

    private static void testAllRatingCoefficientBoundaries() {
        double[][] boundaries = {
                {100.5000, 225}, {100.4999, 223}, {100.0000, 216},
                {99.9999, 213}, {99.5000, 209}, {99.0000, 205},
                {98.9999, 203}, {98.0000, 198}, {97.0000, 194},
                {96.9999, 170}, {94.0000, 157}, {90.0000, 136},
                {80.0000, 108}, {79.9999, 102}, {75.0000, 90},
                {70.0000, 78}, {60.0000, 57}, {50.0000, 40},
                {40.0000, 25}, {30.0000, 14}, {20.0000, 6},
                {10.0000, 1}, {9.9999, 0}
        };

        for (double[] boundary : boundaries) {
            double achievement = boundary[0];
            int expected = (int) boundary[1];
            assertEquals(expected, RatingCalc.calc(10.0, achievement),
                    "coefficient boundary " + achievement);
        }
    }

    private static void testExactDecimalFlooring() {
        assertEquals(63, RatingCalc.calc(8.0, 70.3125),
                "exact integer contribution must not lose one to binary floating point");
        assertEquals(57, RatingCalc.calc(12.5, 57.0000),
                "exact decimal contribution at 57 percent");
        assertEquals(58, RatingCalc.calc(12.5, 58.0000),
                "exact decimal contribution at 58 percent");
    }

    private static void testSelectsBest35LegacyAnd15Current() {
        List<ChartInput> inputs = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            inputs.add(chart(id("legacy", index), "MASTER", Version.LEGACY,
                    (10 + index) / 10.0, 100.0));
        }
        for (int index = 0; index < 20; index++) {
            inputs.add(chart(id("current", index), "MASTER", Version.CURRENT,
                    (10 + index) / 10.0, 100.0));
        }

        B50Result result = B50Calculator.calculate(inputs);

        assertEquals(60, result.items().size(), "all input items are ranked");
        assertEquals(35, result.legacySelected().size(), "legacy chart limit");
        assertEquals(15, result.currentSelected().size(), "current chart limit");
        assertEquals(50, result.selectedCount(), "B50 selected count");
        assertEquals("legacy-39", result.legacySelected().getFirst().id(),
                "highest legacy chart first");
        assertEquals("legacy-05", result.legacySelected().getLast().id(),
                "lowest selected legacy chart");
        assertEquals("current-19", result.currentSelected().getFirst().id(),
                "highest current chart first");
        assertEquals("current-05", result.currentSelected().getLast().id(),
                "lowest selected current chart");
        assertEquals(sum(result.legacySelected()), result.legacyTotal(), "legacy subtotal");
        assertEquals(sum(result.currentSelected()), result.currentTotal(), "current subtotal");
        assertEquals(result.legacyTotal() + result.currentTotal(), result.totalRating(),
                "total rating");
        assertEquals(result.totalRating(), result.total(), "wire-format total alias");

        B50Item firstExcludedLegacy = find(result.items(), "legacy-04", "MASTER");
        B50Item firstExcludedCurrent = find(result.items(), "current-04", "MASTER");
        assertEquals(false, firstExcludedLegacy.selected(), "36th legacy chart excluded");
        assertEquals(36, firstExcludedLegacy.categoryRank(), "legacy pool rank");
        assertEquals(false, firstExcludedCurrent.selected(), "16th current chart excluded");
        assertEquals(16, firstExcludedCurrent.categoryRank(), "current pool rank");
    }

    private static void testDeterministicTieBreakingAndRanks() {
        List<ChartInput> inputs = List.of(
                chart("lower-achievement", "MASTER", Version.LEGACY, 10.3, 99.5),
                chart("tie-b", "MASTER", Version.LEGACY, 10.0, 100.0),
                chart("tie-a", "MASTER", Version.LEGACY, 10.0, 100.0));

        for (ChartInput input : inputs) {
            assertEquals(216, input.rating(), "tie fixture contribution");
        }

        List<B50Item> ranked = B50Calculator.calculate(inputs).items();
        assertEquals("tie-a", ranked.get(0).id(), "ID ascending final tie-break");
        assertEquals(1, ranked.get(0).rank(), "overall rank starts at one");
        assertEquals(1, ranked.get(0).categoryRank(), "category rank starts at one");
        assertEquals("tie-b", ranked.get(1).id(), "ID ascending final tie-break");
        assertEquals("lower-achievement", ranked.get(2).id(),
                "achievement descending tie-break");

        List<B50Item> levelRanked = B50Calculator.calculate(List.of(
                chart("lower-level", "MASTER", Version.LEGACY, 10.0, 10.0),
                chart("higher-level", "MASTER", Version.LEGACY, 10.5, 10.0))).items();
        assertEquals(1, levelRanked.get(0).rating(), "level tie fixture contribution");
        assertEquals(1, levelRanked.get(1).rating(), "level tie fixture contribution");
        assertEquals("higher-level", levelRanked.get(0).id(), "level descending tie-break");
    }

    private static void testDifferentDifficultiesCanShareSongId() {
        B50Result result = B50Calculator.calculate(List.of(
                chart("song", "EXPERT", Version.LEGACY, 10.0, 100.0),
                chart("song", "MASTER", Version.LEGACY, 11.0, 100.0)));
        assertEquals(2, result.items().size(), "different difficulties are unique charts");
    }

    private static void testChartTypesCanShareSongAndDifficulty() {
        B50Result result = B50Calculator.calculate(List.of(
                chart("song", ChartType.STANDARD, "MASTER",
                        Version.LEGACY, 10.0, 100.0),
                chart("song", ChartType.DX, "MASTER",
                        Version.LEGACY, 11.0, 100.0)));
        assertEquals(2, result.items().size(),
                "standard and DX charts are independently rankable");
    }

    private static void testPartialPoolsAndImmutableResult() {
        B50Result empty = B50Calculator.calculate(List.of());
        assertEquals(0, empty.totalRating(), "empty input rating");
        assertEquals(0, empty.selectedCount(), "empty input count");

        B50Result partial = B50Calculator.calculate(List.of(
                chart("legacy", "MASTER", Version.LEGACY, 12.0, 100.0),
                chart("current", "MASTER", Version.CURRENT, 13.0, 100.0)));
        assertEquals(1, partial.legacySelected().size(), "partial legacy pool");
        assertEquals(1, partial.currentSelected().size(), "partial current pool");
        expectThrows(UnsupportedOperationException.class,
                () -> partial.items().add(partial.items().getFirst()),
                "result lists must be immutable");
    }

    private static void testRejectsInvalidChartData() {
        expectThrows(NullPointerException.class,
                () -> new ChartInput(null, "Title", ChartType.DX,
                        "MASTER", 1.0, 1.0, Version.LEGACY),
                "null chart ID");
        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput("  ", "Title", ChartType.DX,
                        "MASTER", 1.0, 1.0, Version.LEGACY),
                "blank chart ID");
        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput("id", " ", ChartType.DX,
                        "MASTER", 1.0, 1.0, Version.LEGACY),
                "blank title");
        expectThrows(NullPointerException.class,
                () -> new ChartInput("id", "Title", null,
                        "MASTER", 1.0, 1.0, Version.LEGACY),
                "null chart type");
        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput("id", "Title", ChartType.DX,
                        " ", 1.0, 1.0, Version.LEGACY),
                "blank difficulty");
        expectThrows(IllegalArgumentException.class,
                () -> new ChartInput("id", "Title", ChartType.DX,
                        "UTAGE", 1.0, 1.0, Version.LEGACY),
                "unsupported difficulty");
        assertEquals("MASTER",
                new ChartInput("id", "Title", ChartType.DX,
                        "master", 1.0, 1.0, Version.LEGACY).difficulty(),
                "difficulty is canonicalized");
        assertEquals("RE:MASTER",
                new ChartInput("id", "Title", ChartType.DX,
                        "Re Master", 1.0, 1.0, Version.LEGACY).difficulty(),
                "Re:MASTER alias is canonicalized");
        expectThrows(NullPointerException.class,
                () -> new ChartInput("id", "Title", ChartType.DX,
                        "MASTER", 1.0, 1.0, null),
                "null version");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, -0.1, 1.0),
                "negative level");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 0.9, 1.0),
                "level below game domain minimum");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 16.0, 1.0),
                "level above domain maximum");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 14.25, 1.0),
                "level precision above one decimal place");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 14.0000000001, 1.0),
                "nearby level must not pass decimal-place validation");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, Double.NaN, 1.0),
                "non-finite level");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 1.0, -0.1),
                "negative achievement");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 1.0, 101.0001),
                "achievement above domain maximum");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 1.0, 100.00001),
                "achievement precision above four decimal places");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY, 8.0, 70.312499999999),
                "nearby achievement must not pass decimal-place validation");
        expectThrows(IllegalArgumentException.class,
                () -> chart("id", "MASTER", Version.LEGACY,
                        1.0, Double.POSITIVE_INFINITY),
                "non-finite achievement");
    }

    private static void testAllowsChartSpecificVersionPools() {
        B50Result result = B50Calculator.calculate(List.of(
                chart("same-song", "EXPERT", Version.LEGACY, 12.0, 100.0),
                chart("same-song", "MASTER", Version.CURRENT, 13.0, 100.0)));
        assertEquals(1, result.legacySelected().size(),
                "an older chart remains in B35 when a new chart is added later");
        assertEquals(1, result.currentSelected().size(),
                "a later chart of the same song can enter B15");
    }

    private static void testRejectsInvalidCollections() {
        expectThrows(NullPointerException.class,
                () -> B50Calculator.calculate(null), "null input list");
        expectThrows(IllegalArgumentException.class,
                () -> B50Calculator.calculate(java.util.Arrays.asList(
                        chart("id", "MASTER", Version.LEGACY, 1.0, 1.0), null)),
                "null input entry");
        expectThrows(IllegalArgumentException.class,
                () -> B50Calculator.calculate(List.of(
                        chart("same-id", "MASTER", Version.LEGACY, 1.0, 1.0),
                        chart("same-id", "MASTER", Version.CURRENT, 2.0, 2.0))),
                "duplicate chart across pools");
        expectThrows(IllegalArgumentException.class,
                () -> B50Calculator.calculate(List.of(
                        new ChartInput("case-id", "One", ChartType.DX,
                                "MASTER", 1.0, 1.0, Version.LEGACY),
                        new ChartInput("case-id", "Two", ChartType.DX,
                                "master", 2.0, 2.0, Version.LEGACY))),
                "difficulty aliases cannot bypass duplicate detection");
    }

    private static ChartInput chart(
            String id,
            String difficulty,
            Version version,
            double level,
            double achievement) {
        return chart(id, ChartType.DX, difficulty, version, level, achievement);
    }

    private static ChartInput chart(
            String id,
            ChartType chartType,
            String difficulty,
            Version version,
            double level,
            double achievement) {
        return new ChartInput(
                id, "Chart " + id, chartType, difficulty, level, achievement, version);
    }

    private static String id(String prefix, int index) {
        return prefix + "-" + String.format("%02d", index);
    }

    private static B50Item find(List<B50Item> items, String id, String difficulty) {
        for (B50Item item : items) {
            if (item.id().equals(id) && item.difficulty().equals(difficulty)) {
                return item;
            }
        }
        throw new AssertionError("missing item: " + id + " / " + difficulty);
    }

    private static int sum(List<B50Item> items) {
        int total = 0;
        for (B50Item item : items) {
            total += item.rating();
        }
        return total;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        testsRun++;
        if (!expected.equals(actual)) {
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
                message + ": expected " + expectedType.getSimpleName() + " to be thrown");
    }
}
