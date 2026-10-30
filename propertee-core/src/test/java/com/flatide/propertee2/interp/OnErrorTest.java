package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.module.ModuleSource;
import com.flatide.propertee2.value.HandlerOutcome;
import com.flatide.propertee2.value.TeeError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spec v0.37.0 — {@code on error}, as a host sees it, and the load-time checks. */
class OnErrorTest {

    private final List<String> lines = new ArrayList<>();   // both channels, in order

    private TeeError fail(Engine engine, String source) {
        return assertThrows(TeeError.class, () -> engine.execute(source, Map.of(), lines::add, lines::add));
    }

    @Test void theHandlersValueReachesTheHostAfterTheReportedError() {
        TeeError e = fail(new Engine(), """
                on error do
                    PRINT("handler:", _ERROR.message)
                    return {"failed": true}
                end
                FAIL("boom")
                """);
        assertEquals(List.of("Runtime error: Runtime Error at line 5:1: boom", "handler: boom"), lines);
        assertEquals(HandlerOutcome.Kind.RETURNED, e.handler().kind());
        assertEquals(Map.of("failed", true), e.handler().value());
        assertEquals("boom", e.getMessage());          // the run fails with the original error
    }

    @Test void aHandlerWithoutReturnGivesTheEmptyObject() {
        TeeError e = fail(new Engine(), "on error do\n    PRINT(\"h\")\nend\nx = 1 / 0\n");
        assertEquals(HandlerOutcome.Kind.RETURNED, e.handler().kind());
        assertEquals(Map.of(), e.handler().value());
    }

    @Test void anErrorInTheHandlerEndsItAndTheHostGetsTheEmptyObject() {
        TeeError e = fail(new Engine(), "on error do\n    x = nope\n    return 1\nend\nFAIL(\"first\")\n");
        assertEquals(List.of("Runtime error: Runtime Error at line 5:1: first",
                "[HANDLER ERROR] Runtime Error at line 2:9: Variable 'nope' is not defined"), lines);
        assertEquals(HandlerOutcome.Kind.FAILED, e.handler().kind());
        assertEquals("Variable 'nope' is not defined", e.handler().error().getMessage());
        assertEquals(Map.of(), e.handler().value());
        assertEquals("first", e.getMessage());
    }

    @Test void aHostReporterReplacesTheReportedLine() {
        List<String> reported = new ArrayList<>();
        TeeError e = fail(new Engine().setFailureReporter(err -> reported.add(err.positioned())),
                "on error do\n    PRINT(\"h\")\nend\nFAIL(\"boom\")\n");
        assertEquals(List.of("Runtime Error at line 4:1: boom"), reported);
        assertEquals(List.of("h"), lines);
        assertEquals(HandlerOutcome.Kind.RETURNED, e.handler().kind());
    }

    @Test void withoutAHandlerTheRuntimeReportsNothing() {
        TeeError e = fail(new Engine(), "PRINT(\"before\")\nFAIL(\"boom\")\n");
        assertNull(e.handler());                       // the host prints it, as before
        assertEquals(List.of("before"), lines);
    }

    @Test void theErrorHasItsPositionCountedFromOne() {
        TeeError e = fail(new Engine(), "x = 1\n  y = SUBSTRING(\"abc\", 0)\n");
        assertEquals(2, e.line());
        assertEquals(7, e.column());                   // the call, from 1
        assertEquals(6, e.col());                      // ANTLR's, from 0
    }

    @Test void aSessionSnippetRunsItsOwnHandler() {
        Engine.Session session = new Engine().session(Map.of(), lines::add, lines::add);
        TeeError e = assertThrows(TeeError.class, () -> session.eval("""
                on error do
                    PRINT("snippet handler:", _ERROR.line)
                end
                x = 1
                y = x.missing
                """));
        assertEquals(List.of("Runtime error: Runtime Error at line 5:5: Property 'missing' does not exist",
                "snippet handler: 5"), lines);
        assertEquals(HandlerOutcome.Kind.RETURNED, e.handler().kind());
        assertEquals(1, session.eval("x"));            // the session stays usable
    }

    /** What FILE_LINES reports when the script itself changed the file, a delete included. */
    private static final String REWRITTEN = "it was rewritten after FILE_LINES() was called — call FILE_LINES() again";

