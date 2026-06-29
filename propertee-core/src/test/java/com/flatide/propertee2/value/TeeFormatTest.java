package com.flatide.propertee2.value;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Locks value rendering to the exact byte output the fixtures expect
 * (LANGUAGE.md §Output Formatting; 11_arrays, 82_map_extensions, 84_json, 02_arithmetic).
 */
class TeeFormatTest {

    private static List<Object> list(Object... xs) { return new ArrayList<>(Arrays.asList(xs)); }

    private static Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test void numbers() {
        assertEquals("10", TeeFormat.display(10));
        assertEquals("3.14", TeeFormat.display(3.14));
        assertEquals("3.3333333333333335", TeeFormat.display(10.0 / 3.0)); // 02_arithmetic
        assertEquals("5", TeeFormat.display(10.0 / 2.0));                  // whole double drops .0
        assertEquals("3", TeeFormat.display(1.0 + 2.0));                   // LANGUAGE.md §Number
        assertEquals("0", TeeFormat.display(0.0));
        assertEquals("-5", TeeFormat.display(-5));
    }

    @Test void scalarsAndEmpties() {
        assertEquals("hello", TeeFormat.display("hello"));   // top-level string: bare
        assertEquals("true", TeeFormat.display(true));
        assertEquals("false", TeeFormat.display(false));
        assertEquals("{}", TeeFormat.display(Values.emptyObject()));
        assertEquals("[]", TeeFormat.display(list()));
    }

    @Test void arraysDisplay() {
        assertEquals("[ 1, 2, 3 ]", TeeFormat.display(list(1, 2, 3)));            // 11_arrays
        assertEquals("[ 'a', 'b', 'c' ]", TeeFormat.display(list("a", "b", "c"))); // 13_strings
        assertEquals("[ 1, 2, 'hello' ]", TeeFormat.display(list(1, 2, "hello"))); // LANGUAGE.md
    }

    @Test void objectsDisplay() {
        assertEquals("{ \"x\": 1, \"y\": 99, \"z\": 3 }",
                TeeFormat.display(obj("x", 1, "y", 99, "z", 3)));                  // 82_map_extensions
        assertEquals("{ \"name\": 'Alice', \"age\": 30 }",
                TeeFormat.display(obj("name", "Alice", "age", 30)));              // LANGUAGE.md line 1083
    }

    @Test void jsonCompactAndEscaped() {
        assertEquals("{\"name\":\"Alice\",\"age\":30}",
                TeeFormat.json(obj("name", "Alice", "age", 30)));                 // 84_json
        assertEquals("[1,2,3]", TeeFormat.json(list(1, 2, 3)));
        assertEquals("\"hello\"", TeeFormat.json("hello"));
        assertEquals("42", TeeFormat.json(42));
        assertEquals("true", TeeFormat.json(true));
        assertEquals("{}", TeeFormat.json(Values.emptyObject()));
        assertEquals("\"a\\\"b\\\\c\"", TeeFormat.json("a\"b\\c"));               // escaping
    }
}
