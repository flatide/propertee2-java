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

    /** Only the documented keywords are hideable (if/loop/function/multi/thread/debug) — not break/continue/return. */
    @Test void hidingANonHideableKeywordIsANoOp() {
        // "break" is not a hideable keyword, so hiding it leaves break working
        String ok = new Engine().setHiddenKeywords(java.util.Set.of("break")).run("""
                loop true do
                    break
                end
                PRINT("ok")
                """);
        assertEquals("ok\n", ok);

        // a documented keyword ("if") is hidden -> runtime error at its token
        String hidden = new Engine().setHiddenKeywords(java.util.Set.of("if")).run("if true then PRINT(1) end\n");
        assertEquals("Runtime error: Runtime Error at line 1:0: 'if' is not available in this environment\n", hidden);
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

    /** A host external function receives a deep copy of its args — mutating them cannot change script state. */
    @Test void externalCannotMutateScriptArgs() {
        Engine e = new Engine().registerExternal("APPEND_HOST", args -> {
            @SuppressWarnings("unchecked")
            java.util.List<Object> list = (java.util.List<Object>) args.get(0);
            list.add(999);                 // host tries to mutate the script's array
            return list.size();            // returns 4 (its own copy grew)
        });
        String out = e.run("""
                arr = [1, 2, 3]
                n = APPEND_HOST(arr)
                PRINT(arr)
                PRINT(n.value)
                """);
        assertEquals("[ 1, 2, 3 ]\n4\n", out);   // arr unchanged; host saw an isolated copy
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

    /** Spec v0.13.0: every overflowing integer operator fails loudly (fixtures pin + and literals). */
    @Test void integerOverflowCoversAllOperators() {
        assertEquals("Runtime error: Runtime Error at line 1:6: Integer overflow\n",
                engine.run("PRINT(-2147483647 - 2)\n"));
        assertEquals("Runtime error: Runtime Error at line 1:6: Integer overflow\n",
                engine.run("PRINT(46341 * 46341)\n"));
        assertEquals("Runtime error: Runtime Error at line 1:6: Integer overflow\n",
                engine.run("PRINT(-(-2147483647 - 1))\n"));                       // negate MIN
        assertEquals("Runtime error: Runtime Error: Integer overflow\n",
                engine.run("PRINT(ABS(-2147483647 - 1))\n"));                     // |MIN| doesn't fit
        assertEquals("Runtime error: Runtime Error: Integer overflow\n",
                engine.run("PRINT(CEIL(2147483648.5))\n"));
        assertEquals("Runtime error: Runtime Error: Integer overflow\n",
                engine.run("PRINT(ROUND(-3000000000.4))\n"));
        // MIN itself is a legal value: reachable, printable, usable.
        assertEquals("-2147483648\n-2147483647\n",
                engine.run("min = -2147483647 - 1\nPRINT(min)\nPRINT(min + 1)\n"));
    }

    /** Spec v0.13.0: data conversion still promotes out-of-range integrals — JSON round-trips intact. */
    @Test void dataConversionStillPromotesBeyondTheIntegerRange() {
        assertEquals("9999999999\ntrue\n", engine.run(
                "v = UNWRAP(JSON_PARSE(\"{\\\"n\\\":9999999999}\")).n\nPRINT(v)\nPRINT(v > 2147483647)\n"));
    }

    /** Spec v0.13.0: interpreter-dispatched names are a host-API error at registration time. */
    @Test void interpreterDispatchedNamesCannotBeRegistered() {
        for (String name : new String[] { "PRINT", "SLEEP", "FAIL", "UNWRAP" }) {
            IllegalArgumentException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new Engine().registerExternal(name, args -> 1));
            assertTrue(e.getMessage().contains("'" + name + "'"), e.getMessage());
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Engine().registerPure("SLEEP", args -> 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Engine().registerExternalAsync("FAIL", args -> 1));
        // A non-reserved ALL-CAPS name still registers fine (that namespace belongs to the host);
        // externals stay Result-wrapped as ever.
        assertEquals("42\n", new Engine().registerPure("ANSWER", args -> 42).run("PRINT(ANSWER().value)\n"));
    }
}
