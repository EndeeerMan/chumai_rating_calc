import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Replaces title-derived/offset identifiers with the synchronized base SongID.
 *
 * <p>Titles and aliases are discovery inputs only.  Once a song is resolved,
 * every persistent chart key is {@code SongID + chartType + difficulty}.</p>
 */
public final class MaimaiScoreCanonicalizer {
    private static final Map<String, Integer> COMBO_ORDER = Map.of(
            "", 0, "fc", 1, "fcp", 2, "ap", 3, "app", 4);
    private static final Map<String, Integer> SYNC_ORDER = Map.of(
            "", 0, "sync", 1, "fs", 2, "fsp", 3, "fsd", 4, "fsdp", 5);

    private final SongCatalog songCatalog;

    public MaimaiScoreCanonicalizer(SongCatalog songCatalog) {
        this.songCatalog = Objects.requireNonNull(
                songCatalog, "songCatalog must not be null");
    }

    /** Canonicalizes and collapses duplicate chart keys, keeping best badges. */
    public List<ChartInput> charts(List<ChartInput> input) {
        Objects.requireNonNull(input, "input must not be null");
        LinkedHashMap<ChartKey, ChartInput> canonical = new LinkedHashMap<>();
        int row = 0;
        for (ChartInput chart : input) {
            row++;
            Objects.requireNonNull(chart, "chart[" + row + "] must not be null");
            ChartInput normalized = chart(chart);
            ChartKey key = ChartKey.of(normalized);
            ChartInput previous = canonical.get(key);
            canonical.put(
                    key,
                    previous == null ? normalized : strongest(previous, normalized));
        }
        List<ChartInput> result = List.copyOf(new ArrayList<>(canonical.values()));
        B50Calculator.calculate(result);
        return result;
    }

    /** Canonicalizes SongIDs and titles in imported play-history records. */
    public List<PlayHistoryStore.PlayRecord> records(
            List<PlayHistoryStore.PlayRecord> input) {
        Objects.requireNonNull(input, "input must not be null");
        List<PlayHistoryStore.PlayRecord> result = new ArrayList<>(input.size());
        int row = 0;
        for (PlayHistoryStore.PlayRecord record : input) {
            row++;
            Objects.requireNonNull(record, "record[" + row + "] must not be null");
            SongIdentity identity = identity(record.songId(), record.title());
            result.add(new PlayHistoryStore.PlayRecord(
                    record.source(),
                    record.sourceRecordId(),
                    identity.songId(),
                    identity.title(),
                    normalizeChartType(record.chartType()),
                    normalizeDifficulty(record.difficulty()),
                    record.achievement(),
                    record.dxScore(),
                    record.rank(),
                    record.comboStatus(),
                    record.syncStatus(),
                    record.judgmentDetails(),
                    record.playDetails(),
                    record.playedAt(),
                    record.importedAt(),
                    record.importBatchId()));
        }
        return List.copyOf(result);
    }

    private ChartInput chart(ChartInput chart) {
        SongIdentity identity = identity(chart.id(), chart.title());
        return new ChartInput(
                identity.songId(),
                identity.title(),
                chart.chartType(),
                chart.difficulty(),
                chart.level(),
                chart.achievement(),
                chart.version(),
                chart.comboStatus(),
                chart.syncStatus());
    }

    private SongIdentity identity(String suppliedId, String title) {
        return songCatalog.resolveSong(suppliedId, title)
                .map(song -> new SongIdentity(song.songId(), song.title()))
                .orElseGet(() -> {
                    String trimmed = suppliedId == null ? "" : suppliedId.trim();
                    if (!trimmed.matches("[0-9]+")) {
                        throw new IllegalArgumentException(
                                "歌曲“" + title + "”尚未匹配到曲库 SongID，请搜索并选择歌曲");
                    }
                    return new SongIdentity(
                            SongCatalog.canonicalSongId(trimmed), title.trim());
                });
    }

    private static ChartInput strongest(ChartInput left, ChartInput right) {
        double achievement = Math.max(left.achievement(), right.achievement());
        String combo = stronger(left.comboStatus(), right.comboStatus(), COMBO_ORDER);
        String sync = stronger(left.syncStatus(), right.syncStatus(), SYNC_ORDER);
        // Both values already describe the same canonical chart.  Prefer the
        // latest submitted catalogue metadata while merging independent score
        // achievements.
        return new ChartInput(
                right.id(),
                right.title(),
                right.chartType(),
                right.difficulty(),
                right.level(),
                achievement,
                right.version(),
                combo,
                sync);
    }

    private static String stronger(
            String left,
            String right,
            Map<String, Integer> order) {
        String normalizedLeft = left == null ? "" : left;
        String normalizedRight = right == null ? "" : right;
        return order.getOrDefault(normalizedRight, 0)
                        > order.getOrDefault(normalizedLeft, 0)
                ? normalizedRight
                : normalizedLeft;
    }

    private static String normalizeChartType(String value) {
        String normalized = Objects.requireNonNull(value, "chartType must not be null")
                .trim()
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "standard", "std", "sd" -> "standard";
            case "dx" -> "dx";
            default -> throw new IllegalArgumentException(
                    "unsupported maimai chart type: " + value);
        };
    }

    private static String normalizeDifficulty(String value) {
        String normalized = Objects.requireNonNull(value, "difficulty must not be null")
                .trim()
                .toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", "");
        return switch (normalized) {
            case "BASIC", "ADVANCED", "EXPERT", "MASTER" -> normalized;
            case "REMASTER", "RE:MASTER" -> "RE:MASTER";
            case "UTAGE" -> "UTAGE";
            default -> throw new IllegalArgumentException(
                    "unsupported maimai difficulty: " + value);
        };
    }

    private record SongIdentity(String songId, String title) {
    }

    private record ChartKey(String songId, ChartType chartType, String difficulty) {
        private static ChartKey of(ChartInput chart) {
            return new ChartKey(chart.id(), chart.chartType(), chart.difficulty());
        }
    }
}
