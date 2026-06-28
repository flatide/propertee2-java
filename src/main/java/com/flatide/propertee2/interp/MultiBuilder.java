package com.flatide.propertee2.interp;

import com.flatide.propertee2.value.TeeError;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects {@code thread} statements during a {@code multi} setup phase. Named/dynamic keys must be
 * unique (LANGUAGE.md §thread); unnamed (or empty-string) threads get {@code "#1"}, {@code "#2"}, …
 * by 1-based position among unnamed threads only (51_multi_auto_keys).
 */
final class MultiBuilder {

    /** A captured thread: resolved key + function name + already-evaluated args (captured at spawn time). */
    record Pending(String key, String funcName, List<Object> args, int line, int col) {}

    final List<Pending> pending = new ArrayList<>();
    private final Set<String> usedKeys = new HashSet<>();
    private int unnamed = 0;

    /** Add a thread; {@code keyOrEmpty} null or "" means unnamed. Throws on a duplicate explicit key. */
    void add(String keyOrEmpty, String funcName, List<Object> args, int line, int col) {
        String key;
        if (keyOrEmpty == null || keyOrEmpty.isEmpty()) {
            key = "#" + (++unnamed);
        } else {
            key = keyOrEmpty;
            if (!usedKeys.add(key)) {
                throw new TeeError("Duplicate result key '" + key + "' in multi block", line, col);
            }
        }
        pending.add(new Pending(key, funcName, args, line, col));
    }
}
