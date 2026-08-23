package com.flatide.propertee2.interp;

/**
 * One user-function return crossed by the debugger step that produced a stop (host API, 0.29.0).
 * {@code value} is a deep copy taken at return time — safe to retain and display after the run
 * moves on. See {@link DebugFrame#returns()} for exactly which returns are recorded.
 */
public record DebugReturn(String function, Object value) { }
