package com.flatide.propertee2.coop;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The single-baton cooperative coordinator (design §1, §2-"retained", §6), promoted from the
 * validated spike. At most one fiber is RUNNING (holds the baton) at any instant, so interpreter
 * state needs no locks and execution stays deterministic. The baton is handed off ONLY at explicit
 * yield points; the next holder is chosen by a deterministic round-robin over fiber ids (§6).
 *
 * <p>Off-baton work (real Thread.sleep for SLEEP, real blocking calls for Coop.blocking) runs on
 * the fiber's own vthread WITHOUT the baton — enforcing the "no blocking while holding the baton"
 * invariant (§1) and letting independent sleeps/blocks overlap instead of serialize.
 *
 * <p>Per-fiber identity is carried in a {@link ScopedValue} (JEP 506, final in JDK 25) rather than
 * a ThreadLocal — immutable, structured, and leak-free.
 */
public final class Scheduler {
    /** The fiber currently executing on this virtual thread. */
    static final ScopedValue<Fiber> SELF = ScopedValue.newInstance();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition timerCond = lock.newCondition();
    private final Condition doneCond = lock.newCondition();

    private final List<Fiber> fibers = new ArrayList<>();           // all fibers, ascending id order
    private final PriorityQueue<Fiber> sleepers =
            new PriorityQueue<>((a, b) -> a.wakeNanos != b.wakeNanos
                    ? Long.compare(a.wakeNanos, b.wakeNanos)
                    : Integer.compare(a.id, b.id));                 // (wakeTime, id) — deterministic tie-break

    /** Statements/iterations between carrier yields; property read once so it stays fixed per process. */
    private static final int SLICE = Integer.getInteger("propertee2.coop.slice", 1024);

    private Fiber current = null;
    private int lastRunId = -1;
    private int idSeq = 0;
    private int aliveCount = 0;
    private volatile boolean shutdown = false;
    private volatile boolean aborted = false;
    private int sliceCount = 0;              // mutated only by the baton holder; handoffs fence it

    public Fiber self() { return SELF.isBound() ? SELF.get() : null; }

    // ---- host abort --------------------------------------------------------

