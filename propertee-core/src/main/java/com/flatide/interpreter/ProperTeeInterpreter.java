package com.flatide.interpreter;

import com.flatide.parser.ProperTeeParser;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.host.DefaultPlatformProvider;
import com.flatide.propertee2.interp.Interpreter;
import com.flatide.propertee2.value.Values;
import com.flatide.scheduler.SchedulerListener;
import com.flatide.task.UnsupportedTaskRunner;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v1 API surface (com.flatide.interpreter.ProperTeeInterpreter) over the propertee2 cooperative engine.
 *
 * <p>v1's interpreter was a stepper visitor driven by the scheduler; propertee2 replaces that with a
 * recursive tree-walk over single-baton virtual-thread coroutines. This façade keeps v1's host-facing
 * shape — the same constructor, a public {@link #variables} globals map (a host injects e.g. {@code _SYS}
 * before the run and reads {@code result} after), {@link #createRootStepper}, and a {@link RootStepper}
 * exposing {@link RootStepper#hasExplicitReturn()} — while delegating execution to the engine. PRINT is
 * streamed live to the stdout {@link BuiltinFunctions.PrintFunction}.
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
     * Run the program on the cooperative engine. Called by {@link com.flatide.scheduler.Scheduler#run}.
     * A runtime error propagates (as in v1) for the host to catch. The listener is reserved for
     * logical-thread lifecycle events (not yet emitted).
     */
    public Object execute(RootStepper stepper, SchedulerListener listener) {
        BuiltinFunctions.PrintFunction stdout = builtins.stdout;
        Interpreter.Sink sink = line -> { if (stdout != null) stdout.print(new Object[]{ line }); };

        com.flatide.propertee2.host.PlatformProvider platform = builtins.platform != null
                ? new PlatformAdapter(builtins.platform)
                : new DefaultPlatformProvider();
        com.flatide.task.TaskRunner taskRunner = builtins.taskRunner != null
                ? builtins.taskRunner
                : new UnsupportedTaskRunner();

        Coop coop = new Coop();
        Interpreter interp = new Interpreter(sink, properties, coop, platform, taskRunner);
        interp.setLoopLimit(maxIterations);
        for (Map.Entry<String, BuiltinFunctions.BuiltinFunction> e : builtins.custom.entrySet()) {
            final BuiltinFunctions.BuiltinFunction fn = e.getValue();
            interp.addRawBuiltin(e.getKey(), fn::call);
        }
        interp.globals().putAll(this.variables);                 // inject host globals (e.g. _SYS) before the run

        coop.run("main", () -> interp.run(stepper.tree));         // a TeeError here propagates to the host

        stepper.hasExplicitReturn = interp.hasExplicitReturn();
        stepper.result = interp.returnValue();
        this.variables.clear();
        this.variables.putAll(interp.globals());                 // expose final globals so the host reads `result`
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
