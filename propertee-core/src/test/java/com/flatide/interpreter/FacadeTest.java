package com.flatide.interpreter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.core.ScriptParser;
import com.flatide.parser.ProperTeeParser;
import com.flatide.scheduler.Scheduler;
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
}
