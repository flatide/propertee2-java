package com.flatide.propertee2.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The canonical conformance fixture set (94 .tee/.expected pairs under
 * src/test/resources/tests — the 84 copied from v1, plus 87–92 (spec v0.7.0)
 * and 93–96 (spec v0.8.0) added by the spec batches). The list is HARDCODED — not
 * directory-discovered — matching the v1 ScriptTest convention so a missing or
 * stray fixture shows up as a test failure (conformance-tests.md §하네스 TODO).
 *
 * <p>Numbers 31 and 79 are intentionally absent (skipped in v1 / not in this corpus).
 * The full conformance harness (PE) runs each fixture and diffs stdout against
 * .expected with the documented host injections; for now {@code ParseAllFixturesTest}
 * uses this list as a parser smoke test.
 */
public final class Fixtures {
    private Fixtures() {}

    public static final List<String> ALL = List.of(
            "01_variables_types", "02_arithmetic", "03_comparisons_logic", "04_if_else",
            "05_condition_loop", "06_value_loop", "07_keyvalue_loop", "08_break_continue",
            "09_functions", "10_recursion", "11_arrays", "12_objects",
            "13_strings", "14_scope", "15_nested_loops", "16_thread_basic",
            "17_thread_results", "18_thread_global_snapshot", "19_thread_sleep", "20_thread_monitor",
            "21_thread_no_result", "22_thread_calling_thread", "23_error_type_mismatch", "24_error_undefined_var",
            "25_error_undefined_func", "26_error_div_zero", "27_error_null_access", "28_error_not_boolean",
            "29_error_loop_limit", "30_thread_local_scope", "32_error_monitor_assign", "33_complex_expressions",
            "34_builtin_properties", "35_object_computed_keys", "36_function_with_loops", "37_thread_with_loops",
            "38_many_threads", "39_escape_strings", "40_multi_after_multi", "41_result_pattern",
            "42_global_prefix", "43_global_prefix_error", "44_global_prefix_thread", "45_global_prefix_thread_error",
            "46_thread_error_result", "47_spawn_outside_multi", "48_has_key", "49_multi_result_collection",
            "50_multi_dynamic_spawn", "51_multi_auto_keys", "52_multi_duplicate_key_error", "53_len_on_maps",
            "54_map_positional_access", "55_thread_status_field", "56_monitor_reads_result", "57_dynamic_thread_keys",
            "58_dynamic_key_digit_error", "59_dynamic_key_type_error", "60_dynamic_key_duplicate", "61_duplicate_auto_key",
            "62_range_array", "63_range_step_zero", "64_time_functions", "65_keys",
            "66_sort", "67_sort_errors", "68_cow_semantics", "69_thread_isolation",
            "70_debug_statement", "71_async_external", "72_shell", "73_keyword_ignore",
            "74_function_ignore", "75_range_step_eval_once", "76_range_tiny_float_bound", "77_range_int_overflow",
            "78_task_basic", "80_task_unique_ids", "81_string_matching", "82_map_extensions",
            "83_type_env", "84_json", "85_file_io", "86_props_object",
            // spec v0.7.0 breaking batch (ProperTee issues #1/#2/#5/#6/#7)
            "87_short_circuit", "88_error_condition_not_boolean", "89_error_loop_condition_not_boolean",
            "90_slice_count", "91_error_random_single_arg", "92_error_len_non_collection",
            // spec v0.8.0: first-class null literal (ProperTee issue #4)
            "93_null_literal", "94_json_null_roundtrip", "95_error_null_condition",
            "96_error_null_member");

    /** Load a fixture's source ({@code <name>.tee}) from the test classpath. */
    public static String source(String name) {
        return read(name + ".tee");
    }

    /** Load a fixture's expected output ({@code <name>.expected}) from the test classpath. */
    public static String expected(String name) {
        return read(name + ".expected");
    }

    private static String read(String file) {
        String path = "/tests/" + file;
        try (InputStream in = Fixtures.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("fixture not on classpath: " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
