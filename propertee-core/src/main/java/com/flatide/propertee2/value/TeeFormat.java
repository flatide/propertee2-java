package com.flatide.propertee2.value;

import java.util.List;
import java.util.Map;

/**
 * Value-to-string rendering. Two distinct forms (both verified against fixtures —
 * LANGUAGE.md §Output Formatting, and 11_arrays / 82_map_extensions / 84_json):
 *
 * <ul>
 *   <li>{@link #display} — what {@code PRINT} / {@code TO_STRING} produce. Top-level strings
 *       are bare; strings INSIDE arrays/objects are single-quoted. Arrays: {@code [ 1, 2, 'x' ]}
 *       (empty {@code []}). Objects: {@code { "k": 'v' }} (empty {@code {}}). Whole doubles drop {@code .0}.</li>
 *   <li>{@link #json} — what {@code JSON_FORMAT} produces: compact, double-quoted & escaped strings,
 *       no inner spaces: {@code {"k":"v"}}, {@code [1,2,3]}.</li>
 * </ul>
 */
public final class TeeFormat {
    private TeeFormat() {}

    // ---- display (PRINT / TO_STRING) -------------------------------------

    /** Top-level display: a String renders bare; collections single-quote their string elements. */
    public static String display(Object v) {
        if (v instanceof String s) return s;        // top-level string: no quotes
        return render(v);
    }

    /** Render where a String element is single-quoted (i.e. inside an array/object). */
    private static String renderElement(Object v) {
        if (v instanceof String s) return "'" + s + "'";
        return render(v);
    }

    private static String render(Object v) {
        if (v instanceof String s)  return s;
        if (v instanceof JsonNull)  return "null";  // spec v0.8.0 (#4): unquoted, also inside containers
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Integer i) return i.toString();
        if (v instanceof Double d)  return formatDouble(d);
        if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{ ");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(", ");
                first = false;
                sb.append('"').append(e.getKey()).append("\": ").append(renderElement(e.getValue()));
            }
            return sb.append(" }").toString();
        }
        if (v instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[ ");
            boolean first = true;
            for (Object e : list) {
                if (!first) sb.append(", ");
                first = false;
                sb.append(renderElement(e));
            }
            return sb.append(" ]").toString();
        }
        return String.valueOf(v);
    }

    /**
     * Number display: integers as-is; doubles drop a trailing {@code .0} when whole
     * ({@code 10/2 → 5}, {@code 1.0+2.0 → 3}) but keep full precision otherwise
     * ({@code 10/3 → 3.3333333333333335}).
     */
    public static String formatDouble(double d) {
        if (!Double.isInfinite(d) && !Double.isNaN(d)
                && d == Math.floor(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        return Double.toString(d);
    }

    // ---- json (JSON_FORMAT) ----------------------------------------------

    public static String json(Object v) {
        StringBuilder sb = new StringBuilder();
        json(v, sb);
        return sb.toString();
    }

    private static void json(Object v, StringBuilder sb) {
        if (v instanceof String s) {
            jsonString(s, sb);
        } else if (v instanceof JsonNull) {
            sb.append("null");                      // spec v0.8.0 (#4): lossless round-trip
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Integer i) {
            sb.append(i.toString());
        } else if (v instanceof Double d) {
            sb.append(formatDouble(d));
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                jsonString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                json(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object e : list) {
                if (!first) sb.append(',');
                first = false;
                json(e, sb);
            }
            sb.append(']');
        } else {
            jsonString(String.valueOf(v), sb);
        }
    }

    private static void jsonString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }
}
