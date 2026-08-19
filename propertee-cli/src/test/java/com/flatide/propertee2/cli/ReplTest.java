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
}
