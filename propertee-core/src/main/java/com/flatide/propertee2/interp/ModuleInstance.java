package com.flatide.propertee2.interp;

import com.flatide.propertee2.module.ModuleSource;

import java.util.LinkedHashMap;
import java.util.Map;

/** One per-run imported module instance: immutable code plus isolated mutable globals. */
final class ModuleInstance {
    final ModuleSource source;
    final Map<String, Object> globals = new LinkedHashMap<>();
    final Map<String, UserFunction> functions = new LinkedHashMap<>();

    ModuleInstance(ModuleSource source) {
        this.source = source;
    }

    String identity() {
        return source.identity();
    }
}
