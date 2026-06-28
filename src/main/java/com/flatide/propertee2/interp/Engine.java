package com.flatide.propertee2.interp;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.value.TeeError;

import java.util.Map;

/**
 * Top-level entry point: parse + interpret a ProperTee script and return its stdout.
 * A {@link TeeError} surfaces as the v1 line: {@code Runtime error: Runtime Error at line L:C: <msg>},
 * appended after whatever was already printed (the error fixtures assert exactly this).
 */
public final class Engine {

    /** Run with no host-injected properties. */
    public String run(String source) {
        return run(source, Map.of());
    }

    /** Run with host-injected built-in properties (the {@code -p} flag / {@code _PROPS}). */
    public String run(String source, Map<String, Object> props) {
        StringBuilder out = new StringBuilder();
        try {
            ProperTeeParser.RootContext root = Parsing.parse(source);
            new Interpreter(out, props).run(root);
        } catch (TeeError e) {
            out.append("Runtime error: ").append(e.positioned()).append('\n');
        }
        return out.toString();
    }
}
