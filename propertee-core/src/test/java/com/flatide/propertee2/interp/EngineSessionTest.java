package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.value.JsonNull;
import com.flatide.propertee2.value.TeeError;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link Engine.Session} — the persistent-state snippet API behind the CLI REPL (0.18.0). */
class EngineSessionTest {

    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();

    private Engine.Session session() {
        return new Engine().session(Map.of(), out::add, err::add);
    }

    @Test void stateAccumulatesAcrossSnippets() {
        Engine.Session s = session();
        assertNull(s.eval("x = 10"));                              // assignment: nothing to echo
        assertEquals(15, s.eval("x + 5"));                         // trailing bare expression echoes
        assertNull(s.eval("function double(n) do\n    return n * 2\nend"));
        assertEquals(20, s.eval("double(x)"));                     // function persists too
        assertEquals(10, s.globals().get("x"));
    }

    @Test void echoRules() {
        Engine.Session s = session();
        assertEquals("ab", s.eval("\"a\" + \"b\""));
        assertEquals(JsonNull.NULL, s.eval("null"));               // the null VALUE echoes (not Java null)
        // a {}-valued trailing expression (PRINT/SLEEP return absence) comes back as the empty
        // object — the HOST decides to suppress it (the CLI does); Java null means "no expression"
        assertEquals(Map.of(), s.eval("PRINT(\"hi\")"));
        assertEquals(Map.of(), s.eval("SLEEP(0)"));
        assertEquals(List.of("hi"), out);                          // PRINT went to the sink exactly once
        assertEquals(7, s.eval("if true then\n    y = 7\nend\ny")); // multi-statement snippet: last expr echoes
    }

    @Test void topLevelReturnEchoes() {
        Engine.Session s = session();
        assertEquals(42, s.eval("return 42"));
    }

    @Test void runtimeErrorLeavesTheSessionUsable() {
        Engine.Session s = session();
        s.eval("a = 1");
        TeeError e = assertThrows(TeeError.class, () -> s.eval("b = 2\nc = nope + 1"));
        assertTrue(e.getMessage().contains("nope"));
        assertEquals(2, s.eval("b"));                              // the statement before the error took effect
        assertEquals(1, s.eval("a"));                              // and prior state survives
    }

    @Test void snippetsRunWithFullScriptSemantics() {
        Engine.Session s = session();
        // SLEEP + multi/thread inside one snippet behave exactly as a script (root fiber per snippet)
        s.eval("""
                function work(n) do
                    SLEEP(10)
                    return n * n
                end""");
        Object echoed = s.eval("""
                multi r do
                    thread a: work(3)
                    thread b: work(4)
                end
                r.a.value + r.b.value""");
        assertEquals(25, echoed);
    }

    @Test void loadTimeRejectionAppliesPerSnippet() {
        Engine engine = new Engine().setIgnoredFunctions(Set.of("ENV"));
        Engine.Session s = engine.session(Map.of(), out::add, err::add);
        assertEquals(3, s.eval("1 + 2"));
        TeeError blocked = assertThrows(TeeError.class, () -> s.eval("if false then\n    ENV(\"HOME\")\nend"));
        assertTrue(blocked.getMessage().contains("not available"));
        assertEquals(4, s.eval("2 + 2"));                          // rejected snippet didn't poison the session
    }

    @Test void syntaxExceptionReportsEofForIncompleteInput() {
        assertTrue(assertThrows(Parsing.SyntaxException.class,
                () -> Parsing.parse("if x then\n")).atEof());       // unclosed block: continuation signal
        assertTrue(assertThrows(Parsing.SyntaxException.class,
                () -> Parsing.parse("x = \n")).atEof());            // dangling operator
        Parsing.SyntaxException hard = assertThrows(Parsing.SyntaxException.class,
                () -> Parsing.parse("x = )\n"));                    // definite error mid-input
        assertEquals(false, hard.atEof());
    }
}
