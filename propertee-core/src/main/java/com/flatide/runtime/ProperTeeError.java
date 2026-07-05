package com.flatide.runtime;

/**
 * v1's host-facing runtime-error type, ported verbatim (R-phase compat surface). In v1 the source
 * position was baked into the exception <em>message</em> ({@code createError}: {@code "Runtime
 * Error at line L:C: <msg>"}), so hosts like TeeBox read {@code e.getMessage()} and store it as the
 * run's {@code errorMessage}. The propertee2 engine keeps the position separate
 * ({@code value/TeeError.positioned()}); the v1 façade converts at its boundary so the host
 * contract — message string included — matches v1 byte-for-byte.
 */
public class ProperTeeError extends RuntimeException {
    public ProperTeeError(String message) {
        super(message);
    }
}
