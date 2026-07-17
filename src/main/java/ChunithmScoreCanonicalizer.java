import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Revalidates helper-provided CHUNITHM data against the active song catalog. */
public final class ChunithmScoreCanonicalizer {
    private final ChunithmCatalog catalog;

    public ChunithmScoreCanonicalizer(ChunithmCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog must not be null");
    }

    /**
     * Replaces title, constant and version with catalog values. SongID remains
     * the primary identity and must exist; the supplied title must also match
     * that exact song (or one of its catalog aliases), so a forged ID/title pair
     * cannot silently select another song.
     */
    public List<ChunithmChartInput> charts(List<ChunithmChartInput> values) {
        Objects.requireNonNull(values, "values must not be null");
        CatalogIndex index = new CatalogIndex(catalog.currentCatalog());
        LinkedHashMap<String, ChunithmChartInput> unique = new LinkedHashMap<>();
        for (ChunithmChartInput value : List.copyOf(values)) {
            ChunithmChartInput chart = canonicalChart(value, index);
            String key = chart.songId() + "\u0000" + chart.difficulty();
            ChunithmChartInput previous = unique.get(key);
            if (previous == null || chart.score() > previous.score()) {
                unique.put(key, chart);
            }
        }
        return List.copyOf(new ArrayList<>(unique.values()));
    }

    /** Replaces record SongID/title/difficulty with the same catalog identity. */
    public List<ChunithmHistoryStore.PlayRecord> records(
            List<ChunithmHistoryStore.PlayRecord> values) {
        Objects.requireNonNull(values, "values must not be null");
        CatalogIndex index = new CatalogIndex(catalog.currentCatalog());
        return List.copyOf(values).stream()
                .map(value -> canonicalRecord(value, index))
                .toList();
    }

    private static ChunithmChartInput canonicalChart(
            ChunithmChartInput supplied,
            CatalogIndex index) {
        Objects.requireNonNull(supplied, "charts must not contain null");
        Match match = index.match(
                supplied.songId(), supplied.title(), supplied.difficulty());
        return new ChunithmChartInput(
                match.song().songId(),
                match.song().title(),
                match.chart().difficulty(),
                match.chart().constant().doubleValue(),
                supplied.score(),
                match.chart().version());
    }

    private static ChunithmHistoryStore.PlayRecord canonicalRecord(
            ChunithmHistoryStore.PlayRecord supplied,
            CatalogIndex index) {
        Objects.requireNonNull(supplied, "records must not contain null");
        Match match = index.match(
                supplied.songId(), supplied.title(), supplied.difficulty());
        return new ChunithmHistoryStore.PlayRecord(
                supplied.source(),
                supplied.sourceRecordId(),
                match.song().songId(),
                match.song().title(),
                match.chart().difficulty(),
                supplied.score(),
                supplied.rank(),
                supplied.clearStatus(),
                supplied.comboStatus(),
                supplied.playedAt(),
                supplied.track(),
                supplied.judgmentDetails(),
                supplied.importedAt(),
                supplied.importBatchId());
    }

    private static String normalizeTitle(String value) {
        Objects.requireNonNull(value, "title must not be null");
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replaceAll("\\s+", "");
    }

    private static String normalizeDifficulty(String value) {
        Objects.requireNonNull(value, "difficulty must not be null");
        String compact = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[\\s:_-]+", "");
        return switch (compact) {
            case "BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA" -> compact;
            case "WORLD'SEND", "WORLDSEND" -> "WORLD'S END";
            default -> throw new IllegalArgumentException(
                    "unsupported CHUNITHM difficulty: " + value);
        };
    }

    private record Match(ChunithmCatalog.Song song, ChunithmCatalog.Chart chart) {
    }

    private static final class CatalogIndex {
        private final Map<String, ChunithmCatalog.Song> songsById;

        private CatalogIndex(ChunithmCatalog.CatalogResult catalog) {
            songsById = new HashMap<>();
            for (ChunithmCatalog.Song song : catalog.songs()) {
                if (songsById.put(song.songId(), song) != null) {
                    throw new IllegalArgumentException(
                            "CHUNITHM catalog contains a duplicate SongID");
                }
            }
        }

        private Match match(String suppliedId, String suppliedTitle, String difficulty) {
            String songId = ChunithmCoverService.canonicalSongId(suppliedId);
            ChunithmCatalog.Song song = songsById.get(songId);
            if (song == null) {
                throw new IllegalArgumentException(
                        "CHUNITHM SongID is not present in the catalog: " + songId);
            }

            String normalizedTitle = normalizeTitle(suppliedTitle);
            boolean titleMatches = normalizeTitle(song.title()).equals(normalizedTitle)
                    || song.aliases().stream()
                            .map(ChunithmScoreCanonicalizer::normalizeTitle)
                            .anyMatch(normalizedTitle::equals);
            if (!titleMatches) {
                throw new IllegalArgumentException(
                        "CHUNITHM title does not match SongID " + songId);
            }

            String canonicalDifficulty = normalizeDifficulty(difficulty);
            ChunithmCatalog.Chart matchedChart = null;
            for (ChunithmCatalog.Chart chart : song.charts()) {
                if (normalizeDifficulty(chart.difficulty()).equals(canonicalDifficulty)) {
                    if (matchedChart != null) {
                        throw new IllegalArgumentException(
                                "CHUNITHM catalog has an ambiguous difficulty for SongID "
                                        + songId);
                    }
                    matchedChart = chart;
                }
            }
            if (matchedChart == null) {
                throw new IllegalArgumentException(
                        "CHUNITHM difficulty is not available for SongID " + songId);
            }
            return new Match(song, matchedChart);
        }
    }
}
