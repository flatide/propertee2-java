package com.flatide.propertee2.coop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Production Coop runtime — the spike's validated properties as regression tests
 * (see docs/spike-findings.md). Baton serialization makes the shared StringBuilder
 * safe: handoffs establish happens-before, so only one fiber touches it at a time.
 */
class CoopRuntimeTest {

    /** Spawn child fibers, run them cooperatively, and wait for all to finish (a PD-style mini-multi). */
    private static void runParallel(Coop coop, List<Runnable> tasks) {
        coop.run("root", () -> {
            Scheduler s = coop.scheduler();
            Fiber parent = s.self();
            AtomicInteger remaining = new AtomicInteger(tasks.size());
            for (Runnable t : tasks) {
                s.spawn("w", () -> {
                    try {
                        t.run();
                    } finally {
                        if (remaining.decrementAndGet() == 0) s.wake(parent);
                    }
                });
            }
            s.awaitChildren(() -> remaining.get() == 0);
        });
    }

    @Test void rootRunsToCompletion() {
        StringBuilder out = new StringBuilder();
        new Coop().run("root", () -> out.append("ran"));
        assertEquals("ran", out.toString());
    }

    @RepeatedTest(20) void deterministicRoundRobin() {
        Coop coop = new Coop();
        StringBuilder order = new StringBuilder();
        List<Runnable> tasks = new ArrayList<>();
        for (String key : List.of("A", "B", "C")) {
            tasks.add(() -> {
                for (int i = 0; i < 3; i++) {
                    order.append(key);
                    coop.yield_();
                }
            });
        }
        runParallel(coop, tasks);
        assertEquals("ABCABCABC", order.toString());   // id-ordered round-robin, stable across repeats
    }

    @Test void independentSleepsOverlap() {
        Coop coop = new Coop();
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) tasks.add(() -> coop.sleep(100));
        long t0 = System.nanoTime();
        runParallel(coop, tasks);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 250, "4 sleeps of 100ms should overlap (~100ms), got " + ms + "ms");
    }

    @Test void blockingReturnsValueOnRoot() {
        Coop coop = new Coop();
        int[] result = new int[1];
        coop.run("root", () -> result[0] = coop.blocking(() -> 42));
        assertEquals(42, result[0]);
    }

    @Test void coopIsReusableAcrossRunsThatSleep() {
        Coop coop = new Coop();
        int[] x = new int[1];
        // Before the fresh-scheduler-per-run fix, the second run's sleep had no live timer to wake it
        // (the first run latched shutdown) and this would hang — hence the preemptive timeout.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            coop.run("r1", () -> { coop.sleep(10); x[0] += 1; });
            coop.run("r2", () -> { coop.sleep(10); x[0] += 10; });
        });
        assertEquals(11, x[0]);
    }

    @Test void blockingReacquiresBatonOnException() {
        Coop coop = new Coop();
        // If blocking() skipped re-acquire on exception, the fiber would complete off-baton and
        // this run would deadlock. Reaching the assertion proves the baton was reacquired.
        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                coop.run("root", () -> coop.blocking(() -> { throw new IllegalStateException("io failed"); })));
        assertTrue(thrown.getMessage().contains("io failed"));
    }

    // ---- host abort (0.16.0) ----------------------------------------------

    /** Aborts the coop from a plain thread after a short delay. */
    private static void abortSoon(Coop coop) {
        Thread killer = new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) { }
            coop.abort();
        });
        killer.setDaemon(true);
        killer.start();
    }

    @Test void abortStopsABusyLoneFiberFromAnotherThread() {
        Coop coop = new Coop();
        abortSoon(coop);
        // A lone spinning fiber never releases the baton — only the checkpoint can end it.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(AbortError.class, () -> coop.run("root", () -> {
                    while (true) coop.checkpoint();
                })));
    }

    @Test void abortWakesASleepingFiberEarly() {
        Coop coop = new Coop();
        abortSoon(coop);
        long t0 = System.nanoTime();
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(AbortError.class, () -> coop.run("root", () -> coop.sleep(60_000))));
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        assertTrue(ms < 5_000, "a 60s sleep should be force-woken by abort, took " + ms + "ms");
    }

    @Test void abortDuringBlockingAppliesWhenTheHostCallReturns() throws Exception {
        Coop coop = new Coop();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread killer = new Thread(() -> {
            try { entered.await(); } catch (InterruptedException ignored) { }
            coop.abort();          // fully applied before the host call is released below
            release.countDown();
        });
        killer.setDaemon(true);
        killer.start();
        boolean[] resumedAfterBlocking = new boolean[1];
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(AbortError.class, () -> coop.run("root", () -> {
                    coop.blocking(() -> {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException ignored) { }
                        return 1;
                    });
                    resumedAfterBlocking[0] = true;   // must be unreachable: abort fires at re-acquire
                })));
        assertEquals(false, resumedAfterBlocking[0]);
    }

    @Test void abortBeforeRunIsLatched() {
        Coop coop = new Coop();
        coop.abort();                                  // before run() creates its fresh scheduler
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(AbortError.class, () -> coop.run("root", coop::checkpoint)));
    }

    @Test void abortUnwindsSleepingWorkersAndTheWaitingParent() {
        Coop coop = new Coop();
        abortSoon(coop);
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 3; i++) tasks.add(() -> coop.sleep(60_000));
        // Workers are force-woken and die of AbortError (quietly, in the spawn wrapper); the last one
        // wakes the WAITING parent, whose own poll then rethrows. Completing at all proves the drain.
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                assertThrows(AbortError.class, () -> runParallel(coop, tasks)));
    }
}
