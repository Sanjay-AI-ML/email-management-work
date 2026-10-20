package ems.gmail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight JSON parser and serializer with no external dependencies.
 * Parses JSON into Java Maps, Lists, Strings, Numbers, and Booleans.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object result = parse(json);
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new LinkedHashMap<>();
    }

    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return new Parser(trimmed).parseValue();
    }

    public static String getString(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    public static String getStringOrDefault(Map<String, Object> map, String key, String defaultValue) {
        String val = getString(map, key);
        return val != null ? val : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> getList(Map<String, Object> map, String key) {
        if (map == null) return List.of();
        Object val = map.get(key);
        if (val instanceof List<?> list) {
            return (List<Object>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getObject(Map<String, Object> map, String key) {
        if (map == null) return Map.of();
        Object val = map.get(key);
        if (val instanceof Map<?, ?> obj) {
            return (Map<String, Object>) obj;
        }
        return Map.of();
    }

    public static String escapeString(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static final class Parser {
        private final String input;
        private int pos = 0;

        Parser(String input) {
            this.input = input;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) return null;
            char c = input.charAt(pos);
            if (c == '{') return parseObject();
            if (c == '[') return parseArray();
            if (c == '"') return parseString();
            if (c == 't' || c == 'f') return parseBoolean();
            if (c == 'n') return parseNull();
            if (c == '-' || Character.isDigit(c)) return parseNumber();
            throw new IllegalArgumentException("Unexpected character at " + pos + ": " + c);
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            consume('{');
            skipWhitespace();
            if (peek('}')) {
                consume('}');
                return map;
            }
            while (pos < input.length()) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                consume(':');
                skipWhitespace();
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    consume('}');
                    break;
                }
                consume(',');
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            consume('[');
            skipWhitespace();
            if (peek(']')) {
                consume(']');
                return list;
            }
            while (pos < input.length()) {
                skipWhitespace();
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    consume(']');
                    break;
                }
                consume(',');
            }
            return list;
        }

        private String parseString() {
            consume('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= input.length()) break;
                    char escape = input.charAt(pos++);
                    switch (escape) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 <= input.length()) {
                                String hex = input.substring(pos, pos + 4);
                                pos += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                            }
                        }
                        default -> sb.append(escape);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Boolean parseBoolean() {
            if (input.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (input.startsWith("false", pos)) {
                pos += 4;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("Invalid boolean at pos " + pos);
        }

        private Object parseNull() {
            if (input.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("Invalid null literal at pos " + pos);
        }

        private Number parseNumber() {
            int start = pos;
            if (input.charAt(pos) == '-') pos++;
            while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            boolean isDecimal = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isDecimal = true;
                pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isDecimal = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && Character.isDigit(input.charAt(pos))) pos++;
            }
            String numStr = input.substring(start, pos);
            if (isDecimal) {
                return Double.parseDouble(numStr);
            }
            try {
                return Long.parseLong(numStr);
            } catch (NumberFormatException e) {
                return Double.parseDouble(numStr);
            }
        }

        private void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        private void consume(char expected) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at " + pos +
                        ", found: " + (pos < input.length() ? input.charAt(pos) : "EOF"));
            }
            pos++;
        }

        private boolean peek(char expected) {
            skipWhitespace();
            return pos < input.length() && input.charAt(pos) == expected;
        }
    }
}
