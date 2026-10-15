package com.flatide.propertee2.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The REPL loop: continuation, echo, error recovery. Non-interactive (no prompts), as when piped. */
class ReplTest {

    private record Run(String out, String err) {}

    private Run repl(String input) throws IOException {
        return repl(input, Map.of());
    }

    private Run repl(String input, Map<String, Object> props) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Repl.run(props, new BufferedReader(new StringReader(input)),
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8),
                false, "test");
        return new Run(outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test void statePersistsAndExpressionsEcho() throws IOException {
        Run r = repl("x = 10\nx + 5\nPRINT(x)\n");
        assertEquals("15\n10\n", r.out());        // echo of x+5, then PRINT output; no echo for assignment/PRINT
        assertEquals("", r.err());
    }

    @Test void unclosedBlockContinuesUntilEnd() throws IOException {
        Run r = repl("function f(n) do\n    return n * 2\nend\nf(21)\n");
        assertEquals("42\n", r.out());
        assertEquals("", r.err());
    }

    @Test void blankLineAbandonsAnUnfinishedInput() throws IOException {
        Run r = repl("if true then\n\n1 + 1\n");   // blank line escapes the continuation
        assertEquals("2\n", r.out());              // the session goes on
        assertEquals(true, r.err().contains("syntax error"));
    }

    @Test void runtimeErrorPrintsAndSessionContinues() throws IOException {
        Run r = repl("nope + 1\n\"still\" + \" alive\"\n");
        assertEquals(true, r.out().startsWith("Runtime error: Runtime Error at line 1:"));
        assertEquals(true, r.out().endsWith("still alive\n"));
        assertEquals("", r.err());
    }

    @Test void definiteSyntaxErrorReportsWithoutContinuation() throws IOException {
        Run r = repl("x = )\n7\n");
        assertEquals("7\n", r.out());
        assertEquals(true, r.err().contains("syntax error"));
    }

    @Test void exitCommandStopsTheLoop() throws IOException {
        Run r = repl("1 + 1\n:exit\n99\n");
        assertEquals("2\n", r.out());              // nothing after :exit runs
    }

    @Test void propsAreInjected() throws IOException {
        Run r = repl("width * 2\n", Map.of("width", 100));
        assertEquals("200\n", r.out());
    }

    @Test void shellWorksOutOfTheBox() throws IOException {
        // 0.19.0: the CLI wires an in-process SimpleTaskRunner, so SHELL succeeds without a host
        Run r = repl("r = SHELL(\"echo from-repl\")\nr.value\n");
        assertEquals("from-repl\n", r.out());
        assertEquals("", r.err());
    }

    // ---- the debugger (0.22.0): debug breaks in the REPL / :run / -i --------------------------

    private Run replPreload(String input, String preload) throws IOException {
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Repl.run(Map.of(), new BufferedReader(new StringReader(input)),
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8),
                false, "test", preload);
        return new Run(outBuf.toString(StandardCharsets.UTF_8), errBuf.toString(StandardCharsets.UTF_8));
    }

    /** The confirmation {@code :load} prints (0.34.0): it loads without running. */
    private static String loaded(String path) {
        return "loaded " + path + " — :run runs it, :debug pauses at its first statement\n";
    }

    private String tempScript(String source) throws IOException {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("repl-debug", ".tee");
        f.toFile().deleteOnExit();
        java.nio.file.Files.writeString(f, source, StandardCharsets.UTF_8);
        return f.toString();
    }

    @Test void debugPromptEvaluatesInThePausedFrameAndContinues() throws IOException {
        // Typed function, then a call that breaks inside it: the prompt reads/writes the local
        // frame (with multi-line continuation), and :c resumes with the mutation applied.
        Run r = repl("""
                function f(a) do
                    b = a + 1
                    debug
                    return b
                end
                f(1)
                b
                if b == 2 then
                b = 99
                end
                :c
                """);
        assertEquals("debug at line 3:4\n2\n99\n", r.out());
        assertEquals("", r.err());
    }

    @Test void debugQuitAbortsTheRunButKeepsTheSession() throws IOException {
        String script = tempScript("g = 2\ndebug\ng = 3\n");
        Run r = repl("g = 1\n:run " + script + "\n:q\ng\n");
        assertEquals("debug at line 2:0\ndebug: run aborted; session state kept\n2\n", r.out());
        assertEquals("", r.err());
    }

    @Test void runRunsAFileIntoTheSession() throws IOException {
        String script = tempScript("x = 7\nfunction dbl(n) do\n    return n * 2\nend\n");
        Run r = repl(":run " + script + "\ndbl(x)\n");
        assertEquals("14\n", r.out());
        assertEquals("", r.err());
    }

    @Test void varsDumpsThePausedFunctionFrame() throws IOException {
        String script = tempScript("function f(a) do\n    b = a * 2\n    debug\n    return b\nend\ntop = f(3)\n");
        Run r = repl(":run " + script + "\n:vars\n:c\ntop\n");
        assertEquals("debug at line 3:4\na = 3\nb = 6\n6\n", r.out());
        assertEquals("", r.err());
    }

    @Test void preloadRunsTheScriptThenDropsIntoTheReplWithItsState() throws IOException {
        // `propertee2 -i script.tee`: the debugger is live during the preload, and the REPL
        // afterwards shares the script's globals.
        String script = tempScript("seed = 5\ndebug\nseed = seed + 1\n");
        Run r = replPreload("seed\n:c\nseed\n", script);
        assertEquals("debug at line 2:0\n5\n6\n", r.out());
        assertEquals("", r.err());
    }

    @Test void argsFlowIntoThePreloadAndTheSession() throws IOException {
        // `propertee2 -i script.tee alpha 42` — _ARGS visible in the preload and afterwards
        String script = tempScript("PRINT(\"from script:\", _ARGS.1)\n");
        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Repl.run(Map.of(), java.util.List.of("alpha", "42"),
                new BufferedReader(new StringReader("_ARGS.2\n")),
                new PrintStream(outBuf, true, StandardCharsets.UTF_8),
                new PrintStream(errBuf, true, StandardCharsets.UTF_8),
                false, "test", script);
        assertEquals("from script: alpha\n42\n", outBuf.toString(StandardCharsets.UTF_8));
        assertEquals("", errBuf.toString(StandardCharsets.UTF_8));
    }

    @Test void eofAtTheDebugPromptResumesTheRun() throws IOException {
        String script = tempScript("debug\nPRINT(\"after\")\n");
        Run r = replPreload(":run " + script + "\n", null);
        assertEquals("debug at line 1:0\nafter\n", r.out());
        assertEquals("", r.err());
    }

    // ---- 0.23.0: stepping, :where, line breakpoints -------------------------------------------

    private static final String STEP_SCRIPT = """
            function inner(n) do
                x = n * 2
                return x
            end
            a = 1
            debug
            b = inner(a)
            PRINT(b)
            """;

    @Test void stepNextThenIntoWalksTheScriptWithWhere() throws IOException {
        String script = tempScript(STEP_SCRIPT);
        Run r = repl(":run " + script + "\n:n\n:s\n:vars\n:w\n:c\n");
        assertEquals("""
                debug at line 6:0
                at line 7:0: b = inner(a)
                at line 2:4: x = n * 2
                n = 1
                top level → inner (called at 7:4)
                at line 2:4: x = n * 2
                2
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void stepOutReturnsToTheCaller() throws IOException {
        String script = tempScript(STEP_SCRIPT);
        Run r = repl(":run " + script + "\n:s\n:s\n:r\n:c\n");
        // debug@6 -> :s stops at 7, :s enters inner (line 2), :r runs to the caller's next
        // statement — echoing what inner returned on the way (0.29.0)
        assertEquals("""
                debug at line 6:0
                at line 7:0: b = inner(a)
                at line 2:4: x = n * 2
                inner returned 2
                at line 8:0: PRINT(b)
                2
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void stepOverAcrossACallEchoesItsReturn() throws IOException {
        String script = tempScript(STEP_SCRIPT);
        Run r = repl(":run " + script + "\n:n\n:n\n:c\n");
        // The second :n runs b = inner(a) to completion; its crossed return is echoed (0.29.0).
        assertEquals("""
                debug at line 6:0
                at line 7:0: b = inner(a)
                inner returned 2
                at line 8:0: PRINT(b)
                2
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void breakpointSetAfterLoadFiresOnRunAndDeletes() throws IOException {
        String script = tempScript("x = 1\nx = x + 1\nPRINT(x)\n");
        // load (nothing runs), set a breakpoint by line, run into it, then delete it and re-run
        Run r = repl(":load " + script + "\n:b 2\n:run\nx\n:c\n:bd 2\n:run\n");
        assertEquals(loaded(script) + """
                breakpoint added at line 2
                breakpoint at line 2:0: x = x + 1
                1
                2
                breakpoint deleted at line 2
                2
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void breakpointListAndClearAll() throws IOException {
        Run r = repl(":b\n:b 5\n:b 3\n:b\n:bd\n:b\n");
        assertEquals("""
                (no breakpoints)
                breakpoint added at line 5
                breakpoint added at line 3
                breakpoints: 3, 5
                all breakpoints deleted
                (no breakpoints)
                """, r.out());
        assertEquals("", r.err());
    }

    // ---- 0.31.0: :run / :debug re-execution -------------------------------------------------

    @Test void runReexecutesTheLastLoadedScript() throws IOException {
        String script = tempScript("::hits = ::hits + 1\nPRINT(::hits)\n");
        // seed the global, load once, then :run twice without repeating the path
        Run r = repl("::hits = 0\n:load " + script + "\n:run\n:run\n");
        assertEquals(loaded(script) + "1\n2\n", r.out());       // :load itself runs nothing
        assertEquals("", r.err());
    }

    @Test void runTakesAnExplicitPathAndRemembersIt() throws IOException {
        String a = tempScript("PRINT(\"a\")\n");
        String b = tempScript("PRINT(\"b\")\n");
        Run r = repl(":load " + a + "\n:run " + b + "\n:run\n");   // :run b, then :run defaults to b
        assertEquals(loaded(a) + "b\nb\n", r.out());
        assertEquals("", r.err());
    }

    @Test void runWithNoLoadedScriptReports() throws IOException {
        Run r = repl(":run\n");
        assertEquals("", r.out());
        assertEquals(true, r.err().contains("no script loaded yet"));
    }

    @Test void debugPausesTheLoadedScriptAtItsFirstStatement() throws IOException {
        String script = tempScript("x = 1\nx = x + 1\nPRINT(x)\n");
        // :debug stops before line 1; :vars shows nothing yet, :c runs to completion
        Run r = repl(":load " + script + "\n:debug\n:vars\n:c\n");
        assertEquals(loaded(script) + """
                at line 1:0: x = 1
                (no locals — top level; use :globals)
                2
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void debugReRunSteppingWalksFromEntry() throws IOException {
        String script = tempScript("a = 2\nb = a + 3\nPRINT(b)\n");
        // pause at line 1, step to 2, step to 3, then continue
        Run r = repl(":debug " + script + "\n:n\n:n\n:c\n");
        assertEquals("""
                at line 1:0: a = 2
                at line 2:0: b = a + 3
                at line 3:0: PRINT(b)
                5
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void listShowsTheLoadedScriptWithLineNumbers() throws IOException {
        String script = tempScript("x = 1\nfunction f(n) do\n    return n\nend\nPRINT(f(x))\n");
        Run r = repl(":load " + script + "\n:list\n");
        assertEquals(loaded(script) + """
                 1  x = 1
                 2  function f(n) do
                 3      return n
                 4  end
                 5  PRINT(f(x))
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void listMarksBreakpointLinesAndTakesAnExplicitPath() throws IOException {
        String script = tempScript("a = 1\na = a + 1\nPRINT(a)\n");
        Run r = repl(":b 2\n:list " + script + "\n");
        assertEquals("""
                breakpoint added at line 2
                 1  a = 1
                 2* a = a + 1
                 3  PRINT(a)
                """, r.out());
        assertEquals("", r.err());
    }

    @Test void listWithNoLoadedScriptReports() throws IOException {
        Run r = repl(":list\n");
        assertEquals("", r.out());
        assertEquals(true, r.err().contains("no script loaded yet"));
    }

    @Test void listDoesNotChangeTheReRunTarget() throws IOException {
        String a = tempScript("PRINT(\"a\")\n");
        String b = tempScript("PRINT(\"b\")\n");
        // load a, peek at b with :list, then :run must still default to a
        Run r = repl(":load " + a + "\n:list " + b + "\n:run\n");
        assertEquals(loaded(a) + """
                 1  PRINT("b")
                a
                """, r.out());
        assertEquals("", r.err());
    }

    // ---- 0.34.0: :load only loads -------------------------------------------------------------

    @Test void loadOnlyLoadsAndRunRunsTheLoadedScript() throws IOException {
        String script = tempScript("PRINT(\"ran\")\nx = 7\n");
        // :load prints just its confirmation — no script output, no globals — until :run
        Run r = repl(":load " + script + "\nx\n:run\nx\n");
        assertEquals(loaded(script)
                + "Runtime error: Runtime Error at line 1:0: Variable 'x' is not defined\n"
                + "ran\n7\n", r.out());
        assertEquals("", r.err());
    }

    @Test void loadReportsASyntaxErrorButKeepsTheTarget() throws IOException {
        String script = tempScript("PRINT(\"ran\")\nx = )\n");
        // the check fails without running anything, and :list still defaults to the file
        Run r = repl(":load " + script + "\n:list\n");
        assertEquals(" 1  PRINT(\"ran\")\n 2  x = )\n", r.out());
        assertEquals(true, r.err().startsWith("propertee2: " + script + ": syntax error at 2:4"));
    }

    @Test void loadWithoutAPathReportsUsage() throws IOException {
        Run r = repl(":load\n");
        assertEquals("", r.out());
        assertEquals("propertee2: usage: :load <file>\n", r.err());
    }
}
