package com.flatide.runtime;

import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;

import java.util.List;
import java.util.Map;

/**
 * v1 API surface ({@code com.flatide.runtime.TypeChecker}) over this runtime's value model.
 * Behavior is the same v1 value semantics — display formatting, deep-copy and type predicates all
 * delegate to {@code Values}/{@code TeeFormat}/{@code JsonParser}, which are validated byte-for-byte
 * against the conformance fixtures. Provided so hosts written against v1 (e.g. TeeBox) compile and
 * run unchanged on propertee2.
 */
public final class TypeChecker {
    private TypeChecker() {}

    public static String typeOf(Object value)     { return Values.typeName(value); }
    public static boolean isNumber(Object value)  { return Values.isNumber(value); }
    public static boolean isString(Object value)  { return value instanceof String; }
    public static boolean isBoolean(Object value) { return value instanceof Boolean; }
    public static boolean isList(Object value)    { return value instanceof List; }
    public static boolean isMap(Object value)     { return value instanceof Map; }
    public static boolean isTruthy(Object value)  { return Values.isTruthy(value); }
    public static double toDouble(Object value)   { return Values.toDouble(value); }

    public static boolean isInteger(double d) {
        return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.floor(d);
    }

    /** Whole numbers box to Integer (when in int range), otherwise Double — matching v1 division/format rules. */
    public static Object boxNumber(double d) {
        if (isInteger(d) && Math.abs(d) < Integer.MAX_VALUE) return (int) d;
        return d;
    }

    // Display (PRINT / TO_STRING) — bare top-level strings, single-quoted inside collections.
    public static String formatValue(Object value)    { return TeeFormat.display(value); }
    public static String formatForPrint(Object value) { return TeeFormat.display(value); }
    public static String toStringValue(Object value)  { return TeeFormat.display(value); }
    public static String formatList(List<?> list)     { return TeeFormat.display(list); }
    public static String formatMap(Map<?, ?> map)     { return TeeFormat.display(map); }

    // JSON (JSON_FORMAT) — compact, double-quoted + escaped.
    public static String jsonStringify(Object value)    { return TeeFormat.json(value); }
    public static String formatJsonValue(Object value)  { return TeeFormat.json(value); }
    public static String formatJsonList(List<?> list)   { return TeeFormat.json(list); }
    public static String formatJsonMap(Map<?, ?> map)   { return TeeFormat.json(map); }

    public static Object jsonParse(String text) { return JsonParser.parse(text); }

    public static Object deepCopy(Object value) { return Values.deepCopy(value); }
}
