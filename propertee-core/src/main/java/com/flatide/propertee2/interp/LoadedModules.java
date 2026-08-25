package com.flatide.propertee2.interp;

import java.util.List;
import java.util.Map;

/** Fully resolved and validated direct imports, ready to install before entry execution. */
record LoadedModules(Map<String, ModuleInstance> aliases, List<ModuleInstance> instances) {
    static final LoadedModules EMPTY = new LoadedModules(Map.of(), List.of());
}
