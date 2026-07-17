import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Offline regression tests for canonical SongID persistence and deduplication. */
public final class MaimaiScoreCanonicalizerTest {
    private static int tests;

    private static final String SNAPSHOT = """
            [
              {
                "id":"11849","title":"DATAERR0R","type":"DX",
                "ds":[4.0,7.0,11.0,14.0],
                "level":["4","7","11","14"],
                "basic_info":{"artist":"Cosmograph","genre":"舞萌",
                  "from":"舞萌DX 2026","is_new":true,"bpm":180}
              },
              {
                "id":"11853","title":"Help me, ERINNNNNN!!","type":"DX",
                "ds":[4.0,7.0,11.0,14.3],
                "level":["4","7","11","14+"],
                "basic_info":{"artist":"COOL&CREATE","genre":"东方Project",
                  "from":"舞萌DX 2026","is_new":true,"bpm":185}
              }
            ]
            """;

    private MaimaiScoreCanonicalizerTest() {
    }

    public static void main(String[] args) {
        canonicalizesOffsetAndLegacyLocalIds();
        canonicalizesHistoryIdentity();
        rejectsUnmatchedSyntheticIds();
        System.out.println(
                "MaimaiScoreCanonicalizerTest: all " + tests + " checks passed");
    }

    private static void canonicalizesOffsetAndLegacyLocalIds() {
        MaimaiScoreCanonicalizer canonicalizer = canonicalizer();
        List<ChartInput> result = canonicalizer.charts(List.of(
                chart("local-old-data-error", "DATAERR0R", 99.0, "ap", ""),
                chart("11849", "DATAERR0R", 100.5, "fc", "fsd"),
                chart("1849", "DATAERR0R", 100.5, "fc", "fsd")));

        expect(1, result.size(), "duplicate canonical chart is collapsed");
        ChartInput chart = result.getFirst();
        expect("1849", chart.id(), "DX offset folds to base SongID");
        expect("DATAERR0R", chart.title(), "canonical catalogue title is stored");
        expect(100.5, chart.achievement(), "highest achievement is retained");
        expect("ap", chart.comboStatus(), "strongest combo badge is retained");
        expect("fsd", chart.syncStatus(), "strongest sync badge is retained");
    }

    private static void canonicalizesHistoryIdentity() {
        PlayHistoryStore.PlayRecord record = new PlayHistoryStore.PlayRecord(
                "maimai-wechat", null, "11853", "Help me， ERINNNNNN！！",
                "DX", "MASTER", 100.0, 1234, "sss", "fc", null,
                "2026-07-16T04:00:00Z", null, null)
                .withPlayDetails(PlayDetails.parseMaimai(Map.of(
                        "fast", 3,
                        "dxScore", Map.of("current", 1234))));
        PlayHistoryStore.PlayRecord canonical = canonicalizer()
                .records(List.of(record))
                .getFirst();
        expect("1853", canonical.songId(), "history uses base SongID");
        expect("Help me, ERINNNNNN!!", canonical.title(),
                "history uses canonical title");
        expect("dx", canonical.chartType(), "history chart type is normalized");
        expect(record.playDetails(), canonical.playDetails(),
                "history play details survive SongID canonicalization");
    }

    private static void rejectsUnmatchedSyntheticIds() {
        boolean rejected = false;
        try {
            canonicalizer().charts(List.of(
                    chart("local-unmatched", "Not In Catalogue", 100.0, "", "")));
        } catch (IllegalArgumentException error) {
            rejected = error.getMessage().contains("SongID");
        }
        expect(true, rejected, "unmatched title must be searched instead of persisted");
    }

    private static MaimaiScoreCanonicalizer canonicalizer() {
        SongCatalog catalog = SongCatalog.fromJson(
                "[]",
                SNAPSHOT,
                endpoint -> {
                    throw new AssertionError("offline canonicalization must not use network");
                },
                Duration.ofMinutes(30),
                new AtomicLong(1)::get);
        return new MaimaiScoreCanonicalizer(catalog);
    }

    private static ChartInput chart(
            String songId,
            String title,
            double achievement,
            String combo,
            String sync) {
        return new ChartInput(
                songId, title, ChartType.DX, "MASTER", 14.0,
                achievement, Version.CURRENT, combo, sync);
    }

    private static void expect(Object expected, Object actual, String label) {
        tests++;
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(
                    label + ": expected " + expected + ", got " + actual);
        }
    }
}
