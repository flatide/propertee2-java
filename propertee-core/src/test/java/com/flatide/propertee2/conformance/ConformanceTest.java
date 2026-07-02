package com.flatide.propertee2.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.interp.Engine;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Semantic conformance: run each fixture through the {@link Engine} and diff stdout against
 * {@code .expected} (design §10.1 PE). All 90 fixtures pass — including multi/thread/monitor and the
 * host tail, with the documented host injections (-p properties, ENV/file via the default platform,
 * external functions, hidden keywords / ignored functions) configured per fixture below.
 */
class ConformanceTest {

    /** All 90 fixtures now pass; nothing is deferred. */
    static final Set<String> PENDING = Set.of();

    /** Host-injected built-in properties (the {@code -p} flag) for fixtures that need them. */
    static final Map<String, Map<String, Object>> PROPS = Map.of(
            "34_builtin_properties", Map.of("width", 100, "height", 200, "name", "test"),
            "86_props_object", Map.of("a", 40, "b", 2));

    static List<String> allFixtures() {
        return Fixtures.ALL.stream().filter(n -> !PENDING.contains(n)).toList();
    }

    /**
     * The only fixture saved without a final newline (all 83 others end in one); a file artifact,
     * not a behavior difference, and the .expected files are not to be edited. Every OTHER fixture
     * is compared byte-for-byte, including its trailing newline.
     */
    private static final String NO_TRAILING_NEWLINE_ARTIFACT = "86_props_object";

    @ParameterizedTest(name = "{0}")
    @MethodSource("allFixtures")
    void matchesExpected(String name) throws IOException {
        String expected = Fixtures.expected(name);
        String actual = name.equals("85_file_io")
                ? runWithTempDir(name)
                : engineFor(name).run(Fixtures.source(name), PROPS.getOrDefault(name, Map.of()));
        if (name.equals(NO_TRAILING_NEWLINE_ARTIFACT)) {
            expected = stripOneTrailingNewline(expected);
            actual = stripOneTrailingNewline(actual);
        }
        assertEquals(expected, actual, () -> "stdout mismatch for " + name + ".tee");
    }

    /** Run a fixture that does file I/O against a fresh temp dir injected as the {@code testDir} property. */
    private static String runWithTempDir(String name) throws IOException {
        Path dir = Files.createTempDirectory("propertee2-" + name + "-");
        try {
            return engineFor(name).run(Fixtures.source(name), Map.of("testDir", dir.toString()));
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** Build the Engine with the host integration each fixture documents (conformance-tests.md). */
    private static Engine engineFor(String name) {
        Engine e = new Engine();
        switch (name) {
            case "41_result_pattern" -> {
                e.registerExternal("GET_BALANCE", a -> switch ((String) a.get(0)) {
                    case "alice" -> 3000;
                    case "bob" -> 0;
                    default -> throw new RuntimeException("account not found");
                });
                e.registerExternal("DIVIDE_SAFE", a -> {
                    int x = ((Number) a.get(0)).intValue(), y = ((Number) a.get(1)).intValue();
                    if (y == 0) throw new RuntimeException("division by zero");
                    return x / y;
                });
            }
            case "71_async_external" -> {
                e.registerExternalAsync("SLOW_FETCH", a -> {
                    String n = (String) a.get(0);
                    if (n.equals("error")) throw new RuntimeException("fetch error: error");
                    return n + "_data";
                });
                e.registerExternalAsync("SLOW_COMPUTE", a -> ((Number) a.get(0)).intValue() * 10);
                e.registerExternalAsync("SLOW_TIMEOUT", a -> { throw new RuntimeException("timeout"); });
            }
            case "73_keyword_ignore" -> e.setHiddenKeywords(Set.of("if"));
            case "74_function_ignore" -> e.setIgnoredFunctions(Set.of("SHELL"));
            default -> { }
        }
        return e;
    }

    private static String stripOneTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    @Test
    void allFixturesCovered() {
        assertEquals(90, allFixtures().size());
        assertEquals(90, Fixtures.ALL.size());
    }
}
