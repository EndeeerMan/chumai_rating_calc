import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CHUNITHM B30 + N20 calculator.
 *
 * <p>The score breakpoints, interpolation, two-decimal floor, pool sizes and
 * fixed divisor of 50 follow Diving-Fish's official implementation in
 * {@code database/routes/chunithm.py} (main branch, 2026-04-27). Latest
 * versions must be supplied from {@code /chunithmprober/latest_version}.
 * Rating ties retain input order, matching Python's stable list sort.</p>
 *
 * <p>World's End records are retained in {@link ChunithmResult#items()} for
 * display, but are deliberately ineligible for B30/N20. Diving-Fish exposes
 * those charts with a zero constant; allowing them to fill a short pool would
 * produce non-musical contributions of up to 2.15.</p>
 */
public final class ChunithmCalculator {
    public static final int B30_LIMIT = 30;
    public static final int N20_LIMIT = 20;

    private static final Comparator<ChunithmChartInput> RANKING_ORDER =
            Comparator.comparingInt(ChunithmChartInput::ratingHundredths).reversed();

    private ChunithmCalculator() {
    }

    public static ChunithmResult calculate(
            List<ChunithmChartInput> inputs,
            Collection<String> latestVersions) {
        return calculate(inputs, latestVersions, List.of());
    }

    /**
     * Calculates B30/N20 while retaining, but excluding, songs that the
     * catalogue provider marks as disabled.
     */
    public static ChunithmResult calculate(
            List<ChunithmChartInput> inputs,
            Collection<String> latestVersions,
            Collection<String> disabledSongIds) {
        Objects.requireNonNull(inputs, "inputs must not be null");
        Set<String> latest = normalizeLatestVersions(latestVersions);
        Set<String> disabled = normalizeDisabledSongIds(disabledSongIds);

        List<ChunithmChartInput> allCharts = new ArrayList<>(inputs.size());
        List<ChunithmChartInput> oldCharts = new ArrayList<>();
        List<ChunithmChartInput> newCharts = new ArrayList<>();
        Set<ChartKey> chartKeys = new HashSet<>();

        int index = 0;
        for (ChunithmChartInput input : inputs) {
            if (input == null) {
                throw new IllegalArgumentException(
                        "inputs[" + index + "] must not be null");
            }
            ChartKey key = ChartKey.of(input);
            if (!chartKeys.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate chart: " + input.songId() + " / "
                                + input.difficulty());
            }
            String normalizedVersion = normalizeVersion(input.version());

            allCharts.add(input);
            if (!input.isWorldsEnd() && !disabled.contains(input.songId())) {
                if (latest.contains(normalizedVersion)) {
                    newCharts.add(input);
                } else {
                    oldCharts.add(input);
                }
            }
            index++;
        }

        allCharts.sort(RANKING_ORDER);
        oldCharts.sort(RANKING_ORDER);
        newCharts.sort(RANKING_ORDER);

        Map<ChartKey, Integer> poolRanks = new HashMap<>();
        addPoolRanks(oldCharts, poolRanks);
        addPoolRanks(newCharts, poolRanks);

