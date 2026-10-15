package com.flatide.propertee2.interp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.flatide.propertee2.value.JsonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link Engine#execute} and {@link Engine.Outcome} — what a run leaves for its host (0.34.0). */
class EngineOutcomeTest {

    private final List<String> out = new ArrayList<>();
    private final List<String> err = new ArrayList<>();

    private Engine.Outcome execute(String source) {
        return new Engine().execute(source, Map.of(), out::add, err::add);
    }

    @Test void topLevelReturnIsTheResult() {
        Engine.Outcome o = execute("PRINT(\"hello\")\nresult = 1\nreturn [true, \"x\"]\nPRINT(\"never\")");
        assertTrue(o.hasExplicitReturn());
        assertEquals(List.of(true, "x"), o.returnValue());
        assertEquals(List.of(true, "x"), o.result());              // an explicit return beats `result`
        assertEquals(List.of("hello"), out);                       // output still streams as usual
    }

    @Test void withoutReturnTheResultGlobalIsTheResult() {
        Engine.Outcome o = execute("result = {\"n\": 2}\nfunction f() do\n    result = 9\n    return 0\nend\nf()");
        assertFalse(o.hasExplicitReturn());
        assertEquals(Map.of("n", 2), o.result());                  // a function's local `result` does not count
    }

    @Test void withNeitherTheResultIsTheEmptyObject() {
        Engine.Outcome o = execute("x = 1");
        assertFalse(o.hasExplicitReturn());
        assertEquals(Map.of(), o.result());                        // absence is {}, never Java null
        assertEquals(1, o.globals().get("x"));
    }

    @Test void bareReturnAndNullAreDeliberateResults() {
        assertEquals(Map.of(), execute("result = 5\nreturn").result());   // bare return: {} — and it wins
        assertEquals(JsonNull.NULL, execute("return null").result());     // the null value, kept as data
    }
}
