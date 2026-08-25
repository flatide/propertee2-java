package com.flatide.propertee2.module;

/**
 * One syntactic module request. {@code version == null} means that the host must resolve its
 * active/latest version; a positive value means an exact version was written in the import.
 */
public record ModuleRequest(String moduleId, Integer version) {
    public ModuleRequest {
        if (moduleId == null || moduleId.isBlank()) {
            throw new IllegalArgumentException("moduleId must not be blank");
        }
        if (version != null && version <= 0) {
            throw new IllegalArgumentException("module version must be a positive integer");
        }
    }

    /** Canonical request text used in diagnostics. */
    public String displayName() {
        return version == null ? moduleId : moduleId + "." + version;
    }
}
