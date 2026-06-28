package com.flatide.propertee2.coop;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The Coop primitive surface (design §3) over one {@link Scheduler}. Every potential blocking point
 * goes through here so the "no blocking while holding the baton" invariant (§1) is structurally
 * enforced. In PC the interpreter runs as the single root fiber; PD adds {@code multi} child fibers.
 */
public final class Coop {
    private final Scheduler scheduler = new Scheduler();

    public Scheduler scheduler() { return scheduler; }

    /** Run the program body as the root fiber to completion; rethrows whatever the body threw. */
    public void run(String rootName, Runnable body) {
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
