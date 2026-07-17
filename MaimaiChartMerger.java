import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Merges official per-chart bests into a user's existing maimai score set. */
public final class MaimaiChartMerger {
    private static final Map<String, Integer> COMBO_ORDER = Map.of(
            "", 0,
            "fc", 1,
            "fcp", 2,
            "ap", 3,
            "app", 4);
    private static final Map<String, Integer> SYNC_ORDER = Map.of(
            "", 0,
            "sync", 1,
            "fs", 2,
            "fsp", 3,
            "fsd", 4,
            "fsdp", 5);

    private MaimaiChartMerger() {
    }

    /**
     * Keeps the highest achievement for each chart while independently
     * preserving the strongest FC/AP and sync badges reported by the official
     * merged-best page.
     */
    public static MergeResult merge(
            List<ChartInput> existing,
            List<ChartInput> imported) {
        Objects.requireNonNull(existing, "existing must not be null");
        Objects.requireNonNull(imported, "imported must not be null");
        B50Calculator.calculate(existing);

        LinkedHashMap<ChartKey, ChartInput> merged = new LinkedHashMap<>();
        for (ChartInput chart : existing) {
            merged.put(ChartKey.of(chart), chart);
        }

        int added = 0;
        int updated = 0;
        int unchanged = 0;
        for (ChartInput incoming : imported) {
            ChartKey key = ChartKey.of(incoming);
            ChartInput current = merged.get(key);
            if (current == null) {
                merged.put(key, incoming);
                added++;
                continue;
            }
            ChartInput next = mergeOne(current, incoming);
            if (next.equals(current)) {
                unchanged++;
            } else {
                merged.put(key, next);
                updated++;
            }
        }

        List<ChartInput> charts = List.copyOf(new ArrayList<>(merged.values()));
        B50Calculator.calculate(charts);
        return new MergeResult(charts, added, updated, unchanged);
    }

    private static ChartInput mergeOne(ChartInput current, ChartInput incoming) {
        ChartInput scoreWinner = incoming.achievement() > current.achievement()
                ? incoming
                : current;
        String combo = stronger(
                current.comboStatus(), incoming.comboStatus(), COMBO_ORDER);
        String sync = stronger(
                current.syncStatus(), incoming.syncStatus(), SYNC_ORDER);

        // Official catalog metadata is preferred even when the score itself is
        // unchanged, because constants and new/old pool membership can change.
        return new ChartInput(
                incoming.id(),
                incoming.title(),
                incoming.chartType(),
                incoming.difficulty(),
                incoming.level(),
                scoreWinner.achievement(),
                incoming.version(),
                combo,
                sync);
    }

    private static String stronger(
            String left,
            String right,
            Map<String, Integer> order) {
        return order.get(right) > order.get(left) ? right : left;
    }

    public record MergeResult(
            List<ChartInput> charts,
            int added,
            int updated,
            int unchanged) {
        public MergeResult {
            charts = List.copyOf(Objects.requireNonNull(charts, "charts must not be null"));
            if (added < 0 || updated < 0 || unchanged < 0) {
                throw new IllegalArgumentException("Merge counters must not be negative");
            }
        }

        public int changed() {
            return Math.addExact(added, updated);
        }
    }

    private record ChartKey(String id, ChartType chartType, String difficulty) {
        private static ChartKey of(ChartInput chart) {
            return new ChartKey(
                    chart.id(),
                    chart.chartType(),
                    chart.difficulty().toUpperCase(Locale.ROOT));
        }
    }
}
