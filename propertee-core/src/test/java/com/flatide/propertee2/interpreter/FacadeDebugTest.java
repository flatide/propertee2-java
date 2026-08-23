package com.flatide.propertee2.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.interp.DebugFrame;
import com.flatide.propertee2.interp.DebugHandler;
import com.flatide.propertee2.interp.DebugQuit;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.scheduler.Scheduler;
import com.flatide.propertee2.scheduler.SchedulerListener;
import com.flatide.propertee2.scheduler.ThreadContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The v1 façade's debug hooks (0.26.0) — {@code ProperTeeInterpreter.setDebugHandler} /
 * {@code debugBreakpoints()} — driven exactly as a debugging host (TeeBox's debug re-run)
 * would. Engine-level debugger semantics are pinned in {@code interp.DebugHandlerTest}; this
 * pins the façade plumbing: the handler and the live breakpoint set reach the per-run core
 * interpreter, and {@code DebugQuit} reaches the host raw.
 */
class FacadeDebugTest {

    private static String join(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.length; i++) { if (i > 0) b.append(' '); b.append(args[i]); }
        return b.toString();
    }

    private final List<String> stdoutLines = new ArrayList<>();
    private final List<String> breaks = new ArrayList<>();     // "REASON@line" per onBreak

    private ProperTeeInterpreter visitorFor(String script, DebugHandler handler) {
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "dbg-run", null, null);
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 1000, "error", builtins);
        visitor.setDebugHandler(handler);
        this.tree = ScriptParser.parse(script, new ArrayList<>());
        return visitor;
    }

    private ProperTeeParser.RootContext tree;

    private Object run(ProperTeeInterpreter visitor, SchedulerListener listener) {
        return new Scheduler(visitor, listener).run(visitor.createRootStepper(tree));
    }

    @Test void debugStatementBreaksAndEvalMutatesThePausedScope() {
        ProperTeeInterpreter visitor = visitorFor(
                "x = 1\n" +
                "debug\n" +
                "PRINT(x)\n",
                frame -> {
                    breaks.add(frame.reason() + "@" + frame.line());
                    frame.eval("x = 42");                      // write into the paused (global) scope
                });
        run(visitor, null);
        assertEquals(List.of("DEBUG_STATEMENT@2"), breaks);
        assertEquals(List.of("42"), stdoutLines);              // the mutation is what resumed
    }

    @Test void breakpointsFireFromTheFacadeLiveSetIncludingMidRunAdds() {
        ProperTeeInterpreter[] ref = new ProperTeeInterpreter[1];
        ProperTeeInterpreter visitor = visitorFor(
                "PRINT(\"a\")\n" +
                "PRINT(\"b\")\n" +
                "PRINT(\"c\")\n" +
                "PRINT(\"d\")\n",
                frame -> {
                    breaks.add(frame.reason() + "@" + frame.line());
                    if (frame.line() == 2) ref[0].debugBreakpoints().add(4);   // mid-run add via the façade set
                });
        ref[0] = visitor;
        visitor.debugBreakpoints().add(2);                     // pre-run seed, before execute() exists
        run(visitor, null);
        assertEquals(List.of("BREAKPOINT@2", "BREAKPOINT@4"), breaks);
        assertEquals(List.of("a", "b", "c", "d"), stdoutLines);
    }

    @Test void steppingWorksThroughTheFacade() {
        ProperTeeInterpreter visitor = visitorFor(
                "x = 1\n" +
                "debug\n" +
                "x = x + 1\n" +
                "PRINT(x)\n",
                frame -> {
                    breaks.add(frame.reason() + "@" + frame.line());
                    if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT) {
                        frame.stepOver();
                    } else {
                        assertEquals("x = x + 1", frame.statementText());
                    }
                });
        run(visitor, null);
        assertEquals(List.of("DEBUG_STATEMENT@2", "STEP@3"), breaks);
        assertEquals(List.of("2"), stdoutLines);
    }

    @Test void workerBreakCarriesLogicalThreadIdentityThroughTheFacade() {
        ProperTeeInterpreter visitor = visitorFor("""
                function worker(n) do
                    debug
                    return n
                end
                multi r do
                    thread job: worker(7)
                monitor 1
                    debug
                end
                PRINT(r.job.value)
                """, frame -> breaks.add(frame.reason() + "@" + frame.line()
                + "#" + frame.threadId() + ":" + frame.threadName()
                + ":" + frame.locals().get("n")));
        run(visitor, null);
        assertEquals(List.of("DEBUG_STATEMENT@2#1:worker:7"), breaks,
                "worker breaks once and the monitor debug remains excluded");
        assertEquals(List.of("7"), stdoutLines);
    }

    @Test void stepReturnsSurfaceThroughTheFacade() {
        ProperTeeInterpreter visitor = visitorFor("""
                function f(n) do
                    return n + 1
                end
                debug
                x = f(41)
                PRINT(x)
                """, frame -> {
            StringBuilder b = new StringBuilder(frame.reason() + "@" + frame.line());
            for (var ret : frame.returns()) b.append('[').append(ret.function()).append('=').append(ret.value()).append(']');
            breaks.add(b.toString());
            if (frame.reason() == DebugFrame.Reason.DEBUG_STATEMENT || frame.line() == 5) frame.stepOver();
        });
        run(visitor, null);
        assertEquals(List.of("DEBUG_STATEMENT@4", "STEP@5", "STEP@6[f=42]"), breaks,
                "the crossed return reaches a facade host's frame");
        assertEquals(List.of("42"), stdoutLines);
    }

    /** quit() must reach the host as DebugQuit — not remapped to ProperTeeError/ProperTeeAborted. */
    @Test void quitUnwindsExecuteAsDebugQuitAndReportsTheMainThreadTerminal() {
        List<ThreadContext> errored = new ArrayList<>();
        SchedulerListener listener = new SchedulerListener() {
            @Override public void onThreadCreated(ThreadContext t) { }
            @Override public void onThreadUpdated(ThreadContext t) { }
            @Override public void onThreadCompleted(ThreadContext t) { }
            @Override public void onThreadError(ThreadContext t) { errored.add(t); }
        };
        ProperTeeInterpreter visitor = visitorFor(
                "PRINT(\"before\")\n" +
                "debug\n" +
                "PRINT(\"after\")\n",
                DebugFrame::quit);
        assertThrows(DebugQuit.class, () -> run(visitor, listener));
        assertEquals(List.of("before"), stdoutLines);          // nothing past the quit ran
        assertEquals(1, errored.size());                       // main still reaches a terminal state
        assertInstanceOf(DebugQuit.class, errored.get(0).error);
    }

    /** Without a handler the façade changes nothing: debug stays the spec'd normal-execution no-op. */
    @Test void noHandlerKeepsDebugANoOp() {
        ProperTeeInterpreter visitor = visitorFor(
                "debug\n" +
                "PRINT(\"ran\")\n",
                null);
        visitor.debugBreakpoints().add(2);                     // ignored too: only consulted when armed
        run(visitor, null);
        assertTrue(breaks.isEmpty());
        assertEquals(List.of("ran"), stdoutLines);
    }
}
