/**
 * Ranked output for one input chart.
 *
 * <p>{@code rank} is its 1-based position among all input charts, while
 * {@code categoryRank} is its 1-based position inside its version pool.</p>
 */
public record B50Item(
        String id,
        String title,
        ChartType chartType,
        String difficulty,
        double level,
        double achievement,
        Version version,
        int rating,
        boolean selected,
        int rank,
        int categoryRank) {

    static B50Item from(
            ChartInput input, boolean selected, int rank, int categoryRank) {
        return new B50Item(
                input.id(),
                input.title(),
                input.chartType(),
                input.difficulty(),
                input.level(),
                input.achievement(),
                input.version(),
                input.rating(),
                selected,
                rank,
                categoryRank);
    }
}
