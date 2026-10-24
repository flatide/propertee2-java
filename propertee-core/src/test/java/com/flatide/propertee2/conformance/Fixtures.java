package com.flatide.propertee2.conformance;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The canonical conformance fixture set (153 .tee/.expected pairs under
 * src/test/resources/tests, from the v1 baseline through spec v0.32.0). The list is HARDCODED — not
 * directory-discovered — matching the v1 ScriptTest convention so a missing or
 * stray fixture shows up as a test failure (conformance-tests.md §하네스 TODO).
 *
 * <p>Numbers 31, 79, and 104 are intentionally absent (retired or not in this corpus).
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
            "96_error_null_member",
            // spec v0.9.0: elseif (ProperTee issue #3)
            "97_elseif", "98_error_elseif_condition",
            // spec v0.10.0: FAIL/UNWRAP/OK/ERR/IS_RESULT + genuine-Result brand
            "99_fail", "100_unwrap", "101_result_brand", "102_error_unwrap_nonresult",
            "103_fail_thread",
            // spec v0.12.0: reserved all-uppercase namespace (104 retired with it)
            "105_error_reserved_function_name", "106_function_name_case",
            // spec v0.13.0: the integer envelope (111 rewritten under v0.14.0 — see below)
            "107_integer_limits", "108_error_integer_overflow", "109_error_integer_literal",
            "110_error_integer_overflow_builtin", "111_thread_ignored_function",
            // spec v0.14.0: load-time rejection of blocked constructs + ECMA display + nominal
            // number identity + SUM fail-loud overflow (design-draft-v1.0-gate.md items 2 & 4)
            "112_load_reject_dead_branch", "113_number_display", "114_number_identity",
            "115_error_sum_overflow",
            // spec v0.15.0: CONTAINS extended to array membership
            "116_array_contains",
            // spec v0.16.0: monitor = watchdog thread (32 rewritten — worker purity, iteration
            // locals, per-iteration captured result snapshot)
            "117_monitor_watchdog_locals", "118_monitor_captures_snapshot",
            "119_error_monitor_global_member", "120_monitor_fresh_scope",
            // snapshot-at-multi-entry restored to v1/js/spec semantics (post-ship review round 2)
            "121_multi_setup_global_write",
            // spec v0.17.0: FIND/FIND_FIRST/FIND_LAST literal position search
            "122_find_positions", "123_error_find_empty",
            // spec v0.18.0: whole-file & JSON file I/O (host-injected testDir, like 85)
            "124_read_json_file", "125_read_file_write_json",
            // spec v0.19.0: multi ... limit K concurrency cap
            "126_multi_limit_cap", "127_multi_limit_one", "128_error_multi_limit_zero",
            "129_error_multi_limit_decimal",
            // spec v0.20.0: _ARGS positional invocation arguments (130 harness-injected)
            "130_args", "131_args_empty",
            // spec v0.21.0: monitor-only worker/SHELL observation projection
            "132_monitor_thread_info",
            // spec v0.23.0: function-only imports (resolver supplied by the host harness)
            "133_import_modules",
            // spec v0.24.0: WORDS, the pinned TRIM/WORDS whitespace set, non-empty SPLIT delimiter
            "134_words_whitespace", "135_error_split_empty_delimiter",
            // spec v0.25.0: TO_NUMBER reads exactly a decimal numeral
            "136_to_number_numerals", "137_error_to_number_hex",
            // spec v0.26.0: FILE_LINES, a file as an array read as it is used (138 host-injected testDir)
            "138_file_lines", "139_error_file_lines_missing",
            // spec v0.27.0: LIST_DIR / LIST_DIR_NAMES name patterns (host-injected testDir)
            "140_list_dir_pattern",
            // spec v0.29.0: regex matches as objects, REGEX_FIND_ALL
            "141_regex_find_object", "142_regex_find_all", "143_error_regex_find_old_index",
            "144_error_regex_invalid_pattern",
            // spec v0.30.0: rounding to digits (TRUNC, ties away from zero), FORMAT/PRINTF, and the
            // math built-ins' argument checks
            "145_round_digits", "146_round_ties", "147_error_round_args", "148_format",
            "149_printf", "150_error_format", "151_error_math_args",
            // spec v0.31.0: number patterns in FORMAT/PRINTF and TEXT; ALIGN_* by screen columns
            "152_text", "153_align", "154_error_text_align",
            // spec v0.32.0: JSON has no Infinity or NaN (156 host-injected testDir)
            "155_error_json_nonfinite", "156_error_write_json_nonfinite",
            // spec v0.33.0: the integer range is ±9,007,199,254,740,991
            "157_integer_range_builtins");

    /** Load a fixture's source ({@code <name>.tee}) from the test classpath. */
    public static String source(String name) {
        return read(name + ".tee");
    }

    /** Load a fixture's expected output ({@code <name>.expected}) from the test classpath. */
    public static String expected(String name) {
        return read(name + ".expected");
    }

    /** Load a supporting source used by a host-injected fixture. */
    static String supportingSource(String relativePath) {
        return read(relativePath);
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
