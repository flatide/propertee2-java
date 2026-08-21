package com.flatide.propertee2.interp;

/**
 * Thrown by {@link DebugFrame#quit()} to abort the paused run from the debugger. An
 * {@code Error} (like {@code coop.AbortError}) so it is structurally invisible to the
 * interpreter's script-level catches; unlike {@code Coop.abort()} it carries no latch — the
 * run's {@code Coop} (and therefore an {@code Engine.Session}) remains usable for the next run.
 */
public final class DebugQuit extends Error {
    public DebugQuit() {
        super("Run aborted from the debugger", null, false, false);
    }
}
