package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Interpreter behaviors not pinned by a single .expected fixture. */
class InterpreterTest {

    private final Engine engine = new Engine();

    /** Dynamic local scope: a callee sees the caller's locals (LANGUAGE.md §Lookup Order). */
    @Test void nestedCallSeesCallerLocal() {
        String out = engine.run("""
                function outer() do
                    a = 10
                    function inner() do
                        return a
                    end
                    return inner()
                end
                PRINT(outer())
                """);
        assertEquals("10\n", out);
    }

    /** A plain read inside a function never falls through to globals — only :: does. */
    @Test void plainReadInFunctionDoesNotSeeGlobals() {
        String out = engine.run("""
                x = 10
                function badRead() do
                    return x
                end
                badRead()
                """);
        assertEquals("Runtime error: Runtime Error at line 3:11: Variable 'x' is not defined in local scope."
                + " Use ::x to access the global variable.\n", out);
    }

    /** Built-in properties sit at the bottom of the lookup chain; a global with the same name wins. */
    @Test void globalShadowsBuiltinProperty() {
        String out = engine.run("PRINT(width)\nwidth = 5\nPRINT(width)\n", Map.of("width", 100));
        assertEquals("100\n5\n", out);
    }

    /** A worker that leaks a non-error control-flow signal must yield an error result, never stay "running". */
    @Test void workerLeakingControlFlowYieldsErrorNotRunning() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String out = engine.run("""
                    function w() do
                        break
                        return 1
                    end
                    multi r do
                        thread t: w()
                    end
                    PRINT(r.t.status)
                    PRINT(r.t.ok)
                    """);
            assertTrue(out.contains("error"), out);   // not stuck "running"
            assertTrue(out.contains("false"), out);
        });
    }

    /** A monitor body that leaks a control-flow signal must not park the parent forever. */
    @Test void monitorLeakingControlFlowDoesNotHang() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            String out = engine.run("""
                    function slow() do
                        SLEEP(30)
                        return
                    end
                    multi r do
                        thread t: slow()
                    monitor 10
                        break
                    end
                    PRINT("done")
                    """);
            assertTrue(out.contains("done"), out);
        });
    }

    /** Writing through a property must not mutate the read-only host snapshot across runs. */
    @Test void propertyWriteDoesNotMutateHostSnapshot() {
        Map<String, Object> props = new HashMap<>();
        props.put("a", 1);

        // First run shadows _PROPS.a via copy-on-write into a global.
        assertEquals("99\n", engine.run("_PROPS.a = 99\nPRINT(_PROPS.a)\n", props));

        // A fresh run with the SAME host props still sees the original value.
        assertEquals("1\n", engine.run("PRINT(_PROPS.a)\n", props));
        // The caller's map itself is untouched.
        assertEquals(1, props.get("a"));
    }
}
