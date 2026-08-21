package com.flatide.propertee2.interp;

/**
 * One entry of the paused run's call stack ({@link DebugFrame#callStack()}): the user function
 * that was called, and the line:column of the call site in the source.
 */
public record DebugCallSite(String function, int line, int column) {
}
