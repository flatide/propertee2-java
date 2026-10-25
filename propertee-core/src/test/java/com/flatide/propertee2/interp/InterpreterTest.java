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

    /** Spec v0.13.0, v0.33.0: every overflowing integer operator fails loudly (fixtures pin + and literals). */
    @Test void anIndexIsAnIntegerWhenReadingAndWriting() {   // spec v0.34.0
        assertEquals("Runtime error: Runtime Error at line 2:6: Array index must be an integer\n",
                engine.run("a = [10, 20, 30]\nPRINT(a.$(1.5))\n"));
        assertEquals("Runtime error: Runtime Error at line 3:0: Array index must be an integer\n",
                engine.run("a = [10, 20, 30]\ni = 1.5\na.$i = 99\nPRINT(a)\n"));
        assertEquals("Runtime error: Runtime Error at line 2:6: String index must be an integer\n",
                engine.run("s = \"hello\"\nPRINT(s.$(0.5))\n"));
        assertEquals("Runtime error: Runtime Error at line 2:6: String index out of bounds\n",
                engine.run("s = \"hello\"\nPRINT(s.6)\n"));
        assertEquals("Runtime error: Runtime Error at line 2:6: String index must be a number\n",
                engine.run("s = \"hello\"\nPRINT(s.x)\n"));
        assertEquals("[ 10, 99, 30 ] 30 e\n",
                engine.run("a = [10, 20, 30]\ns = \"hello\"\ni = 2.0\na.$i = 99\nPRINT(a, a.$(i + 1), s.$i)\n"));
    }

    @Test void integerOverflowCoversAllOperators() {
        String overflow = "Integer overflow: the result is outside ±9,007,199,254,740,991\n";
        assertEquals("Runtime error: Runtime Error at line 1:6: " + overflow,
                engine.run("PRINT(-9007199254740991 - 2)\n"));
        assertEquals("Runtime error: Runtime Error at line 1:6: " + overflow,
                engine.run("PRINT(4503599627370496 + 4503599627370496)\n"));   // 2^53
        assertEquals("Runtime error: Runtime Error at line 1:6: " + overflow,
                engine.run("PRINT(94906266 * 94906266)\n"));
        assertEquals("Runtime error: Runtime Error at line 1:6: " + overflow,
                engine.run("PRINT(9007199254740991 * 9007199254740991)\n"));   // past a long too
        assertEquals("Runtime error: Runtime Error: " + overflow,
                engine.run("PRINT(CEIL(9007199254740992.5))\n"));
        assertEquals("Runtime error: Runtime Error: " + overflow,
                engine.run("PRINT(ROUND(-10000000000000000.4))\n"));
        // The range is symmetric: negation and ABS cannot overflow, and the ends are usable.
        assertEquals("9007199254740991 9007199254740991 -9007199136250225\n",
                engine.run("PRINT(-(-9007199254740991), ABS(-9007199254740991), -94906265 * 94906265)\n"));
    }

    /** Spec v0.13.0, v0.33.0: data conversion still promotes integrals beyond the range — JSON round-trips intact. */
    @Test void dataConversionStillPromotesBeyondTheIntegerRange() {
        // 9999999999 is an integer now, so arithmetic on it is checked; 2^53 + 1 is the nearest decimal
        assertEquals("9999999999\n9007199254740992 9007199254740992\n", engine.run("""
                v = UNWRAP(JSON_PARSE("{\\"n\\":9999999999,\\"m\\":9007199254740993}"))
                PRINT(v.n)
                PRINT(v.m, v.m + 1)
                """));
        assertTrue(engine.run("v = UNWRAP(JSON_PARSE(\"9999999999\"))\nPRINT(v * 1000000)\n")
                .contains("Integer overflow"));
    }

    /** Spec v0.33.0: a host's Long is an integer inside the range and the nearest decimal beyond it. */
    @Test void hostLongsAreIntegersInsideTheRange() {
        Engine e = new Engine()
                .registerExternal("BYTES", args -> 5_498_058_138_880L)
                .registerExternal("SMALL", args -> 7L)
                .registerExternal("HUGE", args -> Long.MAX_VALUE);
        assertEquals("5498058138880 true\n7\n9223372036854776000 9223372036854776000\n", e.run("""
                b = BYTES().value
                PRINT(b, b * 1000 > b)
                PRINT(SMALL().value)
                h = HUGE().value
                PRINT(h, h + 1)
                """));
        Map<String, Object> props = new HashMap<>();
        props.put("total", 9_007_199_254_740_991L);
        assertTrue(engine.run("PRINT(total + 1)\n", props).contains("Integer overflow"));
        // a result keeps the canonical box: Integer when it fits 32 bits, Long beyond
        Engine.Outcome o = engine.execute("return [1, 1024 * 1024 * 1024 * 1024]\n", Map.of(), s -> {}, s -> {});
        java.util.List<?> list = (java.util.List<?>) o.result();
        assertEquals(Integer.class, list.get(0).getClass());
        assertEquals(Long.class, list.get(1).getClass());
    }

    /** Spec v0.13.0: interpreter-dispatched names are a host-API error at registration time. */
    @Test void interpreterDispatchedNamesCannotBeRegistered() {
        for (String name : new String[] { "PRINT", "PRINTF", "SLEEP", "FAIL", "UNWRAP" }) {
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
