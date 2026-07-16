package com.flatide.propertee2.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.propertee2.core.ScriptParser;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.scheduler.Scheduler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Drives the v1 façade exactly as TeeBox's ScriptExecutor does, over the propertee2 engine. */
class FacadeTest {

    private static String join(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < args.length; i++) { if (i > 0) b.append(' '); b.append(args[i]); }
        return b.toString();
    }

    @Test void streamsPrintAndExposesResultGlobal() {
        List<String> stdoutLines = new ArrayList<>();
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "run-1", null, null);

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "PRINT(\"hello\")\nPRINT(_SYS.runId)\nresult = { \"a\": 1 }\n", new ArrayList<>());

        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 1000, "error", builtins);
        Map<String, Object> sys = new LinkedHashMap<>();
        sys.put("runId", "run-1");
        visitor.variables.put("_SYS", sys);                       // host-injected global, like TeeBox

        Scheduler scheduler = new Scheduler(visitor, null);
        ProperTeeInterpreter.RootStepper stepper = visitor.createRootStepper(tree);
        Object result = scheduler.run(stepper);

        assertEquals(List.of("hello", "run-1"), stdoutLines);     // streamed live, incl. injected _SYS
        assertFalse(stepper.hasExplicitReturn());
        assertEquals(Map.of("a", 1), visitor.variables.get("result"));   // result read-back

        // TeeBox's output selection: explicit return value, else the `result` global (value-model §8)
        Object outputData = stepper.hasExplicitReturn() ? result : visitor.variables.get("result");
        assertEquals(Map.of("a", 1), outputData);
        assertEquals(Map.of(), result);                           // no explicit return -> run() yields {}
    }

    /** v1 host contract: the exception MESSAGE carries the position (TeeBox stores getMessage()). */
    @Test void runtimeErrorReachesTheHostWithThePositionedV1Message() {
        BuiltinFunctions.PrintFunction sink = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null, null);

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "x = 1\nFAIL(\"upstream unreachable: giving up\")\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), sink, sink, 1000, "error", builtins);

        com.flatide.propertee2.runtime.ProperTeeError e =
                org.junit.jupiter.api.Assertions.assertThrows(com.flatide.propertee2.runtime.ProperTeeError.class,
                        () -> new Scheduler(visitor).run(visitor.createRootStepper(tree)));
        assertEquals("Runtime Error at line 2:0: upstream unreachable: giving up", e.getMessage());
    }

    /** v1 host contract: [THREAD ERROR] lines stream to the stderr sink, never to stdout (TeeBox tags by stream). */
    @Test void threadErrorsStreamToTheStderrSink() {
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> stderrLines.add(join(args));
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "r", null, null);

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "function boom() do\n" +
                "    FAIL(\"worker down\")\n" +
                "end\n" +
                "function fine() do\n" +
                "    return 7\n" +
                "end\n" +
                "multi r do\n" +
                "    thread a: fine()\n" +
                "    thread b: boom()\n" +
                "end\n" +
                "PRINT(\"after:\", r.a.value, r.b.ok)\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 1000, "error", builtins);
        new Scheduler(visitor).run(visitor.createRootStepper(tree));

        assertEquals(List.of("after: 7 false"), stdoutLines);
        assertEquals(List.of("[THREAD ERROR] Runtime Error at line 2:4: worker down"), stderrLines);
    }

    /** v1 iterationLimitBehavior="warn": the loop stops with a stderr warning; the run continues. */
    @Test void warnLoopsStopTheLoopWithAStderrWarningAndContinue() {
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> stderrLines.add(join(args));
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "r", null, null);

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "i = 0\nloop i < 10 do\n    i = i + 1\nend\nPRINT(\"i =\", i)\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 3, "warn", builtins);
        new Scheduler(visitor).run(visitor.createRootStepper(tree));

        assertEquals(List.of("i = 3"), stdoutLines);   // the loop ran exactly maxIterations times, as in v1
        assertEquals(List.of("Warning: Loop exceeded maximum iterations (3), stopping loop"), stderrLines);
    }

    @Test void explicitReturnAndCustomBuiltin() {
        BuiltinFunctions.PrintFunction sink = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null, null);
        builtins.register("DOUBLE", args -> ((Integer) args.get(0)) * 2);   // raw host builtin (no Result wrapper)

        ProperTeeParser.RootContext tree = ScriptParser.parse("return DOUBLE(21)\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), sink, sink, 1000, "error", builtins);
        Object result = new Scheduler(visitor).run(visitor.createRootStepper(tree));

        assertEquals(42, result);
    }

    @Test void knownFunctionNamesCoverDispatchCatalogAndHostRegistrations() {
        BuiltinFunctions.PrintFunction sink = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null, null);
        builtins.register("STREAM_FILE", args -> null);
        builtins.registerBlocking("THUMBNAIL", args -> null);

        java.util.Set<String> names = builtins.knownFunctionNames();
        // Interpreter-dispatched names (not in the catalog).
        assertEquals(true, names.containsAll(java.util.Set.of("PRINT", "SLEEP", "FAIL", "UNWRAP")));
        // Catalog: pure, host-gated, and blocking builtins are all callable names.
        assertEquals(true, names.containsAll(java.util.Set.of("SUM", "CONTAINS", "JSON_PARSE", "ENV", "SHELL", "HTTP_GET")));
        // Host registrations on this instance.
        assertEquals(true, names.containsAll(java.util.Set.of("STREAM_FILE", "THUMBNAIL")));
        // A typo is not a known name.
        assertEquals(false, names.contains("PRIN"));
    }

    // ---- host abort (0.16.0) ----------------------------------------------

    /** Calls visitor.abort() from a plain thread after a short delay, as a host cancel would. */
    private static void abortSoon(ProperTeeInterpreter visitor) {
        Thread killer = new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) { }
            visitor.abort();
        });
        killer.setDaemon(true);
        killer.start();
    }

    /** The empty-body infinite loop never reaches a statement yield — only the per-iteration checkpoint ends it. */
    @Test void abortEndsABusyInfiniteLoopAsProperTeeAborted() {
        BuiltinFunctions.PrintFunction sink = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null, null);
        ProperTeeParser.RootContext tree = ScriptParser.parse("loop true infinite do\nend\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), sink, sink, 1000, "error", builtins);
        Scheduler scheduler = new Scheduler(visitor);
        abortSoon(visitor);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                org.junit.jupiter.api.Assertions.assertThrows(com.flatide.propertee2.runtime.ProperTeeAborted.class,
                        () -> scheduler.run(visitor.createRootStepper(tree))));
    }

    @Test void abortWakesAScriptSleepEarly() {
        List<String> stdoutLines = new ArrayList<>();
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> {};
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "r", null, null);
        ProperTeeParser.RootContext tree = ScriptParser.parse("SLEEP(60000)\nPRINT(\"after\")\n", new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 1000, "error", builtins);
        Scheduler scheduler = new Scheduler(visitor);
        abortSoon(visitor);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                org.junit.jupiter.api.Assertions.assertThrows(com.flatide.propertee2.runtime.ProperTeeAborted.class,
                        () -> scheduler.run(visitor.createRootStepper(tree))));
        assertEquals(List.of(), stdoutLines);          // the statement after the sleep never ran
    }

    /**
     * Abort is not a script error: no [THREAD ERROR]/[MONITOR ERROR] lines, no final monitor tick —
     * but every announced worker still reaches a terminal listener state (a cancelled run must not
     * leave "running" thread entries behind in a host's run view).
     */
    @Test void abortedMultiReportsTerminalWorkersWithoutScriptErrorSurface() {
        List<String> stdoutLines = new ArrayList<>();
        List<String> stderrLines = new ArrayList<>();
        BuiltinFunctions.PrintFunction stdout = args -> stdoutLines.add(join(args));
        BuiltinFunctions.PrintFunction stderr = args -> stderrLines.add(join(args));
        BuiltinFunctions builtins = new BuiltinFunctions(stdout, stderr, "r", null, null);

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "function spin() do\n" +
                "    loop true infinite do\n" +
                "    end\n" +
                "    return 1\n" +
                "end\n" +
                "multi r do\n" +
                "    thread a: spin()\n" +
                "    thread b: spin()\n" +
                "monitor 60000\n" +
                "    PRINT(\"tick\")\n" +
                "end\n", new ArrayList<>());

        List<String> created = new ArrayList<>();
        List<String> terminal = new ArrayList<>();
        com.flatide.propertee2.scheduler.SchedulerListener listener = new com.flatide.propertee2.scheduler.SchedulerListener() {
            private String key(com.flatide.propertee2.scheduler.ThreadContext t) {
                return t.resultKeyName != null ? t.resultKeyName : t.name;
            }
            @Override public void onThreadCreated(com.flatide.propertee2.scheduler.ThreadContext t) { created.add(key(t)); }
            @Override public void onThreadUpdated(com.flatide.propertee2.scheduler.ThreadContext t) { }
            @Override public void onThreadCompleted(com.flatide.propertee2.scheduler.ThreadContext t) { terminal.add(key(t)); }
            @Override public void onThreadError(com.flatide.propertee2.scheduler.ThreadContext t) { terminal.add(key(t)); }
        };

        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), stdout, stderr, 1000, "error", builtins);
        Scheduler scheduler = new Scheduler(visitor, listener);
        abortSoon(visitor);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () ->
                org.junit.jupiter.api.Assertions.assertThrows(com.flatide.propertee2.runtime.ProperTeeAborted.class,
                        () -> scheduler.run(visitor.createRootStepper(tree))));

        assertEquals(List.of(), stdoutLines);          // no mid tick, and the guaranteed final tick was skipped
        assertEquals(List.of(), stderrLines);          // no [THREAD ERROR]/[MONITOR ERROR] — abort is not a script error
        assertTrue(created.containsAll(List.of("main", "a", "b")), "created: " + created);
        assertTrue(terminal.containsAll(created), "every announced thread must reach a terminal state — created: "
                + created + ", terminal: " + terminal);
    }

    @Test void blockingHostBuiltinIsResultWrappedOffBaton() {
        java.util.List<String> out = new ArrayList<>();
        BuiltinFunctions.PrintFunction sink = a -> out.add(join(a));
        BuiltinFunctions builtins = new BuiltinFunctions(sink, sink, "r", null, null);
        builtins.registerBlocking("DBL", args -> ((Integer) args.get(0)) * 2);    // return -> Result.ok(value)
        builtins.registerBlocking("BOOM", args -> { throw new RuntimeException("kaboom"); }); // throw -> Result.error

        ProperTeeParser.RootContext tree = ScriptParser.parse(
                "a = DBL(21)\nb = BOOM()\nPRINT(a.ok)\nPRINT(a.value)\nPRINT(b.ok)\nPRINT(b.value)\n",
                new ArrayList<>());
        ProperTeeInterpreter visitor =
                new ProperTeeInterpreter(new LinkedHashMap<>(), sink, sink, 1000, "error", builtins);
        new Scheduler(visitor).run(visitor.createRootStepper(tree));

        assertEquals(List.of("true", "42", "false", "kaboom"), out);
    }
}
