package com.flatide.propertee2.value;

import java.util.ArrayDeque;
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
 *       no inner spaces: {@code {"k":"v"}}, {@code [1,2,3]}. Infinity, -Infinity and NaN have no
 *       JSON form; {@link #firstNonFinite} finds one and says where it is.</li>
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
        if (v instanceof Integer || v instanceof Long) return v.toString();
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
     * Number display: ECMA-262 {@code Number::toString} (spec v0.14.0). Whole doubles drop the
     * fraction ({@code 10/2 → 5}, {@code 1.0+2.0 → 3}); fractional values keep shortest
     * round-trip digits ({@code 10/3 → 3.3333333333333335}); magnitudes in {@code [1e-6, 1e21)}
     * render plain ({@code 0.0001}, {@code 15000000.5}, {@code 6000000000}), outside that band
     * exponential ({@code 1e+21}, {@code 1e-7}). Uniform across PRINT / TO_STRING / JSON_FORMAT.
     *
     * <p>The significant digits come from {@link Double#toString} (shortest round-tripping on the
     * reference's JDK 25); only the placement/exponent is reformatted to ECMA rules. A handful of
     * values near {@link Double#MIN_VALUE} where the platform's {@code Double.toString} is not the
     * absolute shortest ({@code 5e-324} vs {@code 4.9E-324}) are implementation-defined and outside
     * the conformance envelope (design-draft-v1.0-gate.md §4).
     */
    public static String formatDouble(double d) {
        if (Double.isNaN(d)) return "NaN";                                  // Infinity - Infinity
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";  // past the double range
        if (d == 0.0) return "0";                                          // covers +0.0 and -0.0 (ECMA String(-0) == "0")
        return d < 0 ? "-" + ecma(-d) : ecma(d);
    }

    /** ECMA-262 Number::toString rendering of a finite {@code d > 0}. */
    private static String ecma(double d) {
        // The shortest significant digits `s` (k of them) and the ECMA exponent `n` such that
        // value = s × 10^(n-k), i.e. 0.s × 10^n.
        DecimalDigits dd = DecimalDigits.of(d);
        String s = dd.digits();
        int k = s.length();
        int n = dd.exponent();

        if (k <= n && n <= 21) return s + "0".repeat(n - k);                 // integer, no point:  6000000000
        if (0 < n && n <= 21)  return s.substring(0, n) + "." + s.substring(n); // point inside:      15000000.5
        if (-6 < n && n <= 0)  return "0." + "0".repeat(-n) + s;             // leading zeros:      0.0001
        String mant = k == 1 ? s : s.charAt(0) + "." + s.substring(1);       // exponential:        1e+21 / 1e-7
        int e = n - 1;
        return mant + "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
    }

    // ---- json (JSON_FORMAT) ----------------------------------------------

    /**
     * JSON text of {@code v}. JSON has no form for Infinity, -Infinity or NaN (spec v0.32.0), so a
     * value holding one throws {@link NotJsonException}; {@link #firstNonFinite} says where it is.
     */
    public static String json(Object v) {
        StringBuilder sb = new StringBuilder();
        json(v, sb);
        return sb.toString();
    }

    /** Thrown by {@link #json} at the first Infinity, -Infinity or NaN in the value. */
    public static final class NotJsonException extends IllegalArgumentException {
        NotJsonException(double d) {
            super("JSON cannot represent " + formatDouble(d));
        }
    }

    /**
     * A number JSON has no form for, as {@code PRINT} shows it ({@code Infinity}, {@code -Infinity},
     * {@code NaN}), and the path a script reaches it by.
     */
    public record NonFinite(String path, String number) {}

    /**
     * The first Infinity, -Infinity or NaN in {@code v}, in the order {@link #json} writes, or null
     * when there is none. The path starts at {@code name}, the caller's word for the value, and
     * goes down one member at a time, 1-based: {@code value.list.2}. A key that is a plain name
     * ({@code [A-Za-z_][A-Za-z0-9_]*}) is written as it is, any other key as {@link #json} writes
     * a string: {@code value."my key".1}.
     */
    public static NonFinite firstNonFinite(Object v, String name) {
        ArrayDeque<String> members = new ArrayDeque<>();
        Double d = nonFinite(v, members);
        if (d == null) return null;
        StringBuilder path = new StringBuilder(name);
        for (String member : members) path.append('.').append(member);
        return new NonFinite(path.toString(), formatDouble(d));
    }

    /** The first non-finite number in {@code v}; the members leading to it are added on the way back. */
    private static Double nonFinite(Object v, ArrayDeque<String> members) {
        if (v instanceof Double d) return Double.isFinite(d) ? null : d;
        // A FILE_LINES array still unread holds only its file's lines, which are strings; walking it
        // would read the file again. Once loaded — always so after a script changed it — it is walked.
        if (v instanceof LazyLines lines && !lines.isLoaded()) return null;
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Double d = nonFinite(e.getValue(), members);
                if (d != null) {
                    members.addFirst(member(String.valueOf(e.getKey())));
                    return d;
                }
            }
        } else if (v instanceof List<?> list) {
            int index = 0;
            for (Object e : list) {
                index++;
                Double d = nonFinite(e, members);
                if (d != null) {
                    members.addFirst(Integer.toString(index));
                    return d;
                }
            }
        }
        return null;
    }

    private static String member(String key) {
        boolean plain = !key.isEmpty();
        for (int i = 0; i < key.length() && plain; i++) {
            char c = key.charAt(i);
            plain = c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (i > 0 && c >= '0' && c <= '9');
        }
        if (plain) return key;
        StringBuilder sb = new StringBuilder();
        jsonString(key, sb);
        return sb.toString();
    }

    private static void json(Object v, StringBuilder sb) {
        if (v instanceof String s) {
            jsonString(s, sb);
        } else if (v instanceof JsonNull) {
            sb.append("null");                      // spec v0.8.0 (#4): lossless round-trip
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Integer || v instanceof Long) {
            sb.append(v.toString());
        } else if (v instanceof Double d) {
            if (!Double.isFinite(d)) throw new NotJsonException(d);
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
