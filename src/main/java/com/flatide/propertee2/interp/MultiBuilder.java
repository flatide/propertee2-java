package com.flatide.propertee2.interp;

import com.flatide.propertee2.parser.ProperTeeParser.FunctionCallContext;
import com.flatide.propertee2.value.TeeError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects {@code thread} statements during a {@code multi} setup phase. Named/dynamic keys must be
 * unique (LANGUAGE.md §thread); unnamed (or empty-string) threads get {@code "#1"}, {@code "#2"}, …
 * by 1-based position among unnamed threads only (51_multi_auto_keys). An auto-generated key that
 * collides with an explicit key is its own error, reported at the function-call position (61).
 */
final class MultiBuilder {

    /**
     * A captured thread: resolved key, function name, already-evaluated args (captured at spawn time),
     * and the call context — so dispatch errors (unknown function / arity) point at the thread's call site.
     */
    record Pending(String key, String funcName, List<Object> args, FunctionCallContext call) {}

    final List<Pending> pending = new ArrayList<>();
    private final Set<String> usedKeys = new HashSet<>();
    private int unnamed = 0;

    /** Add a named/dynamic-keyed thread. Duplicate explicit key → error at the thread-statement position. */
    void addNamed(String key, String funcName, List<Object> args, FunctionCallContext call, int line, int col) {
        if (!usedKeys.add(key)) {
            throw new TeeError("Duplicate result key '" + key + "' in multi block", line, col);
        }
        pending.add(new Pending(key, funcName, args, call));
    }

    /** Add an unnamed thread (auto-key {@code #N}). A collision with an explicit key → error at (line, col). */
    void addUnnamed(String funcName, List<Object> args, FunctionCallContext call, int line, int col) {
        String key = "#" + (++unnamed);
        if (!usedKeys.add(key)) {
            throw new TeeError("Auto-generated key '" + key + "' conflicts with an explicit key in multi block",
                    line, col);
        }
        pending.add(new Pending(key, funcName, args, call));
    }
}
