import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, reliably observed maimai result-page values for one play.
 *
 * <p>Every value is optional because the official page can change independently
 * and the helper must not invent data it cannot parse. An object that is
 * present must contain at least one verified value.</p>
 */
public final class PlayDetails {
    private static final int MAX_COUNT = 100_000_000;
    private static final int MAX_RATING_DELTA = 100_000;
    private static final int MAX_PARTNERS = 5;
    private static final int MAX_IMAGE_URL_LENGTH = 2_048;
    private static final String OFFICIAL_IMAGE_ORIGIN =
            "https://maimai.wahlap.com";
    private static final Set<String> ROOT_KEYS = Set.of(
            "fast", "late", "maxCombo", "maxSync", "dxScore", "rating",
            "partners");
    private static final Set<String> PROGRESS_KEYS = Set.of(
            "current", "maximum");
    private static final Set<String> DX_SCORE_KEYS = Set.of(
            "current", "maximum");
    private static final Set<String> RATING_KEYS = Set.of(
            "value", "playerTotal", "delta", "frame", "frameImageUrl");
    private static final Set<String> RATING_FRAMES = Set.of(
            "white", "blue", "green", "yellow", "red", "purple",
            "bronze", "silver", "gold", "platinum", "rainbow");
    private static final Set<String> PARTNER_KEYS = Set.of(
            "stars", "level", "imageUrl");

    private final Integer fast;
    private final Integer late;
    private final Progress maxCombo;
    private final Progress maxSync;
    private final DxScore dxScore;
    private final Rating rating;
    private final List<Partner> partners;

    private PlayDetails(
            Integer fast,
            Integer late,
            Progress maxCombo,
            Progress maxSync,
            DxScore dxScore,
            Rating rating,
            List<Partner> partners) {
        if (fast == null
                && late == null
                && maxCombo == null
                && maxSync == null
                && dxScore == null
                && rating == null
                && partners == null) {
            throw new IllegalArgumentException(
                    "playDetails must contain at least one field");
        }
        this.fast = fast;
        this.late = late;
        this.maxCombo = maxCombo;
        this.maxSync = maxSync;
        this.dxScore = dxScore;
        this.rating = rating;
        this.partners = partners == null ? null : List.copyOf(partners);
    }

    /** Strictly parses the optional public/import JSON shape. */
    public static PlayDetails parseMaimai(Object value) {
        if (value == null) {
            return null;
        }
        Map<String, Object> root = requireObject(value, "playDetails");
        requireAllowedKeys(root, ROOT_KEYS, "playDetails");
        return new PlayDetails(
                optionalCount(root, "fast", "playDetails.fast"),
                optionalCount(root, "late", "playDetails.late"),
                parseProgress(root.get("maxCombo"),
                        root.containsKey("maxCombo"), "playDetails.maxCombo"),
                parseProgress(root.get("maxSync"),
                        root.containsKey("maxSync"), "playDetails.maxSync"),
                parseDxScore(root.get("dxScore"),
                        root.containsKey("dxScore"), "playDetails.dxScore"),
                parseRating(root.get("rating"),
                        root.containsKey("rating"), "playDetails.rating"),
                parsePartners(root.get("partners"),
                        root.containsKey("partners"), "playDetails.partners"));
    }

    public Integer fast() {
        return fast;
    }

    public Integer late() {
        return late;
    }

    public Progress maxCombo() {
        return maxCombo;
    }

    public Progress maxSync() {
        return maxSync;
    }

    public DxScore dxScore() {
        return dxScore;
    }

    public Rating rating() {
        return rating;
    }

    public List<Partner> partners() {
        return partners;
    }

    /**
     * Fills only values absent from this instance. Existing captured values win
     * on conflicts so a later import cannot rewrite historical result data.
     */
    public PlayDetails mergeMissing(PlayDetails incoming) {
        Objects.requireNonNull(incoming, "incoming must not be null");
        return new PlayDetails(
                first(fast, incoming.fast),
                first(late, incoming.late),
                Progress.merge(maxCombo, incoming.maxCombo),
                Progress.merge(maxSync, incoming.maxSync),
                DxScore.merge(dxScore, incoming.dxScore),
                Rating.merge(rating, incoming.rating),
                partners == null ? incoming.partners : partners);
    }

