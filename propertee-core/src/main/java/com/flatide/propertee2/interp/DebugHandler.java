package com.flatide.propertee2.interp;

/**
 * Host hook for the {@code debug} statement (host API, 0.22.0 — zero language change).
 *
 * <p>The language spec defines {@code debug} as "an explicit breakpoint for the debugger: in a
 * debug mode execution pauses at each {@code debug} statement; in normal execution it is a
 * no-op". Attaching a handler ({@link Interpreter#setDebugHandler} /
 * {@code Engine.Session#setDebugHandler}) turns the host into such a debug mode: the interpreter
 * calls {@link #onBreak} at each {@code debug} statement and the paused script resumes when the
 * callback returns.
 *
 * <p>Freeze semantics: the callback runs <b>on the paused fiber, holding the scheduler baton</b> —
 * a deliberate exception to the "no blocking while holding the baton" contract, because a frozen
 * world is exactly what a breakpoint means. While the handler blocks (e.g. reading console
 * input), nothing else runs: no worker interleaving, no monitor ticks, no sleep wakes. Sleep
 * deadlines are absolute, so a long pause makes overdue sleepers wake back-to-back on resume —
 * in deadline order, preserving output order. Caveat: evaluating code that itself suspends
 * ({@code SLEEP}, {@code multi}, blocking builtins) via {@link DebugFrame#eval} releases the
 * baton for the duration of that evaluation, letting the world advance.
 *
 * <p>Scope: the handler fires only on the root logical thread in NORMAL context (top level,
 * plain function calls, {@code multi} setup). A {@code debug} inside a worker or monitor body
 * stays a no-op, as does a {@code debug} reached while a break is already active (code run
 * through {@link DebugFrame#eval} cannot re-enter the debugger).
 */
@FunctionalInterface
public interface DebugHandler {

    /** Called at each {@code debug} statement; the script resumes when this returns. */
    void onBreak(DebugFrame frame);
}
