package com.flatide.propertee2.interp;

import java.util.List;
import java.util.function.Function;

/**
 * A host-registered external function (LANGUAGE.md §External Functions). The body returns a raw
 * value (wrapped into {@code Result.ok}) or throws (wrapped into {@code Result.error}). Args are
 * deep-copied before the call and the return value after it, so a host mutating either cannot
 * change script state (LANGUAGE.md §async externals are deep-copied / thread-safe).
 *
 * <p>{@code blocking} = true routes the call through {@code Coop.blocking} (design §3.1 — a host
 * function may block, so it must release the baton). {@code registerExternal} /
 * {@code registerExternalAsync} are both blocking (the unified §3.1 contract); only
 * {@code registerPure} opts out, and only when the host guarantees the function never blocks.
 */
record ExternalFunction(Function<List<Object>, Object> body, boolean blocking) {}