    /** Returns an immutable value suitable for {@link Json#stringify}. */
    public Map<String, Object> toJsonValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        putIfPresent(value, "fast", fast);
        putIfPresent(value, "late", late);
        putIfPresent(value, "maxCombo", maxCombo);
        putIfPresent(value, "maxSync", maxSync);
        putIfPresent(value, "dxScore", dxScore);
        putIfPresent(value, "rating", rating);
        if (partners != null) {
            value.put(
                    "partners",
                    partners.stream().map(Partner::toJsonValue).toList());
        }
        return Collections.unmodifiableMap(value);
    }

    private static Progress parseProgress(
            Object value, boolean present, String field) {
        if (!present) {
            return null;
        }
        Map<String, Object> raw = requireObject(value, field);
        requireAllowedKeys(raw, PROGRESS_KEYS, field);
        return new Progress(
                optionalCount(raw, "current", field + ".current"),
                optionalCount(raw, "maximum", field + ".maximum"));
    }

    private static DxScore parseDxScore(
            Object value, boolean present, String field) {
        if (!present) {
            return null;
        }
        Map<String, Object> raw = requireObject(value, field);
        requireAllowedKeys(raw, DX_SCORE_KEYS, field);
        return new DxScore(
                optionalCount(raw, "current", field + ".current"),
                optionalCount(raw, "maximum", field + ".maximum"));
    }

    private static Rating parseRating(
            Object value, boolean present, String field) {
        if (!present) {
            return null;
        }
        Map<String, Object> raw = requireObject(value, field);
        requireAllowedKeys(raw, RATING_KEYS, field);
        return new Rating(
                optionalCount(raw, "value", field + ".value"),
                optionalCount(raw, "playerTotal", field + ".playerTotal"),
                optionalSignedCount(
                        raw, "delta", field + ".delta", MAX_RATING_DELTA),
                raw.containsKey("frame")
                        ? requireRatingFrame(raw.get("frame"), field + ".frame")
                        : null,
                raw.containsKey("frameImageUrl")
                        ? validateOfficialImageUrl(
                                requireString(
                                        raw.get("frameImageUrl"),
                                        field + ".frameImageUrl",
                                        MAX_IMAGE_URL_LENGTH),
                                field + ".frameImageUrl")
                        : null);
    }

    private static List<Partner> parsePartners(
            Object value, boolean present, String field) {
        if (!present) {
            return null;
        }
        if (!(value instanceof List<?> raw)) {
            throw new IllegalArgumentException(field + " must be a JSON array");
        }
        if (raw.isEmpty() || raw.size() > MAX_PARTNERS) {
            throw new IllegalArgumentException(
                    field + " must contain between 1 and " + MAX_PARTNERS
                            + " entries");
        }
        List<Partner> result = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            String itemField = field + "[" + index + "]";
            Map<String, Object> item = requireObject(raw.get(index), itemField);
            requireExactKeys(item, PARTNER_KEYS, itemField);
            result.add(new Partner(
                    requiredCount(item, "stars", itemField + ".stars"),
                    requiredCount(item, "level", itemField + ".level"),
                    requireString(item.get("imageUrl"),
                            itemField + ".imageUrl", MAX_IMAGE_URL_LENGTH)));
        }
        return List.copyOf(result);
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

    private static void requireAllowedKeys(
            Map<String, Object> value, Set<String> allowed, String field) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must contain at least one field");
        }
        Set<String> unknown = new LinkedHashSet<>(value.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " contains unknown field: " + unknown.iterator().next());
        }
    }

    private static void requireExactKeys(
            Map<String, Object> value, Set<String> expected, String field) {
        requireAllowedKeys(value, expected, field);
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(value.keySet());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(
                    field + " is missing field: " + missing.iterator().next());
        }
    }

    private static int requiredCount(
            Map<String, Object> value, String key, String field) {
        Integer count = optionalCount(value, key, field);
        if (count == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return count;
    }

    private static String requireString(
            Object value, String field, int maxLength) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String normalized = text.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is too long");
        }
        return normalized;
    }

    private static Integer optionalCount(
            Map<String, Object> value, String key, String field) {
        if (!value.containsKey(key)) {
            return null;
        }
        Object raw = value.get(key);
        final int count;
        if (raw instanceof BigDecimal number) {
            try {
                count = number.intValueExact();
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        field + " must be an integer", error);
            }
        } else if (raw instanceof Integer number) {
            count = number;
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (count < 0 || count > MAX_COUNT) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return count;
    }

    private static Integer optionalSignedCount(
            Map<String, Object> value,
            String key,
            String field,
            int absoluteMaximum) {
        if (!value.containsKey(key)) {
            return null;
        }
        Object raw = value.get(key);
        final int count;
        if (raw instanceof BigDecimal number) {
            try {
                count = number.intValueExact();
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        field + " must be an integer", error);
            }
        } else if (raw instanceof Integer number) {
            count = number;
        } else {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        if (count < -absoluteMaximum || count > absoluteMaximum) {
            throw new IllegalArgumentException(field + " is out of range");
        }
        return count;
    }

    private static Integer first(Integer existing, Integer incoming) {
        return existing == null ? incoming : existing;
    }

    private static void putIfPresent(
            Map<String, Object> target, String key, Object value) {
        if (value instanceof Progress progress) {
            target.put(key, progress.toJsonValue());
        } else if (value instanceof DxScore score) {
            target.put(key, score.toJsonValue());
        } else if (value instanceof Rating capturedRating) {
            target.put(key, capturedRating.toJsonValue());
        } else if (value != null) {
            target.put(key, value);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PlayDetails details
                && Objects.equals(fast, details.fast)
                && Objects.equals(late, details.late)
                && Objects.equals(maxCombo, details.maxCombo)
                && Objects.equals(maxSync, details.maxSync)
                && Objects.equals(dxScore, details.dxScore)
                && Objects.equals(rating, details.rating)
                && Objects.equals(partners, details.partners);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                fast, late, maxCombo, maxSync, dxScore, rating, partners);
    }

    @Override
    public String toString() {
        return toJsonValue().toString();
    }

    /** A captured current/maximum pair. Either side may be unavailable. */
    public record Progress(Integer current, Integer maximum) {
        public Progress {
            if (current == null && maximum == null) {
                throw new IllegalArgumentException(
                        "playDetails progress must contain at least one field");
            }
            validateCount(current, "current");
            validateCount(maximum, "maximum");
            if (current != null && maximum != null && current > maximum) {
                throw new IllegalArgumentException(
                        "playDetails current must not exceed maximum");
            }
        }

        private static Progress merge(Progress existing, Progress incoming) {
            if (existing == null) {
                return incoming;
            }
            if (incoming == null) {
                return existing;
            }
            Integer current = first(existing.current, incoming.current);
            Integer maximum = first(existing.maximum, incoming.maximum);
            if (current != null && maximum != null && current > maximum) {
                if (existing.current == null) {
                    current = null;
                } else if (existing.maximum == null) {
                    maximum = null;
                }
            }
            return new Progress(current, maximum);
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            putIfPresent(value, "current", current);
            putIfPresent(value, "maximum", maximum);
            return Collections.unmodifiableMap(value);
        }
    }

    /** Captured DX score values. Missing official values remain absent. */
    public record DxScore(Integer current, Integer maximum) {
        public DxScore {
            if (current == null && maximum == null) {
                throw new IllegalArgumentException(
                        "playDetails.dxScore must contain at least one field");
            }
            validateCount(current, "current");
            validateCount(maximum, "maximum");
            if (current != null && maximum != null && current > maximum) {
                throw new IllegalArgumentException(
                        "playDetails.dxScore.current must not exceed maximum");
            }
        }

        private static DxScore merge(DxScore existing, DxScore incoming) {
            if (existing == null) {
                return incoming;
            }
            if (incoming == null) {
                return existing;
            }
            Integer current = first(existing.current, incoming.current);
            Integer maximum = first(existing.maximum, incoming.maximum);
            if (current != null && maximum != null && current > maximum) {
                if (existing.current == null) {
                    current = null;
                } else if (existing.maximum == null) {
                    maximum = null;
                }
            }
            return new DxScore(
                    current,
                    maximum);
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            putIfPresent(value, "current", current);
            putIfPresent(value, "maximum", maximum);
            return Collections.unmodifiableMap(value);
        }
    }

    /** Captured rating values shown for this play and the player's total. */
    public record Rating(
            Integer value,
            Integer playerTotal,
            Integer delta,
            String frame,
            String frameImageUrl) {
        public Rating {
            if (value == null
                    && playerTotal == null
                    && delta == null
                    && frame == null
                    && frameImageUrl == null) {
                throw new IllegalArgumentException(
                        "playDetails.rating must contain at least one field");
            }
            validateCount(value, "rating.value");
            validateCount(playerTotal, "rating.playerTotal");
            validateSignedCount(delta, "rating.delta", MAX_RATING_DELTA);
            if (frame != null) {
                frame = requireRatingFrame(
                        frame, "playDetails.rating.frame");
            }
            if (frameImageUrl != null) {
                frameImageUrl = validateOfficialImageUrl(
                        frameImageUrl, "playDetails.rating.frameImageUrl");
            }
        }

        private static Rating merge(Rating existing, Rating incoming) {
            if (existing == null) {
                return incoming;
            }
            if (incoming == null) {
                return existing;
            }
            return new Rating(
                    first(existing.value, incoming.value),
                    first(existing.playerTotal, incoming.playerTotal),
                    first(existing.delta, incoming.delta),
                    existing.frame == null ? incoming.frame : existing.frame,
                    existing.frameImageUrl == null
                            ? incoming.frameImageUrl
                            : existing.frameImageUrl);
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> valueMap = new LinkedHashMap<>();
            putIfPresent(valueMap, "value", value);
            putIfPresent(valueMap, "playerTotal", playerTotal);
            putIfPresent(valueMap, "delta", delta);
            putIfPresent(valueMap, "frame", frame);
            putIfPresent(valueMap, "frameImageUrl", frameImageUrl);
            return Collections.unmodifiableMap(valueMap);
        }
    }

    /** One official travel partner shown on the maimai result page. */
    public record Partner(int stars, int level, String imageUrl) {
        public Partner {
            if (stars < 0 || stars > MAX_COUNT) {
                throw new IllegalArgumentException(
                        "playDetails.partners.stars is out of range");
            }
            if (level < 0 || level > MAX_COUNT) {
                throw new IllegalArgumentException(
                        "playDetails.partners.level is out of range");
            }
            imageUrl = validateOfficialImageUrl(
                    imageUrl, "playDetails.partners.imageUrl");
        }

        private Map<String, Object> toJsonValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("stars", stars);
            value.put("level", level);
            value.put("imageUrl", imageUrl);
            return Collections.unmodifiableMap(value);
        }
    }

    private static String validateOfficialImageUrl(String value, String field) {
        String imageUrl = Objects.requireNonNull(
                value, field + " must not be null").trim();
        if (imageUrl.isEmpty() || imageUrl.length() > MAX_IMAGE_URL_LENGTH) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        final URI uri;
        try {
            uri = new URI(imageUrl);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException(
                    field + " is invalid", error);
        }
        String rawPath = uri.getRawPath();
        String lowerPath = rawPath == null
                ? ""
                : rawPath.toLowerCase(Locale.ROOT);
        boolean supportedImage = lowerPath.endsWith(".png")
                || lowerPath.endsWith(".jpg")
                || lowerPath.endsWith(".jpeg")
                || lowerPath.endsWith(".webp");
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"maimai.wahlap.com".equals(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || rawPath == null
                || !rawPath.startsWith("/maimai-mobile/img/")
                || rawPath.indexOf('%') >= 0
                || rawPath.indexOf('\\') >= 0
                || !supportedImage
                || !uri.normalize().getRawPath().equals(rawPath)) {
            throw new IllegalArgumentException(
                    field + " must be a normalized "
                            + "official maimai image URL");
        }
        return OFFICIAL_IMAGE_ORIGIN + rawPath;
    }

    private static String requireRatingFrame(Object value, String field) {
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(field + " must be a string");
        }
        String frame = text.trim().toLowerCase(Locale.ROOT);
        if (!RATING_FRAMES.contains(frame)) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return frame;
    }

    private static void validateCount(Integer value, String field) {
        if (value != null && (value < 0 || value > MAX_COUNT)) {
            throw new IllegalArgumentException(
                    "playDetails." + field + " is out of range");
        }
    }

    private static void validateSignedCount(
            Integer value, String field, int absoluteMaximum) {
        if (value != null
                && (value < -absoluteMaximum || value > absoluteMaximum)) {
            throw new IllegalArgumentException(
                    "playDetails." + field + " is out of range");
        }
    }
}
