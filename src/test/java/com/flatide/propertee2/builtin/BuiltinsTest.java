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

    @Test void errorsAndGating() {
        assertThrows(TeeError.class, () -> call("UNKNOWN_FN", 1));
        assertThrows(TeeError.class, () -> call("ABS", "not-a-number"));
        // host-gated / blocking builtins are not registered as pure-callable in PA
        b.register("SHELL", Builtins.Kind.BLOCKING, a -> "x");
        assertThrows(TeeError.class, () -> call("SHELL", "echo"));
    }
}
