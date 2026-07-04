package com.flatide.propertee2.value;

import java.util.Map;

/**
 * The v1 Result shape — {@code {status, ok, value}} — surfaced to scripts as a plain
 * ProperTee object (a {@link LinkedHashMap}). status ∈ {"running","done","error"}.
 * Used for thread/multi results and external-function returns (value-model §7).
 *
 * <p>This is a value contract: key order is status, ok, value; absence is {@code {}}
 * (never null). The new engine reproduces it exactly.
 */
public final class Result {
    private Result() {}

    public static Map<String, Object> running() {
        return of("running", false, Values.emptyObject());
    }

    public static Map<String, Object> ok(Object value) {
        return of("done", true, value == null ? Values.emptyObject() : value);
    }

    public static Map<String, Object> error(String message) {
        return of("error", false, message == null ? "" : message);
    }

    /** {@code ERR(value)} (spec v0.10.0) — an error Result whose value may be any type (structured errors). */
    public static Map<String, Object> errorValue(Object value) {
        return of("error", false, value == null ? Values.emptyObject() : value);
    }

    private static Map<String, Object> of(String status, boolean ok, Object value) {
        Map<String, Object> m = new TeeResult();   // genuine-Result origin brand (spec v0.10.0)
        m.put("status", status);
        m.put("ok", ok);
        m.put("value", value);
        return m;
    }
}
