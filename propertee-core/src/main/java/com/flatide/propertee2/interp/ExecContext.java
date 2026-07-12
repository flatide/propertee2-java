package com.flatide.propertee2.interp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Per-fiber execution state (design §5 — carried in a {@link ScopedValue}, not shared instance
 * fields, so concurrent {@code multi} workers don't corrupt each other's scopes).
 *
 * <ul>
 *   <li>{@code NORMAL} — the main program / a plain function call: {@code globalsView} is the live
 *       globals; plain top-level vars read/write globals.</li>
 *   <li>{@code WORKER} — a {@code multi} thread body: {@code globalsView} is a READ-ONLY snapshot;
 *       {@code ::} reads it, {@code ::}-writes are an error (purity); plain vars are local.</li>
 *   <li>{@code MONITOR} — one watchdog iteration (spec v0.16.0): same purity as WORKER; each
 *       iteration runs in a fresh base frame holding a deep-copied capture of the result
 *       collection under the result-var name (the caller pushes it before executing the body).</li>
 * </ul>
 */
final class ExecContext {

    enum Mode { NORMAL, WORKER, MONITOR }

    final Mode mode;
    final Map<String, Object> globalsView;            // live globals (NORMAL) or snapshot (WORKER/MONITOR)
    final Deque<Map<String, Object>> frames = new ArrayDeque<>();      // local call frames
    final Deque<MultiBuilder> setupBuilders = new ArrayDeque<>();      // active multi setup phases

    private ExecContext(Mode mode, Map<String, Object> globalsView) {
        this.mode = mode;
        this.globalsView = globalsView;
    }

    static ExecContext normal(Map<String, Object> globals) {
        return new ExecContext(Mode.NORMAL, globals);
    }

    static ExecContext worker(Map<String, Object> snapshot) {
        return new ExecContext(Mode.WORKER, snapshot);
    }

    static ExecContext monitor(Map<String, Object> snapshot) {
        return new ExecContext(Mode.MONITOR, snapshot);
    }
}
