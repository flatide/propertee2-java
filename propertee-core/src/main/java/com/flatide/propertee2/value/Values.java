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
 *   <li>{@code Integer} / {@code Double} — number (division is always Double)</li>
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

    public static boolean isNumber(Object v)  { return v instanceof Integer || v instanceof Double; }
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
        if (v instanceof List) {
            List<Object> src = (List<Object>) v;
            List<Object> out = new ArrayList<>(src.size());
            for (Object e : src) out.add(deepCopy(e));
            return out;
        }
        return v; // Integer, Double, String, Boolean, JsonNull are immutable
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
