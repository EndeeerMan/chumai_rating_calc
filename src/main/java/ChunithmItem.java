import java.math.BigDecimal;
import java.util.Objects;

/** Immutable ranked output for one CHUNITHM input chart. */
public record ChunithmItem(
        String songId,
        String title,
        String difficulty,
        double constant,
        int score,
        String version,
        boolean newChart,
        boolean ratingEligible,
        int ratingHundredths,
        boolean selected,
        int rank,
        int poolRank) {

    public ChunithmItem {
        Objects.requireNonNull(songId, "songId must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(difficulty, "difficulty must not be null");
        Objects.requireNonNull(version, "version must not be null");
        if (ratingHundredths < 0) {
            throw new IllegalArgumentException("ratingHundredths must be non-negative");
        }
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        if (ratingEligible && poolRank < 1) {
            throw new IllegalArgumentException(
                    "eligible charts must have a positive poolRank");
        }
        if (!ratingEligible && (poolRank != 0 || selected)) {
            throw new IllegalArgumentException(
                    "ineligible charts cannot have a pool rank or be selected");
        }
    }

    /** Single-chart Rating as an ordinary JSON-friendly number. */
    public double rating() {
        return ratingHundredths / 100.0;
    }

    /** Exact single-chart Rating with two-decimal precision. */
    public BigDecimal ratingDecimal() {
        return BigDecimal.valueOf(ratingHundredths, 2);
    }

    static ChunithmItem from(
            ChunithmChartInput input,
            boolean newChart,
            boolean ratingEligible,
            boolean selected,
            int rank,
            int poolRank) {
        return new ChunithmItem(
                input.songId(),
                input.title(),
                input.difficulty(),
                input.constant(),
                input.score(),
                input.version(),
                newChart,
                ratingEligible,
                input.ratingHundredths(),
                selected,
                rank,
                poolRank);
    }
}
