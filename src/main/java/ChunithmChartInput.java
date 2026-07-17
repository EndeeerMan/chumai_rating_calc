import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * One CHUNITHM chart score supplied to the B30 + N20 calculation.
 *
 * <p>The individual chart version is compared with the values returned by Diving-Fish's
 * {@code /chunithmprober/latest_version} endpoint. A chart is uniquely
 * identified by {@code songId} and its canonical difficulty.</p>
 */
public record ChunithmChartInput(
        String songId,
        String title,
        String difficulty,
        double constant,
        int score,
        String version) {

    public static final int MAX_SCORE = 1_010_000;

    public ChunithmChartInput {
        songId = requireText(songId, "songId");
        title = requireText(title, "title");
        difficulty = normalizeDifficulty(difficulty);
        version = requireText(version, "version");

        if (!Double.isFinite(constant) || constant < 0.0) {
            throw new IllegalArgumentException(
                    "constant must be finite and non-negative");
        }
        if (!hasAtMostDecimals(constant, 1)) {
            throw new IllegalArgumentException(
                    "constant must use at most 1 decimal place");
        }
        if (score < 0 || score > MAX_SCORE) {
            throw new IllegalArgumentException(
                    "score must be between 0 and " + MAX_SCORE);
        }
    }

    /** The single-chart Rating, floored to two decimal places. */
    public double rating() {
        return ChunithmCalculator.calculateSingleRating(constant, score);
    }

    /** Exact hundredths representation used while summing selected charts. */
    public int ratingHundredths() {
        return ChunithmCalculator.calculateSingleRatingHundredths(constant, score);
    }

    public boolean isWorldsEnd() {
        return "WORLD'S END".equals(difficulty);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String normalizeDifficulty(String value) {
        String normalized = requireText(value, "difficulty")
                .toUpperCase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replaceAll("[\\s:_-]+", "");
        return switch (normalized) {
            case "BASIC", "ADVANCED", "EXPERT", "MASTER", "ULTIMA" -> normalized;
            case "WORLD'SEND", "WORLDSEND" -> "WORLD'S END";
            default -> throw new IllegalArgumentException(
                    "unsupported difficulty: " + value);
        };
    }

    private static boolean hasAtMostDecimals(double value, int decimalPlaces) {
        return BigDecimal.valueOf(value).stripTrailingZeros().scale() <= decimalPlaces;
    }
}
