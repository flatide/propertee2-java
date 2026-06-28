package com.flatide.propertee2.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.interp.Engine;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Fixtures requiring cooperative concurrency (PD) or host integration (PC) — deferred. */
    static final Set<String> PENDING = Set.of(
            // multi / thread / monitor / SLEEP (PD)
            "16_thread_basic", "17_thread_results", "18_thread_global_snapshot", "19_thread_sleep",
            "20_thread_monitor", "21_thread_no_result", "22_thread_calling_thread", "30_thread_local_scope",
            "32_error_monitor_assign", "37_thread_with_loops", "38_many_threads", "40_multi_after_multi",
            "44_global_prefix_thread", "45_global_prefix_thread_error", "46_thread_error_result",
            "49_multi_result_collection", "50_multi_dynamic_spawn", "51_multi_auto_keys",
            "52_multi_duplicate_key_error", "55_thread_status_field", "56_monitor_reads_result",
            "57_dynamic_thread_keys", "58_dynamic_key_digit_error", "59_dynamic_key_type_error",
            "60_dynamic_key_duplicate", "61_duplicate_auto_key", "65_keys", "67_sort_errors",
            "69_thread_isolation",
            // host integration (PC): external functions, shell, tasks, ENV, file I/O, keyword/function hiding
            "41_result_pattern", "71_async_external", "72_shell", "73_keyword_ignore", "74_function_ignore",
            "78_task_basic", "80_task_unique_ids", "83_type_env", "85_file_io");

    /** Host-injected built-in properties (the {@code -p} flag) for fixtures that need them. */
    static final Map<String, Map<String, Object>> PROPS = Map.of(
            "34_builtin_properties", Map.of("width", 100, "height", 200, "name", "test"),
            "86_props_object", Map.of("a", 40, "b", 2));

    static List<String> sequentialFixtures() {
        return Fixtures.ALL.stream().filter(n -> !PENDING.contains(n)).toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sequentialFixtures")
    void matchesExpected(String name) {
        String expected = Fixtures.expected(name);
        String actual = new Engine().run(Fixtures.source(name), PROPS.getOrDefault(name, Map.of()));
        assertEquals(normalize(expected), normalize(actual),
                () -> "stdout mismatch for " + name + ".tee");
    }

    @Test
    void pbCoversFortySixSequentialFixtures() {
        assertEquals(46, sequentialFixtures().size());
        assertEquals(84, Fixtures.ALL.size());
    }

    /**
     * Tolerate a single trailing newline. 86_props_object.expected is the only fixture saved
     * without a final newline (all 83 others end in one); this is a file artifact, not a
     * behavior difference, and the .expected files are not to be edited.
     */
    private static String normalize(String s) {
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }
}
