package com.flatide.propertee2.module;

/**
 * Host seam for resolving an import. Core owns syntax and module semantics; a CLI, TeeBox, or
 * embedding host owns where module source lives and what "latest" means.
 */
@FunctionalInterface
public interface ModuleResolver {
    ModuleSource resolve(ModuleRequest request);
}
