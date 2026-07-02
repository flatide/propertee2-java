package com.flatide.propertee2.value;

/**
 * The JSON {@code null} value (spec v0.8.0 — ProperTee issue #4). A singleton, so the
 * identity fast-path in {@link Values#valuesEqual} makes {@code null == null} true and
 * {@code null == {}} false; immutable, so {@link Values#deepCopy} passes it through.
 *
 * <p><b>"No implicit null" invariant:</b> the language never produces this value —
 * missing arguments, bare {@code return}, no-return functions and failed lookups all
 * remain {@code {}}. It enters only via the {@code null} literal or data (JSON, host
 * props, external functions). Every non-data use (conditions, logic, arithmetic,
 * member access) fails loudly under the spec v0.7.0 strictness rules.
 */
public final class JsonNull {
    public static final JsonNull NULL = new JsonNull();

    private JsonNull() {}

    @Override public String toString() { return "null"; }
}
