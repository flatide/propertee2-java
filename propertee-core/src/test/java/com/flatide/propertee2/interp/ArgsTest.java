package com.flatide.propertee2.interp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * spec v0.20.0 — {@code _ARGS}: the host-API corners around fixtures 130/131 (which pin the
 * script-visible semantics: strings, 1-based, iterable, worker {@code ::_ARGS}, empty default).
 */
class ArgsTest {

    /** setArgs feeds _ARGS; the engine-level default is the empty array. */
    @Test void setArgsFeedsTheArray() {
        assertEquals("2 a b\n", new Engine().setArgs(List.of("a", "b"))
                .run("PRINT(LEN(_ARGS), _ARGS.1, _ARGS.2)"));
        assertEquals("0\n", new Engine().run("PRINT(LEN(_ARGS))"));
    }

    /** A host-injected _ARGS property wins over setArgs — the same precedence rule as _PROPS. */
    @Test void propsSuppliedArgsWinOverSetArgs() {
        String out = new Engine().setArgs(List.of("cli"))
                .run("PRINT(_ARGS.1)", Map.of("_ARGS", List.of("prop-wins")));
        assertEquals("prop-wins\n", out);
    }

    /** _ARGS does not leak into _PROPS — the documented iterate/dump pattern stays pure. */
    @Test void argsStayOutOfProps() {
        String out = new Engine().setArgs(List.of("a"))
                .run("PRINT(LEN(_PROPS))\nPRINT(HAS_KEY(_PROPS, \"_ARGS\"))\nPRINT(HAS_KEY(_PROPS, \"w\"))",
                        Map.of("w", 1));
        assertEquals("1\nfalse\ntrue\n", out);
    }

    /** Sessions opened from the engine inherit its args (the -i REPL path). */
    @Test void sessionInheritsEngineArgs() {
        java.util.List<String> out = new java.util.ArrayList<>();
        Engine.Session s = new Engine().setArgs(List.of("x", "y")).session(Map.of(), out::add, out::add);
        assertEquals("y", s.eval("_ARGS.2"));
    }
}
