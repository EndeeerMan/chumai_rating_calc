import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Merges official CHUNITHM per-chart bests into one user's score set. */
public final class ChunithmChartMerger {
    private ChunithmChartMerger() {
    }

    /**
     * Uses {@code songId + difficulty} as the only chart identity. Repeated
     * identical rows are ignored and rows with a higher score replace the
     * existing score. Catalog metadata from the incoming canonical row is
     * retained even when the previous score remains higher.
     */
    public static MergeResult merge(
            List<ChunithmChartInput> existing,
            List<ChunithmChartInput> imported) {
        Objects.requireNonNull(existing, "existing must not be null");
        Objects.requireNonNull(imported, "imported must not be null");

        LinkedHashMap<ChartKey, ChunithmChartInput> merged = new LinkedHashMap<>();
        for (ChunithmChartInput chart : List.copyOf(existing)) {
            Objects.requireNonNull(chart, "existing must not contain null");
            ChartKey key = ChartKey.of(chart);
            if (merged.putIfAbsent(key, chart) != null) {
                throw new IllegalArgumentException(
                        "existing CHUNITHM scores contain a duplicate chart");
            }
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (ChunithmChartInput incoming : List.copyOf(imported)) {
            Objects.requireNonNull(incoming, "imported must not contain null");
            ChartKey key = ChartKey.of(incoming);
            ChunithmChartInput current = merged.get(key);
            if (current == null) {
                merged.put(key, incoming);
                added++;
                continue;
            }

            ChunithmChartInput next = new ChunithmChartInput(
                    incoming.songId(),
                    incoming.title(),
                    incoming.difficulty(),
                    incoming.constant(),
                    Math.max(current.score(), incoming.score()),
                    incoming.version());
            if (next.equals(current)) {
                unchanged++;
            } else {
                merged.put(key, next);
                updated++;
            }
        }

        return new MergeResult(
                List.copyOf(new ArrayList<>(merged.values())),
                added,
                updated,
                unchanged);
    }

    public record MergeResult(
            List<ChunithmChartInput> charts,
            int added,
            int updated,
            int unchanged) {
        public MergeResult {
            charts = List.copyOf(Objects.requireNonNull(
                    charts, "charts must not be null"));
            if (added < 0 || updated < 0 || unchanged < 0) {
                throw new IllegalArgumentException(
                        "Merge counters must not be negative");
            }
        }

        public int changed() {
            return Math.addExact(added, updated);
        }
    }

    private record ChartKey(String songId, String difficulty) {
        private static ChartKey of(ChunithmChartInput chart) {
            return new ChartKey(
                    chart.songId(),
                    chart.difficulty().toUpperCase(Locale.ROOT));
        }
    }
}
