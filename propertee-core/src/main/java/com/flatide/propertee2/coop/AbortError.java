package com.flatide.propertee2.coop;

/**
 * Cooperative-run abort signal, thrown from a {@link Scheduler} checkpoint after
 * {@link Scheduler#abort()}. Deliberately an {@link Error} — not a TeeError and not a
 * RuntimeException — so no script-error machinery can absorb it: the worker
 * {@code [THREAD ERROR]} wrap, the monitor tick catches, and the external-function
 * Result wrap all catch RuntimeException only, and {@code Coop.run} rethrows Errors
 * unchanged. Stackless: it marks "the host cancelled this run", not a code location.
 */
public final class AbortError extends Error {
    public AbortError() {
        super("Run aborted", null, false, false);
    }
}
