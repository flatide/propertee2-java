package com.flatide.propertee2.coop;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Coop primitive surface (design §3) over one {@link Scheduler}. Every potential blocking point
 * goes through here so the "no blocking while holding the baton" invariant (§1) is structurally
 * enforced. In PC the interpreter runs as the single root fiber; PD adds {@code multi} child fibers.
 */
public final class Coop {
    // A Scheduler is one-shot (it latches `shutdown` and accumulates fiber state), so each run gets
    // a fresh one. This keeps Coop reusable: a second run() — including one that sleeps — works cleanly.
    private volatile Scheduler scheduler = new Scheduler();
    private volatile boolean abortRequested = false;

    public Scheduler scheduler() { return scheduler; }

    /**
     * Cooperatively abort the current run — or, latched, the next one (an abort issued before
     * {@code run()} publishes its fresh Scheduler must not be lost). Thread-safe. The run ends
     * by throwing {@link AbortError} out of {@code run()}. The latch is deliberately permanent:
     * an aborted Coop aborts every later run immediately (hosts create one Coop per logical run).
     */
    public void abort() {
        abortRequested = true;
        scheduler.abort();
    }

    /** On-baton cancellation/fairness checkpoint — see {@link Scheduler#checkpoint()}. */
    public void checkpoint() { scheduler.checkpoint(); }

    /** Run the program body as the root fiber to completion; rethrows whatever the body threw. */
    public void run(String rootName, Runnable body) {
        Scheduler fresh = new Scheduler();
        scheduler = fresh;
        if (abortRequested) fresh.abort();   // volatile publish + re-check closes the abort/run race
        AtomicReference<Throwable> error = new AtomicReference<>();
        scheduler.runToCompletion(rootName, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            }
        });
        Throwable t = error.get();
        if (t instanceof RuntimeException re) throw re;
        if (t instanceof Error e) throw e;
        if (t != null) throw new RuntimeException(t);
    }

    // ---- primitives (called from within a fiber) -------------------------

    public void yield_() { scheduler.yield_(); }

    public void sleep(long ms) { scheduler.sleep(ms); }

    public <T> T blocking(Supplier<T> work) { return scheduler.blocking(work); }

    /** True when called from within a running fiber (i.e., a Coop runtime is active). */
    public boolean inFiber() { return scheduler.self() != null; }
}
