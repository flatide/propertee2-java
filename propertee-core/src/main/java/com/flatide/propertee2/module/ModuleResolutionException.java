package com.flatide.propertee2.module;

/** A host-facing resolution failure whose message is safe to surface as a ProperTee load error. */
public final class ModuleResolutionException extends RuntimeException {
    public ModuleResolutionException(String message) {
        super(message);
    }

    public ModuleResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
