package com.flatide.propertee2.interp;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.host.DefaultPlatformProvider;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.value.TeeError;

import java.util.Map;

/**
 * Top-level entry point: parse a ProperTee script and run it as the ROOT fiber of a {@link Coop}
 * cooperative runtime (design §1 — the main program is one logical thread = one vthread). Returns
 * stdout; a {@link TeeError} surfaces as the v1 line
 * {@code Runtime error: Runtime Error at line L:C: <msg>}, appended after whatever was printed.
 */
public final class Engine {

    /** Run with no host-injected properties and the default platform. */
    public String run(String source) {
        return run(source, Map.of());
    }

    /** Run with host-injected built-in properties ({@code -p} / {@code _PROPS}) and the default platform. */
    public String run(String source, Map<String, Object> props) {
        return run(source, props, new DefaultPlatformProvider());
    }

    /** Run with explicit host integration (used by the conformance harness for ENV / file I/O). */
    public String run(String source, Map<String, Object> props, PlatformProvider platform) {
        StringBuilder out = new StringBuilder();
        try {
            ProperTeeParser.RootContext root = Parsing.parse(source);
            Coop coop = new Coop();
            Interpreter interp = new Interpreter(out, props, coop, platform);
            coop.run("main", () -> interp.run(root));   // the program is the root logical thread
        } catch (TeeError e) {
            out.append("Runtime error: ").append(e.positioned()).append('\n');
        }
        return out.toString();
    }
}