    /**
     * Cooperatively abort the run: every fiber throws {@link AbortError} at its next checkpoint
     * (statement boundary, loop iteration, sleep wake, blocking re-acquire, children wake).
     * Thread-safe, callable from OUTSIDE any fiber. Sleeping fibers are force-woken so a long
     * SLEEP doesn't delay the abort; WAITING parents are woken by their dying children through
     * the normal signal path; BLOCKED fibers observe the flag when the host call returns.
     */
    public void abort() {
        aborted = true;
        lock.lock();
        try {
            while (!sleepers.isEmpty()) {
                Fiber f = sleepers.poll();
                if (f.state == FiberState.SLEEPING) f.state = FiberState.READY;
            }
            if (current == null) scheduleNext();
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * On-baton cancellation + carrier-fairness point (called at statement boundaries via
     * {@code yield_} and at every loop iteration via the interpreter). Never hands off the
     * baton — the deterministic round-robin is untouched. Every {@code SLICE} calls it
     * {@code Thread.yield()}s so a compute-bound fiber unmounts its carrier and other runs'
     * virtual threads get CPU time (JDK vthreads are not time-sliced).
     */
    public void checkpoint() {
        if (aborted) throw new AbortError();
        if (++sliceCount >= SLICE) {
            sliceCount = 0;
            Thread.yield();                     // off-lock by construction; baton retained
        }
    }

    private void pollAbort() {
        if (aborted) throw new AbortError();
    }

    // ---- driver ----------------------------------------------------------

    /** Run one root program to completion on a fresh cooperative system. Blocks the caller. */
    public void runToCompletion(String rootName, Runnable rootBody) {
        Thread timer = Thread.ofVirtual().name("coop-timer").unstarted(this::timerLoop);
        spawn(rootName, rootBody);
        timer.start();
        lock.lock();
        try {
            scheduleNext();
            timerCond.signalAll();
            while (aliveCount > 0) doneCond.awaitUninterruptibly();
            shutdown = true;
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
        try { timer.join(); } catch (InterruptedException ignored) { }
    }

    /** Create a fiber in READY state with a parked vthread. (Used by the root run and, in PD, by multi.) */
    public Fiber spawn(String name, Runnable body) {
        lock.lock();
        try {
            int id = idSeq++;
            Fiber[] box = new Fiber[1];
            Thread vt = Thread.ofVirtual().name("coop-" + id + "-" + name).unstarted(() ->
                    ScopedValue.where(SELF, box[0]).run(() -> {
                        Fiber me = box[0];
                        parkUntilRunning(me);       // wait for the baton before any interpreter code
                        try {
                            body.run();
                        } catch (AbortError ignored) {
                            // Aborted fibers die quietly (no uncaught-handler noise). The root's abort
                            // is captured and rethrown to the host by Coop.run's own wrapper first.
                        } finally {
                            complete(me);           // release the baton for the last time
                        }
                    }));
            Fiber f = new Fiber(id, name, vt);
            box[0] = f;
            f.state = FiberState.READY;
            fibers.add(f);
            aliveCount++;
            vt.start();                             // immediately parks (state != RUNNING)
            return f;
        } finally {
            lock.unlock();
        }
    }

    // ---- baton transitions (called by the fiber holding the baton) -------

    /** Coop.yield — fairness handoff (design §3, seam 3). No-op when no other fiber is READY. */
    public void yield_() {
        checkpoint();                         // before the alone-shortcut below — a lone busy fiber must still abort
        Fiber me = SELF.get();
        lock.lock();
        try {
            if (!anotherReady(me)) return;        // alone — keep the baton, skip a pointless handoff
            me.state = FiberState.READY;
            releaseAndPick(me);
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
    }

    private boolean anotherReady(Fiber me) {
        for (Fiber f : fibers) {
            if (f != me && f.state == FiberState.READY) return true;
        }
        return false;
    }

    /** Coop.sleep — register wake time, release baton, park; the timer makes it READY (design §3, seam 1). */
    public void sleep(long ms) {
        Fiber me = SELF.get();
        lock.lock();
        try {
            me.state = FiberState.SLEEPING;
            me.wakeNanos = System.nanoTime() + ms * 1_000_000L;
            sleepers.add(me);
            releaseAndPick(me);
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);                       // stack preserved across the sleep — no replay
        pollAbort();                                // covers both force-woken and naturally-expired sleeps
    }

    /**
     * Coop.blocking — release baton, run the real blocking call on THIS vthread off-baton, then
     * re-acquire and return in place (design §3, seam 4 — no statement replay). Re-acquires even on
     * exception so the caller's catch/finally run ON the baton.
     */
    public <T> T blocking(Supplier<T> work) {
        Fiber me = SELF.get();
        lock.lock();
        try {
            me.state = FiberState.BLOCKED;
            releaseAndPick(me);
        } finally {
            lock.unlock();
        }
        try {
            return work.get();                      // REAL blocking, off the baton
        } finally {
            reacquire(me);
        }
    }

    /** Parent transition: park WAITING until {@code allDone} (design §4, PD multi). */
    public void awaitChildren(BooleanSupplier allDone) {
        Fiber me = SELF.get();
        lock.lock();
        try {
            if (allDone.getAsBoolean()) return;
            me.state = FiberState.WAITING;
            releaseAndPick(me);
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
        pollAbort();                          // children died of the abort — the parent must not resume normally
    }

    /** Wake a WAITING parent (called on-baton by the last finishing child, PD). */
    public void wake(Fiber f) {
        lock.lock();
        try {
            if (f.state == FiberState.WAITING) {
                f.state = FiberState.READY;
                if (current == null) scheduleNext();
            }
        } finally {
            lock.unlock();
        }
    }

    // ---- internals -------------------------------------------------------

    private void releaseAndPick(Fiber me) {
        if (current == me) current = null;
        scheduleNext();
    }

    private void reacquire(Fiber me) {
        lock.lock();
        try {
            me.state = FiberState.READY;
            if (current == null) scheduleNext();
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
        pollAbort();                          // abort applies as soon as the blocking host call returns
    }

    private void complete(Fiber me) {
        lock.lock();
        try {
            me.state = FiberState.DONE;
            // Drop the finished fiber so a long run with many `multi` rounds doesn't accumulate DONE
            // fibers (O(total spawned)) and grow the selectNext/anotherReady scan. Safe for the
            // deterministic round-robin: selection is by state+id, not list position, and DONE fibers
            // were skipped anyway; the list stays ascending-id and ~aliveCount in size.
            fibers.remove(me);
            if (current == me) current = null;
            aliveCount--;
            scheduleNext();
            if (aliveCount == 0) doneCond.signalAll();
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** If the baton is free, pick the deterministic round-robin successor and unpark it. Lock held. */
    private void scheduleNext() {
        if (current != null) return;
        Fiber pick = selectNext();
        if (pick != null) {
            current = pick;
            pick.state = FiberState.RUNNING;
            lastRunId = pick.id;
            LockSupport.unpark(pick.vthread);
        }
    }

    /** First READY fiber with id > lastRunId, else the lowest-id READY fiber (round-robin). Lock held. */
    private Fiber selectNext() {
        Fiber first = null;
        for (Fiber f : fibers) {                     // ascending id order
            if (f.state != FiberState.READY) continue;
            if (first == null) first = f;
            if (f.id > lastRunId) return f;
        }
        return first;
    }

    private void parkUntilRunning(Fiber me) {
        while (me.state != FiberState.RUNNING) {
            LockSupport.park();                       // unpark-before-park and spurious wakeups handled by re-check
        }
    }

    private void timerLoop() {
        lock.lock();
        try {
            while (!shutdown) {
                if (sleepers.isEmpty()) {
                    timerCond.awaitUninterruptibly();
                    continue;
                }
                Fiber earliest = sleepers.peek();
                long now = System.nanoTime();
                if (earliest.wakeNanos <= now) {
                    sleepers.poll();
                    if (earliest.state == FiberState.SLEEPING) {
                        earliest.state = FiberState.READY;
                        if (current == null) scheduleNext();
                    }
                } else {
                    try {
                        timerCond.awaitNanos(earliest.wakeNanos - now);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
