package com.flatide.propertee2.interp;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * spec v0.19.0 — {@code multi ... limit K}: the corners the conformance fixtures (126–129)
 * can't observe. The cap itself is proven with a host-side concurrency counter (an external
 * runs off-baton via {@code Coop.blocking}, so true overlap is measurable); the fixtures only
 * pin the script-visible interleaving.
 */
class MultiLimitTest {

    /** The gate genuinely caps concurrent off-baton work at K — and fills all K slots. */
    @Test void limitCapsConcurrentBlockingWorkAtExactlyK() {
        AtomicInteger current = new AtomicInteger();
        AtomicInteger max = new AtomicInteger();
        Engine e = new Engine().registerExternal("TRACK", args -> {
            int c = current.incrementAndGet();
            max.accumulateAndGet(c, Math::max);
            try { Thread.sleep(80); } catch (InterruptedException ex) { throw new RuntimeException(ex); }
            current.decrementAndGet();
            return 0;
        });
        String out = e.run("""
                function job(n) do
                    r = TRACK(n)
                    return n
                end
                multi res limit 2 do
                    loop n in [1..6] do
                        thread $("j" + TO_STRING(n)): job(n)
                    end
                end
                PRINT(LEN(res), res.j1.value, res.j6.value)
                """);
        assertEquals("6 1 6\n", out);
        assertEquals(2, max.get(), "the two slots must actually fill, and never exceed K");
    }

    /** Bad limit shapes beyond the fixtures' zero/decimal: negative, boolean, string. */
    @Test void limitRejectsNegativeAndNonNumberValues() {
        for (String bad : new String[]{"-1", "true", "\"two\""}) {
            String out = new Engine().run("""
                    function f() do
                        return 1
                    end
                    multi r limit %s do
                        thread a: f()
                    end
                    """.formatted(bad));
            assertTrue(out.contains("multi limit must be a positive integer"),
                    "limit " + bad + " must be rejected, got: " + out);
        }
    }

    /** The watchdog is exempt from the gate, and a pending worker reads as plain "running". */
    @Test void monitorDoesNotConsumeASlotAndSeesPendingAsRunning() {
        String out = new Engine().run("""
                function work(ms, n) do
                    SLEEP(ms)
                    return n
                end
                multi r limit 1 do
                    thread t1: work(400, 1)
                    thread t2: work(100, 2)
                monitor 150
                    PRINT(r.t1.status, r.t2.status)
                end
                PRINT("final:", r.t1.value, r.t2.value)
                """);
        // Mid ticks fire while the single slot is saturated (the monitor is not gated), and the
        // never-admitted t2 is indistinguishable from a running worker — both read "running".
        assertTrue(out.contains("running running"), out);
        // Guaranteed final tick after all workers (including the late-admitted t2) are done.
        assertTrue(out.contains("done done"), out);
        assertTrue(out.endsWith("final: 1 2\n"), out);
    }

    /** Textual order: the limit expression is evaluated before the global snapshot is taken. */
    @Test void limitExpressionEvaluatesAtEntryBeforeTheSnapshot() {
        String out = new Engine().run("""
                seen = "init"
                function pick() do
                    ::seen = "by-limit"
                    return 1
                end
                function read() do
                    return ::seen
                end
                multi r limit pick() do
                    thread a: read()
                end
                PRINT(r.a.value)
                """);
        assertEquals("by-limit\n", out, "a global write inside the limit expression precedes the worker snapshot");
    }

    /** Errors under the gate: [THREAD ERROR] flows as usual and the freed slot admits the next worker. */
    @Test void workerErrorFreesTheSlotAndAdmissionContinues() {
        String out = new Engine().run("""
                function boom() do
                    return 1 / 0
                end
                function ok(n) do
                    return n
                end
                multi r limit 1 do
                    thread a: boom()
                    thread b: ok(2)
                end
                PRINT(r.a.status, r.b.value)
                """);
        assertTrue(out.contains("[THREAD ERROR]"), out);
        assertTrue(out.endsWith("error 2\n"), out);
    }
}
