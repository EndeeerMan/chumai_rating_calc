import java.util.List;

public final class MaimaiChartMergerTest {
    private MaimaiChartMergerTest() {
    }

    public static void main(String[] args) {
        keepsHighestAndStrongestBadges();
        addsNewChartsAndPreservesChartPools();
        repeatedIncomingRowsAreIdempotent();
        System.out.println("MaimaiChartMergerTest: all checks passed");
    }

    private static void keepsHighestAndStrongestBadges() {
        ChartInput existing = chart(
                "100", "Old title", "MASTER", 13.0, 100.5000,
                Version.LEGACY, "ap", "fs");
        ChartInput imported = chart(
                "100", "Current title", "MASTER", 13.1, 100.0000,
                Version.LEGACY, "fc", "fsd");
        MaimaiChartMerger.MergeResult result = MaimaiChartMerger.merge(
                List.of(existing), List.of(imported));
        ChartInput merged = result.charts().getFirst();
        assertEquals(100.5000, merged.achievement(), "highest score");
        assertEquals("ap", merged.comboStatus(), "strongest combo");
        assertEquals("fsd", merged.syncStatus(), "strongest sync");
        assertEquals("Current title", merged.title(), "official title");
        assertEquals(13.1, merged.level(), "official constant");
        assertEquals(1, result.updated(), "updated count");
    }

    private static void addsNewChartsAndPreservesChartPools() {
        ChartInput oldExpert = chart(
                "200", "Song", "EXPERT", 12.0, 99.0000,
                Version.LEGACY, "", "");
        ChartInput newMaster = chart(
                "200", "Song", "MASTER", 13.5, 100.0000,
                Version.CURRENT, "fc", "");
        MaimaiChartMerger.MergeResult result = MaimaiChartMerger.merge(
                List.of(oldExpert), List.of(newMaster));
        assertEquals(2, result.charts().size(), "chart count");
        assertEquals(1, result.added(), "added count");
        assertEquals(Version.LEGACY, result.charts().get(0).version(),
                "existing older chart keeps its B35 classification");
        assertEquals(Version.CURRENT, result.charts().get(1).version(),
                "newly added chart keeps its B15 classification");
    }

    private static void repeatedIncomingRowsAreIdempotent() {
        ChartInput lower = chart(
                "300", "Song", "MASTER", 14.0, 99.0,
                Version.CURRENT, "ap", "");
        ChartInput higher = chart(
                "300", "Song", "MASTER", 14.0, 100.5,
                Version.CURRENT, "fc", "fsd");
        MaimaiChartMerger.MergeResult result = MaimaiChartMerger.merge(
                List.of(), List.of(lower, lower, higher));
        assertEquals(1, result.charts().size(), "repeated row count");
        assertEquals(100.5, result.charts().getFirst().achievement(),
                "repeated rows keep highest score");
        assertEquals("ap", result.charts().getFirst().comboStatus(),
                "repeated rows keep strongest combo");
        assertEquals("fsd", result.charts().getFirst().syncStatus(),
                "repeated rows keep strongest sync");
    }

    private static ChartInput chart(
            String id,
            String title,
            String difficulty,
            double level,
            double achievement,
            Version version,
            String combo,
            String sync) {
        return new ChartInput(
                id,
                title,
                ChartType.DX,
                difficulty,
                level,
                achievement,
                version,
                combo,
                sync);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + expected + ", got " + actual);
        }
    }
}
