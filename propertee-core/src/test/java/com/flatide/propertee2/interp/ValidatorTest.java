package com.flatide.propertee2.interp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Static validation pass (ProperTee issue #9): the runtime enforces host restrictions only when a
 * construct is reached; {@link Engine#validate} must report forbidden constructs in untaken
 * branches too, with the runtime's message text and the construct's position.
 */
class ValidatorTest {

    /** The issue's motivating case: forbidden constructs sit in a branch the run never takes. */
    private static final String DEAD_BRANCH = """
            mode = "safe"
            if mode == "safe" then
                PRINT("ok")
            else
                multi r do
                    thread : SHELL("rm -rf /")
                end
            end
            """;

    @Test
    void reportsForbiddenConstructsInUntakenBranches() {
        Engine engine = new Engine()
                .setHiddenKeywords(Set.of("multi"))
                .setIgnoredFunctions(Set.of("SHELL"));
        List<String> violations = engine.validate(DEAD_BRANCH);
        assertEquals(List.of(
                "line 5:4: 'multi' is not available in this environment",
                "line 6:17: 'SHELL' is not available in this environment"), violations);
        // Backstop unchanged: the same script still runs (the safe branch is taken at runtime).
        assertEquals("ok\n", engine.run(DEAD_BRANCH));
    }

    @Test
    void cleanScriptAndNoRestrictionsReturnEmpty() {
        String script = "PRINT(LEN([1, 2, 3]))\n";
        assertTrue(new Engine().validate(script).isEmpty());                       // no restrictions
        assertTrue(new Engine().setHiddenKeywords(Set.of("multi"))
                .setIgnoredFunctions(Set.of("SHELL")).validate(script).isEmpty()); // restrictions, clean script
    }

    @Test
    void coversAllSixHideableKeywords() {
        String script = """
                function w() do
                    return 1
                end
                if true then PRINT(1) end
                loop i in [1] do PRINT(i) end
                debug
                multi r do
                    thread : w()
                end
                """;
        Engine engine = new Engine()
                .setHiddenKeywords(Set.of("if", "loop", "function", "multi", "thread", "debug"));
        List<String> violations = engine.validate(script);
        assertEquals(List.of(
                "line 1:0: 'function' is not available in this environment",
                "line 4:0: 'if' is not available in this environment",
                "line 5:0: 'loop' is not available in this environment",
                "line 6:0: 'debug' is not available in this environment",
                "line 7:0: 'multi' is not available in this environment",
                "line 8:4: 'thread' is not available in this environment"), violations);
    }

    @Test
    void hidingIfReportsAnElseifChainOnce() {
        String script = """
                x = 2
                if x == 1 then PRINT(1)
                elseif x == 2 then PRINT(2)
                else PRINT(3)
                end
                """;
        List<String> violations = new Engine().setHiddenKeywords(Set.of("if")).validate(script);
        assertEquals(List.of("line 2:0: 'if' is not available in this environment"), violations);
    }

    @Test
    void reportsEveryCallSiteOfAnIgnoredFunction() {
        String script = """
                SHELL("a")
                x = 1
                SHELL("b")
                """;
        List<String> violations = new Engine().setIgnoredFunctions(Set.of("SHELL")).validate(script);
        assertEquals(List.of(
                "line 1:0: 'SHELL' is not available in this environment",
                "line 3:0: 'SHELL' is not available in this environment"), violations);
    }
}
