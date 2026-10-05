package org.aspose.pdf.forms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal zero-dependency JSON support for AcroForm export/import
 * ({@link Form#exportJson}, {@link Form#importJson}). Emits and parses the field
 * schema {@code [{"Name":..,"Flags":..,"Value":..}, ...]} used by the
 * Aspose-compatible form-JSON API.
 */
public final class FormJsonSupport {

    private FormJsonSupport() {
    }

    /** JSON-escapes a string value. */
    public static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.toString();
    }

    /** One exported field object. */
    public static final class FieldEntry {
        final String name;
        final int flags;
        final String value;

        public FieldEntry(String name, int flags, String value) {
            this.name = name;
            this.flags = flags;
            this.value = value;
        }
    }

    /** Serializes field entries to a JSON array (indented or single-line). */
    public static String toJson(List<FieldEntry> entries, boolean indented) {
        if (entries.isEmpty()) {
            return "[]";
        }
        StringBuilder b = new StringBuilder();
        if (indented) {
            b.append("[\n");
            for (int i = 0; i < entries.size(); i++) {
                FieldEntry e = entries.get(i);
                b.append("  {\n");
                b.append("    \"Name\": \"").append(escape(e.name)).append("\",\n");
                b.append("    \"Flags\": ").append(e.flags).append(",\n");
                b.append("    \"Value\": \"").append(escape(e.value)).append("\"\n");
                b.append("  }").append(i + 1 < entries.size() ? ",\n" : "\n");
            }
            b.append("]");
        } else {
            b.append("[");
            for (int i = 0; i < entries.size(); i++) {
                FieldEntry e = entries.get(i);
                if (i > 0) {
                    b.append(",");
                }
                b.append("{\"Name\":\"").append(escape(e.name)).append("\",")
                        .append("\"Flags\":").append(e.flags).append(",")
                        .append("\"Value\":\"").append(escape(e.value)).append("\"}");
            }
            b.append("]");
        }
        return b.toString();
    }

    // ─── Minimal JSON parser (objects, arrays, strings, numbers, literals) ───

    /** Parses a JSON document into Java objects (Map/List/String/Double/Boolean/null). */
    public static Object parse(String json) {
        return new Parser(json).parseValue();
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Object parseValue() {
            skipWs();
            char c = peek();
            switch (c) {
                case '{': return parseObject();
                case '[': return parseArray();
                case '"': return parseString();
                case 't': expect("true"); return Boolean.TRUE;
                case 'f': expect("false"); return Boolean.FALSE;
                case 'n': expect("null"); return null;
                default: return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; // {
            skipWs();
            if (peek() == '}') { i++; return m; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                i++; // :
                Object val = parseValue();
                m.put(key, val);
                skipWs();
                char c = peek();
                i++; // , or }
                if (c == '}') break;
            }
            return m;
        }

        private List<Object> parseArray() {
            List<Object> a = new ArrayList<>();
            i++; // [
            skipWs();
            if (peek() == ']') { i++; return a; }
            while (true) {
                a.add(parseValue());
                skipWs();
                char c = peek();
                i++; // , or ]
                if (c == ']') break;
            }
            return a;
        }

        private String parseString() {
            StringBuilder b = new StringBuilder();
            i++; // opening quote
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"': b.append('"'); break;
                        case '\\': b.append('\\'); break;
                        case '/': b.append('/'); break;
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case 'b': b.append('\b'); break;
                        case 'f': b.append('\f'); break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: b.append(e);
                    }
                } else {
                    b.append(c);
                }
            }
            return b.toString();
        }

        private Object parseNumber() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            return Double.parseDouble(s.substring(start, i));
        }

        private void expect(String lit) {
            i += lit.length();
        }

        private char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }
    }
}
