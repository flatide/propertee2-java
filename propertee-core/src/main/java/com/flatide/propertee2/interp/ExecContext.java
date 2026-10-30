package com.flatide.propertee2.interp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
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
    final int threadId;                                      // 0 main, positive multi worker, -1 monitor
    final String threadName;
    final Map<String, Object> globalsView;            // live globals (NORMAL) or snapshot (WORKER/MONITOR)
    final Map<String, Object> rootGlobalsView;
    final Map<String, Map<String, Object>> moduleGlobalsViews;
    final ModuleInstance module;                              // null while entry code is executing
    final Deque<Map<String, Object>> frames;                  // local call frames
    final Deque<MultiBuilder> setupBuilders;                  // active multi setup phases
    final Deque<DebugCallSite> callStack;                     // debugger :where — outermost first
    final WorkerShellObservation shellObservation;                     // monitored WORKER only
    final Deque<Interpreter.SetTracker> setTrackers = new ArrayDeque<>();   // open `otherwise` blocks that may set a variable
    int calls;                                                // script function calls running, for setTrackers

    private ExecContext(Mode mode, int threadId, String threadName,
                        Map<String, Object> globalsView,
                        Map<String, Object> rootGlobalsView,
                        Map<String, Map<String, Object>> moduleGlobalsViews,
                        ModuleInstance module,
                        Deque<Map<String, Object>> frames,
                        Deque<MultiBuilder> setupBuilders,
                        Deque<DebugCallSite> callStack,
                        WorkerShellObservation shellObservation) {
        this.mode = mode;
        this.threadId = threadId;
        this.threadName = threadName;
        this.globalsView = globalsView;
        this.rootGlobalsView = rootGlobalsView;
        this.moduleGlobalsViews = moduleGlobalsViews;
        this.module = module;
        this.frames = frames;
        this.setupBuilders = setupBuilders;
        this.callStack = callStack;
        this.shellObservation = shellObservation;
    }

    static ExecContext normal(Map<String, Object> globals,
                              Map<String, Map<String, Object>> moduleGlobals) {
        return new ExecContext(Mode.NORMAL, 0, "main", globals, globals, moduleGlobals, null,
                new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>(), null);
    }

    static ExecContext worker(Map<String, Object> rootSnapshot,
                              Map<String, Map<String, Object>> moduleSnapshots,
                              ModuleInstance module, int threadId, String threadName,
                              WorkerShellObservation shellObservation) {
        Map<String, Object> current = module == null
                ? rootSnapshot : moduleSnapshots.get(module.identity());
        return new ExecContext(Mode.WORKER, threadId, threadName, current, rootSnapshot,
                moduleSnapshots, module, new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>(),
                shellObservation);
    }

    static ExecContext monitor(Map<String, Object> rootSnapshot,
                               Map<String, Map<String, Object>> moduleSnapshots,
                               ModuleInstance module) {
        Map<String, Object> current = module == null
                ? rootSnapshot : moduleSnapshots.get(module.identity());
        return new ExecContext(Mode.MONITOR, -1, "monitor", current, rootSnapshot,
                moduleSnapshots, module, new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>(), null);
    }

    /** Enter a module without exposing the caller's locals; scheduler/debugger state stays shared. */
    ExecContext forModule(ModuleInstance target) {
        Map<String, Object> targetGlobals = moduleGlobalsViews.get(target.identity());
        if (targetGlobals == null) throw new IllegalStateException("missing globals for " + target.identity());
        return new ExecContext(mode, threadId, threadName, targetGlobals, rootGlobalsView,
                moduleGlobalsViews, target, new ArrayDeque<>(), setupBuilders, callStack, shellObservation);
    }
}
