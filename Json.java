import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, dependency-free JSON parser and serializer.
 *
 * <p>Objects are parsed as insertion-ordered {@link Map maps}, arrays as
 * {@link List lists}, and numbers as {@link BigDecimal}. The implementation is
 * intentionally strict: trailing data, invalid escapes, non-finite numbers,
 * excessive nesting, and cyclic values are rejected.</p>
 */
public final class Json {
    private static final int MAX_DEPTH = 256;

    private Json() {
    }

    /** Parses exactly one JSON value. */
    public static Object parse(String json) {
        if (json == null) {
            throw new JsonException("JSON text must not be null");
        }
        return new Parser(json).parse();
    }

    /** Serializes maps, iterables, arrays, strings, booleans, numbers, and null. */
    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder(256);
        writeValue(value, output, new IdentityHashMap<>(), 0);
        return output.toString();
    }

    private static void writeValue(
            Object value,
            StringBuilder output,
            IdentityHashMap<Object, Boolean> activeContainers,
            int depth) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String text) {
            writeString(text, output);
        } else if (value instanceof Character character) {
            writeString(character.toString(), output);
        } else if (value instanceof Boolean bool) {
            output.append(bool);
        } else if (value instanceof Number number) {
            writeNumber(number, output);
        } else if (value instanceof Map<?, ?> map) {
            enterContainer(value, activeContainers, depth);
            try {
                output.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw new JsonException("JSON object keys must be strings");
                    }
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    writeString(key, output);
                    output.append(':');
                    writeValue(entry.getValue(), output, activeContainers, depth + 1);
                }
                output.append('}');
            } finally {
                activeContainers.remove(value);
            }
        } else if (value instanceof Iterable<?> iterable) {
            enterContainer(value, activeContainers, depth);
            try {
                output.append('[');
                boolean first = true;
                for (Object item : iterable) {
                    if (!first) {
                        output.append(',');
                    }
                    first = false;
                    writeValue(item, output, activeContainers, depth + 1);
                }
                output.append(']');
            } finally {
                activeContainers.remove(value);
            }
        } else if (value.getClass().isArray()) {
            enterContainer(value, activeContainers, depth);
            try {
                output.append('[');
                int length = Array.getLength(value);
                for (int index = 0; index < length; index++) {
                    if (index > 0) {
                        output.append(',');
                    }
                    writeValue(Array.get(value, index), output, activeContainers, depth + 1);
                }
                output.append(']');
            } finally {
                activeContainers.remove(value);
            }
        } else {
            throw new JsonException(
                    "Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    private static void enterContainer(
            Object container,
            IdentityHashMap<Object, Boolean> activeContainers,
            int depth) {
        if (depth >= MAX_DEPTH) {
            throw new JsonException("JSON nesting exceeds " + MAX_DEPTH + " levels");
        }
        if (activeContainers.put(container, Boolean.TRUE) != null) {
            throw new JsonException("Cyclic value cannot be serialized as JSON");
        }
    }

    private static void writeNumber(Number number, StringBuilder output) {
        if (number instanceof Double value && !Double.isFinite(value)) {
            throw new JsonException("Non-finite numbers are not valid JSON");
        }
        if (number instanceof Float value && !Float.isFinite(value)) {
            throw new JsonException("Non-finite numbers are not valid JSON");
        }

        if (number instanceof BigDecimal decimal) {
            output.append(decimal.toPlainString());
        } else if (number instanceof BigInteger integer
                || number instanceof Byte
                || number instanceof Short
                || number instanceof Integer
                || number instanceof Long) {
            output.append(number);
        } else if (number instanceof Double || number instanceof Float) {
            output.append(number);
        } else {
            String text = number.toString();
            try {
                output.append(new BigDecimal(text).toPlainString());
            } catch (NumberFormatException error) {
                throw new JsonException("Invalid JSON number: " + text, error);
            }
        }
    }

    private static void writeString(String value, StringBuilder output) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> output.append("\\\"");
                case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");
                case '\f' -> output.append("\\f");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\t' -> output.append("\\t");
                default -> {
                    if (character < 0x20
                            || character == '\u2028'
                            || character == '\u2029'
                            || (Character.isSurrogate(character)
                            && !isPartOfSurrogatePair(value, index))) {
                        appendUnicodeEscape(character, output);
                    } else {
                        output.append(character);
                    }
                }
            }
        }
        output.append('"');
    }

    private static boolean isPartOfSurrogatePair(String value, int index) {
        char character = value.charAt(index);
        if (Character.isHighSurrogate(character)) {
            return index + 1 < value.length()
                    && Character.isLowSurrogate(value.charAt(index + 1));
        }
        return index > 0 && Character.isHighSurrogate(value.charAt(index - 1));
    }

    private static void appendUnicodeEscape(char character, StringBuilder output) {
        final char[] hex = "0123456789abcdef".toCharArray();
        output.append("\\u")
                .append(hex[(character >>> 12) & 0xf])
                .append(hex[(character >>> 8) & 0xf])
                .append(hex[(character >>> 4) & 0xf])
                .append(hex[character & 0xf]);
    }

    /** Thrown when JSON input or a value to serialize is invalid. */
    public static final class JsonException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private JsonException(String message) {
            super(message);
        }

        private JsonException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class Parser {
        private final String input;
        private int index;

        private Parser(String input) {
            this.input = input;
        }

        private Object parse() {
            skipWhitespace();
            if (index == input.length()) {
                throw error("Expected a JSON value");
            }
            Object value = parseValue(0);
            skipWhitespace();
            if (index != input.length()) {
                throw error("Unexpected trailing data");
            }
            return value;
        }

        private Object parseValue(int depth) {
            if (depth >= MAX_DEPTH) {
                throw error("JSON nesting exceeds " + MAX_DEPTH + " levels");
            }
            if (index >= input.length()) {
                throw error("Unexpected end of JSON input");
            }

            return switch (input.charAt(index)) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject(int depth) {
            index++;
            skipWhitespace();
            Map<String, Object> object = new LinkedHashMap<>();
            if (consume('}')) {
                return object;
            }

            while (true) {
                if (index >= input.length() || input.charAt(index) != '"') {
                    throw error("Expected a string object key");
                }
                String key = parseString();
                skipWhitespace();
                require(':');
                skipWhitespace();
                Object value = parseValue(depth);
                if (object.containsKey(key)) {
                    throw error("Duplicate object key: " + key);
                }
                object.put(key, value);
                skipWhitespace();
                if (consume('}')) {
                    return object;
                }
                require(',');
                skipWhitespace();
            }
        }

        private List<Object> parseArray(int depth) {
            index++;
            skipWhitespace();
            List<Object> array = new ArrayList<>();
            if (consume(']')) {
                return array;
            }

            while (true) {
                array.add(parseValue(depth));
                skipWhitespace();
                if (consume(']')) {
                    return array;
                }
                require(',');
                skipWhitespace();
            }
        }

        private String parseString() {
            index++;
            StringBuilder value = new StringBuilder();
            while (index < input.length()) {
                char character = input.charAt(index++);
                if (character == '"') {
                    return value.toString();
                }
                if (character == '\\') {
                    if (index >= input.length()) {
                        throw error("Unterminated string escape");
                    }
                    char escaped = input.charAt(index++);
                    switch (escaped) {
                        case '"', '\\', '/' -> value.append(escaped);
                        case 'b' -> value.append('\b');
                        case 'f' -> value.append('\f');
                        case 'n' -> value.append('\n');
                        case 'r' -> value.append('\r');
                        case 't' -> value.append('\t');
                        case 'u' -> value.append(parseUnicodeEscape());
                        default -> throw error("Invalid string escape: \\" + escaped);
                    }
                } else if (character < 0x20) {
                    throw error("Unescaped control character in string");
                } else {
                    value.append(character);
                }
            }
            throw error("Unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > input.length()) {
                throw error("Incomplete Unicode escape");
            }
            int value = 0;
            for (int offset = 0; offset < 4; offset++) {
                int digit = Character.digit(input.charAt(index++), 16);
                if (digit < 0) {
                    throw error("Invalid Unicode escape");
                }
                value = value * 16 + digit;
            }
            return (char) value;
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) {
                throw error("Invalid JSON token");
            }
            index += literal.length();
            return value;
        }

        private BigDecimal parseNumber() {
            int start = index;
            consume('-');
            if (index >= input.length()) {
                throw error("Incomplete JSON number");
            }

            if (input.charAt(index) == '0') {
                index++;
                if (index < input.length() && Character.isDigit(input.charAt(index))) {
                    throw error("Leading zeros are not allowed in JSON numbers");
                }
            } else {
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }

            if (consume('.')) {
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }
            if (index < input.length()
                    && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                index++;
                if (index < input.length()
                        && (input.charAt(index) == '+' || input.charAt(index) == '-')) {
                    index++;
                }
                requireDigit();
                while (index < input.length() && Character.isDigit(input.charAt(index))) {
                    index++;
                }
            }

            String text = input.substring(start, index);
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException error) {
                throw new JsonException("Invalid JSON number at position " + start, error);
            }
        }

        private void requireDigit() {
            if (index >= input.length() || !Character.isDigit(input.charAt(index))) {
                throw error("Expected a digit");
            }
        }

        private boolean consume(char expected) {
            if (index < input.length() && input.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                throw error("Expected '" + expected + "'");
            }
        }

        private void skipWhitespace() {
            while (index < input.length()) {
                char character = input.charAt(index);
                if (character == ' ' || character == '\t'
                        || character == '\n' || character == '\r') {
                    index++;
                } else {
                    return;
                }
            }
        }

        private JsonException error(String message) {
            return new JsonException(message + " at position " + index);
        }
    }
}
