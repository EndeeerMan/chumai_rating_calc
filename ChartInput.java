import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * One chart score supplied to the B50 calculation.
 *
 * <p>A chart is uniquely identified by {@code id}, {@code chartType}, and
 * its canonicalized difficulty name.</p>
 */
public record ChartInput(
        String id,
        String title,
        ChartType chartType,
        String difficulty,
        double level,
        double achievement,
        Version version,
        String comboStatus,
        String syncStatus) {

    public static final double MAX_LEVEL = 15.9;
    public static final double MAX_ACHIEVEMENT = 101.0;

    public ChartInput {
        id = requireText(id, "id");
        title = requireText(title, "title");
        Objects.requireNonNull(chartType, "chartType must not be null");
        difficulty = normalizeDifficulty(difficulty);
        Objects.requireNonNull(version, "version must not be null");
        comboStatus = normalizeComboStatus(comboStatus);
        syncStatus = normalizeSyncStatus(syncStatus);

        if (!Double.isFinite(level) || level < 1.0 || level > MAX_LEVEL) {
            throw new IllegalArgumentException(
                    "level must be finite and between 1.0 and " + MAX_LEVEL);
        }
        if (!hasAtMostDecimals(level, 1)) {
            throw new IllegalArgumentException("level must use at most 1 decimal place");
        }
        if (!Double.isFinite(achievement)
                || achievement < 0.0
                || achievement > MAX_ACHIEVEMENT) {
            throw new IllegalArgumentException(
                    "achievement must be finite and between 0.0 and "
                            + MAX_ACHIEVEMENT);
        }
        if (!hasAtMostDecimals(achievement, 4)) {
            throw new IllegalArgumentException(
                    "achievement must use at most 4 decimal places");
        }
    }

    /** Backward-compatible constructor for scores without completion statuses. */
    public ChartInput(
            String id,
            String title,
            ChartType chartType,
            String difficulty,
            double level,
            double achievement,
            Version version) {
        this(id, title, chartType, difficulty, level, achievement, version, "", "");
    }

    /** Uses the coefficient table and flooring rule in {@link RatingCalc}. */
    public int rating() {
        return RatingCalc.calc(level, achievement);
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
                .replaceAll("\\s+", "");
        return switch (normalized) {
            case "BASIC", "ADVANCED", "EXPERT", "MASTER" -> normalized;
            case "REMASTER", "RE:MASTER" -> "RE:MASTER";
            default -> throw new IllegalArgumentException("unsupported difficulty: " + value);
        };
    }

    private static String normalizeComboStatus(String value) {
        String normalized = normalizeOptionalStatus(value);
        return switch (normalized) {
            case "", "fc", "fcp", "ap", "app" -> normalized;
            default -> throw new IllegalArgumentException(
                    "unsupported comboStatus: " + value);
        };
    }

    private static String normalizeSyncStatus(String value) {
        String normalized = normalizeOptionalStatus(value);
        return switch (normalized) {
            case "", "sync", "fs", "fsp", "fsd", "fsdp" -> normalized;
            default -> throw new IllegalArgumentException(
                    "unsupported syncStatus: " + value);
        };
    }

    private static String normalizeOptionalStatus(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean hasAtMostDecimals(double value, int decimalPlaces) {
        return BigDecimal.valueOf(value).stripTrailingZeros().scale() <= decimalPlaces;
    }
}
