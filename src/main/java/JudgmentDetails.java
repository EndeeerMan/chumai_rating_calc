import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable player-result details for one play.
 *
 * <p>maimai exposes a per-note-type judgment table. CHUNITHM instead exposes
 * global judgments, per-note-type achievement percentages and maximum combo.
 * Older stored CHUNITHM records may still contain the former note-count shape;
 * it remains readable so a later official synchronization can enrich it.</p>
 *
 * <pre>{@code
 * // maimai
 * {
 *   "byNoteType":{"tap":{"criticalPerfect":0,"perfect":0,"great":0,"good":0,"miss":0}}
 * }
 *
 * // CHUNITHM (there is deliberately no byNoteType field)
 * {
 *   "judgments":{"justiceCritical":0,"justice":0,"attack":0,"miss":0},
 *   "noteAchievements":{"tap":97.05,"hold":100.98,"slide":100.35,
 *     "air":99.52,"flick":96.93},
 *   "maxCombo":0
 * }
 * }</pre>
 */
public final class JudgmentDetails {
    private enum Game {
        MAIMAI,
        CHUNITHM
    }

    private static final int MAX_COUNT = 10_000_000;
    private static final BigDecimal MAX_ACHIEVEMENT = new BigDecimal("101.00");
    private static final Set<String> CHUNITHM_REQUIRED_KEYS = Set.of(
            "judgments", "maxCombo");
    private static final Set<String> CHUNITHM_ALLOWED_KEYS = Set.of(
            "judgments", "noteCounts", "noteAchievements", "maxCombo");
    private static final Set<String> MAIMAI_KEYS = Set.of("byNoteType");
    private static final Set<String> MAIMAI_NOTE_TYPES = Set.of(
            "tap", "hold", "slide", "touch", "break");
    private static final Set<String> MAIMAI_JUDGMENTS = Set.of(
            "criticalPerfect", "perfect", "great", "good", "miss");
    private static final Set<String> CHUNITHM_NOTE_TYPES = Set.of(
            "tap", "hold", "slide", "air", "flick");
    private static final Set<String> CHUNITHM_JUDGMENTS = Set.of(
            "justiceCritical", "justice", "attack", "miss");

    private final Game game;
    private final Map<String, Integer> judgments;
    private final Map<String, Integer> noteCounts;
    private final Map<String, BigDecimal> noteAchievements;
    private final Integer maxCombo;
    private final Map<String, Map<String, Integer>> byNoteType;

    private JudgmentDetails(
            Game game,
            Map<String, Integer> judgments,
            Map<String, Integer> noteCounts,
            Map<String, BigDecimal> noteAchievements,
            Integer maxCombo,
            Map<String, Map<String, Integer>> byNoteType) {
        this.game = Objects.requireNonNull(game, "game must not be null");
        this.judgments = judgments == null ? null : immutableCopy(judgments);
        this.noteCounts = noteCounts == null ? null : immutableCopy(noteCounts);
        this.noteAchievements = noteAchievements == null
                ? null
                : immutableDecimalCopy(noteAchievements);
        this.maxCombo = maxCombo;
        this.byNoteType = byNoteType == null
                ? null
                : deepImmutableCopy(byNoteType);
    }

    public static JudgmentDetails parseMaimai(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> root = requireObject(value, "judgmentDetails");
        requireKeys(root, MAIMAI_KEYS, MAIMAI_KEYS, "judgmentDetails");
        Map<String, Map<String, Integer>> byNoteType = parseMatrix(
                root.get("byNoteType"),
                "judgmentDetails.byNoteType",
                MAIMAI_NOTE_TYPES,
                MAIMAI_JUDGMENTS);
        return new JudgmentDetails(
                Game.MAIMAI, null, null, null, null, byNoteType);
    }

