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
        if (Double.isNaN(d)) return "NaN";                                  // unreachable in ProperTee
        if (Double.isInfinite(d)) return d > 0 ? "Infinity" : "-Infinity";  //   (division by zero errors)
        if (d == 0.0) return "0";                                          // covers +0.0 and -0.0 (ECMA String(-0) == "0")
        return d < 0 ? "-" + ecma(-d) : ecma(d);
    }

    /** ECMA-262 Number::toString rendering of a finite {@code d > 0}. */
    private static String ecma(double d) {
        // Decompose Double.toString(d) into shortest significant digits `s` (k of them) and the
        // ECMA exponent `n` such that value = s × 10^(n-k). Double.toString always yields a mantissa
        // with a decimal point, optionally an 'E' exponent (e.g. "6.0E9", "0.001", "1.50000005E7").
        String js = Double.toString(d);
        int exp = 0;
        int ei = js.indexOf('E');
        if (ei >= 0) { exp = Integer.parseInt(js.substring(ei + 1)); js = js.substring(0, ei); }
        int dot = js.indexOf('.');
        String digits = js.substring(0, dot) + js.substring(dot + 1);
        int pointExp = exp - (js.length() - dot - 1);   // value = digits × 10^pointExp
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;   // leading zeros: value-neutral
        int end = digits.length();
        while (end - start > 1 && digits.charAt(end - 1) == '0') { end--; pointExp++; } // trailing zeros: bump exponent
        String s = digits.substring(start, end);
        int k = s.length();
        int n = pointExp + k;

        if (k <= n && n <= 21) return s + "0".repeat(n - k);                 // integer, no point:  6000000000
        if (0 < n && n <= 21)  return s.substring(0, n) + "." + s.substring(n); // point inside:      15000000.5
        if (-6 < n && n <= 0)  return "0." + "0".repeat(-n) + s;             // leading zeros:      0.0001
        String mant = k == 1 ? s : s.charAt(0) + "." + s.substring(1);       // exponential:        1e+21 / 1e-7
        int e = n - 1;
        return mant + "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
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
