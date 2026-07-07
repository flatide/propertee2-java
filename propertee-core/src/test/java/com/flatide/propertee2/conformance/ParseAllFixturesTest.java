package com.flatide.propertee2.conformance;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.Parsing;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * PA parser smoke test: every fixture in the corpus must lex+parse with the vanilla
 * grammar (runtime-error and host-feature fixtures are still syntactically valid).
 * This validates grammar/ProperTee.g4 + the ANTLR codegen against all 113 inputs
 * before the interpreter exists. Semantic conformance (diff vs .expected) lands in PE.
 */
class ParseAllFixturesTest {

    static java.util.List<String> fixtures() { return Fixtures.ALL; }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void parsesCleanly(String name) {
        String source = Fixtures.source(name);
        assertDoesNotThrow(() -> Parsing.parse(source), () -> "failed to parse " + name + ".tee");
    }

    @Test
    void corpusIsComplete() {
        assertEquals(113, Fixtures.ALL.size(), "expected 113 fixtures");
        // every listed fixture has both a .tee and a .expected on the classpath
        for (String name : Fixtures.ALL) {
            assertDoesNotThrow(() -> Fixtures.source(name), () -> name + ".tee missing");
            assertDoesNotThrow(() -> Fixtures.expected(name), () -> name + ".expected missing");
        }
    }
}
