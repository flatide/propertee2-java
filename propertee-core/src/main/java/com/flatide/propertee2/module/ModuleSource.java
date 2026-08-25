package com.flatide.propertee2.module;

/**
 * Source selected by a host resolver. The resolved identity is stable for one run and is used to
 * identify duplicate imports and the module's isolated globals/debug source.
 * {@code version} may be null for an explicitly unversioned fallback source.
 */
public record ModuleSource(String moduleId, Integer version, String sourceId, String source) {
    public ModuleSource {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        if (version != null && version <= 0) {
            throw new IllegalArgumentException("module version must be a positive integer");
        }
        if (sourceId == null || sourceId.isBlank()) {
            throw new IllegalArgumentException("sourceId must not be blank");
        }
        if (source == null) throw new IllegalArgumentException("source must not be null");
    }

    public String identity() {
        return version == null ? moduleId : moduleId + "." + version;
    }
}
