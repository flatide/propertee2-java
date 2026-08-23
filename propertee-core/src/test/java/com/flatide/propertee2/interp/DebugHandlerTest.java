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
 * no-op) can't: in-place evaluation semantics, re-entry, worker inclusion/monitor exemption, and
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

    /** Workers break with their own id/name/scope; the monitor watchdog remains excluded. */
    @Test void workerDebugBreaksButMonitorDebugRemainsANoOp() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> breaks.add(
                frame.reason() + "@" + frame.line() + "#" + frame.threadId()
                        + ":" + frame.threadName() + ":" + frame.locals().get("n")));
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
        assertEquals(List.of(
                "DEBUG_STATEMENT@2#1:w:1",
                "DEBUG_STATEMENT@2#2:w:2"), breaks,
                "both workers break; monitor debug contributes no third break");
        assertEquals(List.of("1 2"), out);
    }

    /** Host line breakpoints apply inside workers even when the script has no debug statement. */
    @Test void lineBreakpointFiresInsideWorker() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> breaks.add(
                frame.reason() + "@" + frame.line() + "#" + frame.threadId()
                        + ":" + frame.threadName() + ":" + frame.locals().get("n")));
        s.debugBreakpoints().add(2);
        s.eval("""
                function w(n) do
                    x = n + 1
                    return x
                end
                multi r do
                    thread answer: w(41)
                end
                PRINT(r.answer.value)
                """);
        assertEquals(List.of("BREAKPOINT@2#1:w:41"), breaks);
        assertEquals(List.of("42"), out);
    }

    /** A step armed in a worker follows that worker instead of jumping to a sibling. */
    @Test void workerStepStaysOnThePausedLogicalThread() {
        List<String> out = new ArrayList<>();
        Steps steps = new Steps() {
            @Override public void onBreak(DebugFrame frame) {
                super.onBreak(frame);
                if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT) frame.stepOver();
            }
        };
        session(out, steps).eval("""
                function w(n) do
                    x = n
                    debug
                    x = x + 1
                    return x
                end
                multi r limit 1 do
                    thread a: w(1)
                    thread b: w(10)
                end
                PRINT(r.a.value, r.b.value)
                """);
        assertEquals(List.of(
                "DEBUG_STATEMENT@3", "STEP@4",
                "DEBUG_STATEMENT@3", "STEP@4"), steps.breaks);
        assertEquals(List.of("2 11"), out);
    }

    /**
     * The interleaved discriminator for step scoping: without a limit, sibling wb's statements
     * run between wa arming stepOver and wa's next statement (deterministic round-robin yields
     * at every statement head). An unscoped step would fire on wb's very next statement.
     */
    @Test void interleavedSiblingCannotStealAnArmedWorkerStep() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(frame.reason() + "@" + frame.line() + "#" + frame.threadId()
                    + ":" + frame.threadName());
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT) frame.stepOver();
        });
        s.eval("""
                function wa(n) do
                    x = n
                    debug
                    x = x + 1
                    x = x + 1
                    return x
                end
                function wb(n) do
                    y = n
                    y = y + 1
                    y = y + 2
                    y = y + 3
                    return y
                end
                multi r do
                    thread a: wa(1)
                    thread b: wb(100)
                end
                PRINT(r.a.value, r.b.value)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@3#1:wa", "STEP@4#1:wa"), breaks,
                "the armed step fires on wa only; interleaving wb never stops");
        assertEquals(List.of("3 106"), out);
    }

    // ---- 0.29.0: auto-display of crossed function return values -------------------------------

    /** reason@line plus, when present, the crossed returns as [f=v,...] (Java toString values). */
    private static String fmt(DebugFrame frame) {
        StringBuilder b = new StringBuilder(frame.reason() + "@" + frame.line());
        if (!frame.returns().isEmpty()) {
            b.append(frame.returns().stream()
                    .map(r -> r.function() + "=" + r.value())
                    .collect(java.util.stream.Collectors.joining(",", "[", "]")));
        }
        return b.toString();
    }

    /** stepOver shows the returns of calls the stepped statement made — nested calls excluded. */
    @Test void stepOverShowsReturnsOfCallsMadeByTheSteppedStatementOnly() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(fmt(frame));
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 9) frame.stepOver();
        });
        s.eval("""
                function g(n) do
                    return n * 2
                end
                function f(n) do
                    x = g(n)
                    return x + 1
                end
                debug
                y = f(20)
                PRINT(y)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@8", "STEP@9", "STEP@10[f=41]"), breaks,
                "f's return surfaces; g's nested return does not");
        assertEquals(List.of("41"), out);
    }

    /** stepOut shows the return value of the function being left. */
    @Test void stepOutShowsTheReturnOfTheFunctionBeingLeft() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(fmt(frame));
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT) frame.stepOut();
        });
        s.eval("""
                function f(n) do
                    debug
                    return n + 1
                end
                z = f(41) + 1
                PRINT(z)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@2", "STEP@6[f=42]"), breaks);
        assertEquals(List.of("43"), out);
    }

    /** Stepping across a return statement surfaces that return at the caller-side stop (pdb-like). */
    @Test void stepIntoAcrossAReturnStatementShowsThatReturn() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(fmt(frame));
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 3) frame.stepInto();
        });
        s.eval("""
                function f(n) do
                    debug
                    return n * 2
                end
                y = f(21)
                PRINT(y)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@2", "STEP@3", "STEP@6[f=42]"), breaks);
        assertEquals(List.of("42"), out);
    }

    /** The all-stop rule extends to returns: a breakpoint stop drops what the step had crossed. */
    @Test void breakpointStopDropsCrossedReturns() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(fmt(frame));
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 5) frame.stepOver();
        });
        s.debugBreakpoints().add(6);
        s.eval("""
                function f() do
                    return 7
                end
                debug
                x = f()
                PRINT(x)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@4", "STEP@5", "BREAKPOINT@6"), breaks,
                "the stop at line 6 is a breakpoint, so f's crossed return is not delivered");
        assertEquals(List.of("7"), out);
    }

    /** Returns recorded for a worker's step never include an interleaving sibling's returns. */
    @Test void workerStepReturnsExcludeSiblingThreads() {
        List<String> breaks = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            breaks.add(fmt(frame) + "#" + frame.threadId());
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 7) frame.stepOver();
        });
        s.eval("""
                function inner(n) do
                    return n + 1
                end
                function wa(n) do
                    x = n
                    debug
                    x = inner(x)
                    return x
                end
                function wb(n) do
                    y = inner(n)
                    y = inner(y)
                    y = inner(y)
                    return y
                end
                multi r do
                    thread a: wa(1)
                    thread b: wb(100)
                end
                PRINT(r.a.value, r.b.value)
                """);
        assertEquals(List.of("DEBUG_STATEMENT@6#1", "STEP@7#1", "STEP@8[inner=2]#1"), breaks,
                "wb's interleaving inner() returns (101/102/103) must not appear");
        assertEquals(List.of("2 103"), out);
    }

    /** A function with no explicit return records the language's absence value {}. */
    @Test void implicitReturnRecordsTheEmptyObject() {
        List<DebugReturn> lastReturns = new ArrayList<>();
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, frame -> {
            if (frame.reason() == DebugFrame.Reason.STEP) lastReturns.addAll(frame.returns());
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 4) frame.stepOver();
        });
        s.eval("""
                function noop() do
                end
                debug
                noop()
                PRINT("done")
                """);
        assertEquals(1, lastReturns.size());
        assertEquals("noop", lastReturns.get(0).function());
        assertEquals(new java.util.LinkedHashMap<>(), lastReturns.get(0).value());
        assertEquals(List.of("done"), out);
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

    /** quit() from a worker is promoted to the whole run without poisoning the reusable session. */
    @Test void workerQuitAbortsTheWholeRunAndTheSessionSurvives() {
        List<String> out = new ArrayList<>();
        Engine.Session s = session(out, DebugFrame::quit);
        s.eval("g = 1");
        assertThrows(DebugQuit.class, () -> s.eval("""
                function stop_worker() do
                    debug
                    return 1
                end
                multi r do
                    thread stop: stop_worker()
                end
                g = 99
                """));
        assertEquals(1, s.globals().get("g"), "nothing after the aborted multi ran");
        assertEquals(2, s.eval("g + 1"), "Scheduler-only abort does not latch the session Coop");
    }

    // ---- 0.23.0: stepping, call stack, line breakpoints ---------------------------------------

    private static final String STEP_SRC = """
            function inner(n) do
                x = n * 2
                return x
            end
            a = 1
            debug
            b = inner(a)
            c = b + 1
            PRINT(c)
            """;

    /** A scripted handler: records (reason, line) per break and replays queued commands. */
    private static class Steps implements DebugHandler {
        final List<String> breaks = new ArrayList<>();
        final List<java.util.function.Consumer<DebugFrame>> plan = new ArrayList<>();
        int at = 0;
        Steps then(java.util.function.Consumer<DebugFrame> cmd) { plan.add(cmd); return this; }
        @Override public void onBreak(DebugFrame frame) {
            breaks.add(frame.reason() + "@" + frame.line());
            if (at < plan.size()) plan.get(at++).accept(frame);
        }
    }

    /** :n — pause at the next statement at the same depth; the call inside runs unpaused. */
    @Test void stepOverSkipsIntoCalledFunctions() {
        List<String> out = new ArrayList<>();
        Steps steps = new Steps().then(DebugFrame::stepOver).then(f -> {
            assertEquals("b = inner(a)", f.statementText());
            f.stepOver();
        }).then(f -> { });                                    // line 8: plain continue
        session(out, steps).eval(STEP_SRC);
        assertEquals(List.of("DEBUG_STATEMENT@6", "STEP@7", "STEP@8"), steps.breaks);
        assertEquals(List.of("3"), out);
    }

    /** :s into the call, :where inside it, :r back out to the caller's next statement. */
    @Test void stepIntoSeesTheCallStackAndStepOutReturns() {
        List<String> out = new ArrayList<>();
        Steps steps = new Steps()
                .then(DebugFrame::stepInto)                   // debug@6 -> next stmt anywhere
                .then(DebugFrame::stepInto)                   // 7: b = inner(a) -> into inner
                .then(f -> {                                  // 2: x = n * 2 (inside inner)
                    assertEquals(List.of(new DebugCallSite("inner", 7, 4)), f.callStack());
                    assertEquals(1, f.locals().get("n"));
                    f.stepOut();
                })
                .then(f -> assertEquals(List.of(), f.callStack()));   // 8: back at top level
        session(out, steps).eval(STEP_SRC);
        assertEquals(List.of("DEBUG_STATEMENT@6", "STEP@7", "STEP@2", "STEP@8"), steps.breaks);
        assertEquals(List.of("3"), out);
    }

    /** A line breakpoint fires each time the line executes — loop iterations included. */
    @Test void lineBreakpointFiresPerIteration() {
        List<String> out = new ArrayList<>();
        List<Object> seenI = new ArrayList<>();
        Engine.Session s = session(out, f -> seenI.add(f.eval("i")));
        s.debugBreakpoints().add(6);
        s.eval("""
                function bump(n) do
                    return n + 1
                end
                total = 0
                loop i in [1..3] do
                    total = total + bump(i)
                end
                PRINT(total)
                """);
        assertEquals(List.of(1, 2, 3), seenI);
        assertEquals(List.of("9"), out);
    }

    /** A block statement pauses before executing as a whole; its text is sliced to one line. */
    @Test void blockStatementTextIsSliced() {
        List<String> out = new ArrayList<>();
        String[] text = new String[1];
        Steps steps = new Steps().then(DebugFrame::stepOver).then(f -> text[0] = f.statementText());
        session(out, steps).eval("debug\nloop i in [1..2] do\n    x = i\nend\n");
        assertEquals(List.of("DEBUG_STATEMENT@1", "STEP@2"), steps.breaks);
        assertEquals("loop i in [1..2] do …", text[0]);
    }

    /** A step armed when a run ends must not leak a break into the next snippet. */
    @Test void armedStepDoesNotLeakIntoTheNextRun() {
        List<String> out = new ArrayList<>();
        Steps steps = new Steps().then(DebugFrame::stepOver);   // debug is the last statement
        Engine.Session s = session(out, steps);
        s.eval("x = 1\ndebug");
        s.eval("y = 2\nPRINT(y)");
        assertEquals(List.of("DEBUG_STATEMENT@2"), steps.breaks, "no STEP break in the second snippet");
        assertEquals(List.of("2"), out);
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
