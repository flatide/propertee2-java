package com.flatide.propertee2.value;

/**
 * A ProperTee runtime error. Carries an optional source position — the line, and the column as
 * ANTLR counts it, from 0 — so the top level can render {@code Runtime Error at line L:C: <message>},
 * where both count from 1 (spec v0.37.0; until then the column was printed from 0).
 *
 * <p>The error MESSAGE STRING must match v1 byte-for-byte — the error fixtures
 * (e.g. {@code 24_error_undefined_var.expected}) assert on it. See the error table
 * in {@code docs/LANGUAGE.md} (§Runtime Errors) and {@code value-model-and-builtins.md} §9.
 */
public final class TeeError extends RuntimeException {
    private final int line;
    private final int col;
    private final String sourceId;
    private volatile HandlerOutcome handler;

    public TeeError(String message) {
        this(message, null, -1, -1);
    }

    public TeeError(String message, int line, int col) {
        this(message, null, line, col);
    }

    public TeeError(String message, String sourceId, int line, int col) {
        super(message);
        this.sourceId = sourceId;
        this.line = line;
        this.col = col;
    }

    public String sourceId() { return sourceId; }
    public int line() { return line; }
    /** The column as ANTLR counts it, from 0; {@link #column()} is the one to show. */
    public int col() { return col; }
    /** The column counted from 1, as the error report and {@code _ERROR.column} give it (spec v0.37.0). */
    public int column() { return col < 0 ? col : col + 1; }
    public boolean hasPosition() { return line >= 0; }

    /**
     * How the script's {@code on error} handler ended (spec v0.37.0), or {@code null} when no handler
     * ran. When it is set, the runtime has already reported this error, before the handler started —
     * a host that prints the error itself skips it.
     */
    public HandlerOutcome handler() { return handler; }

    /** Record how the handler ended; set once, by the interpreter, after the handler. */
    public void handled(HandlerOutcome outcome) { this.handler = outcome; }

    /** Attach a position to a positionless error (interpreter fills this in at the throwing node). */
    public TeeError at(int line, int col) {
        return hasPosition() ? this : new TeeError(getMessage(), sourceId, line, col);
    }

    /** Attach source identity without changing the compatibility rendering of unnamed sources. */
    public TeeError at(String sourceId, int line, int col) {
        return hasPosition() ? this : new TeeError(getMessage(), sourceId, line, col);
    }

    /**
     * The report form (without the outer "Runtime error: " prefix): {@code Runtime Error at line L:C: <msg>}
     * for an error carrying a source position — every runtime error does since spec v0.37.0, a
     * built-in's at its call — or {@code Runtime Error: <msg>} for one raised outside a script, such
     * as a host property holding NaN.
     */
    public String positioned() {
        if (!hasPosition()) return "Runtime Error: " + getMessage();
        if (sourceId != null && !sourceId.equals("<memory>")) {
            return "Runtime Error at " + sourceId + " line " + line + ":" + column() + ": " + getMessage();
        }
        return "Runtime Error at line " + line + ":" + column() + ": " + getMessage();
    }
}
