package com.flatide.propertee2.interp;

import java.util.Map;

/**
 * The paused execution frame handed to a {@link DebugHandler} — a window into the exact scope
 * the {@code debug} statement was hit in, valid only for the duration of
 * {@link DebugHandler#onBreak}.
 */
public interface DebugFrame {

    /** Why the run paused (0.23.0 — stepping and line breakpoints joined the debug statement). */
    enum Reason { DEBUG_STATEMENT, STEP, BREAKPOINT }

    /** What triggered this break. */
    Reason reason();

    /**
     * ProperTee logical-thread id: {@code 0} for the main thread and a positive id for a
     * {@code multi} worker. Monitor iterations never produce a frame.
     */
    default int threadId() { return 0; }

    /** Logical-thread display name ({@code "main"} or the spawned worker function name). */
    default String threadName() { return "main"; }

    /**
     * Auto-display of function return values (0.29.0): the user-function returns the step that
     * produced this {@link Reason#STEP} stop crossed, in chronological order. Recorded are only
     * normal returns on the stepping logical thread that land in the frame the step was armed in
     * (or shallower) — the calls made from the stepped statement itself, or the function
     * {@link #stepOut} left — never their nested/recursive inner calls. Values are deep copies
     * taken at return time. Empty for {@link Reason#DEBUG_STATEMENT} and
     * {@link Reason#BREAKPOINT} stops, and empty when the step crossed no return.
     */
    default java.util.List<DebugReturn> returns() { return java.util.List.of(); }

    /** 1-based line of the paused statement. */
    int line();

    /** 0-based column of the paused statement (the position error messages use). */
    int column();

    /**
     * The paused statement's source text — the first line of it, with {@code " …"} appended when
     * the statement spans further (a block statement pauses <i>before</i> executing as a whole).
     */
    String statementText();

    /**
     * The paused run's user-function call stack, outermost call first; empty at top level. Only
     * calls made while a debug handler was attached are recorded (attach before the run).
     */
    java.util.List<DebugCallSite> callStack();

    /**
     * Resume, pausing again before the next statement — including statements inside functions
     * the current statement calls (pdb {@code step}).
     */
    void stepInto();

    /**
     * Resume, pausing again before the next statement at the current call depth or shallower —
     * a call in the current statement runs to completion (pdb {@code next}).
     */
    void stepOver();

    /**
     * Resume, pausing again before the next statement after the current function returns
     * (pdb {@code return}).
     */
    void stepOut();

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
