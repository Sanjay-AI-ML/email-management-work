package ems.gmail;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.ToNumberPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Member 2 JSON helper API backed by the project's strict JSON parser.
 * Parses JSON into Java Maps, Lists, Strings, Numbers, and Booleans.
 */
public final class SimpleJson {
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT)
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE).create();

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
        if (json == null || json.isBlank()) return null;
        return JSON.fromJson(json, Object.class);
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

}
