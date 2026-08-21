package com.flatide.propertee2.interp;

import java.util.Map;

/**
 * The paused execution frame handed to a {@link DebugHandler} — a window into the exact scope
 * the {@code debug} statement was hit in, valid only for the duration of
 * {@link DebugHandler#onBreak}.
 */
public interface DebugFrame {

    /** 1-based line of the {@code debug} statement. */
    int line();

    /** 0-based column of the {@code debug} statement (the position error messages use). */
    int column();

    /**
     * Parse and execute {@code source} in the paused scope — the debugger's read-eval step.
     * Name lookup, assignment, and function definition behave exactly as if the code stood at
     * the {@code debug} statement: inside a function the innermost local frame is read and
     * written; at top level, the globals. Returns the echo value (a trailing bare expression's,
     * or a {@code return}'s — {@code return} does not unwind the paused script), Java
     * {@code null} when there is nothing to echo. {@code break}/{@code continue} are swallowed
     * rather than leaking into the paused script's enclosing loop, and {@code debug} statements
     * inside the source are no-ops. Throws {@link com.flatide.propertee2.Parsing.SyntaxException}
     * on a parse error and {@link com.flatide.propertee2.value.TeeError} on a runtime error;
     * both leave the paused script intact (effects of statements that ran are kept, like any
     * script error).
     */
    Object eval(String source);

    /**
     * Read-only view of the innermost local frame — the paused function's parameters and locals.
     * Empty at top level (locals <i>are</i> the globals there — see {@link #globals()}).
     */
    Map<String, Object> locals();

    /** Read-only view of the globals. */
    Map<String, Object> globals();

    /**
     * Abort the paused run: throws {@link DebugQuit}, which unwinds the whole run (an
     * {@code Error}, invisible to script-level error handling — no {@code [THREAD ERROR]}, no
     * error Result). Hosts catch it where they started the run; an {@code Engine.Session}
     * stays usable afterwards, keeping all state from before the abort.
     */
    void quit();
}