        List<ChunithmItem> items = new ArrayList<>(allCharts.size());
        for (int rankedIndex = 0; rankedIndex < allCharts.size(); rankedIndex++) {
            ChunithmChartInput input = allCharts.get(rankedIndex);
            boolean eligible = !input.isWorldsEnd()
                    && !disabled.contains(input.songId());
            boolean newChart = latest.contains(normalizeVersion(input.version()));
            int poolRank = eligible ? poolRanks.get(ChartKey.of(input)) : 0;
            int limit = newChart ? N20_LIMIT : B30_LIMIT;
            items.add(ChunithmItem.from(
                    input,
                    newChart,
                    eligible,
                    eligible && poolRank <= limit,
                    rankedIndex + 1,
                    poolRank));
        }
        return new ChunithmResult(items);
    }

    /** Score contribution floored to exactly two decimal places. */
    public static double calculateSingleRating(double constant, int score) {
        return calculateSingleRatingHundredths(constant, score) / 100.0;
    }

    static int calculateSingleRatingHundredths(double constant, int score) {
        validateSingleRatingInput(constant, score);
        int ds = BigDecimal.valueOf(constant).movePointRight(2).intValueExact();

        if (score < 500_000) {
            return 0;
        }
        if (score < 800_000) {
            return Math.max(0, interpolateFloor(
                    500_000, 800_000, 0, (ds - 500) / 2, score));
        }
        if (score < 900_000) {
            return Math.max(0, interpolateFloor(
                    800_000, 900_000, (ds - 500) / 2, ds - 500, score));
        }
        if (score < 925_000) {
            return Math.max(0, interpolateFloor(
                    900_000, 925_000, ds - 500, ds - 300, score));
        }
        if (score < 975_000) {
            return Math.max(0, interpolateFloor(
                    925_000, 975_000, ds - 300, ds, score));
        }
        if (score < 1_000_000) {
            return interpolateFloor(975_000, 1_000_000, ds, ds + 100, score);
        }
        if (score < 1_005_000) {
            return interpolateFloor(
                    1_000_000, 1_005_000, ds + 100, ds + 150, score);
        }
        if (score < 1_007_500) {
            return interpolateFloor(
                    1_005_000, 1_007_500, ds + 150, ds + 200, score);
        }
        if (score < 1_009_000) {
            return interpolateFloor(
                    1_007_500, 1_009_000, ds + 200, ds + 215, score);
        }
        return ds + 215;
    }

    public static Comparator<ChunithmChartInput> rankingOrder() {
        return RANKING_ORDER;
    }

    private static int interpolateFloor(
            int x1, int x2, int y1Hundredths, int y2Hundredths, int x) {
        long width = x2 - x1;
        long offset = x - x1;
        long numerator = (long) y1Hundredths * width
                + (long) (y2Hundredths - y1Hundredths) * offset;
        return Math.toIntExact(Math.floorDiv(numerator, width));
    }

    private static void validateSingleRatingInput(double constant, int score) {
        if (!Double.isFinite(constant) || constant < 0.0) {
            throw new IllegalArgumentException(
                    "constant must be finite and non-negative");
        }
        if (BigDecimal.valueOf(constant).stripTrailingZeros().scale() > 1) {
            throw new IllegalArgumentException(
                    "constant must use at most 1 decimal place");
        }
        if (score < 0 || score > ChunithmChartInput.MAX_SCORE) {
            throw new IllegalArgumentException(
                    "score must be between 0 and " + ChunithmChartInput.MAX_SCORE);
        }
    }

    private static Set<String> normalizeLatestVersions(Collection<String> versions) {
        Objects.requireNonNull(versions, "latestVersions must not be null");
        Set<String> normalized = new HashSet<>();
        int index = 0;
        for (String version : versions) {
            if (version == null) {
                throw new IllegalArgumentException(
                        "latestVersions[" + index + "] must not be null");
            }
            String normalizedVersion = normalizeVersion(version);
            if (normalizedVersion.isEmpty()) {
                throw new IllegalArgumentException(
                        "latestVersions[" + index + "] must not be blank");
            }
            normalized.add(normalizedVersion);
            index++;
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeDisabledSongIds(Collection<String> songIds) {
        Objects.requireNonNull(songIds, "disabledSongIds must not be null");
        Set<String> normalized = new HashSet<>();
        int index = 0;
        for (String songId : songIds) {
            if (songId == null || songId.isBlank()) {
                throw new IllegalArgumentException(
                        "disabledSongIds[" + index + "] must not be blank");
            }
            normalized.add(songId.trim());
            index++;
        }
        return Set.copyOf(normalized);
    }

    /** Mirrors the browser's NFKC, case-insensitive, whitespace-folded key. */
    static String normalizeVersion(String version) {
        Objects.requireNonNull(version, "version must not be null");
        String normalized = Normalizer.normalize(version, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(normalized.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)
                    || Character.isSpaceChar(codePoint)
                    || codePoint == 0xfeff) {
                pendingSpace = output.length() > 0;
            } else {
                if (pendingSpace) {
                    output.append(' ');
                    pendingSpace = false;
                }
                output.appendCodePoint(codePoint);
            }
        }
        return output.toString();
    }

    private static void addPoolRanks(
            List<ChunithmChartInput> charts, Map<ChartKey, Integer> ranks) {
        for (int index = 0; index < charts.size(); index++) {
            ranks.put(ChartKey.of(charts.get(index)), index + 1);
        }
    }

    private record ChartKey(String songId, String difficulty) {
        static ChartKey of(ChunithmChartInput input) {
            return new ChartKey(input.songId(), input.difficulty());
        }
    }
}
