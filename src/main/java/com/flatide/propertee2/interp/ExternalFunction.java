package com.flatide.propertee2.interp;

import java.util.List;
import java.util.function.Function;

/**
 * A host-registered external function (LANGUAGE.md §External Functions). The body returns a raw
 * value (wrapped into {@code Result.ok}) or throws (wrapped into {@code Result.error}). When
 * {@code async}, the runtime runs it through {@code Coop.blocking} (design §3.1) so blocking host
 * I/O does not freeze other ProperTee threads.
 */
record ExternalFunction(Function<List<Object>, Object> body, boolean async) {}
