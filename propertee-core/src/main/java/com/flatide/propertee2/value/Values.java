package com.flatide.propertee2.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Core value-model utilities (value-model-and-builtins.md §1–§6). ProperTee runtime
 * values are plain Java objects — no wrapper types:
 *
 * <ul>
 *   <li>{@code Integer} / {@code Long} / {@code Double} — number (division is always Double). An
 *       integer is boxed as an {@code Integer} when it fits 32 bits and as a {@code Long} beyond
 *       that, inside the integer range ±{@value #MAX_INTEGER} (spec v0.33.0) —
 *       {@link #integer(long)} gives that box.</li>
 *   <li>{@code String}, {@code Boolean}</li>
 *   <li>{@code LinkedHashMap<String,Object>} — object (insertion order preserved)</li>
 *   <li>{@code ArrayList<Object>} — array</li>
 * </ul>
 *
 * There is NO null: absence is {@code {}} (an empty object).
 */
public final class Values {
    private Values() {}

    /** "no null" — absence is an empty object (value-model §1). */
    public static Map<String, Object> emptyObject() {
        return new LinkedHashMap<>();
    }

    /**
     * The integer range is ±(2^53 − 1) (spec v0.33.0; 32-bit before): every integer in it is exact
     * as a decimal too, and it is the range RFC 8259 §6 says JSON readers agree on.
     */
    public static final long MAX_INTEGER = 9_007_199_254_740_991L;

    /** The error for an integer result outside the range (spec v0.33.0). */
    public static final String INTEGER_OVERFLOW = "Integer overflow: the result is outside ±9,007,199,254,740,991";

    /**
     * The error for a decimal result too large to represent (spec v0.36.0): numbers are always
     * finite, as division by zero was always an error, so there is no Infinity or NaN.
     */
    public static final String NUMBER_OVERFLOW = "Number overflow: the result is too large to represent";

    /** {@code d}, or the {@link #NUMBER_OVERFLOW} error when it is not finite (spec v0.36.0). */
    public static double decimal(double d) {
        if (!Double.isFinite(d)) throw new TeeError(NUMBER_OVERFLOW);
        return d;
    }

    /**
     * The error for a host value holding Infinity or NaN, or null when it holds none (spec
     * v0.36.0) — a script's numbers are finite, so one cannot come in from the host either.
     * {@code name} is how the script reaches the value: a property's name, {@code FN() result}.
     */
    public static String hostNonFinite(Object v, String name) {
        TeeFormat.NonFinite f = TeeFormat.firstNonFinite(v, name);
        return f == null ? null : f.path() + " is " + f.number() + ", which ProperTee numbers cannot represent";
    }

    /** The error for an integer literal outside the range; tokens are unsigned digits. */
    public static String literalOutOfRange(String token) {
        return "Integer literal out of range: " + token + " is larger than 9,007,199,254,740,991";
    }

    public static boolean isNumber(Object v)  { return v instanceof Integer || v instanceof Long || v instanceof Double; }
    public static boolean isInteger(Object v) { return v instanceof Integer || v instanceof Long; }

    public static boolean fitsInteger(long v) { return v >= -MAX_INTEGER && v <= MAX_INTEGER; }

    /** The canonical box of an integer the caller has checked with {@link #fitsInteger}. */
    public static Object integer(long v) {
        return v == (int) v ? (Object) (int) v : (Object) v;
    }

    /**
     * A {@code long} from outside the language — a host, a file size, a time: an integer when it
     * is inside the range, otherwise the nearest decimal, as JSON data beyond it reads.
     */
    public static Object fromLong(long v) {
        return fitsInteger(v) ? integer(v) : (Object) (double) v;
    }
    public static boolean isObject(Object v)  { return v instanceof Map; }
    public static boolean isArray(Object v)   { return v instanceof List; }

    /** double view of a number value (caller must ensure {@link #isNumber}). */
    public static double toDouble(Object v) {
        return ((Number) v).doubleValue();
    }

    /**
     * Type name as used in error messages and {@code TYPE_OF} (value-model §2/§9):
     * number / string / boolean / object / array. Unknown host values fall back to the
     * Java simple name so a leak is visible rather than silent.
     */
    public static String typeName(Object v) {
        if (isNumber(v)) return "number";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        if (v instanceof JsonNull) return "null";   // spec v0.8.0 (#4)
        return v == null ? "object" : v.getClass().getSimpleName();
    }

    /** Truthiness (LANGUAGE.md §Truthiness): ONLY {@code true} is truthy; everything else is falsy. */
    public static boolean isTruthy(Object v) {
        return Boolean.TRUE.equals(v);
    }

    /**
     * Copy-on-write deep copy applied at assignment and loop binding (value-model §6;
     * tests 68_cow_semantics, 69_thread_isolation). Numbers/strings/booleans are
     * immutable and returned as-is; objects/arrays are copied recursively.
     */
    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object v) {
        if (v instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) v;
            // A genuine Result stays genuine across copies (spec v0.10.0 — origin propagation).
            Map<String, Object> out = (v instanceof TeeResult) ? new TeeResult() : new LinkedHashMap<>(src.size());
            for (Map.Entry<String, Object> e : src.entrySet()) {
                out.put(e.getKey(), deepCopy(e.getValue()));
            }
            return out;
        }
        if (v instanceof LazyLines lazy) return lazy.copy();   // FILE_LINES: the recipe is shared until read
        if (v instanceof List) {
            List<Object> src = (List<Object>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object e : src) out.add(deepCopy(e));
            return out;
        }
        if (v instanceof Long l) return fromLong(l);   // a host's Long gets its canonical box
        return v; // Integer, Double, String, Boolean, JsonNull are immutable
    }

    /**
     * The copy for a host boundary — a run's result, an external function's arguments, the v1
     * façade's {@code TypeChecker.deepCopy}: {@link #deepCopy} except that a {@code FILE_LINES}
     * array is read in full (its whole-use limit applies), so the host only ever holds plain values.
     */
    @SuppressWarnings("unchecked")
    public static Object materialize(Object v) {
        if (v instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) v;
            Map<String, Object> out = (v instanceof TeeResult) ? new TeeResult() : new LinkedHashMap<>(src.size());
            for (Map.Entry<String, Object> e : src.entrySet()) out.put(e.getKey(), materialize(e.getValue()));
            return out;
        }
        if (v instanceof LazyLines lazy) return new ArrayList<Object>(lazy);   // iterating reads the file
        if (v instanceof List) {
            List<Object> src = (List<Object>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object e : src) out.add(materialize(e));
            return out;
        }
        return v;
    }

    /**
     * Structural equality for {@code ==} / {@code !=}. Numbers compare by value
     * ({@code 1 == 1.0}); objects and arrays compare deeply; everything else by
     * {@link Object#equals}. Object comparison is order-insensitive (e.g. {@code {} == {}}).
     */
    @SuppressWarnings("unchecked")
    public static boolean valuesEqual(Object a, Object b) {
        if (a == b) return true;
        if (isNumber(a) && isNumber(b)) return toDouble(a) == toDouble(b);
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a, mb = (Map<String, Object>) b;
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<String, Object> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey())) return false;
                if (!valuesEqual(e.getValue(), mb.get(e.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a, lb = (List<Object>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) {
                if (!valuesEqual(la.get(i), lb.get(i))) return false;
            }
            return true;
        }
        return a != null && a.equals(b);
    }
}
