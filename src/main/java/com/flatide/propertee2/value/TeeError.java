package com.flatide.propertee2.value;

/**
 * A ProperTee runtime error. Carries an optional source position (1-based line,
 * 0-based column, matching ANTLR token positions) so the top level can render the
 * v1-exact form: {@code Runtime Error at line L:C: <message>}.
 *
 * <p>The error MESSAGE STRING must match v1 byte-for-byte — the error fixtures
 * (e.g. {@code 24_error_undefined_var.expected}) assert on it. See the error table
 * in {@code docs/LANGUAGE.md} (§Runtime Errors) and {@code value-model-and-builtins.md} §9.
 */
public final class TeeError extends RuntimeException {
    private final int line;
    private final int col;

    public TeeError(String message) {
        this(message, -1, -1);
    }

    public TeeError(String message, int line, int col) {
        super(message);
        this.line = line;
        this.col = col;
    }

    public int line() { return line; }
    public int col() { return col; }
    public boolean hasPosition() { return line >= 0; }

    /** Attach a position to a positionless error (interpreter fills this in at the throwing node). */
    public TeeError at(int line, int col) {
        return hasPosition() ? this : new TeeError(getMessage(), line, col);
    }

    /** v1 form: {@code Runtime Error at line L:C: <message>} (without the outer "Runtime error: " prefix). */
    public String positioned() {
        if (!hasPosition()) return getMessage();
        return "Runtime Error at line " + line + ":" + col + ": " + getMessage();
    }
}
