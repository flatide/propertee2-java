import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Spike — the single-baton cooperative coordinator (design §1, §2-"retained", §6).
 *
 * Invariant: at most one fiber is RUNNING (holds the baton) at any instant, so
 * interpreter state needs no locks and execution stays deterministic. The baton is
 * handed off ONLY at explicit yield points; the next holder is chosen by a
 * deterministic round-robin over fiber ids (design §6) so copied-over .expected
 * output ordering is reproducible.
 *
 * Off-baton work (real Thread.sleep for SLEEP, real blocking calls for Coop.blocking)
 * happens on the fiber's own vthread WITHOUT the baton — that is what makes the
 * "no blocking while holding the baton" invariant (design §1) enforceable and what
 * lets independent sleeps/blocks overlap instead of serialize.
 */
final class Scheduler {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition timerCond = lock.newCondition();   // wakes the timer thread
    private final Condition doneCond  = lock.newCondition();   // wakes main when all fibers finish

    private final List<Fiber> fibers = new ArrayList<>();      // all fibers, kept in ascending id order
    private final PriorityQueue<Fiber> sleepers =
            new PriorityQueue<>((a, b) -> a.wakeNanos != b.wakeNanos
                    ? Long.compare(a.wakeNanos, b.wakeNanos)
                    : Integer.compare(a.id, b.id));            // (wakeTime, id) — deterministic tie-break

    private Fiber current = null;     // the baton holder, or null when the baton is free
    private int lastRunId = -1;       // for round-robin successor selection
    private int idSeq = 0;
    private int aliveCount = 0;
    private volatile boolean shutdown = false;

    private final ThreadLocal<Fiber> self = new ThreadLocal<>();

    Fiber self() { return self.get(); }

    // ---- public driver ---------------------------------------------------

    /** Run one root program to completion on a fresh cooperative system. Blocks the caller. */
    void runToCompletion(String rootName, Runnable rootBody) {
        Thread timer = Thread.ofVirtual().name("coop-timer").unstarted(this::timerLoop);
        spawn(rootName, rootBody);          // creates root fiber (READY) + its parked vthread
        timer.start();
        lock.lock();
        try {
            scheduleNext();                 // hand the baton to the root fiber
            timerCond.signalAll();
            while (aliveCount > 0) doneCond.awaitUninterruptibly();
            shutdown = true;
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
        try { timer.join(); } catch (InterruptedException ignored) { }
    }

    // ---- fiber creation --------------------------------------------------

    /** Create a fiber in READY state with a parked vthread. Caller must trigger scheduling. */
    Fiber spawn(String name, Runnable body) {
        lock.lock();
        try {
            int id = idSeq++;
            Fiber[] box = new Fiber[1];
            Thread vt = Thread.ofVirtual().name("coop-" + id + "-" + name).unstarted(() -> {
                Fiber me = box[0];
                self.set(me);
                parkUntilRunning(me);       // wait for the baton before executing any interpreter code
                try {
                    body.run();
                } finally {
                    complete(me);           // release the baton for the last time
                }
            });
            Fiber f = new Fiber(id, name, vt);
            box[0] = f;
            f.state = FiberState.READY;
            fibers.add(f);                  // appended => list stays in ascending id order
            aliveCount++;
            vt.start();                     // immediately parks (state != RUNNING)
            return f;
        } finally {
            lock.unlock();
        }
    }

    // ---- baton transitions (all called by the fiber that holds the baton) -

    /** Coop.yield — fairness handoff (design §3, seam 3). */
    void yield_() {
        Fiber me = self.get();
        lock.lock();
        try {
            me.state = FiberState.READY;
            releaseAndPick(me);
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
    }

    /** Coop.sleep — register wake time, release baton, park; timer makes it READY (design §3, seam 1). */
    void sleep(long ms) {
        Fiber me = self.get();
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
        parkUntilRunning(me);               // stack preserved across the sleep — no replay
    }

    /**
     * Coop.blocking — release baton, run the real blocking call on THIS vthread off-baton,
     * then re-acquire and return the value in place (design §3, seam 4 — no statement replay).
     */
    <T> T blocking(java.util.function.Supplier<T> work) {
        Fiber me = self.get();
        lock.lock();
        try {
            me.state = FiberState.BLOCKED;
            releaseAndPick(me);             // someone else gets the baton while we block
        } finally {
            lock.unlock();
        }
        try {
            return work.get();              // REAL blocking, off the baton — scheduler keeps running
        } finally {
            reacquire(me);                  // reacquire even if work threw, so the caller's catch/finally
                                            // run ON the baton — otherwise the fiber would complete()
                                            // off-baton and corrupt `current`/aliveCount (deadlock).
        }
    }

    /** Parent transition: park WAITING until its multi children finish (design §4). */
    void awaitChildren(java.util.function.BooleanSupplier allDone) {
        Fiber me = self.get();
        lock.lock();
        try {
            if (allDone.getAsBoolean()) return;   // already finished — keep the baton
            me.state = FiberState.WAITING;
            releaseAndPick(me);
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
    }

    /** Wake a WAITING parent (called on-baton by the last finishing child). */
    void wake(Fiber f) {
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

    /** Release the baton from `me` (state already set) and pick the next holder. Lock held. */
    private void releaseAndPick(Fiber me) {
        if (current == me) current = null;
        scheduleNext();
    }

    /** A fiber returning from off-baton work asks for the baton back. */
    private void reacquire(Fiber me) {
        lock.lock();
        try {
            me.state = FiberState.READY;
            if (current == null) scheduleNext();
        } finally {
            lock.unlock();
        }
        parkUntilRunning(me);
    }

    /** Body finished: mark DONE, release baton, decrement alive count. */
    private void complete(Fiber me) {
        lock.lock();
        try {
            me.state = FiberState.DONE;
            if (current == me) current = null;
            aliveCount--;
            scheduleNext();
            if (aliveCount == 0) doneCond.signalAll();
            timerCond.signalAll();
        } finally {
            lock.unlock();
        }
        // vthread now exits
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
        for (Fiber f : fibers) {            // fibers is in ascending id order
            if (f.state != FiberState.READY) continue;
            if (first == null) first = f;
            if (f.id > lastRunId) return f;
        }
        return first;
    }

    /** Block the calling vthread until the scheduler grants it the baton. */
    private void parkUntilRunning(Fiber me) {
        while (me.state != FiberState.RUNNING) {
            LockSupport.park();
            // spurious wakeups & unpark-before-park are handled by re-checking the volatile state
        }
    }

    // ---- timer (wakes SLEEPING fibers in (wakeTime, id) order) ------------

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
                        Thread.currentThread().interrupt();   // re-loop; shutdown flag ends it
                    }
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
