package com.flatide.propertee2.interp;

/**
 * One entry of the paused run's call stack ({@link DebugFrame#callStack()}): the user function
 * that was called, and the line:column of the call site in the source.
 */
public record DebugCallSite(String function, String sourceId, int line, int column) {
    /** Compatibility constructor for hosts that did not yet carry source identity. */
    public DebugCallSite(String function, int line, int column) {
        this(function, "<memory>", line, column);
    }
}
