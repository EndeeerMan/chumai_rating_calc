import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Selects the best 35 legacy charts and the best 15 current-version charts.
 *
 * <p>Ranking is deterministic. Charts are ordered by rating descending,
 * achievement descending, chart constant descending, and finally chart ID
 * and difficulty ascending. The sequential ranks are therefore stable even
 * when contributions are tied.</p>
 */
public final class B50Calculator {
    public static final int LEGACY_LIMIT = 35;
    public static final int CURRENT_LIMIT = 15;

    private static final Comparator<ChartInput> RANKING_ORDER =
            Comparator.comparingInt(ChartInput::rating).reversed()
                    .thenComparing(
                            Comparator.comparingDouble(ChartInput::achievement).reversed())
                    .thenComparing(
                            Comparator.comparingDouble(ChartInput::level).reversed())
                    .thenComparing(ChartInput::id)
                    .thenComparing(ChartInput::chartType)
                    .thenComparing(ChartInput::difficulty)
                    .thenComparing(ChartInput::title);

    private B50Calculator() {
    }

    public static B50Result calculate(List<ChartInput> inputs) {
        Objects.requireNonNull(inputs, "inputs must not be null");

        List<ChartInput> allCharts = new ArrayList<>(inputs.size());
        List<ChartInput> legacyCharts = new ArrayList<>();
        List<ChartInput> currentCharts = new ArrayList<>();
        Set<ChartKey> chartKeys = new HashSet<>();

        int index = 0;
        for (ChartInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException("inputs[" + index + "] must not be null");
            }
            ChartKey key = ChartKey.of(input);
            if (!chartKeys.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate chart: " + input.id() + " / "
                                + input.chartType() + " / " + input.difficulty());
            }
            allCharts.add(input);
            switch (input.version()) {
                case LEGACY -> legacyCharts.add(input);
                case CURRENT -> currentCharts.add(input);
            }
            index++;
        }

        allCharts.sort(RANKING_ORDER);
        legacyCharts.sort(RANKING_ORDER);
        currentCharts.sort(RANKING_ORDER);

        Map<ChartKey, Integer> categoryRanks = new HashMap<>();
        addCategoryRanks(legacyCharts, categoryRanks);
        addCategoryRanks(currentCharts, categoryRanks);

        List<B50Item> items = new ArrayList<>(allCharts.size());
        for (int indexInRanking = 0; indexInRanking < allCharts.size(); indexInRanking++) {
            ChartInput input = allCharts.get(indexInRanking);
            int categoryRank = categoryRanks.get(ChartKey.of(input));
            int limit = input.version() == Version.LEGACY ? LEGACY_LIMIT : CURRENT_LIMIT;
            items.add(B50Item.from(
                    input,
                    categoryRank <= limit,
                    indexInRanking + 1,
                    categoryRank));
        }
        return new B50Result(items);
    }

    public static Comparator<ChartInput> rankingOrder() {
        return RANKING_ORDER;
    }

    private static void addCategoryRanks(
            List<ChartInput> charts, Map<ChartKey, Integer> categoryRanks) {
        for (int index = 0; index < charts.size(); index++) {
            categoryRanks.put(ChartKey.of(charts.get(index)), index + 1);
        }
    }

    private record ChartKey(String id, ChartType chartType, String difficulty) {
        static ChartKey of(ChartInput input) {
            return new ChartKey(input.id(), input.chartType(), input.difficulty());
        }
    }
}