    @Test void aReturnValueThatCannotBeReadIsAnErrorInTheHandler() throws IOException {
        Path f = Files.createTempFile("propertee2-on-error-", ".txt");
        try {
            Files.writeString(f, "one\ntwo\n");
            TeeError e = assertThrows(TeeError.class, () -> new Engine().execute("""
                    on error do
                        x = FILE_LINES(f)
                        DELETE_FILE(f)
                        return x
                    end
                    FAIL("original")
                    """, Map.of("f", f.toString()), lines::add, lines::add));
            assertEquals(List.of("Runtime error: Runtime Error at line 6:1: original",
                    "[HANDLER ERROR] Runtime Error at line 4:5: FILE_LINES() cannot read '" + f
                            + "': " + REWRITTEN), lines);
            assertEquals("original", e.getMessage());   // the original error stays the run's
            assertEquals(HandlerOutcome.Kind.FAILED, e.handler().kind());
            assertEquals(Map.of(), e.handler().value());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    @Test void aSnippetsReturnValueThatCannotBeReadRunsItsHandler() throws IOException {
        Path f = Files.createTempFile("propertee2-on-error-", ".txt");
        try {
            Files.writeString(f, "one\n");
            Engine.Session session = new Engine().session(Map.of("f", f.toString()), lines::add, lines::add);
            TeeError e = assertThrows(TeeError.class, () -> session.eval(
                    "on error do\n    PRINT(\"handler\")\nend\nx = FILE_LINES(f)\nDELETE_FILE(f)\nreturn x\n"));
            assertEquals(List.of("Runtime error: Runtime Error at line 6:1: FILE_LINES() cannot read '" + f
                    + "': " + REWRITTEN, "handler"), lines);
            assertEquals(HandlerOutcome.Kind.RETURNED, e.handler().kind());
        } finally {
            Files.deleteIfExists(f);
        }
    }

    // ---- otherwise blocks -------------------------------------------------

    @Test void aBlockSetsItsVariableItselfNotInAFunctionItCalls() {
        String module = "function g() do\n    x = 1\n    return x\nend\n";
        String out = new Engine().setModuleResolver(modules(Map.of("util", module))).run("""
                import util as u
                function f() do
                    x = 99
                    x = ERR("no x") otherwise do
                        u::g()
                    end
                    return x
                end
                PRINT(f())
                """);
        assertEquals("Runtime error: Runtime Error at line 6:5: The 'otherwise' block must set 'x'"
                + " or leave with continue, break, return or FAIL\n", out);
        // a setup's own `x` is not the function's either
        assertEquals("Runtime error: Runtime Error at line 6:5: The 'otherwise' block must set 'x'"
                + " or leave with continue, break, return or FAIL\n", new Engine().run("""
                function f() do
                    x = ERR("no x") otherwise do
                        multi do
                            x = 1
                        end
                    end
                    return x
                end
                PRINT(f())
                """));
    }

    // ---- imports ----------------------------------------------------------

    private static ModuleResolver modules(Map<String, String> sources) {
        return request -> {
            String source = sources.get(request.displayName());
            return source == null ? null
                    : new ModuleSource(request.moduleId(), request.version(), "module:" + request.displayName(), source);
        };
    }

    @Test void errorSourceNamesTheModuleAndAModulesOwnHandlerIsSkipped() {
        String out = new Engine().setModuleResolver(modules(Map.of("util", """
                on error do
                    PRINT("the module's handler does not run in import mode")
                end
                function boom(x) do
                    return x.missing
                end
                """))).run("""
                import util as u
                on error do
                    PRINT("source:", _ERROR.source, "line", _ERROR.line, "column", _ERROR.column)
                end
                u::boom({})
                """);
        assertEquals("Runtime error: Runtime Error at module:util line 5:12: Property 'missing' does not exist\n"
                + "source: module:util line 5 column 12\n", out);
    }

    @Test void aModuleBreakingALoadRuleIsRefusedWithItsSource() {
        String out = new Engine().setModuleResolver(modules(Map.of("bad", """
                function f() do
                    continue
                end
                """))).run("import bad as b\nPRINT(\"not printed\")\n");
        assertEquals("Runtime error: Runtime Error at module:bad line 2:5: 'continue' must be inside a loop\n", out);
    }

    // ---- load-time checks -------------------------------------------------

    private static String refusal(String source) {
        TeeError e = LoadChecks.firstViolation(Parsing.parse(source));
        return e == null ? null : e.positioned();
    }

    @Test void aMonitorBodyIsAFunctionBodyForBreakAndContinue() {
        assertEquals("Runtime Error at line 5:5: 'continue' must be inside a loop", refusal("""
                multi r do
                    thread a: SLEEP(1)
                monitor 10
                    PRINT("tick")
                    continue
                end
                """));
        assertNull(refusal("multi r do\n    thread a: SLEEP(1)\nmonitor 10\n    loop i in [1] do\n        continue\n    end\nend\n"));
    }

    @Test void aSetupIsNoBoundaryButAFunctionInALoopIs() {
        assertNull(refusal("loop i in [1, 2] do\n    multi do\n        break\n    end\nend\n"));
        assertEquals("Runtime Error at line 3:9: 'break' must be inside a loop", refusal("""
                loop i in [1, 2] do
                    function f() do
                        break
                    end
                end
                """));
        assertEquals("Runtime Error at line 1:22: 'break' must be inside a loop", refusal("x = ERR(1) otherwise break\n"));
    }

    @Test void theHandlerComesRightAfterTheImports() {
        assertNull(refusal("import a as b\non error do\n    PRINT(1)\nend\nPRINT(2)\n"));
        assertEquals("Runtime Error at line 3:5: 'on error' must come right after the imports",
                refusal("function f() do\n    PRINT(1)\n    on error do\n    end\nend\n"));
    }

    @Test void aReturnValueStartsOnTheReturnLine() {
        assertNull(refusal("function f() do\n    return\nend\n"));          // nothing follows but `end`
        assertNull(refusal("function f(r) do\n    v = r otherwise return {}\n    PRINT(v)\nend\n"));
        assertEquals("Runtime Error at line 2:5: The value of 'return' must start on the same line",
                refusal("function f() do\n    return\n        5\nend\n"));
    }

    @Test void theFirstViolationInDocumentOrderWinsAcrossChecks() {
        String source = "if true then\n    PRINT(1)\nend\ncontinue\n";
        TeeError hiddenFirst = LoadChecks.firstViolation(Parsing.parse(source), Set.of("if"), Set.of());
        assertEquals("Runtime Error at line 1:1: 'if' is not available in this environment", hiddenFirst.positioned());
        TeeError ruleFirst = LoadChecks.firstViolation(Parsing.parse("break\nSHELL(\"x\")\n"), Set.of(), Set.of("SHELL"));
        assertEquals("Runtime Error at line 1:1: 'break' must be inside a loop", ruleFirst.positioned());
    }
}
