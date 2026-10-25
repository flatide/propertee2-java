package com.flatide.propertee2.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.flatide.propertee2.value.TeeError;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuiltinsTest {
    private final Builtins b = Builtins.standard();

    private Object call(String name, Object... args) {
        return b.call(name, new ArrayList<>(Arrays.asList(args)));
    }

    private static List<Object> list(Object... xs) { return new ArrayList<>(Arrays.asList(xs)); }
    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test void math_returnTypes() {                       // 02_arithmetic
        assertEquals(15, call("SUM", 1, 2, 3, 4, 5));
        assertEquals(Integer.class, call("SUM", 1, 2, 3).getClass());
        assertEquals(3.0, call("SUM", 1, 2.0));
        assertEquals(Double.class, call("SUM", 1, 2.0).getClass());
        assertEquals(8, call("MAX", 5, 2, 8, 1));
        assertEquals(1, call("MIN", 5, 2, 8, 1));
        assertEquals(7, call("ABS", -7));
        assertEquals(3.5, call("ABS", -3.5));
        assertEquals(3, call("FLOOR", 3.9));
        assertEquals(4, call("CEIL", 3.1));
        assertEquals(4, call("ROUND", 3.5));
        assertEquals(Integer.class, call("FLOOR", 3.9).getClass());
    }

    @Test void rounding_digits() {                        // spec v0.30.0: 145_round_digits, 146_round_ties
        assertEquals(3.4, call("FLOOR", 3.48, 1));
        assertEquals(-3.5, call("FLOOR", -3.48, 1));
        assertEquals(-3.4, call("CEIL", -3.48, 1));
        assertEquals(-3.4, call("TRUNC", -3.48, 1));
        assertEquals(3.5, call("ROUND", 3.48, 1));
        assertEquals(0.29, call("FLOOR", 0.29, 2));            // the shown digits, not 0.28999…
        assertEquals(1.01, call("ROUND", 1.005, 2));
        assertEquals(1200.0, call("ROUND", 1234.5, -2));
        assertEquals(Double.class, call("ROUND", 5, 2).getClass());     // with digits: a decimal
        assertEquals(Integer.class, call("TRUNC", -3.9).getClass());    // without: an integer
        assertEquals(-3, call("TRUNC", -3.9));
        assertEquals(-3, call("ROUND", -2.5));                         // a tie away from zero
        assertEquals(0, call("ROUND", 0.49999999999999994));
        assertEquals(3000000001.0, call("ROUND", 3000000000.7, 0));    // no integer overflow
        assertEquals(Double.POSITIVE_INFINITY, call("CEIL", Double.MAX_VALUE, -308));
        assertEquals(3.48, call("ROUND", 3.48, 1e300));                // any whole digits
        assertEquals("ROUND() digits must be an integer",
                assertThrows(TeeError.class, () -> call("ROUND", 3.48, 1.5)).getMessage());
        assertEquals("CEIL() takes at most 2 arguments",
                assertThrows(TeeError.class, () -> call("CEIL", 3.48, 1, 9)).getMessage());
        assertEquals(3000000000L, call("TRUNC", 3000000000.5));           // spec v0.33.0: inside the range
        assertEquals("Integer overflow: the result is outside ±9,007,199,254,740,991",
                assertThrows(TeeError.class, () -> call("TRUNC", 1e16)).getMessage());
    }

    @Test void format() {                                 // spec v0.31.0: 148_format, 150_error_format
        assertEquals("3개, 평균 3.40점", call("FORMAT", "{}개, 평균 {0.00}점", 3, 3.4));
        assertEquals("[-007] [1,234,567.89] [45.7%] [{}]",
                call("FORMAT", "[{000}] [{#,##0.00}] [{0.0%}] [{{}}]", -7, 1234567.891, 0.4567));
        assertEquals("[0.0] [3] [1.01]", call("FORMAT", "[{0.0}] [{0}] [{0.00}]", -0.04, 2.5, 1.005));
        assertEquals("FORMAT() format has 1 placeholder, got 2 values",
                assertThrows(TeeError.class, () -> call("FORMAT", "{}", 1, 2)).getMessage());
        assertEquals("FORMAT() invalid format: {:.2f}",                // spec v0.30.0's syntax
                assertThrows(TeeError.class, () -> call("FORMAT", "{:.2f}", 1)).getMessage());
        assertEquals("FORMAT() {0.00} requires a number, got string",
                assertThrows(TeeError.class, () -> call("FORMAT", "{0.00}", "3.4")).getMessage());
    }

    @Test void text() {                                   // spec v0.31.0: 152_text, 154_error_text_align
        assertEquals("1,234,567.89", call("TEXT", 1234567.891, "#,##0.00"));
        assertEquals("3.40", call("TEXT", 3.4, "0.00"));
        assertEquals("3", call("TEXT", 3, "0.##"));               // no point when no decimal is left
        assertEquals("0.5", call("TEXT", 0.5, "#.##"));           // at least one integer digit
        assertEquals("-0,007", call("TEXT", -7, "0,000"));        // zeros after the sign, grouped
        assertEquals("15%", call("TEXT", 0.145, "0%"));           // the point moves on the shown digits
        assertEquals("0.00", call("TEXT", -0.004, "0.00"));       // no sign on zero
        assertEquals("Infinity", call("TEXT", Double.POSITIVE_INFINITY, "0.00"));
        assertEquals("TEXT() invalid pattern: '0.0.0'",
                assertThrows(TeeError.class, () -> call("TEXT", 3, "0.0.0")).getMessage());
        assertEquals("TEXT() invalid pattern: 'y'",               // the pattern is checked first
                assertThrows(TeeError.class, () -> call("TEXT", "x", "y")).getMessage());
        assertEquals("TEXT() requires a number, got string",
                assertThrows(TeeError.class, () -> call("TEXT", "3", "0")).getMessage());
    }

    @Test void align() {                                  // spec v0.31.0: 153_align, 154_error_text_align
        assertEquals("ab    ", call("ALIGN_LEFT", "ab", 6));
        assertEquals("    42", call("ALIGN_RIGHT", 42, 6));
        assertEquals("  ab   ", call("ALIGN_CENTER", "ab", 7));
        assertEquals("사과  ", call("ALIGN_LEFT", "사과", 6));      // a Hangul syllable takes two columns
        assertEquals("long", call("ALIGN_RIGHT", "long", 2));     // not cut
        assertEquals("ALIGN_LEFT() width must be an integer from 0 to 999",
                assertThrows(TeeError.class, () -> call("ALIGN_LEFT", "a", 1000)).getMessage());
        assertEquals("ALIGN_RIGHT() takes at most 2 arguments",
                assertThrows(TeeError.class, () -> call("ALIGN_RIGHT", "7", 3, "0")).getMessage());
    }

    @Test void typeOf() {                                 // 83_type_env
        assertEquals("number", call("TYPE_OF", 42));
        assertEquals("number", call("TYPE_OF", 3.14));
        assertEquals("string", call("TYPE_OF", "hi"));
        assertEquals("boolean", call("TYPE_OF", true));
        assertEquals("array", call("TYPE_OF", list(1, 2)));
        assertEquals("object", call("TYPE_OF", obj("a", 1)));
        assertEquals("object", call("TYPE_OF", obj()));
    }

    @Test void stringAndLen() {                           // 13_strings
        assertEquals(5, call("LEN", "hello"));
        assertEquals(0, call("LEN", ""));
        assertEquals(3, call("LEN", list(1, 2, 3)));
        assertEquals(1, call("LEN", obj("a", 1)));
        assertEquals("LEN() requires a string, array, or object argument",   // spec v0.7.0 (#7)
                assertThrows(TeeError.class, () -> call("LEN", 42)).getMessage());
        assertEquals("HELLO", call("UPPERCASE", "hello"));
        assertEquals("hello", call("LOWERCASE", "HELLO"));
        assertEquals("hi", call("TRIM", "  hi  "));
        assertEquals("42", call("TO_STRING", 42));
        assertEquals("true", call("TO_STRING", true));
        assertEquals("{}", call("TO_STRING", obj()));
        assertEquals(123, call("TO_NUMBER", "123"));
        assertEquals(Integer.class, call("TO_NUMBER", "123").getClass());
        assertEquals(3.14, call("TO_NUMBER", "3.14"));
    }

    @Test void objectAndJson() {                          // 65_keys, 82_map_extensions, 84_json
        assertEquals(list("a", "b"), call("KEYS", obj("a", 1, "b", 2)));
        assertEquals(list(1, 2), call("VALUES", obj("a", 1, "b", 2)));
        assertEquals(true, call("HAS_KEY", obj("a", 1), "a"));
        assertEquals(false, call("HAS_KEY", obj("a", 1), "x"));
        assertEquals("{\"name\":\"Alice\",\"age\":30}", call("JSON_FORMAT", obj("name", "Alice", "age", 30)));
    }

    @Test void jsonFormatRejectsNonFinite() {             // spec v0.32.0: 155_error_json_nonfinite
        double inf = Double.POSITIVE_INFINITY;
        assertEquals("JSON_FORMAT() value is Infinity, which JSON cannot represent",
                assertThrows(TeeError.class, () -> call("JSON_FORMAT", inf)).getMessage());
        assertEquals("JSON_FORMAT() value.list.2 is NaN, which JSON cannot represent",
                assertThrows(TeeError.class, () -> call("JSON_FORMAT", obj("list", list(1, Double.NaN)))).getMessage());
        assertEquals("JSON_FORMAT() value.\"my key\".1 is -Infinity, which JSON cannot represent",
                assertThrows(TeeError.class, () -> call("JSON_FORMAT", obj("my key", list(-inf)))).getMessage());
        assertEquals("[1.7976931348623157e+308]", call("JSON_FORMAT", list(Double.MAX_VALUE)));
    }

    @Test void stringFns() {                              // 13_strings
        assertEquals("abc", call("SUBSTRING", "abcdef", 1, 3));
        assertEquals("def", call("SUBSTRING", "abcdef", 4));
        assertEquals(list("a", "b", "c"), call("SPLIT", "a,b,c", ","));
        assertEquals(list("a", "b", "c", ""), call("SPLIT", "a,b,c,", ",")); // trailing empties preserved
        assertEquals("a-b-c", call("JOIN", list("a", "b", "c"), "-"));
        assertEquals("123", call("JOIN", list(1, 2, 3), ""));
        assertEquals(list("x", "y", "z"), call("CHARS", "xyz"));
    }

    @Test void substringAndSlicePositionsAreIntegersInRange() {   // spec v0.34.0, 158_error_positions
        for (Object bad : new Object[] {0, -1, 2.5, Double.NaN, Double.POSITIVE_INFINITY, "2"}) {
            assertEquals("SUBSTRING() start must be an integer of 1 or more",
                    assertThrows(TeeError.class, () -> call("SUBSTRING", "hello", bad, 3)).getMessage());
        }
        for (Object bad : new Object[] {-1, 1.5, Double.NaN}) {
            assertEquals("SUBSTRING() length must be an integer of 0 or more",
                    assertThrows(TeeError.class, () -> call("SUBSTRING", "hello", 2, bad)).getMessage());
        }
        assertEquals("SLICE() start must be an integer of 1 or more",
                assertThrows(TeeError.class, () -> call("SLICE", list(1, 2, 3), 0)).getMessage());
        assertEquals("SLICE() count must be an integer of 0 or more",
                assertThrows(TeeError.class, () -> call("SLICE", list(1, 2, 3), 2, -1)).getMessage());
        // past the end there is just less, and a whole decimal is an integer
        assertEquals("", call("SUBSTRING", "hello", 2, 0));
        assertEquals("", call("SUBSTRING", "hello", 6));
        assertEquals("", call("SUBSTRING", "hello", 1e300));
        assertEquals("lo", call("SUBSTRING", "hello", 4, 10));
        assertEquals("ell", call("SUBSTRING", "hello", 2.0, 3.0));
        assertEquals(list(), call("SLICE", list(1, 2, 3), 4, 1));
        assertEquals(list(2, 3), call("SLICE", list(1, 2, 3), 2.0, 1e300));
    }

    @Test void stringMatching() {                         // 81_string_matching
        assertEquals(true, call("CONTAINS", "hello world", "world"));
        assertEquals(false, call("CONTAINS", "hello world", "xyz"));
        assertEquals(true, call("CONTAINS", "abc", ""));
        assertEquals(true, call("STARTS_WITH", "hello world", "hello"));
        assertEquals(true, call("ENDS_WITH", "file.csv", ".csv"));
        assertEquals(true, call("MATCHES", "report_20240101.csv", "^report_\\d{8}\\.csv$"));
        assertEquals(false, call("MATCHES", "info: all ok", "error.*timeout"));
        // REGEX_FIND (spec v0.29.0): a match object; unmatched optional group -> {}; no match -> {}
        assertEquals(obj("match", "status=200", "position", 1, "groups", list("200"), "named", obj()),
                call("REGEX_FIND", "status=200 time=45ms", "status=(\\d+)"));
        assertEquals(new LinkedHashMap<>(), call("REGEX_FIND", "hello world", "xyz(\\d+)"));
        assertEquals(obj("match", "b", "position", 1, "groups", list(new LinkedHashMap<>()), "named", obj()),
                call("REGEX_FIND", "b", "(a)?b"));
        assertEquals("hello ProperTee", call("REPLACE", "hello world", "world", "ProperTee"));
        assertEquals("bbbbbb", call("REPLACE", "aaa", "a", "bb"));
    }

    @Test void regexMatchObjects() {                      // 141-144 (spec v0.29.0)
        // field order is match, position, groups, named; named lists names in pattern order
        Object m = call("REGEX_FIND", "date: 2024-01-15", "(?<year>[0-9]{4})-(?<month>[0-9]{2})-([0-9]{2})");
        assertEquals(obj("match", "2024-01-15", "position", 7, "groups", list("2024", "01", "15"),
                "named", obj("year", "2024", "month", "01")), m);
        assertEquals(list("match", "position", "groups", "named"), new ArrayList<>(((Map<?, ?>) m).keySet()));
        assertEquals(list("b", "a"), new ArrayList<>(((Map<?, ?>) ((Map<?, ?>) call("REGEX_FIND", "xy",
                "(?<b>x)(?<a>y)")).get("named")).keySet()));
        assertEquals(obj("match", "b", "position", 1, "groups", list(obj(), "b"), "named", obj("a", obj())),
                call("REGEX_FIND", "b", "(?<a>a)?(b)"));
        assertEquals(4, ((Map<?, ?>) call("REGEX_FIND", "abc", "$")).get("position"));     // empty match at the end
        // REGEX_FIND_ALL: left to right, no overlap, one further after an empty match
        assertEquals(list(obj("match", "id=4", "position", 1, "groups", list("4"), "named", obj("n", "4")),
                        obj("match", "id=56", "position", 7, "groups", list("56"), "named", obj("n", "56"))),
                call("REGEX_FIND_ALL", "id=4, id=56", "id=(?<n>[0-9]+)"));
        assertEquals(list(), call("REGEX_FIND_ALL", "abc", "[0-9]"));
        assertEquals(2, ((List<?>) call("REGEX_FIND_ALL", "aaaa", "aa")).size());
        List<Object> positions = new ArrayList<>();
        for (Object o : (List<?>) call("REGEX_FIND_ALL", "baaac", "a*")) positions.add(((Map<?, ?>) o).get("position"));
        assertEquals(list(1, 2, 5, 6), positions);
        assertEquals(1, ((List<?>) call("REGEX_FIND_ALL", "a\nb", "^.")).size());
        // the same messages in every regex built-in
        assertEquals("REGEX_FIND_ALL() invalid regex pattern: ([a-z]",
                assertThrows(TeeError.class, () -> call("REGEX_FIND_ALL", "abc", "([a-z]")).getMessage());
        assertEquals("REGEX_FIND_ALL() requires a string argument",
                assertThrows(TeeError.class, () -> call("REGEX_FIND_ALL", 5, "a")).getMessage());
        assertEquals("REGEX_FIND() invalid regex pattern: (",
                assertThrows(TeeError.class, () -> call("REGEX_FIND", "abc", "(")).getMessage());
        assertEquals("MATCHES() invalid regex pattern: (",
                assertThrows(TeeError.class, () -> call("MATCHES", "abc", "(")).getMessage());
    }

    @Test void findFns() {                                // 122_find_positions, 123_error_find_empty (spec v0.17.0)
        assertEquals(list(3), call("FIND", "abcd", "c"));
        assertEquals(list(2, 4), call("FIND", "banana", "an"));
        assertEquals(list(1, 2), call("FIND", "aaa", "aa"));      // overlapping occurrences included
        assertEquals(list(1), call("FIND", "abc", "abc"));
        assertEquals(list(), call("FIND", "abc", "z"));
        assertEquals(3, call("FIND_FIRST", "abcd", "c"));
        assertEquals(Integer.class, call("FIND_FIRST", "abcd", "c").getClass());
        assertEquals(0, call("FIND_FIRST", "abc", "z"));
        assertEquals(3, call("FIND_LAST", "abcd", "c"));
        assertEquals(2, call("FIND_LAST", "aaa", "aa"));          // lastIndexOf scans overlapping too
        assertEquals(0, call("FIND_LAST", "abc", "z"));
        // exact error messages
        assertEquals("FIND() requires a non-empty search string",
                assertThrows(TeeError.class, () -> call("FIND", "abc", "")).getMessage());
        assertEquals("FIND_FIRST() requires a non-empty search string",
                assertThrows(TeeError.class, () -> call("FIND_FIRST", "abc", "")).getMessage());
        assertEquals("FIND_LAST() requires a non-empty search string",
                assertThrows(TeeError.class, () -> call("FIND_LAST", "abc", "")).getMessage());
        assertEquals("FIND() requires a string argument",
                assertThrows(TeeError.class, () -> call("FIND", list(1, 2), 2)).getMessage());
    }

    @Test void wordsSplitTrim() {                         // 134_words_whitespace, 135_error_split_empty_delimiter (spec v0.24.0)
        assertEquals(list("test", "1", "2"), call("WORDS", "    test 1  2"));
        assertEquals(list(), call("WORDS", ""));
        assertEquals(list(), call("WORDS", " \t\n\u000B\f\r"));
        assertEquals(list("a", "b", "c", "d", "e", "f"), call("WORDS", "a\tb\nc\u000Bd\fe\rf"));
        assertEquals(list(" x ", "y"), call("WORDS", " x  y")); // NBSP is not whitespace
        assertEquals(list("x\u0001", "y"), call("WORDS", "x\u0001 y"));             // nor are other controls
        // TRIM strips exactly the six ASCII whitespace characters (String.trim() strips all <= U+0020)
        assertEquals("hi", call("TRIM", " \t\n\u000B\f\rhi \r\n"));
        assertEquals("a  b", call("TRIM", "a  b"));
        assertEquals("", call("TRIM", "   "));
        assertEquals(" hi ", call("TRIM", " hi "));
        assertEquals("\u0001hi\u001F", call("TRIM", "\u0001hi\u001F"));
        assertEquals("﻿hi", call("TRIM", "﻿hi"));
        // SPLIT keeps every field — leading, interior, trailing — and matches literally, left to right
        assertEquals(list("", "", "", "", "test", "1", "", "2"), call("SPLIT", "    test 1  2", " "));
        assertEquals(list("", "a", "", "b", ""), call("SPLIT", ",a,,b,", ","));
        assertEquals(list(""), call("SPLIT", "", ","));
        assertEquals(list("", "a"), call("SPLIT", "aaa", "aa"));
        assertEquals(list("a", "b", ""), call("SPLIT", "a::b::", "::"));
        assertEquals(list("a", "b"), call("SPLIT", "a.b", "."));
        // exact error messages
        assertEquals("SPLIT() requires a non-empty delimiter",
                assertThrows(TeeError.class, () -> call("SPLIT", "abc", "")).getMessage());
        assertEquals("SPLIT() requires a non-empty delimiter",
                assertThrows(TeeError.class, () -> call("SPLIT", "", "")).getMessage());
        assertEquals("WORDS() requires a string argument",
                assertThrows(TeeError.class, () -> call("WORDS", 42)).getMessage());
    }

    @Test void toNumberNumerals() {                       // 136_to_number_numerals, 137_error_to_number_hex (spec v0.25.0)
        // integer text inside the range is an integer (Integer within 32 bits, Long beyond — spec
        // v0.33.0); a fraction, an exponent or a wider value is a Double
        assertEquals(42, call("TO_NUMBER", "42"));
        assertEquals(Integer.class, call("TO_NUMBER", "+5").getClass());
        assertEquals(-5, call("TO_NUMBER", "-5"));
        assertEquals(7, call("TO_NUMBER", "007"));
        assertEquals(0, call("TO_NUMBER", "-0"));
        assertEquals(Integer.MAX_VALUE, call("TO_NUMBER", "2147483647"));
        assertEquals(Integer.MIN_VALUE, call("TO_NUMBER", "-2147483648"));
        assertEquals(2147483648L, call("TO_NUMBER", "2147483648"));
        assertEquals(9007199254740991L, call("TO_NUMBER", "9007199254740991"));
        assertEquals(-9007199254740991L, call("TO_NUMBER", "-9007199254740991"));
        assertEquals(9007199254740992.0, call("TO_NUMBER", "9007199254740992"));
        assertEquals(1e20, call("TO_NUMBER", "100000000000000000000"));
        assertEquals(5.0, call("TO_NUMBER", "5.0"));
        assertEquals(Double.class, call("TO_NUMBER", "5.0").getClass());
        assertEquals(Double.class, call("TO_NUMBER", "1e3").getClass());
        assertEquals(0.5, call("TO_NUMBER", ".5"));
        assertEquals(5.0, call("TO_NUMBER", "5."));
        assertEquals(0.001, call("TO_NUMBER", "1E-3"));
        assertEquals(50.0, call("TO_NUMBER", "+.5e+2"));
        assertEquals(0.0, call("TO_NUMBER", "1e-999"));                      // too small: rounds to 0
        // only the six TRIM whitespace characters around the numeral are ignored
        assertEquals(42, call("TO_NUMBER", " \t\n\u000B\f\r42\r\n"));
        // everything else fails: one runtime's host-parser extras, non-numerals, infinity
        for (String bad : new String[] {
                "0x10", "0X10", "0o17", "0b101",                             // js Number() read these
                "1f", "1.5d", "1D", "0x1p3", "NaN", "１２", "١٢", // Java's parsers read these
                "Infinity", "-Infinity", "+Infinity",                        // both read these
                "", "   ", "1_000", "1,000", ".", "+", "e5", "1e", "1e+", "- 5", "1 000", "++5", "5..0",
                " 42", "﻿42", "42 ", "\u000142", "42\u001F"}) {     // not TRIM whitespace
            assertEquals("TO_NUMBER() cannot convert '" + bad + "' to number",
                    assertThrows(TeeError.class, () -> call("TO_NUMBER", bad), bad).getMessage());
        }
        assertEquals("TO_NUMBER() value out of range: '1e999'",
                assertThrows(TeeError.class, () -> call("TO_NUMBER", "1e999")).getMessage());
        assertEquals("TO_NUMBER() value out of range: ' -1e999 '",
                assertThrows(TeeError.class, () -> call("TO_NUMBER", " -1e999 ")).getMessage());
        assertEquals("TO_NUMBER() value out of range: '" + "9".repeat(400) + "'",
                assertThrows(TeeError.class, () -> call("TO_NUMBER", "9".repeat(400))).getMessage());
        assertEquals("TO_NUMBER() requires a string argument",
                assertThrows(TeeError.class, () -> call("TO_NUMBER", 42)).getMessage());
    }

    @Test void arrayFns() {                               // 11_arrays
        assertEquals(list(99, 2, 3, 4, 5), call("PUSH", list(99, 2, 3), 4, 5));
        assertEquals(list(99, 2, 3, 4), call("POP", list(99, 2, 3, 4, 5)));
        assertEquals(list(1, 2, 3, 4), call("CONCAT", list(1, 2), list(3, 4)));
        // spec v0.7.0 (#6): the 3rd arg is a COUNT (was an end bound until v0.6.0)
        assertEquals(list(20, 30, 40, 50), call("SLICE", list(10, 20, 30, 40, 50), 2, 4));
        assertEquals(list(20, 30, 40), call("SLICE", list(10, 20, 30, 40, 50), 2, 3));
        assertEquals(list(40, 50), call("SLICE", list(10, 20, 30, 40, 50), 4, 99)); // count clamps at end
        assertEquals(list(30, 40, 50), call("SLICE", list(10, 20, 30, 40, 50), 3));
        // original unchanged (COW)
        List<Object> original = list(3, 1, 2);
        call("PUSH", original, 9);
        assertEquals(list(3, 1, 2), original);
    }

    @Test void sortFns() {                                // 66_sort, 67_sort_errors
        assertEquals(list(1, 1, 2, 3), call("SORT", list(3, 1, 1, 2)));
        assertEquals(list("apple", "banana", "cherry"), call("SORT", list("banana", "apple", "cherry")));
        assertEquals(list(3, 2, 1), call("SORT_DESC", list(1, 3, 2)));
        assertEquals(list(true, "two", 1), call("REVERSE", list(1, "two", true)));
        assertEquals(list(), call("SORT", list()));
        // exact error messages (asserted by 67_sort_errors)
        assertEquals("SORT() requires all elements to be the same type (number or string)",
                assertThrows(TeeError.class, () -> call("SORT", list(1, "two", 3))).getMessage());
        assertEquals("SORT() requires an array argument",
                assertThrows(TeeError.class, () -> call("SORT", "hello")).getMessage());
        assertEquals("Property 'score' does not exist in array element at index 2",
                assertThrows(TeeError.class, () -> call("SORT_BY",
                        list(obj("name", "A", "score", 10), obj("name", "B")), "score")).getMessage());
        // SORT_BY ascending by numeric key
        Object byAge = call("SORT_BY", list(obj("n", "C", "age", 30), obj("n", "A", "age", 25), obj("n", "B", "age", 35)), "age");
        assertEquals("A", ((Map<?, ?>) ((List<?>) byAge).get(0)).get("n"));
        assertEquals("B", ((Map<?, ?>) ((List<?>) byAge).get(2)).get("n"));
    }

    @Test void objectAndJsonExtended() {                  // 82_map_extensions, 84_json
        assertEquals(list(1, 2, 3), call("VALUES", obj("a", 1, "b", 2, "c", 3)));
        assertEquals(obj("a", 1, "b", 2, "c", 3), call("MERGE", obj("a", 1, "b", 2), obj("c", 3)));
        assertEquals(obj("x", 1, "y", 99), call("MERGE", obj("x", 1, "y", 2), obj("y", 99))); // 2nd overrides
        assertEquals(obj("a", 1, "c", 3), call("REMOVE_KEY", obj("a", 1, "b", 2, "c", 3), "b"));
        assertEquals(obj("key", "a", "value", 1), ((List<?>) call("ENTRIES", obj("a", 1))).get(0));
        // JSON_PARSE returns a Result {status,ok,value}; null -> {}
        Map<?, ?> parsed = (Map<?, ?>) call("JSON_PARSE", "{\"name\":\"Alice\",\"age\":30}");
        assertEquals(true, parsed.get("ok"));
        assertEquals(obj("name", "Alice", "age", 30), parsed.get("value"));
        assertEquals(false, ((Map<?, ?>) call("JSON_PARSE", "{invalid}")).get("ok"));
        assertEquals(com.flatide.propertee2.value.JsonNull.NULL,
                ((Map<?, ?>) call("JSON_PARSE", "null")).get("value")); // spec v0.8.0 (#4): null preserved
        assertEquals(Integer.valueOf(42), ((Map<?, ?>) call("JSON_PARSE", "42")).get("value"));
        assertEquals(Long.valueOf(5498058138880L), ((Map<?, ?>) call("JSON_PARSE", "5498058138880")).get("value"));
        assertEquals(9007199254740992.0, ((Map<?, ?>) call("JSON_PARSE", "9007199254740992")).get("value"));
        // spec v0.35.0: a numeral too large to represent is an error naming where it is, not Infinity
        Map<?, ?> big = (Map<?, ?>) call("JSON_PARSE", "{\"rows\": [1, {\"size\": -1e999}]}");
        assertEquals(false, big.get("ok"));
        assertEquals("JSON_PARSE() value.rows.2.size is a number too large to represent", big.get("value"));
        assertEquals(0.0, ((Map<?, ?>) call("JSON_PARSE", "1e-999")).get("value"));   // too small is 0
        assertEquals("Invalid JSON: invalid number at position 2", ((Map<?, ?>) call("JSON_PARSE", "1e")).get("value"));
    }

    @Test void timing() {                                 // 64_time_functions (range/format only)
        Object r = call("RANDOM");
        assertEquals(Double.class, r.getClass());
        double rd = (Double) r;
        org.junit.jupiter.api.Assertions.assertTrue(rd >= 0 && rd < 1);
        int ri = (Integer) call("RANDOM", 1, 10);
        org.junit.jupiter.api.Assertions.assertTrue(ri >= 1 && ri <= 10);
        assertEquals("RANDOM() requires zero or two arguments",              // spec v0.7.0 (#5)
                assertThrows(TeeError.class, () -> call("RANDOM", 10)).getMessage());
        // spec v0.33.0: bounds anywhere in the range (a bound past 32 bits used to be cut to
        // 2147483647), the ends included; min may not be greater than max
        for (int k = 0; k < 200; k++) {
            long big = (Long) call("RANDOM", 4_000_000_000L, 5_000_000_000L);
            org.junit.jupiter.api.Assertions.assertTrue(big >= 4_000_000_000L && big <= 5_000_000_000L, "" + big);
            long any = ((Number) call("RANDOM", -9007199254740991L, 9007199254740991L)).longValue();
            org.junit.jupiter.api.Assertions.assertTrue(Math.abs(any) <= 9007199254740991L, "" + any);
        }
        // every integer can come up: the whole range used to give only odd numbers, because
        // Math.random() * 2^54 steps by 2
        boolean even = false, odd = false;
        for (int k = 0; k < 200; k++) {
            if (((Number) call("RANDOM", -9007199254740991L, 9007199254740991L)).longValue() % 2 == 0) even = true; else odd = true;
        }
        org.junit.jupiter.api.Assertions.assertTrue(even && odd, "even " + even + ", odd " + odd);
        assertEquals(9007199254740991L, call("RANDOM", 9007199254740991L, 9007199254740991L));
        assertEquals(3, call("RANDOM", 3.0, 3));                             // a whole decimal will do
        for (Object[] bad : new Object[][] {{0, 1e16}, {1.2, 1.5}, {-1.5, -1.2}, {0, Double.NaN}, {0, Double.POSITIVE_INFINITY}}) {
            assertEquals("RANDOM() bounds must be integers within ±9,007,199,254,740,991",
                    assertThrows(TeeError.class, () -> call("RANDOM", bad)).getMessage());
        }
        assertEquals("RANDOM() min cannot be greater than max",
                assertThrows(TeeError.class, () -> call("RANDOM", 5, 1)).getMessage());
        assertEquals(Long.class, call("MILTIME").getClass());               // an integer (spec v0.33.0)
        org.junit.jupiter.api.Assertions.assertTrue((Long) call("MILTIME") > 1_700_000_000_000L);
        assertEquals(10, ((String) call("DATE")).length());   // yyyy-MM-dd
        assertEquals(8, ((String) call("TIME")).length());    // HH:MM:SS
    }

    @Test void errorsAndGating() {
        assertThrows(TeeError.class, () -> call("UNKNOWN_FN", 1));
        assertThrows(TeeError.class, () -> call("ABS", "not-a-number"));
        // host-gated / blocking builtins are not registered as pure-callable in PA
        b.register("SHELL", Builtins.Kind.BLOCKING, a -> "x");
        assertThrows(TeeError.class, () -> call("SHELL", "echo"));
    }
}
