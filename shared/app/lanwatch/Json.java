package app.lanwatch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer.
 *
 * Both ends of the link need to agree on the wire format, and the desktop side also needs it
 * without a dependency, so this is written here rather than pulled from a library. It covers
 * exactly what the protocol uses: objects, arrays, strings, numbers, booleans and null.
 *
 * Values come back as Map, List, String, Double, Boolean or null. Numbers are always Double,
 * which keeps one code path; a consumer that wants an int casts and rounds.
 */
public final class Json {
    private Json() { }

    // --- writing ---------------------------------------------------------------

    /** Builds an object from alternating keys and values. */
    public static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value.toString());
        } else if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            // A whole number must not come out as "87.0" — the reading side expects a percentage.
            if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                out.append(Long.toString((long) d));
            } else {
                out.append(Double.toString(d));
            }
        } else if (value instanceof Map) {
            writeMap((Map<?, ?>) value, out);
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) out.append(',');
                first = false;
                writeValue(item, out);
            }
            out.append(']');
        } else {
            writeString(value.toString(), out);
        }
    }

    private static void writeMap(Map<?, ?> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) out.append(',');
            first = false;
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            writeValue(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    // Control characters are the only ones JSON forbids outright.
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    // --- reading ---------------------------------------------------------------

    /** Parses a complete document. Returns null when the text is not valid JSON. */
    public static Object read(String text) {
        if (text == null) return null;
        try {
            Cursor cursor = new Cursor(text);
            cursor.skipSpace();
            Object value = cursor.value();
            cursor.skipSpace();
            return cursor.done() ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parses a document that must be an object; null for anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readObject(String text) {
        Object value = read(text);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private static final class Cursor {
        private final String text;
        private int at;

        Cursor(String text) {
            this.text = text;
        }

        boolean done() {
            return at >= text.length();
        }

        void skipSpace() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }

        Object value() {
            skipSpace();
            if (done()) fail();
            char c = text.charAt(at);
            switch (c) {
                case '{': return object();
                case '[': return array();
                case '"': return string();
                case 't': return literal("true", Boolean.TRUE);
                case 'f': return literal("false", Boolean.FALSE);
                case 'n': return literal("null", null);
                default: return number();
            }
        }

        Map<String, Object> object() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            expect('{');
            skipSpace();
            if (peek() == '}') { at++; return map; }
            while (true) {
                skipSpace();
                String key = string();
                skipSpace();
                expect(':');
                map.put(key, value());
                skipSpace();
                char c = next();
                if (c == '}') return map;
                if (c != ',') fail();
            }
        }

        List<Object> array() {
            List<Object> list = new ArrayList<Object>();
            expect('[');
            skipSpace();
            if (peek() == ']') { at++; return list; }
            while (true) {
                list.add(value());
                skipSpace();
                char c = next();
                if (c == ']') return list;
                if (c != ',') fail();
            }
        }

        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') return out.toString();
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (at + 4 > text.length()) fail();
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                        break;
                    default: fail();
                }
            }
        }

        Object number() {
            int start = at;
            if (peek() == '-' || peek() == '+') at++;
            while (at < text.length() && isNumberChar(text.charAt(at))) at++;
            if (at == start) fail();
            String raw = text.substring(start, at);
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException e) {
                fail();
                return null;
            }
        }

        static boolean isNumberChar(char c) {
            return (c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+';
        }

        Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) fail();
            at += word.length();
            return value;
        }

        char peek() {
            if (done()) fail();
            return text.charAt(at);
        }

        char next() {
            if (done()) fail();
            return text.charAt(at++);
        }

        void expect(char c) {
            if (next() != c) fail();
        }

        void fail() {
            throw new IllegalStateException("json at " + at);
        }
    }

    // --- typed readers ---------------------------------------------------------

    public static String str(Map<String, Object> map, String key, String fallback) {
        Object value = map == null ? null : map.get(key);
        return value == null ? fallback : value.toString();
    }

    public static int num(Map<String, Object> map, String key, int fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) return (int) Math.round(((Number) value).doubleValue());
        return fallback;
    }

    /**
     * Reads a number that must survive as a long.
     *
     * The reader turns every number into a Double for simplicity, so an epoch millisecond passes
     * through this rather than through {@link #num}: narrowing 1.7e12 to an int wraps it to a
     * negative date and every age on screen goes wrong.
     */
    public static long longNum(Map<String, Object> map, String key, long fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) return fallback;
            return (long) d;
        }
        return fallback;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map == null ? null : map.get(key);
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return Boolean.parseBoolean((String) value);
        return fallback;
    }
}