import java.util.List;

public final class ChunithmChartMergerTest {
    private ChunithmChartMergerTest() {
    }

    public static void main(String[] args) {
        repeatedRowsUseSongIdDifficultyAndKeepHighestScore();
        lowerScoreStillRefreshesCatalogMetadata();
        System.out.println("ChunithmChartMergerTest: all checks passed");
    }

    private static void repeatedRowsUseSongIdDifficultyAndKeepHighestScore() {
        ChunithmChartInput lower = chart(
                "3", "Song", "MASTER", 12.5, 1_000_000, "OLD");
        ChunithmChartInput higher = chart(
                "3", "Song", "master", 12.5, 1_009_000, "OLD");
        ChunithmChartMerger.MergeResult result = ChunithmChartMerger.merge(
                List.of(), List.of(lower, lower, higher, lower));
        assertEquals(1, result.charts().size(), "one semantic chart");
        assertEquals(1_009_000, result.charts().getFirst().score(), "highest score");
        assertEquals(1, result.added(), "one add");
        assertEquals(1, result.updated(), "one higher update");
        assertEquals(2, result.unchanged(), "identical/lower rows ignored");
    }

    private static void lowerScoreStillRefreshesCatalogMetadata() {
        ChunithmChartInput existing = chart(
                "20", "Old title", "ULTIMA", 14.0, 1_010_000, "OLD");
        ChunithmChartInput incoming = chart(
                "20", "Canonical title", "ULTIMA", 14.2, 1_000_000, "NEW");
        ChunithmChartMerger.MergeResult result = ChunithmChartMerger.merge(
                List.of(existing), List.of(incoming));
        ChunithmChartInput merged = result.charts().getFirst();
        assertEquals(1_010_000, merged.score(), "existing higher score survives");
        assertEquals("Canonical title", merged.title(), "catalog title refreshes");
        assertEquals(14.2, merged.constant(), "catalog constant refreshes");
        assertEquals("NEW", merged.version(), "catalog version refreshes");
        assertEquals(1, result.updated(), "metadata refresh counts updated");
    }

    private static ChunithmChartInput chart(
            String id,
            String title,
            String difficulty,
            double constant,
            int score,
            String version) {
        return new ChunithmChartInput(
                id, title, difficulty, constant, score, version);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    message + ": expected " + expected + ", got " + actual);
        }
    }
}
