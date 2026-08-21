package com.flatide.propertee2.interp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host API 0.22.0 — the {@code debug} breakpoint hook. Covers what fixture 70 (no handler =
 * no-op) can't: in-place evaluation semantics, re-entry, worker/monitor exemption, and
 * {@link DebugQuit} session survival.
 */
class DebugHandlerTest {

    private static Engine.Session session(List<String> out, DebugHandler handler) {
        return new Engine().session(Map.of(), out::add, out::add).setDebugHandler(handler);
    }

    /** Inside a function: eval reads the innermost frame, and assignment mutates the live run. */
    @Test void breakExposesPositionLocalsAndEvalMutatesTheFrame() {
        List<String> out = new ArrayList<>();
        int[] pos = new int[2];
        Object[] seen = new Object[2];
        Engine.Session s = session(out, frame -> {
            pos[0] = frame.line(); pos[1] = frame.column();
            seen[0] = frame.eval("b");                        // read a local
            seen[1] = frame.locals().get("a");                // param visible in locals()
            frame.eval("b = 100");                            // write the local frame
        });
        s.eval("""
                function f(a) do
                    b = a + 1
                    debug
                    return b
                end
                PRINT(f(41))
                """);
        assertEquals(3, pos[0]);
        assertEquals(42, seen[0], "local b before mutation");
        assertEquals(41, seen[1], "param a via locals()");
        assertEquals(List.of("100"), out, "the debugger's assignment changed what f returned");
    }

    /** At top level the paused scope is the globals; locals() is empty there. */
    @Test void topLevelEvalReadsAndWritesGlobals() {
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            assertEquals(Map.of(), frame.locals());
            assertEquals(5, frame.eval("x"));
            assertEquals(5, frame.globals().get("x"));
            frame.eval("x = 7");
        });
        s.eval("x = 5\ndebug\nPRINT(x)");
        assertEquals(List.of("7"), out);
    }

    /** return at the prompt echoes without unwinding; break must not leak into the paused loop. */
    @Test void returnEchoesAndBreakIsSwallowed() {
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            assertEquals(42, frame.eval("return 42"));
            assertEquals(null, frame.eval("break"));
        });
        s.eval("""
                total = 0
                loop i in [1..3] do
                    debug
                    total = total + i
                end
                PRINT(total)
                """);
        assertEquals(List.of("6"), out, "a leaked break would have ended the loop after iteration 1");
    }

    /** debug inside eval'd code is a no-op; separate debug statements each fire once. */
    @Test void debuggerDoesNotReenterItself() {
        AtomicInteger breaks = new AtomicInteger();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.incrementAndGet();
            frame.eval("debug\ny = 1");                       // must not recurse
        });
        s.eval("debug\ndebug\nPRINT(\"done\")");
        assertEquals(2, breaks.get());
        assertEquals(List.of("done"), out);
    }

    /** Workers and the monitor watchdog never break — debug stays a no-op there. */
    @Test void workerAndMonitorDebugAreNoops() {
        AtomicInteger breaks = new AtomicInteger();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> breaks.incrementAndGet());
        s.eval("""
                function w(n) do
                    debug
                    SLEEP(60)
                    return n
                end
                multi r limit 1 do
                    thread a: w(1)
                    thread b: w(2)
                monitor 25
                    debug
                end
                PRINT(r.a.value, r.b.value)
                """);
        assertEquals(0, breaks.get(), "debug in worker/monitor must not invoke the handler");
        assertEquals(List.of("1 2"), out);
    }

    /** quit() aborts the run as DebugQuit; the session keeps pre-abort state and stays usable. */
    @Test void quitAbortsTheRunButTheSessionSurvives() {
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, DebugFrame::quit);
        s.eval("g = 1");
        assertThrows(DebugQuit.class, () -> s.eval("g = 2\ndebug\ng = 3"));
        assertEquals(2, s.globals().get("g"), "effects before the abort persist, like a midway script error");
        assertEquals(12, s.eval("g + 10"), "the session Coop is not poisoned (unlike Coop.abort's latch)");
        assertTrue(out.isEmpty());
    }

    /** Script errors inside eval leave the paused run intact — the handler sees the TeeError. */
    @Test void evalErrorsSurfaceToTheHandlerAndTheRunContinues() {
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            assertThrows(com.flatide.propertee2.value.TeeError.class, () -> frame.eval("1 / 0"));
            assertThrows(com.flatide.propertee2.Parsing.SyntaxException.class, () -> frame.eval("if then"));
        });
        s.eval("debug\nPRINT(\"alive\")");
        assertEquals(List.of("alive"), out);
    }
}
