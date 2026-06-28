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
 * {@code .expected} (design §10.1 PE, started here for the sequential subset PB implements).
 *
 * <p>PB covers the SEQUENTIAL language. The fixtures in {@link #PENDING} exercise cooperative
 * concurrency (multi / thread / SLEEP / monitor) or host integration (external functions, shell,
 * file I/O, ENV, keyword/function hiding) and are deferred to PC/PD — they are NOT asserted here.
 * As those phases land, names move out of PENDING.
 */
class ConformanceTest {

    /** Fixtures still deferred — host integration (PC tail / PD): external functions, shell, tasks,
     * keyword/function hiding. multi/thread/monitor land in PD; ENV + file I/O were wired in PC. */
    static final Set<String> PENDING = Set.of(
            "41_result_pattern", "71_async_external", "72_shell", "73_keyword_ignore", "74_function_ignore",
            "78_task_basic", "80_task_unique_ids");

    /** Host-injected built-in properties (the {@code -p} flag) for fixtures that need them. */
    static final Map<String, Map<String, Object>> PROPS = Map.of(
            "34_builtin_properties", Map.of("width", 100, "height", 200, "name", "test"),
            "86_props_object", Map.of("a", 40, "b", 2));

    static List<String> sequentialFixtures() {
        return Fixtures.ALL.stream().filter(n -> !PENDING.contains(n)).toList();
    }

    /**
     * The only fixture saved without a final newline (all 83 others end in one); a file artifact,
     * not a behavior difference, and the .expected files are not to be edited. Every OTHER fixture
     * is compared byte-for-byte, including its trailing newline.
     */
    private static final String NO_TRAILING_NEWLINE_ARTIFACT = "86_props_object";

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequentialFixtures")
    void matchesExpected(String name) throws IOException {
        String expected = Fixtures.expected(name);
        String actual = name.equals("85_file_io")
                ? runWithTempDir(name)
                : new Engine().run(Fixtures.source(name), PROPS.getOrDefault(name, Map.of()));
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
            return new Engine().run(Fixtures.source(name), Map.of("testDir", dir.toString()));
        } finally {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static String stripOneTrailingNewline(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    @Test
    void nonPendingFixtureCount() {
        assertEquals(77, sequentialFixtures().size());   // all but the 7-fixture host tail
        assertEquals(84, Fixtures.ALL.size());
    }
}
