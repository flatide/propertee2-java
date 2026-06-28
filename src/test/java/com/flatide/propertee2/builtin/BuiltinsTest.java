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
        assertEquals(0, call("LEN", 42));                 // other types -> 0
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

    @Test void stringFns() {                              // 13_strings
        assertEquals("abc", call("SUBSTRING", "abcdef", 1, 3));
        assertEquals("def", call("SUBSTRING", "abcdef", 4));
        assertEquals(list("a", "b", "c"), call("SPLIT", "a,b,c", ","));
        assertEquals(list("a", "b", "c", ""), call("SPLIT", "a,b,c,", ",")); // trailing empties preserved
        assertEquals("a-b-c", call("JOIN", list("a", "b", "c"), "-"));
        assertEquals("123", call("JOIN", list(1, 2, 3), ""));
        assertEquals(list("x", "y", "z"), call("CHARS", "xyz"));
    }

    @Test void stringMatching() {                         // 81_string_matching
        assertEquals(true, call("CONTAINS", "hello world", "world"));
        assertEquals(false, call("CONTAINS", "hello world", "xyz"));
        assertEquals(true, call("CONTAINS", "abc", ""));
        assertEquals(true, call("STARTS_WITH", "hello world", "hello"));
        assertEquals(true, call("ENDS_WITH", "file.csv", ".csv"));
        assertEquals(true, call("MATCHES", "report_20240101.csv", "^report_\\d{8}\\.csv$"));
        assertEquals(false, call("MATCHES", "info: all ok", "error.*timeout"));
        // REGEX_FIND: [full, group1, ...]; unmatched optional group -> {}; no match -> {}
        assertEquals(list("status=200", "200"), call("REGEX_FIND", "status=200 time=45ms", "status=(\\d+)"));
        assertEquals(new LinkedHashMap<>(), call("REGEX_FIND", "hello world", "xyz(\\d+)"));
        assertEquals(list("b", new LinkedHashMap<>()), call("REGEX_FIND", "b", "(a)?b"));
        assertEquals("hello ProperTee", call("REPLACE", "hello world", "world", "ProperTee"));
        assertEquals("bbbbbb", call("REPLACE", "aaa", "a", "bb"));
    }

    @Test void arrayFns() {                               // 11_arrays
        assertEquals(list(99, 2, 3, 4, 5), call("PUSH", list(99, 2, 3), 4, 5));
        assertEquals(list(99, 2, 3, 4), call("POP", list(99, 2, 3, 4, 5)));
        assertEquals(list(1, 2, 3, 4), call("CONCAT", list(1, 2), list(3, 4)));
        assertEquals(list(20, 30, 40), call("SLICE", list(10, 20, 30, 40, 50), 2, 4));
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
        assertEquals(new LinkedHashMap<>(), ((Map<?, ?>) call("JSON_PARSE", "null")).get("value")); // null -> {}
        assertEquals(Integer.valueOf(42), ((Map<?, ?>) call("JSON_PARSE", "42")).get("value"));
    }

    @Test void timing() {                                 // 64_time_functions (range/format only)
        Object r = call("RANDOM");
        assertEquals(Double.class, r.getClass());
        double rd = (Double) r;
        org.junit.jupiter.api.Assertions.assertTrue(rd >= 0 && rd < 1);
        int ri = (Integer) call("RANDOM", 10);
        org.junit.jupiter.api.Assertions.assertTrue(ri >= 0 && ri < 10);
        org.junit.jupiter.api.Assertions.assertTrue((Double) call("MILTIME") > 0);
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
