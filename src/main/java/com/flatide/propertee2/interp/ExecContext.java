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
 *   <li>{@code MONITOR} — a monitor tick: read-only, with the live result collection injected under
 *       the result-var name; any assignment is an error.</li>
 * </ul>
 */
final class ExecContext {

    enum Mode { NORMAL, WORKER, MONITOR }

    final Mode mode;
    final Map<String, Object> globalsView;            // live globals (NORMAL) or snapshot (WORKER/MONITOR)
    final Map<String, Object> injected;               // MONITOR: {resultVarName -> live collection}; else null
    final Deque<Map<String, Object>> frames = new ArrayDeque<>();      // local call frames
    final Deque<MultiBuilder> setupBuilders = new ArrayDeque<>();      // active multi setup phases

    private ExecContext(Mode mode, Map<String, Object> globalsView, Map<String, Object> injected) {
        this.mode = mode;
        this.globalsView = globalsView;
        this.injected = injected;
    }

    static ExecContext normal(Map<String, Object> globals) {
        return new ExecContext(Mode.NORMAL, globals, null);
    }

    static ExecContext worker(Map<String, Object> snapshot) {
        return new ExecContext(Mode.WORKER, snapshot, null);
    }

    static ExecContext monitor(Map<String, Object> snapshot, Map<String, Object> injected) {
        return new ExecContext(Mode.MONITOR, snapshot, injected);
    }
}
