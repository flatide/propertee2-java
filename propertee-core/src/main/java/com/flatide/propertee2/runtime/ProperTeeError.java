package com.flatide.propertee2.runtime;

/**
 * v1's host-facing runtime-error type, ported verbatim (R-phase compat surface). In v1 the source
 * position was baked into the exception <em>message</em> ({@code createError}: {@code "Runtime
 * Error at line L:C: <msg>"}), so hosts like TeeBox read {@code e.getMessage()} and store it as the
 * run's {@code errorMessage}. The propertee2 engine keeps the position separate
 * ({@code value/TeeError.positioned()}); the v1 façade converts at its boundary so the host
 * contract — message string included — matches v1 byte-for-byte.
 */
public class ProperTeeError extends RuntimeException {

    /** How the script's {@code on error} handler ended (spec v0.37.0). The run failed in every case. */
    public enum HandlerEnd {
        /** It reached its end or a {@code return}. */
        RETURNED,
        /** An error ended it ({@code [HANDLER ERROR]}). */
        FAILED,
        /** The host stopped the run while it ran. */
        STOPPED
    }

    private final HandlerEnd handlerEnd;
    private final Object handlerResult;
    private final String handlerError;

    public ProperTeeError(String message) {
        this(message, null, null, null);
    }

    /**
     * A run that failed after its {@code on error} handler ran (spec v0.37.0): how it ended, the
     * value it returned — {@code {}} without a {@code return}, or when an error or a stop ended it —
     * and the error that ended it, positioned as {@link #getMessage()} is.
     */
    public ProperTeeError(String message, HandlerEnd handlerEnd, Object handlerResult, String handlerError) {
        super(message);
        this.handlerEnd = handlerEnd;
        this.handlerResult = handlerResult;
        this.handlerError = handlerError;
    }

    /** True when the script's {@code on error} handler ran; the run failed all the same. */
    public boolean handled() { return handlerEnd != null; }

    /** How the handler ended, or {@code null} when no handler ran. */
    public HandlerEnd handlerEnd() { return handlerEnd; }

    /** The value the handler handed to the host, or {@code null} when no handler ran. */
    public Object handlerResult() { return handlerResult; }

    /** The error that ended the handler ({@link HandlerEnd#FAILED} only), else {@code null}. */
    public String handlerError() { return handlerError; }
}
