package ems.storage;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Minimal JSON string-array codec; avoids storing Java serialization in the database. */
final class ReasonJson {
    private ReasonJson() { }

    static String encode(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (String value : values) {
            if (result.length() > 1) result.append(',');
            result.append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '"' || c == '\\') result.append('\\').append(c);
                else if (c < 32) result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                else result.append(c);
            }
            result.append('"');
        }
        return result.append(']').toString();
    }

    static List<String> decode(String json) throws SQLException {
        List<String> values = new ArrayList<>();
        if (json == null || json.isBlank()) return values;
        String text = json.trim();
        if (!text.startsWith("[") || !text.endsWith("]")) throw new SQLException("Invalid stored spam reasons.");
        int i = 1;
        while (i < text.length() - 1) {
            if (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == ',') { i++; continue; }
            if (text.charAt(i++) != '"') throw new SQLException("Invalid stored spam reason.");
            StringBuilder value = new StringBuilder();
            boolean closed = false;
            while (i < text.length() - 1) {
                char c = text.charAt(i++);
                if (c == '"') { closed = true; break; }
                if (c != '\\') { value.append(c); continue; }
                if (i >= text.length() - 1) throw new SQLException("Invalid stored spam reason escape.");
                char escaped = text.charAt(i++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'u' -> {
                        if (i + 4 > text.length() - 1) throw new SQLException("Invalid unicode escape in spam reason.");
                        try { value.append((char) Integer.parseInt(text.substring(i, i + 4), 16)); }
                        catch (NumberFormatException bad) { throw new SQLException("Invalid unicode escape in spam reason.", bad); }
                        i += 4;
                    }
                    default -> throw new SQLException("Invalid stored spam reason escape.");
                }
            }
            if (!closed) throw new SQLException("Unclosed stored spam reason.");
            values.add(value.toString());
        }
        return values;
    }
}
