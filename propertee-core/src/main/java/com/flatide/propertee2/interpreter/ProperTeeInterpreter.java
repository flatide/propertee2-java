package com.flatide.propertee2.interpreter;

import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.host.DefaultPlatformProvider;
import com.flatide.propertee2.interp.Interpreter;
import com.flatide.propertee2.value.Values;
import com.flatide.propertee2.scheduler.SchedulerListener;
import com.flatide.propertee2.task.UnsupportedTaskRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1 API surface (com.flatide.propertee2.interpreter.ProperTeeInterpreter) over the propertee2 cooperative engine.
 *
 * <p>v1's interpreter was a stepper visitor driven by the scheduler; propertee2 replaces that with a
 * recursive tree-walk over single-baton virtual-thread coroutines. This façade keeps v1's host-facing
 * shape — the same constructor, a public {@link #variables} globals map (a host injects e.g. {@code _SYS}
 * before the run and reads {@code result} after), {@link #createRootStepper}, and a {@link RootStepper}
 * exposing {@link RootStepper#hasExplicitReturn()} — while delegating execution to the engine. PRINT is
 * streamed live to the stdout {@link BuiltinFunctions.PrintFunction}; {@code [THREAD ERROR]} /
 * {@code [MONITOR ERROR]} / loop-limit warnings stream to the stderr one, and
 * {@code iterationLimitBehavior="warn"} stops the offending loop with a warning instead of failing
 * the run — both exactly as in v1.
 */
public class ProperTeeInterpreter {

    /** Program globals — injected before the run (e.g. {@code _SYS}) and read back after (e.g. {@code result}). */
    public Map<String, Object> variables = new LinkedHashMap<>();
    public BuiltinFunctions builtins;

    private final Map<String, Object> properties;
    private final int maxIterations;
    private final String iterationLimitBehavior;

    public ProperTeeInterpreter(Map<String, Object> properties,
                                BuiltinFunctions.PrintFunction stdout,
                                BuiltinFunctions.PrintFunction stderr,
                                int maxIterations,
                                String iterationLimitBehavior,
                                BuiltinFunctions builtins) {
        this.properties = properties;
        this.maxIterations = maxIterations;
        this.iterationLimitBehavior = iterationLimitBehavior;
        this.builtins = builtins;
    }

    public RootStepper createRootStepper(ProperTeeParser.RootContext tree) {
        return new RootStepper(tree);
    }

    /**
     * Run the program on the cooperative engine. Called by {@link com.flatide.propertee2.scheduler.Scheduler#run}.
     * A runtime error propagates (as in v1) for the host to catch. The listener receives logical-thread
     * lifecycle events: the program's main thread plus each {@code multi} worker (created/completed/error).
     */
    public Object execute(RootStepper stepper, SchedulerListener listener) {
        BuiltinFunctions.PrintFunction stdout = builtins.stdout;
        Interpreter.Sink sink = line -> { if (stdout != null) stdout.print(new Object[]{ line }); };
        // v1's second channel: [THREAD ERROR]/[MONITOR ERROR]/loop-limit warnings go to the host's
        // stderr print sink (a host like TeeBox tags its log lines by stream), never to stdout.
        BuiltinFunctions.PrintFunction stderr = builtins.stderr;
        Interpreter.Sink errSink = stderr != null ? line -> stderr.print(new Object[]{ line }) : sink;

        com.flatide.propertee2.host.PlatformProvider platform = builtins.platform != null
                ? new PlatformAdapter(builtins.platform)
                : new DefaultPlatformProvider();
        com.flatide.propertee2.task.TaskRunner taskRunner = builtins.taskRunner != null
                ? builtins.taskRunner
                : new UnsupportedTaskRunner();

        Coop coop = new Coop();
        Interpreter interp = new Interpreter(sink, properties, coop, platform, taskRunner, builtins.runId);
        interp.setErrorSink(errSink);
        interp.setLoopLimit(maxIterations);
        interp.setLoopLimitWarns("warn".equals(iterationLimitBehavior));
        for (Map.Entry<String, BuiltinFunctions.BuiltinFunction> e : builtins.custom.entrySet()) {
            final BuiltinFunctions.BuiltinFunction fn = e.getValue();
            interp.addRawBuiltin(e.getKey(), fn::call);          // cheap, on the baton (raw return)
        }
        for (Map.Entry<String, BuiltinFunctions.BuiltinFunction> e : builtins.blocking.entrySet()) {
            final BuiltinFunctions.BuiltinFunction fn = e.getValue();
            interp.addBlockingExternal(e.getKey(), fn::call);    // may block -> off the baton, Result-wrapped
        }
        // Surface multi worker threads to the host listener (created before each runs, completed/error after),
        // keyed by the worker's result-key name — so a host that observes a run sees alpha/beta/... threads.
        if (listener != null) {
            interp.setThreadObserver(new Interpreter.ThreadObserver() {
                private final java.util.Map<Integer, com.flatide.propertee2.scheduler.ThreadContext> ctxs =
                        new java.util.concurrent.ConcurrentHashMap<>();

                @Override public void created(int id, String name, Integer parentId, String resultKeyName) {
                    com.flatide.propertee2.scheduler.ThreadContext tc = new com.flatide.propertee2.scheduler.ThreadContext();
                    tc.id = id; tc.name = name; tc.parentId = parentId; tc.resultKeyName = resultKeyName;
                    tc.inThreadContext = true; tc.state = com.flatide.propertee2.scheduler.ThreadState.RUNNING;
                    ctxs.put(id, tc);
                    listener.onThreadCreated(tc);
                }
                @Override public void completed(int id, Object result) {
                    com.flatide.propertee2.scheduler.ThreadContext tc = ctxs.remove(id);   // free the worker context
                    if (tc == null) tc = new com.flatide.propertee2.scheduler.ThreadContext();
                    tc.state = com.flatide.propertee2.scheduler.ThreadState.COMPLETED; tc.result = result;
                    listener.onThreadCompleted(tc);
                }
                @Override public void error(int id, Throwable err) {
                    com.flatide.propertee2.scheduler.ThreadContext tc = ctxs.remove(id);   // free the worker context
                    if (tc == null) tc = new com.flatide.propertee2.scheduler.ThreadContext();
                    tc.state = com.flatide.propertee2.scheduler.ThreadState.ERROR; tc.error = err;
                    listener.onThreadError(tc);
                }
            });
        }

        interp.globals().putAll(this.variables);                 // inject host globals (e.g. _SYS) before the run

        // Register the program's root logical thread with the host listener so it can observe the run
        // (the run-detail "threads"); fire created before the run so a poller sees it during execution.
        com.flatide.propertee2.scheduler.ThreadContext main = new com.flatide.propertee2.scheduler.ThreadContext();
        main.id = 0;
        main.name = "main";
        main.state = com.flatide.propertee2.scheduler.ThreadState.RUNNING;
        if (listener != null) listener.onThreadCreated(main);

        try {
            coop.run("main", () -> interp.run(stepper.tree));     // a TeeError here propagates to the host
        } catch (com.flatide.propertee2.value.TeeError e) {
            // v1 host contract: the position lives in the exception MESSAGE (v1's createError baked
            // "Runtime Error at line L:C: <msg>" in; hosts store e.getMessage() as errorMessage).
            // TeeError keeps it separate, so convert at the façade boundary (fidelity gap until 0.9.1).
            com.flatide.propertee2.runtime.ProperTeeError hostError =
                    new com.flatide.propertee2.runtime.ProperTeeError(e.positioned());
            main.state = com.flatide.propertee2.scheduler.ThreadState.ERROR;
            main.error = hostError;
            if (listener != null) listener.onThreadError(main);
            throw hostError;
        } catch (Throwable t) {
            main.state = com.flatide.propertee2.scheduler.ThreadState.ERROR;
            main.error = t;
            if (listener != null) listener.onThreadError(main);
            throw t;
        }

        stepper.hasExplicitReturn = interp.hasExplicitReturn();
        stepper.result = interp.returnValue();
        this.variables.clear();
        this.variables.putAll(interp.globals());                 // expose final globals so the host reads `result`

        main.state = com.flatide.propertee2.scheduler.ThreadState.COMPLETED;
        main.result = stepper.result;
        if (listener != null) listener.onThreadCompleted(main);
        return stepper.result;
    }

    /** Opaque run handle — in v1 a stepper, here just the parsed program plus the captured return status. */
    public class RootStepper {
        final ProperTeeParser.RootContext tree;
        boolean hasExplicitReturn = false;
        Object result = Values.emptyObject();

        RootStepper(ProperTeeParser.RootContext tree) { this.tree = tree; }

        public boolean hasExplicitReturn() { return hasExplicitReturn; }
    }
}