    public static JudgmentDetails parseChunithm(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> root = requireObject(value, "judgmentDetails");
        requireKeys(root, CHUNITHM_REQUIRED_KEYS, CHUNITHM_ALLOWED_KEYS,
                "judgmentDetails");
        Map<String, Integer> judgments = parseExactCounts(
                root.get("judgments"),
                "judgmentDetails.judgments",
                CHUNITHM_JUDGMENTS);
        Map<String, Integer> noteCounts = root.containsKey("noteCounts")
                ? parseExactCounts(
                        root.get("noteCounts"),
                        "judgmentDetails.noteCounts",
                        CHUNITHM_NOTE_TYPES)
                : null;
        Map<String, BigDecimal> noteAchievements = root.containsKey(
                "noteAchievements")
                ? parseExactAchievements(
                        root.get("noteAchievements"),
                        "judgmentDetails.noteAchievements",
                        CHUNITHM_NOTE_TYPES)
                : null;
        if (noteCounts == null && noteAchievements == null) {
            throw new IllegalArgumentException(
                    "judgmentDetails must contain noteAchievements");
        }
        int maxCombo = requireCount(
                root.get("maxCombo"), "judgmentDetails.maxCombo");
        return new JudgmentDetails(
                Game.CHUNITHM, judgments, noteCounts, noteAchievements,
                maxCombo, null);
    }

    public static JudgmentDetails maimai(
            Map<String, ? extends Map<String, Integer>> byNoteType) {
        Map<String, Object> matrix = new LinkedHashMap<>();
        Objects.requireNonNull(byNoteType, "byNoteType must not be null")
                .forEach(matrix::put);
        return parseMaimai(Map.of("byNoteType", matrix));
    }

    public static JudgmentDetails chunithm(
            Map<String, Integer> judgments,
            Map<String, Integer> noteCounts,
            Integer maxCombo) {
        return parseChunithm(jsonShape(
                judgments, noteCounts, maxCombo));
    }

    public static JudgmentDetails chunithmAchievements(
            Map<String, Integer> judgments,
            Map<String, BigDecimal> noteAchievements,
            Integer maxCombo) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("judgments", Objects.requireNonNull(
                judgments, "judgments must not be null"));
        value.put("noteAchievements", Objects.requireNonNull(
                noteAchievements, "noteAchievements must not be null"));
        value.put("maxCombo", Objects.requireNonNull(
                maxCombo, "maxCombo must not be null"));
        return parseChunithm(value);
    }

    static JudgmentDetails requireMaimai(JudgmentDetails value) {
        return requireGame(value, Game.MAIMAI, "maimai");
    }

    static JudgmentDetails requireChunithm(JudgmentDetails value) {
        return requireGame(value, Game.CHUNITHM, "CHUNITHM");
    }

    public Map<String, Integer> judgments() {
        return judgments;
    }

    public Map<String, Integer> noteCounts() {
        return noteCounts;
    }

    public Map<String, BigDecimal> noteAchievements() {
        return noteAchievements;
    }

    public Integer maxCombo() {
        return maxCombo;
    }

    public Map<String, Map<String, Integer>> byNoteType() {
        return byNoteType;
    }

    /** Returns a deeply immutable value suitable for {@link Json#stringify}. */
    public Map<String, Object> toJsonValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        if (game == Game.MAIMAI) {
            value.put("byNoteType", byNoteType);
        } else {
            value.put("judgments", judgments);
            if (noteCounts != null) {
                value.put("noteCounts", noteCounts);
            }
            if (noteAchievements != null) {
                value.put("noteAchievements", noteAchievements);
            }
            value.put("maxCombo", maxCombo);
        }
        return Collections.unmodifiableMap(value);
    }

    /** Adds fields absent from an older capture without rewriting history. */
    public JudgmentDetails mergeMissing(JudgmentDetails incoming) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        if (game != incoming.game) {
            throw new IllegalArgumentException(
                    "judgmentDetails game does not match");
        }
        return new JudgmentDetails(
                game,
                judgments == null ? incoming.judgments : judgments,
                noteCounts == null ? incoming.noteCounts : noteCounts,
                noteAchievements == null
                        ? incoming.noteAchievements
                        : noteAchievements,
                maxCombo == null ? incoming.maxCombo : maxCombo,
                byNoteType == null ? incoming.byNoteType : byNoteType);
    }

    private static Map<String, Integer> parseExactCounts(
            Object value, String field, Set<String> requiredKeys) {
        Map<String, Object> raw = requireObject(value, field);
        requireKeys(raw, requiredKeys, requiredKeys, field);
        Map<String, Integer> result = new LinkedHashMap<>();
        for (String key : requiredKeys) {
            result.put(key, requireCount(raw.get(key), field + "." + key));
        }
        return result;
    }

    private static Map<String, BigDecimal> parseExactAchievements(
            Object value, String field, Set<String> requiredKeys) {
        Map<String, Object> raw = requireObject(value, field);
        requireKeys(raw, requiredKeys, requiredKeys, field);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String key : requiredKeys) {
            result.put(key, requireAchievement(
                    raw.get(key), field + "." + key));
        }
        return result;
    }

    private static Map<String, Map<String, Integer>> parseMatrix(
            Object value,
            String field,
            Set<String> allowedNoteTypes,
            Set<String> requiredJudgments) {
        Map<String, Object> raw = requireObject(value, field);
        if (raw.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must contain at least one note type");
        }
        requireKeys(raw, Set.of(), allowedNoteTypes, field);
        Map<String, Map<String, Integer>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            result.put(
                    entry.getKey(),
                    parseExactCounts(
                            entry.getValue(),
                            field + "." + entry.getKey(),
                            requiredJudgments));
        }
        return result;
    }

    private static Map<String, Object> jsonShape(
            Map<String, Integer> judgments,
            Map<String, Integer> noteCounts,
            Integer maxCombo) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("judgments", Objects.requireNonNull(
                judgments, "judgments must not be null"));
        value.put("noteCounts", Objects.requireNonNull(
                noteCounts, "noteCounts must not be null"));
        value.put("maxCombo", Objects.requireNonNull(
                maxCombo, "maxCombo must not be null"));
        return value;
    }

    private static JudgmentDetails requireGame(
            JudgmentDetails value, Game expected, String game) {
        if (value != null && value.game != expected) {
            throw new IllegalArgumentException(
                    "judgmentDetails does not use the " + game + " schema");
        }
        return value;
    }

    private static Map<String, Object> requireObject(Object value, String field) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(field + " has a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static void requireKeys(
            Map<String, Object> value,
            Set<String> required,
            Set<String> allowed,
            String field) {
        Set<String> unknown = new LinkedHashSet<>(value.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains unknown field: " + unknown.iterator().next());
        }
        Set<String> missing = new LinkedHashSet<>(required);
        missing.removeAll(value.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is missing field: " + missing.iterator().next());
        }
    }

    private static int requireCount(Object value, String field) {
        final int count;
        if (value instanceof BigDecimal number) {
            try {
                count = number.intValueExact();
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        field + " must be an integer", error);
            }
        } else if (value instanceof Integer number) {
            count = number;
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (count < 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return count;
    }

    private static BigDecimal requireAchievement(Object value, String field) {
        final BigDecimal result;
        if (value instanceof BigDecimal number) {
            result = number;
        } else if (value instanceof Integer number) {
            result = BigDecimal.valueOf(number);
        } else {
            throw new IllegalArgumentException(field + " must be a number");
        }
        BigDecimal canonical = result.stripTrailingZeros();
        if (canonical.scale() > 2
                || result.compareTo(BigDecimal.ZERO) < 0
                || result.compareTo(MAX_ACHIEVEMENT) > 0) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return canonical.scale() < 0 ? canonical.setScale(0) : canonical;
    }

    private static Map<String, Integer> immutableCopy(
            Map<String, Integer> source) {
        Objects.requireNonNull(source, "source must not be null");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, BigDecimal> immutableDecimalCopy(
            Map<String, BigDecimal> source) {
        Objects.requireNonNull(source, "source must not be null");
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Map<String, Integer>> deepImmutableCopy(
            Map<String, Map<String, Integer>> source) {
        Map<String, Map<String, Integer>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableCopy(value)));
        return Collections.unmodifiableMap(copy);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JudgmentDetails details
                && game == details.game
                && Objects.equals(judgments, details.judgments)
                && Objects.equals(noteCounts, details.noteCounts)
                && Objects.equals(noteAchievements, details.noteAchievements)
                && Objects.equals(maxCombo, details.maxCombo)
                && Objects.equals(byNoteType, details.byNoteType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                game, judgments, noteCounts, noteAchievements, maxCombo,
                byNoteType);
    }

    @Override
    public String toString() {
        return toJsonValue().toString();
    }
}
