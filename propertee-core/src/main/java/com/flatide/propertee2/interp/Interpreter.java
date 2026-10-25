package com.flatide.propertee2.interp;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.builtin.Builtins;
import com.flatide.propertee2.coop.AbortError;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.coop.Fiber;
import com.flatide.propertee2.coop.FiberState;
import com.flatide.propertee2.coop.Scheduler;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.module.ModuleResolver;
import com.flatide.propertee2.parser.ProperTeeParser.*;
import com.flatide.propertee2.value.JsonNull;
import com.flatide.propertee2.value.LazyLines;
import com.flatide.propertee2.value.LineReader;
import com.flatide.propertee2.value.Result;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.TeeResult;
import com.flatide.propertee2.value.Values;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The recursive tree-walk interpreter (design §2 — NO stepper / replay machine).
 * Covers the SEQUENTIAL language: scopes, arithmetic/comparison/logic, access &amp; indexing,
 * literals, ranges, control flow, functions, builtins, and v1-exact value semantics
 * (no-null/{}, 1-based, deepCopy/COW, strict types). Cooperative concurrency
 * (multi/thread/SLEEP/monitor) and host-gated/blocking builtins land in PC/PD.
 */
public final class Interpreter {

    /** One line of program output (no trailing newline). Lets a host stream PRINT live (façade) or buffer it. */
    public interface Sink { void line(String text); }

    private final Sink out;
    private Sink err;   // runtime diagnostics — v1's stderr channel ([THREAD ERROR]/[MONITOR ERROR]/loop warnings)
    private final Coop coop;
    private final Map<String, Object> globals = new LinkedHashMap<>();   // the program's top-level globals
    private final Map<String, Object> builtinProps;          // host-injected (-p), incl. _PROPS
    private final Map<String, UserFunction> functions = new LinkedHashMap<>();
    private final Map<String, ModuleInstance> moduleAliases = new LinkedHashMap<>();
    private final Map<String, ModuleInstance> modulesByIdentity = new LinkedHashMap<>();
    private final Builtins builtins;
    private final Map<String, ExternalFunction> externals;   // host-registered external functions
    private final Set<String> hiddenKeywords;                // keywords made unavailable in this environment
    private final Set<String> ignoredFunctions;              // function names made unavailable
    private int maxIterations = 1000;                        // max loop iterations (configurable via façade)
    private boolean loopLimitWarns = false;                  // v1 "warn": warn + stop the loop instead of failing
    private boolean hasExplicitReturn = false;               // did the program hit a top-level `return`?
    private Object returnValue = Values.emptyObject();        // the top-level return value (if any)

    // Per-fiber execution state (frames, globals view, mode) — isolated across multi workers (§5).
    private static final ScopedValue<ExecContext> CTX = ScopedValue.newInstance();
    private ExecContext ec() { return CTX.get(); }

    /** Façade-friendly constructor: no host externals / hidden keywords (custom builtins go via addRawBuiltin). */
    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       com.flatide.propertee2.task.TaskRunner taskRunner, String runId) {
        this(out, props, coop, platform, new LinkedHashMap<>(), Set.of(), Set.of(), taskRunner, runId);
    }

    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       Map<String, ExternalFunction> externals, Set<String> hiddenKeywords,
                       Set<String> ignoredFunctions, com.flatide.propertee2.task.TaskRunner taskRunner) {
        this(out, props, coop, platform, externals, hiddenKeywords, ignoredFunctions, taskRunner, null);
    }

    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       Map<String, ExternalFunction> externals, Set<String> hiddenKeywords,
                       Set<String> ignoredFunctions, com.flatide.propertee2.task.TaskRunner taskRunner, String runId) {
        this.out = out;
        this.err = out;   // single merged channel unless the host separates them (Engine.run buffers both in order)
        this.coop = coop;
        this.builtins = Builtins.standard(platform, taskRunner, runId);
        this.builtins.setIoGate(ioGate);   // FILE_LINES reads release the baton where that is safe
        this.externals = externals;
        this.hiddenKeywords = hiddenKeywords;
        this.ignoredFunctions = ignoredFunctions;
        this.builtinProps = new LinkedHashMap<>();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) {
                String nonFinite = Values.hostNonFinite(e.getValue(), e.getKey());   // spec v0.36.0
                if (nonFinite != null) throw new TeeError(nonFinite);
                builtinProps.put(e.getKey(), Values.deepCopy(e.getValue()));
            }
            // _PROPS: all inputs as one object (LANGUAGE.md §_PROPS), unless the caller supplied it.
            if (!builtinProps.containsKey("_PROPS")) {
                Map<String, Object> all = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : props.entrySet()) all.put(e.getKey(), Values.deepCopy(e.getValue()));
                builtinProps.put("_PROPS", all);
            }
        }
        // _ARGS (spec v0.20.0): positional invocation arguments as a string array. Never absent —
        // the empty array is the no-arguments value (no implicit null). A property named _ARGS
        // supplied by the host wins, the same precedence rule as _PROPS above.
        argsFromProps = builtinProps.containsKey("_ARGS");
        if (!argsFromProps) builtinProps.put("_ARGS", new ArrayList<>());
    }

    private final boolean argsFromProps;

    /**
     * Host entry for the {@code _ARGS} array (spec v0.20.0): the positional arguments after the
     * script on the command line (or a host's equivalent), as strings, in order. Ignored when the
     * host injected an {@code _ARGS} property explicitly (the {@code _PROPS} precedence rule).
     */
    public void setArgs(List<String> args) {
        if (argsFromProps || args == null) return;
        builtinProps.put("_ARGS", new ArrayList<Object>(args));
    }

    public void run(RootContext root) {
        resetStepState();
        entrySourceId = sourceId(root);
        ScopedValue.where(CTX, ExecContext.normal(globals, liveModuleGlobals())).run(() -> execProgram(root));
    }

    private void execProgram(RootContext root) {
        try {
            for (StatementContext s : root.statement()) { exec(s); coop.yield_(); }
        } catch (Signals.Return r) {
            hasExplicitReturn = true;          // top-level return: stop, remember the value (value-model §8)
            returnValue = Values.materialize(r.value);   // the host's result: a FILE_LINES array is read in full
        } catch (Signals.Break | Signals.Continue b) {
            // break/continue outside a loop: ignore
        }
    }

    /**
     * REPL support (0.18.0): run one snippet against this interpreter's persistent state — globals
     * and user functions accumulate across calls. Statement semantics are identical to {@link #run};
     * the only addition is the return value, for the host to echo: the value of a trailing bare
     * expression statement, or of a top-level {@code return} — Java {@code null} when the snippet
     * ends in neither (the ProperTee {@code null} value is {@link com.flatide.propertee2.value.JsonNull#NULL},
     * so {@code null} is a safe "nothing to echo" sentinel).
     */
    public Object runSnippet(RootContext root) {
        return runSnippet(root, false);
    }

    /**
     * {@link #runSnippet(RootContext)} with an optional entry pause (0.31.0): when
     * {@code pauseAtEntry} is true and a debug handler is attached, the debugger stops before the
     * snippet's first root statement (a {@code Reason.STEP} stop), so a host can restart a loaded
     * script under the debugger without editing a {@code debug} statement into it. Without a
     * handler the flag is ignored and the snippet runs normally.
     */
    public Object runSnippet(RootContext root, boolean pauseAtEntry) {
        resetStepState();
        entryPausePending = pauseAtEntry && debugHandler != null;
        entrySourceId = sourceId(root);
        Object[] echo = new Object[1];
        try {
            ScopedValue.where(CTX, ExecContext.normal(globals, liveModuleGlobals()))
                    .run(() -> echo[0] = execSnippet(root));
        } finally {
            entryPausePending = false;   // an empty/definition-free snippet must not leak the pause
        }
        return echo[0];
    }

    /** Install fully validated imports before execution; entry statements never observe partial loads. */
    void installModules(LoadedModules loaded) {
        // Preflight the persistent-session namespace before mutating either map. A rejected REPL
        // snippet must not leave the aliases resolved earlier in that snippet installed.
        //
        // Re-binding an existing alias is allowed when the module id matches: re-running a loaded
        // script re-resolves its imports against the CURRENT stored content — fresh instance,
        // empty globals, possibly a newer version (the current-content semantics TeeBox debug
        // re-runs pinned). A different module id under the same alias is a conflict, and a NEW
        // alias may not duplicate an identity that stays bound to another alias.
        for (Map.Entry<String, ModuleInstance> e : loaded.aliases().entrySet()) {
            ModuleInstance existing = moduleAliases.get(e.getKey());
            if (existing != null
                    && !existing.source.moduleId().equals(e.getValue().source.moduleId())) {
                throw new TeeError("Import alias '" + e.getKey() + "' is already bound to module '"
                        + existing.source.moduleId() + "'");
            }
        }
        for (ModuleInstance module : loaded.instances()) {
            ModuleInstance existing = modulesByIdentity.get(module.identity());
            if (existing == null) continue;
            String boundAlias = aliasOf(existing);
            if (boundAlias != null && !loaded.aliases().containsKey(boundAlias)) {
                throw new TeeError("Duplicate import of module '" + module.identity() + "'");
            }
        }
        for (Map.Entry<String, ModuleInstance> e : loaded.aliases().entrySet()) {
            ModuleInstance old = moduleAliases.get(e.getKey());
            if (old != null) modulesByIdentity.remove(old.identity());   // rebind retires the old instance
        }
        moduleAliases.putAll(loaded.aliases());
        for (ModuleInstance module : loaded.instances()) modulesByIdentity.put(module.identity(), module);
    }

    /** The alias currently bound to this instance (identities and aliases are one-to-one). */
    private String aliasOf(ModuleInstance instance) {
        for (Map.Entry<String, ModuleInstance> e : moduleAliases.entrySet()) {
            if (e.getValue() == instance) return e.getKey();
        }
        return null;
    }

    /** Resolve, validate, and atomically install the entry's direct imports. */
    public void resolveModules(RootContext root, ModuleResolver resolver) {
        installModules(ModuleLoader.load(root, resolver, hiddenKeywords, ignoredFunctions));
    }

    private Map<String, Map<String, Object>> liveModuleGlobals() {
        Map<String, Map<String, Object>> views = new LinkedHashMap<>();
        modulesByIdentity.forEach((identity, module) -> views.put(identity, module.globals));
        return views;
    }

    private Object execSnippet(RootContext root) {
        try {
            List<StatementContext> stmts = root.statement();
            Object last = null;
            for (int i = 0; i < stmts.size(); i++) {
                StatementContext s = stmts.get(i);
                if (i == stmts.size() - 1 && s instanceof ExprStmtContext e) {
                    // bypasses exec() for the echo, so the debugger hook must fire here too
                    if (debugHandler != null) debugStep(s);
                    last = eval(e.expression());
                } else {
                    exec(s);
                }
                coop.yield_();
            }
            return last;
        } catch (Signals.Return r) {
            hasExplicitReturn = true;
            returnValue = Values.materialize(r.value);
            return returnValue;
        } catch (Signals.Break | Signals.Continue b) {
            return null;                       // break/continue outside a loop: ignore (as in run)
        }
    }

    // ---- Façade hooks (used by the v1-compat com.flatide.propertee2.interpreter layer) -----------------------

    /**
     * The program's top-level globals — a host may inject before {@link #run} and read after (e.g.
     * `result`). The map is the live one, so an injected value goes in as is: pass it through
     * {@link Values#deepCopy} first, which gives a host {@code Long} its integer box, and do not
     * inject Infinity or NaN ({@link Values#hostNonFinite}, spec v0.36.0).
     */
    public Map<String, Object> globals() { return globals; }

    /** True if the program ended with an explicit top-level {@code return}. */
    public boolean hasExplicitReturn() { return hasExplicitReturn; }

    /** The top-level return value (or empty object if none). */
    public Object returnValue() { return returnValue; }

    /** Configure the maximum loop iterations (v1 maxIterations). */
    public void setLoopLimit(int limit) { if (limit > 0) this.maxIterations = limit; }

    /** FILE_LINES limits (spec v0.26.0): the whole-use limit and the longest line, in bytes. */
    public void setFileLinesLimits(long loadLimitBytes, int maxLineBytes) {
        builtins.setFileLinesLimits(loadLimitBytes, maxLineBytes);
    }

    /**
     * Route runtime diagnostics — {@code [THREAD ERROR]}, {@code [MONITOR ERROR]}, and loop-limit
     * warnings — to a separate channel, matching v1's stderr print sink. Defaults to the main sink.
     */
    public void setErrorSink(Sink err) { if (err != null) this.err = err; }

    /**
     * v1 {@code iterationLimitBehavior="warn"}: on hitting the loop limit, print a warning on the
     * error channel and stop that loop (the program continues) instead of raising a runtime error.
     */
    public void setLoopLimitWarns(boolean warns) { this.loopLimitWarns = warns; }

    /**
     * Register a host builtin whose return value is used without a Result wrapper, e.g. STREAM_FILE.
     * The value is deep-copied like an external's, which also gives a host's {@code Long} its
     * integer box — one beyond the range becomes the nearest decimal (spec v0.33.0).
     */
    public void addRawBuiltin(String name, java.util.function.Function<List<Object>, Object> fn) {
        builtins.register(Engine.requireReplaceableName(name), Builtins.Kind.PURE, args -> {
            Object v = fn.apply(args);
            String nonFinite = Values.hostNonFinite(v, name + "() result");     // spec v0.36.0
            if (nonFinite != null) throw new TeeError(nonFinite);
            return Values.deepCopy(v);
        });
    }

    /**
     * Register a host function that may block (image/network/disk work). It runs OFF the baton via
     * {@code Coop.blocking} (§3.1, so it never stalls the cooperative scheduler), with its return value
     * wrapped in {@code Result.ok} and a thrown exception in {@code Result.error} — args and return are
     * deep-copied for isolation. Use this (not {@link #addRawBuiltin}) for anything slow.
     */
    public void addBlockingExternal(String name, java.util.function.Function<List<Object>, Object> fn) {
        externals.put(Engine.requireReplaceableName(name), new ExternalFunction(fn, true));
    }

    /** Observes multi worker logical-thread lifecycle, so a host (façade) can surface them to a listener. */
    public interface ThreadObserver {
        void created(int id, String name, Integer parentId, String resultKeyName);
        void completed(int id, Object result);
        void error(int id, Throwable error);
    }

    private ThreadObserver threadObserver = null;
    private final java.util.concurrent.atomic.AtomicInteger workerIdSeq =
            new java.util.concurrent.atomic.AtomicInteger(0);          // 0 reserved for the main thread

    /** Observe multi worker threads (created/completed/error). The program's main thread is the host's id 0. */
    public void setThreadObserver(ThreadObserver observer) { this.threadObserver = observer; }

    private boolean inFunction() { return !ec().frames.isEmpty(); }
    private Map<String, Object> localTop() { return ec().frames.peek(); }
    private Map<String, Object> currentScope() { return inFunction() ? localTop() : ec().globalsView; }

    // ====================================================================
    // Statements
    // ====================================================================

    private void exec(StatementContext s) {
        if (debugHandler != null) debugStep(s);   // 0.23.0 stepping/breakpoints; zero cost unarmed
        switch (s) {
            case AssignStmtContext a   -> execAssign(a.assignment());
            case IfStmtContext i       -> execIf(i.ifStatement());
            case IterStmtContext it    -> execLoop(it.iterationStmt());
            case FuncDefStmtContext f  -> defineFunction(f.functionDef());
            case FlowStmtContext fl    -> execFlow(fl.flowControl());
            case ExprStmtContext e     -> eval(e.expression());
            case ParallelExecStmtContext p -> execMulti(p.parallelStmt());
            case SpawnExecStmtContext sp   -> execSpawn(sp.spawnStmt());
            default -> throw err("unsupported statement", s);
        }
    }

    /** Run a block, yielding at each statement boundary so multi workers interleave (design §6, §70). */
    private void execBlock(BlockContext block) {
        for (StatementContext s : block.statement()) { exec(s); coop.yield_(); }
    }

    // ====================================================================
    // multi / thread / monitor  (design §4; LANGUAGE.md §Multi Blocks)
    // ====================================================================

    /** A {@code thread key: f(args)} statement — valid only while collecting a multi setup phase. */
    private void execSpawn(SpawnStmtContext sp) {
        requireKeyword("thread", sp.getStart());
        MultiBuilder builder = ec().setupBuilders.peek();
        if (builder == null) throw err("thread can only be used inside multi blocks", sp);
        SpawnKeyStmtContext k = (SpawnKeyStmtContext) sp;
        FunctionCallContext fc = k.functionCall();
        String funcName = fc.funcName.getText();
        ModuleInstance targetModule = resolveCallModule(fc);
        List<Object> args = new ArrayList<>();
        for (ExpressionContext arg : fc.expression()) args.add(Values.deepCopy(eval(arg))); // captured at spawn time

        String key = k.access() != null ? objectKey(k.access()) : "";     // no key or "" -> unnamed (#N)
        if (key.isEmpty()) {
            // auto-key conflicts report at the function-call position (61_duplicate_auto_key)
            builder.addUnnamed(funcName, targetModule, args, fc,
                    fc.getStart().getLine(), fc.getStart().getCharPositionInLine());
        } else {
            builder.addNamed(key, funcName, targetModule, args, fc,
                    sp.getStart().getLine(), sp.getStart().getCharPositionInLine());
        }
    }

    private void execMulti(ParallelStmtContext m) {
        requireKeyword("multi", m.getStart());
        ExecContext c = ec();

        // 0. optional concurrency cap (spec v0.19.0): evaluated once, in textual order — before
        //    the snapshot and the setup phase. Nominal typing applies (`limit 2.0` is an error
        //    even though the value is whole); there is no "0 = unlimited".
        int limit = Integer.MAX_VALUE;
        if (m.limitExpr != null) {
            Object v = eval(m.limitExpr);
            if (!Values.isInteger(v) || ((Number) v).longValue() <= 0) throw err("multi limit must be a positive integer", m.limitExpr);
            limit = (int) Math.min(((Number) v).longValue(), Integer.MAX_VALUE);
        }

        // 1. read-only global snapshot all threads share (design §4, thread purity), taken at
        //    multi ENTRY — before the setup phase — matching v1/js and the spec: a :: write in
        //    setup updates the real globals but is invisible to the workers/watchdog (fixture 121)
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) Values.deepCopy(c.rootGlobalsView);
        Map<String, Map<String, Object>> moduleSnapshots = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : c.moduleGlobalsViews.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> copied = (Map<String, Object>) Values.deepCopy(e.getValue());
            moduleSnapshots.put(e.getKey(), copied);
        }

        // 2. setup phase: isolated scope, collect thread statements
        MultiBuilder builder = new MultiBuilder();
        c.frames.push(new LinkedHashMap<>());
        c.setupBuilders.push(builder);
        try {
            execBlock(m.block());
        } finally {
            c.setupBuilders.pop();
            c.frames.pop();
        }

        // 3. pre-build the result collection with "running" entries, in spawn order
        Map<String, Object> collection = new LinkedHashMap<>();
        for (MultiBuilder.Pending p : builder.pending) collection.put(p.key(), Result.running());

        // 4. spawn workers (+ optional monitor), wait for all, in-place result updates
        runThreads(builder.pending, collection, snapshot, moduleSnapshots, c.module, m, limit);

        // 5. assign the collection to resultVar (current scope) — global at top level, local in a function
        if (m.resultVar != null) currentScope().put(m.resultVar.getText(), collection);
    }

    private void runThreads(List<MultiBuilder.Pending> pending, Map<String, Object> collection,
                            Map<String, Object> snapshot,
                            Map<String, Map<String, Object>> moduleSnapshots,
                            ModuleInstance enclosingModule, ParallelStmtContext m, int limit) {
        Scheduler s = coop.scheduler();
        Fiber parent = s.self();
        boolean hasMonitor = m.monitorClause() != null;
        AtomicInteger remaining = new AtomicInteger(pending.size());                               // workers only
        AtomicInteger parentPending = new AtomicInteger(pending.size() + (hasMonitor ? 1 : 0));    // gate parent resume
        AtomicReference<DebugQuit> debugQuit = new AtomicReference<>();
        Runnable signalParent = () -> { if (parentPending.decrementAndGet() == 0) s.wake(parent); };

        // Announce every worker up front, in spawn order — a not-yet-admitted worker under
        // `limit` is externally indistinguishable from a running one (spec v0.19.0: no new
        // status), so a host observer sees the full thread list from the start, as before.
        int[] tids = new int[pending.size()];
        WorkerShellObservation[] observations = hasMonitor && m.resultVar != null
                ? new WorkerShellObservation[pending.size()] : null;
        for (int i = 0; i < pending.size(); i++) {
            tids[i] = workerIdSeq.incrementAndGet();              // 1, 2, ... (the main thread is the host's id 0)
            MultiBuilder.Pending p = pending.get(i);
            if (observations != null) {
                observations[i] = new WorkerShellObservation(tids[i], p.funcName(), p.key());
            }
            if (threadObserver != null) threadObserver.created(tids[i], p.funcName(), 0, p.key());
        }

        // Admission gate (spec v0.19.0 `limit K`): at most `limit` workers exist concurrently.
        // The first K spawn below; each finishing worker admits the next in its finally —
        // on-baton and in spawn order, so admission is deterministic. The chain is also the
        // run-abort unwind path: a worker dying of AbortError still admits the next, which
        // aborts at its own first checkpoint, until every announced worker has reached a
        // terminal state — otherwise the parent would wait on parentPending forever.
        AtomicInteger nextToAdmit = new AtomicInteger(0);
        Runnable[] admitNext = new Runnable[1];
        admitNext[0] = () -> {
            int i = nextToAdmit.getAndIncrement();
            if (i >= pending.size()) return;
            MultiBuilder.Pending p = pending.get(i);
            int tid = tids[i];
            WorkerShellObservation observation = observations != null ? observations[i] : null;
            s.spawn(p.key(), () -> ScopedValue.where(
                    CTX, ExecContext.worker(snapshot, moduleSnapshots, p.module(),
                            tid, p.funcName(), observation)).run(() -> {
                try {
                    Object r = invokeNamed(p.funcName(), p.args(), p.call());
                    collection.put(p.key(), Result.ok(r));
                    if (threadObserver != null) threadObserver.completed(tid, r);
                } catch (DebugQuit e) {
                    // A debugger quit from a worker must end the WHOLE run, not become a thread
                    // error or disappear in the child fiber. Abort only this Scheduler (not the
                    // Coop latch, so Engine.Session remains reusable), then let the parent remap
                    // its AbortError back to the original DebugQuit below.
                    debugQuit.compareAndSet(null, e);
                    if (threadObserver != null) threadObserver.error(tid, e);
                    s.abort();
                    throw new AbortError();
                } catch (AbortError e) {
                    // Host abort, not a script error: no [THREAD ERROR], no result entry (the run is
                    // unwinding), but the observer must still see a terminal state — otherwise a
                    // cancelled run's thread list shows this worker as running forever.
                    if (threadObserver != null) threadObserver.error(tid, e);
                    throw e;
                } catch (RuntimeException e) {
                    // TeeError, or a stray control-flow signal / other failure: never leave the entry "running"
                    String pos = failureText(e);
                    err.line("[THREAD ERROR] " + pos);
                    collection.put(p.key(), Result.error(pos));
                    if (threadObserver != null) threadObserver.error(tid, e);
                } finally {
                    remaining.decrementAndGet();
                    admitNext[0].run();       // free the slot before releasing the parent
                    signalParent.run();
                }
            }));
        };
        int seats = Math.min(limit, pending.size());
        for (int i = 0; i < seats; i++) admitNext[0].run();

        if (hasMonitor) {
            MonitorClauseContext mc = m.monitorClause();
            long interval = parseIntLiteral(mc.INTEGER().getText(), mc);   // an INTEGER literal (the integer range)
            String resultVarName = m.resultVar != null ? m.resultVar.getText() : null;
            s.spawn("monitor", () -> {
                try {
                    // The watchdog thread (spec v0.16.0): each iteration runs like a function
                    // invocation — a fresh base frame seeded with a deep-copied CAPTURE of the
                    // result collection (a consistent snapshot even if the body suspends), same
                    // globals purity as workers.
                    Runnable tick = () -> {
                        ExecContext mctx = ExecContext.monitor(snapshot, moduleSnapshots, enclosingModule);
                        Map<String, Object> iterScope = new LinkedHashMap<>();
                        if (resultVarName != null) {
                            iterScope.put(resultVarName, monitorCollection(collection, pending, observations));
                        }
                        mctx.frames.push(iterScope);
                        ScopedValue.where(CTX, mctx).run(() -> {
                            try {
                                execBlock(mc.block());
                            } catch (TeeError e) {
                                err.line("[MONITOR ERROR] " + e.positioned());
                                throw e;
                            }
                        });
                    };
                    runMonitor(interval, remaining, tick);
                } finally {
                    signalParent.run();   // always release the parent, even if a monitor tick threw
                }
            });
        }

        try {
            s.awaitChildren(() -> parentPending.get() == 0);
        } catch (AbortError e) {
            DebugQuit requested = debugQuit.get();
            if (requested != null) throw requested;
            throw e;
        }
    }

    /**
     * Build the watchdog's capture without ever mutating the semantic result collection. Each
     * Result entry stays genuine; only its copied {@code value} is replaced by thread information.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> monitorCollection(Map<String, Object> collection,
                                                  List<MultiBuilder.Pending> pending,
                                                  WorkerShellObservation[] observations) {
        Map<String, Object> capture = (Map<String, Object>) Values.deepCopy(collection);
        if (observations == null) return capture;
        for (int i = 0; i < pending.size(); i++) {
            String key = pending.get(i).key();
            Object entryValue = capture.get(key);
            if (!(entryValue instanceof Map<?, ?> rawEntry)) continue;
            Map<String, Object> entry = (Map<String, Object>) rawEntry;
            String state = String.valueOf(entry.get("status"));
            Object returnValue = entry.get("value");
            entry.put("value", observations[i].monitorValue(state, returnValue));
        }
        return capture;
    }

    /**
     * Monitor loop (matches v1's observed tick counts — 20/32/56): a mid-tick fires only if the
     * workers aren't done after each sleep; on a tick error, mid-ticking stops but one final tick
     * still runs after all workers finish (so a failing monitor prints exactly twice — 32).
     */
    private void runMonitor(long interval, AtomicInteger remaining, Runnable tick) {
        boolean aborted = false;
        while (remaining.get() > 0) {
            coop.sleep(interval);
            if (remaining.get() == 0) break;
            if (!aborted) {
                try {
                    tick.run();
                } catch (RuntimeException e) {   // TeeError (already printed) or stray control-flow/runtime
                    aborted = true;              // stop mid-ticking
                }
            }
        }
        coop.checkpoint();   // an aborted run skips the guaranteed final tick (abort = stop everything now)
        try {
            tick.run();                          // final tick, once, after all workers are done
        } catch (RuntimeException ignored) {
            // already printed inside tick (for TeeError); otherwise just end the monitor cleanly
        }
    }

    /** Render a worker/monitor failure as a result/error string (positionless for non-interpreter failures). */
    private static String failureText(RuntimeException e) {
        if (e instanceof TeeError te) return te.positioned();
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "Runtime Error: " + msg;
    }

    private void execAssign(AssignmentContext a) {
        Object value = eval(a.expression());
        assign(a.lvalue(), value);
    }

    private void execIf(IfStatementContext i) {
        requireKeyword("if", i.getStart());
        if (condition(i.condition)) {
            execBlock(i.thenBody);
            return;
        }
        // spec v0.9.0 (#3): elseif arms — first true condition wins; later conditions unevaluated
        for (int n = 0; n < i.elseifConds.size(); n++) {
            if (condition(i.elseifConds.get(n))) {
                execBlock(i.elseifBodies.get(n));
                return;
            }
        }
        if (i.elseBody != null) {
            execBlock(i.elseBody);
        }
    }

    private void execFlow(FlowControlContext f) {
        // Hideable keywords are exactly if/loop/function/multi/thread/debug (LANGUAGE.md §Host
        // Environment Restrictions); break/continue/return are NOT hideable, matching v1.
        switch (f) {
            case BreakStmtContext b    -> throw Signals.Break.INSTANCE;
            case ContinueStmtContext c -> throw Signals.Continue.INSTANCE;
            case ReturnStmtContext r   ->
                    throw new Signals.Return(r.expression() != null ? eval(r.expression()) : Values.emptyObject());
            case DebugStmtContext d    -> execDebug(d);
            default -> throw err("unsupported flow statement", f);
        }
    }

    // ---- debug statement + stepping/breakpoints (host API 0.22.0/0.23.0 — the spec defines
    //      debug as "pauses in a debug mode"; a host with a handler attached IS a debug mode) ----

    private DebugHandler debugHandler;
    private boolean inDebugSession;   // one-baton-confined; suppresses re-entry while any fiber is paused
    private enum StepMode { NONE, IN, OVER, OUT }
    private StepMode stepMode = StepMode.NONE;
    private int stepDepth;            // frames.size() when the step was armed
    private int stepThreadId;         // step stays on the logical thread that armed it
    private final List<DebugReturn> stepReturns = new java.util.ArrayList<>();   // returns the armed
                                      // step crossed (0.29.0) — delivered at the STEP stop, cleared
                                      // on every break/arm/run-end alongside stepMode
    private java.util.Set<Integer> debugBreakLines = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Map<String, java.util.Set<Integer>> moduleDebugBreakLines =
            new java.util.concurrent.ConcurrentHashMap<>();
    private String entrySourceId = "<memory>";
    private boolean entryPausePending;   // one-shot: stop before the first root statement (0.31.0)

    /** Attach a {@link DebugHandler} — turns {@code debug} from a no-op into a breakpoint. */
    public void setDebugHandler(DebugHandler handler) { this.debugHandler = handler; }

    /**
     * Live, mutable set of 1-based source lines that break before executing (0.23.0). Lines are
     * the current snippet's/loaded file's own numbering. Only consulted while a handler is
     * attached, on the main logical thread or a {@code multi} worker; monitor iterations are
     * deliberately excluded.
     */
    public java.util.Set<Integer> debugBreakpoints() { return debugBreakLines; }

    /** Live breakpoints for one imported source id. */
    public java.util.Set<Integer> debugBreakpoints(String sourceId) {
        return moduleDebugBreakLines.computeIfAbsent(sourceId,
                ignored -> java.util.concurrent.ConcurrentHashMap.newKeySet());
    }

    /**
     * Adopt a host-owned breakpoint set (must be a concurrent set) in place of the default one
     * (0.26.0). Lets a host that builds a fresh interpreter per run — e.g. the v1 façade — hand
     * out one stable set whose mutations, before or mid-run, this interpreter observes live.
     */
    public void setDebugBreakpoints(java.util.Set<Integer> lines) {
        this.debugBreakLines = java.util.Objects.requireNonNull(lines);
    }

    /** Adopt a host-owned live breakpoint set for one imported source id. */
    public void setDebugBreakpoints(String sourceId, java.util.Set<Integer> lines) {
        moduleDebugBreakLines.put(java.util.Objects.requireNonNull(sourceId),
                java.util.Objects.requireNonNull(lines));
    }

    /** Reset per-run debugger state — a step armed at the end of one run must not leak into the next. */
    private void resetStepState() {
        stepMode = StepMode.NONE;
        stepThreadId = 0;
        stepReturns.clear();
        entryPausePending = false;
    }

    /**
     * Auto-display of function return values (0.29.0): while a step is armed, record each user
     * function that returns NORMALLY on the arming logical thread and lands in the armed frame or
     * shallower — i.e. the calls made from the stepped statement itself (stepOver/stepInto) or the
     * function being left (stepOut), never their nested/recursive inner calls, so the list stays
     * small and relevant. Values are deep-copied at return time. Error unwinding records nothing,
     * and code run from a break ({@link DebugFrame#eval} after arming) is excluded.
     */
    private void recordStepReturn(String function, Object value) {
        if (debugHandler == null || stepMode == StepMode.NONE || inDebugSession) return;
        ExecContext c = ec();
        if (c.mode == ExecContext.Mode.MONITOR) return;
        if (c.threadId != stepThreadId || c.frames.size() > stepDepth) return;
        stepReturns.add(new DebugReturn(function, Values.deepCopy(value)));
    }

    /**
     * The pre-statement hook behind stepping and line breakpoints. {@code debug} statements are
     * excluded — {@link #execDebug} owns that break (one pause per statement, reason
     * DEBUG_STATEMENT). Any break disarms the pending step.
     */
    private void debugStep(StatementContext s) {
        if (inDebugSession) return;
        ExecContext c = ec();
        if (c.mode == ExecContext.Mode.MONITOR) return;
        if (s instanceof FlowStmtContext fl && fl.flowControl() instanceof DebugStmtContext) return;
        String sourceId = sourceId(s);
        java.util.Set<Integer> sourceLines = moduleDebugBreakLines.get(sourceId);
        boolean bp = (sourceId.equals(entrySourceId) && debugBreakLines.contains(s.getStart().getLine()))
                || (sourceLines != null && sourceLines.contains(s.getStart().getLine()));
        boolean step = c.threadId == stepThreadId && switch (stepMode) {
            case NONE -> false;
            case IN   -> true;
            case OVER -> c.frames.size() <= stepDepth;
            case OUT  -> c.frames.size() < stepDepth;
        };
        boolean entry = entryPausePending && c.threadId == 0;   // pause-at-entry: first root statement
        if (entry) entryPausePending = false;
        if (!bp && !step && !entry) return;
        stepMode = StepMode.NONE;
        // Crossed returns belong to a STEP stop only; a breakpoint (all-stop rule) drops them.
        List<DebugReturn> crossed = !bp && !stepReturns.isEmpty() ? List.copyOf(stepReturns) : List.of();
        stepReturns.clear();
        inDebugSession = true;
        try {
            debugHandler.onBreak(new PausedFrame(s,
                    bp ? DebugFrame.Reason.BREAKPOINT : DebugFrame.Reason.STEP, crossed));
        } finally {
            inDebugSession = false;
        }
    }

    private void execDebug(DebugStmtContext d) {
        requireKeyword("debug", d.getStart());   // hideable, like the other keywords
        // No handler = normal execution = no-op (70_debug_statement). Monitor watchdog code stays
        // a no-op even in debug mode; worker code is debuggable. Code evaluated FROM a break must
        // not re-enter the debugger.
        if (debugHandler == null || inDebugSession || ec().mode == ExecContext.Mode.MONITOR) return;
        stepMode = StepMode.NONE;                // stepping onto a debug statement = one break
        entryPausePending = false;               // a leading debug statement IS the entry pause
        stepReturns.clear();
        inDebugSession = true;
        try {
            debugHandler.onBreak(new PausedFrame(d, DebugFrame.Reason.DEBUG_STATEMENT, List.of()));
        } finally {
            inDebugSession = false;
        }
    }

    /** The paused scope handed to the handler — evaluates in place on the paused fiber. */
    private final class PausedFrame implements DebugFrame {
        private final org.antlr.v4.runtime.ParserRuleContext at;
        private final Reason reason;
        private final List<DebugReturn> returns;
        PausedFrame(org.antlr.v4.runtime.ParserRuleContext at, Reason reason, List<DebugReturn> returns) {
            this.at = at;
            this.reason = reason;
            this.returns = returns;
        }

        @Override public Reason reason() { return reason; }
        @Override public List<DebugReturn> returns() { return returns; }
        @Override public int threadId() { return ec().threadId; }
        @Override public String threadName() { return ec().threadName; }
        @Override public String sourceId() { return Interpreter.sourceId(at); }
        @Override public int line() { return at.getStart().getLine(); }
        @Override public int column() { return at.getStart().getCharPositionInLine(); }

        @Override public String statementText() {
            String text = at.getStart().getInputStream().getText(new org.antlr.v4.runtime.misc.Interval(
                    at.getStart().getStartIndex(), at.getStop().getStopIndex()));
            int nl = text.indexOf('\n');
            return nl >= 0 ? text.substring(0, nl).stripTrailing() + " …" : text;
        }

        @Override public List<DebugCallSite> callStack() {
            return List.copyOf(ec().callStack);   // outermost first (callUser appends at the tail)
        }

        private void armStep(StepMode mode) {
            stepMode = mode;
            stepDepth = ec().frames.size();
            stepThreadId = ec().threadId;
            stepReturns.clear();
        }

        @Override public void stepInto() { armStep(StepMode.IN); }
        @Override public void stepOver() { armStep(StepMode.OVER); }
        @Override public void stepOut()  { armStep(StepMode.OUT); }

        @Override public Object eval(String source) {
            RootContext root = Parsing.parse(source);
            // Deliberately NOT execSnippet: that records top-level `return` into the session's
            // hasExplicitReturn/returnValue, which belong to the paused snippet, not the debugger.
            List<StatementContext> stmts = root.statement();
            Object last = null;
            try {
                for (int i = 0; i < stmts.size(); i++) {
                    StatementContext s = stmts.get(i);
                    if (i == stmts.size() - 1 && s instanceof ExprStmtContext e) {
                        last = Interpreter.this.eval(e.expression());
                    } else {
                        exec(s);
                    }
                }
            } catch (Signals.Return r) {
                last = r.value;                    // echo, never unwind the paused script
            } catch (Signals.Break | Signals.Continue ignored) {
                // must not leak into the paused script's enclosing loop
            }
            return last;
        }

        @Override public Map<String, Object> locals() {
            ExecContext c = ec();
            return c.frames.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(c.frames.peek());
        }

        @Override public Map<String, Object> globals() {
            return java.util.Collections.unmodifiableMap(ec().globalsView);
        }

        @Override public void quit() { throw new DebugQuit(); }
    }

    private void defineFunction(FunctionDefContext f) {
        requireKeyword("function", f.getStart());
        String name = f.funcName.getText();
        // spec v0.12.0: the all-uppercase namespace is reserved for built-in/host functions
        if (name.matches("[A-Z][A-Z0-9_]*")) {
            throw err("Cannot define function '" + name
                    + "': all-uppercase names are reserved for built-in and host functions", f);
        }
        List<String> params = new ArrayList<>();
        if (f.parameterList() != null) {
            for (var id : f.parameterList().ID()) params.add(id.getText());
        }
        UserFunction fn = new UserFunction(name, params, f.block(), ec().module);
        if (ec().module == null) functions.put(name, fn);
        else ec().module.functions.put(name, fn);
    }

    // ---- loops -------------------------------------------------------------
    //   (max iterations is the instance field `maxIterations`, default 1000, configurable via setLoopLimit)

    private void execLoop(IterationStmtContext loop) {
        requireKeyword("loop", loop.getStart());
        switch (loop) {
            case ConditionLoopContext c -> conditionLoop(c);
            case ValueLoopContext v     -> valueLoop(v);
            case KeyValueLoopContext kv -> keyValueLoop(kv);
            default -> throw err("unsupported loop", loop);
        }
    }

    private void conditionLoop(ConditionLoopContext c) {
        boolean infinite = c.K_INFINITE() != null;
        int count = 0;
        while (condition(c.expression())) {
            if (!infinite && count >= maxIterations && loopLimitExceeded(c)) break;
            if (runBody(c.block())) break;
            count++;
        }
    }

    /**
     * FILE_LINES reads (value/LazyLines) release the baton only where that is safe: on a fiber that
     * holds it, outside a debugger pause (all-stop — a pause must not let siblings run). A host
     * thread, code already off the baton inside a gated builtin, and a paused fiber read in place.
     */
    private final LazyLines.Gate ioGate = new LazyLines.Gate() {
        @Override
        public <T> T run(LazyLines.Work<T> work) throws java.io.IOException {
            Fiber me = coop.scheduler().self();
            if (me == null || inDebugSession || me.state() != FiberState.RUNNING) return work.call();
            try {
                return coop.blocking(() -> {
                    try {
                        return work.call();
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
            } catch (java.io.UncheckedIOException e) {
                throw e.getCause();
            }
        }
    };

    // A FILE_LINES array not yet read is streamed: one pass, chunk by chunk, no file open between
    // chunks — nothing is held across statement switches, SLEEPs or debugger pauses, and nothing
    // needs closing however the loop ends. Its read errors carry the loop's position (an
    // interpreter-raised error); the same errors through a builtin are positionless as usual.
    private void streamLoop(LazyLines lazy, String keyVar, String valVar, boolean infinite,
                            BlockContext body, IterationStmtContext loop) {
        LineReader reader;
        try {
            reader = lazy.lines();
        } catch (java.io.IOException e) {
            throw err("FILE_LINES() cannot read '" + lazy.path() + "': " + e.getMessage(), loop);
        }
        int count = 0;
        while (true) {
            String line;
            try {
                line = reader.next();
            } catch (java.io.IOException e) {
                throw err("FILE_LINES() cannot read '" + lazy.path() + "': " + e.getMessage(), loop);
            }
            if (line == null) break;
            if (!infinite && count >= maxIterations && loopLimitExceeded(loop)) break;
            if (keyVar != null) bindLocal(keyVar, count + 1);   // 1-based line number
            bindLocal(valVar, line);
            if (runBody(body)) break;
            count++;
        }
    }

    private void valueLoop(ValueLoopContext v) {
        boolean infinite = v.K_INFINITE() != null;
        String var = v.value.getText();
        Object coll = eval(v.expression());
        if (coll instanceof LazyLines lazy && !lazy.isLoaded()) {
            streamLoop(lazy, null, var, infinite, v.block(), v);
            return;
        }
        int count = 0;
        for (Object element : iterableValues(coll, v)) {
            if (!infinite && count >= maxIterations && loopLimitExceeded(v)) break;
            bindLocal(var, element);                // deepCopy => loop var is independent (68 test 7)
            if (runBody(v.block())) break;
            count++;
        }
    }

    private void keyValueLoop(KeyValueLoopContext kv) {
        boolean infinite = kv.K_INFINITE() != null;
        String keyVar = kv.key.getText();
        String valVar = kv.value.getText();
        Object coll = eval(kv.expression());
        if (coll instanceof LazyLines lazy && !lazy.isLoaded()) {
            streamLoop(lazy, keyVar, valVar, infinite, kv.block(), kv);
            return;
        }
        int count = 0;
        for (Map.Entry<Object, Object> entry : iterableEntries(coll, kv)) {
            if (!infinite && count >= maxIterations && loopLimitExceeded(kv)) break;
            bindLocal(keyVar, entry.getKey());
            bindLocal(valVar, entry.getValue());
            if (runBody(kv.block())) break;
            count++;
        }
    }

    /** Run a loop body; returns true if a {@code break} was hit. */
    private boolean runBody(BlockContext body) {
        coop.checkpoint();   // per-iteration abort/fairness point — an empty body never reaches a statement yield
        try {
            execBlock(body);
        } catch (Signals.Break b) {
            return true;
        } catch (Signals.Continue c) {
            // proceed to next iteration
        }
        return false;
    }

    private List<Object> iterableValues(Object coll, ParserRuleContext ctx) {
        if (coll instanceof List<?> list) return new ArrayList<>(list);
        if (coll instanceof Map<?, ?> map) return new ArrayList<>(map.values());
        throw err("Cannot iterate over " + Values.typeName(coll), ctx);
    }

    private List<Map.Entry<Object, Object>> iterableEntries(Object coll, ParserRuleContext ctx) {
        List<Map.Entry<Object, Object>> out = new ArrayList<>();
        if (coll instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) out.add(Map.entry(i + 1, (Object) list.get(i))); // 1-based key
            return out;
        }
        if (coll instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) out.add(Map.entry((Object) e.getKey(), (Object) e.getValue()));
            return out;
        }
        throw err("Cannot iterate over " + Values.typeName(coll), ctx);
    }

    /** Bind a loop var into the current scope as an independent deep copy. */
    private void bindLocal(String name, Object value) {
        currentScope().put(name, Values.deepCopy(value));
    }

    // ====================================================================
    // Assignment / lvalues
    // ====================================================================

    private void assign(LvalueContext lv, Object value) {
        ExecContext c = ec();
        // Workers and the monitor watchdog share the same purity rule (spec v0.16.0): globals are
        // a read-only snapshot; plain names are local (the monitor's locals live one iteration).
        if (c.mode != ExecContext.Mode.NORMAL && rootLvalue(lv) instanceof GlobalVarLValueContext g) {
            throw err("Cannot assign to global variable '::" + g.ID().getText() + "' inside multi block."
                    + " Functions in multi blocks can only read global variables (via ::)"
                    + " and write to local variables.", g);
        }
        switch (lv) {
            case VarLValueContext v -> currentScope().put(v.ID().getText(), Values.deepCopy(value));
            case GlobalVarLValueContext g -> c.globalsView.put(g.ID().getText(), Values.deepCopy(value));
            case PropLValueContext p -> {
                Object container = resolveContainer(p.lvalue());
                setMember(container, p.access(), value, p);
            }
            default -> throw err("unsupported assignment target", lv);
        }
    }

    /** Leftmost lvalue of an assignment chain (e.g. the {@code ::obj} root of {@code ::obj.a.b}). */
    private LvalueContext rootLvalue(LvalueContext lv) {
        while (lv instanceof PropLValueContext p) lv = p.lvalue();
        return lv;
    }

    /** The ACTUAL (non-copied) container an lvalue refers to, so nested mutation persists. */
    private Object resolveContainer(LvalueContext lv) {
        return switch (lv) {
            case VarLValueContext v -> varActual(v.ID().getText(), v);
            case GlobalVarLValueContext g -> globalActual(g.ID().getText(), g);
            case PropLValueContext p -> readMember(resolveContainer(p.lvalue()), p.access(), p);
            default -> throw err("unsupported assignment target", lv);
        };
    }

    private Object varActual(String name, ParserRuleContext ctx) {
        ExecContext c = ec();
        if (!c.frames.isEmpty()) {
            Map<String, Object> frame = frameContaining(name);
            if (frame != null) return frame.get(name);
            throw err("Variable '" + name + "' is not defined in local scope. Use ::" + name
                    + " to access the global variable.", ctx);
        }
        if (c.globalsView.containsKey(name)) return c.globalsView.get(name);
        if (builtinProps.containsKey(name)) return promoteProp(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    private Object globalActual(String name, ParserRuleContext ctx) {
        ExecContext c = ec();
        if (c.globalsView.containsKey(name)) return c.globalsView.get(name);
        if (builtinProps.containsKey(name)) return promoteProp(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    /** Innermost-first search of active call frames (dynamic local scope, LANGUAGE.md §Lookup Order). */
    private Map<String, Object> frameContaining(String name) {
        for (Map<String, Object> frame : ec().frames) {   // ArrayDeque iterates head(innermost) -> tail(outermost)
            if (frame.containsKey(name)) return frame;
        }
        return null;
    }

    /**
     * Built-in properties are READ-ONLY (LANGUAGE.md §Built-in Properties). The first write through
     * a property copies it into a global that then takes precedence in the lookup chain — the host
     * snapshot is never mutated in place. (Only reached in NORMAL mode; worker ::-writes error first.)
     */
    private Object promoteProp(String name) {
        Object copy = Values.deepCopy(builtinProps.get(name));
        ec().globalsView.put(name, copy);
        return copy;
    }

    private void setMember(Object container, AccessContext a, Object value, ParserRuleContext ctx) {
        if (container instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) rawMap;
            map.put(objectKey(a), Values.deepCopy(value));
        } else if (container instanceof List<?> rawList) {
            @SuppressWarnings("unchecked") List<Object> list = (List<Object>) rawList;
            int idx = arrayIndex(a, ctx, "Array");
            if (idx < 1 || idx > list.size()) throw err("Array index out of bounds", ctx);
            list.set(idx - 1, Values.deepCopy(value));
        } else {
            throw err("Property '" + objectKey(a) + "' does not exist", ctx);
        }
    }

    // ====================================================================
    // Expressions
    // ====================================================================

    private Object eval(ExpressionContext e) {
        return switch (e) {
            case AtomExprContext a -> evalAtom(a.atom());
            case MemberAccessExprContext m -> readMember(eval(m.expression()), m.access(), m);
            case UnaryMinusExprContext u -> negate(eval(u.expression()), u);
            case NotExprContext n -> notOp(eval(n.expression()), n);
            case MultiplicativeExprContext mu -> multiplicative(mu);
            case AdditiveExprContext ad -> additive(ad);
            case ComparisonExprContext c -> comparison(c);
            case AndExprContext a -> logical("AND", a.expression(0), a.expression(1), a);
            case OrExprContext o -> logical("OR", o.expression(0), o.expression(1), o);
            default -> throw err("unsupported expression", e);
        };
    }

    private Object evalAtom(AtomContext a) {
        return switch (a) {
            case FuncAtomContext f -> callFunction(f.functionCall());
            case GlobalVarReferenceContext g -> lookupGlobal(g.ID().getText(), g);
            case VarReferenceContext v -> lookupVar(v.ID().getText(), v);
            case DecimalAtomContext d -> Double.parseDouble(d.INTEGER(0).getText() + "." + d.INTEGER(1).getText());
            case IntegerAtomContext i -> Values.integer(parseIntLiteral(i.INTEGER().getText(), i));
            case StringAtomContext s -> unescape(s.STRING().getText());
            case BooleanAtomContext b -> b.K_TRUE() != null;
            case NullAtomContext n -> JsonNull.NULL;            // spec v0.8.0 (#4): the null literal
            case ObjectAtomContext o -> evalObject(o.objectLiteral());
            case ArrayAtomContext ar -> evalArray(ar.arrayLiteral());
            case ParenAtomContext p -> eval(p.expression());
            default -> throw err("unsupported atom", a);
        };
    }

    // ---- arithmetic --------------------------------------------------------

    private Object additive(AdditiveExprContext ad) {
        Object l = eval(ad.expression(0)), r = eval(ad.expression(1));
        String op = ad.getChild(1).getText();
        if (op.equals("+")) {
            if (l instanceof String || r instanceof String) {
                return TeeFormat.display(l) + TeeFormat.display(r);              // concat (coerce)
            }
            if (Values.isNumber(l) && Values.isNumber(r)) return numeric(l, r, '+', ad);
            throw err("Addition requires numeric or string operands. Got "
                    + Values.typeName(l) + " + " + Values.typeName(r), ad);
        }
        requireNumbers("-", l, r, ad);
        return numeric(l, r, '-', ad);
    }

    private Object multiplicative(MultiplicativeExprContext mu) {
        Object l = eval(mu.expression(0)), r = eval(mu.expression(1));
        char op = mu.getChild(1).getText().charAt(0);
        requireNumbers(String.valueOf(op), l, r, mu);
        if (op == '/') {
            if (Values.toDouble(r) == 0.0) throw err("Division by zero", mu);
            return finite(Values.toDouble(l) / Values.toDouble(r), mu);           // always Double
        }
        if (op == '%' && Values.toDouble(r) == 0.0) throw err("Division by zero", mu);
        return numeric(l, r, op, mu);
    }

    /**
     * +,-,*,% with v1 type rule: both integers -> integer, otherwise Double. Integer results must
     * stay in the integer range ±(2^53 − 1) (spec v0.13.0, v0.33.0) — overflow is a loud runtime
     * error, never a wrap.
     */
    private Object numeric(Object l, Object r, char op, ParserRuleContext ctx) {
        if (Values.isInteger(l) && Values.isInteger(r)) {
            long a = ((Number) l).longValue(), b = ((Number) r).longValue();
            long v;
            try {
                v = switch (op) {
                    case '+' -> a + b;                      // operands within ±2^53 cannot leave a long
                    case '-' -> a - b;
                    case '*' -> Math.multiplyExact(a, b);
                    case '%' -> a % b;                      // cannot overflow
                    default -> throw new IllegalStateException();
                };
            } catch (ArithmeticException overflow) {
                throw err(Values.INTEGER_OVERFLOW, ctx);
            }
            if (!Values.fitsInteger(v)) throw err(Values.INTEGER_OVERFLOW, ctx);
            return Values.integer(v);
        }
        double a = Values.toDouble(l), b = Values.toDouble(r);
        return finite(switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '%' -> a % b;
            default -> throw new IllegalStateException();
        }, ctx);
    }

    /**
     * A decimal result too large to represent is an error (spec v0.36.0), as an integer one outside
     * the range is: it used to be Infinity, and Infinity - Infinity NaN.
     */
    private Double finite(double d, ParserRuleContext ctx) {
        if (!Double.isFinite(d)) throw err(Values.NUMBER_OVERFLOW, ctx);
        return d;
    }

    private Object negate(Object v, ParserRuleContext ctx) {
        if (Values.isInteger(v)) return Values.integer(-((Number) v).longValue());   // the range is symmetric
        if (v instanceof Double d) return -d;
        throw err("Unary minus requires a numeric operand. Got " + Values.typeName(v), ctx);
    }

    /** Integer literals must fit the integer range (spec v0.13.0, v0.33.0); tokens are unsigned digits. */
    private long parseIntLiteral(String token, ParserRuleContext ctx) {
        long v;
        try {
            v = Long.parseLong(token);
        } catch (NumberFormatException tooBig) {
            v = -1;
        }
        if (v < 0 || v > Values.MAX_INTEGER) throw err(Values.literalOutOfRange(token), ctx);
        return v;
    }

    // ---- comparison / logic ------------------------------------------------

    private Object comparison(ComparisonExprContext c) {
        Object l = eval(c.expression(0)), r = eval(c.expression(1));
        String op = c.op.getText();
        switch (op) {
            case "==": return Values.valuesEqual(l, r);
            case "!=": return !Values.valuesEqual(l, r);
            default:
                if (!Values.isNumber(l) || !Values.isNumber(r)) {
                    throw err("Relational operator '" + op + "' requires number operands. Got "
                            + Values.typeName(l) + " and " + Values.typeName(r), c);
                }
                double a = Values.toDouble(l), b = Values.toDouble(r);
                return switch (op) {
                    case ">" -> a > b;
                    case "<" -> a < b;
                    case ">=" -> a >= b;
                    case "<=" -> a <= b;
                    default -> throw err("unknown comparison operator " + op, c);
                };
        }
    }

    /**
     * Spec v0.7.0 (#2): {@code and}/{@code or} SHORT-CIRCUIT left to right. Each operand must be
     * boolean, but the right operand is evaluated (and therefore type-checked) only when the left
     * side does not already determine the result — {@code false and X} / {@code true or X} skip X
     * entirely, side effects included. (Until spec v0.6.0 both sides were always evaluated.)
     */
    private Object logical(String name, ExpressionContext left, ExpressionContext right, ParserRuleContext ctx) {
        boolean lb = logicalOperand(name, left, ctx);
        if (name.equals("AND") ? !lb : lb) return lb;
        return logicalOperand(name, right, ctx);
    }

    private boolean logicalOperand(String name, ExpressionContext expr, ParserRuleContext ctx) {
        Object v = eval(expr);
        if (v instanceof Boolean b) return b;
        throw err("Logical " + name + " requires boolean operands. Got " + Values.typeName(v), ctx);
    }

    /**
     * Spec v0.7.0 (#1): an {@code if}/{@code loop} condition must be a boolean — any other type is
     * a runtime error, matching the strictness of the logical operators. (Until spec v0.6.0
     * non-booleans were silently falsy; {@link Values#isTruthy} keeps that rule for the v1-compat
     * {@code TypeChecker} façade.)
     */
    private boolean condition(ExpressionContext expr) {
        Object v = eval(expr);
        if (v instanceof Boolean b) return b;
        throw err("Condition requires a boolean value. Got " + Values.typeName(v), expr);
    }

    private Object notOp(Object v, ParserRuleContext ctx) {
        if (v instanceof Boolean b) return !b;
        throw err("Logical NOT requires a boolean operand. Got " + Values.typeName(v), ctx);
    }

    private void requireNumbers(String op, Object l, Object r, ParserRuleContext ctx) {
        if (!Values.isNumber(l) || !Values.isNumber(r)) {
            throw err("Arithmetic operator '" + op + "' requires numeric operands", ctx);
        }
    }

    // ---- variables ---------------------------------------------------------

    private Object lookupVar(String name, ParserRuleContext ctx) {
        ExecContext c = ec();
        if (!c.frames.isEmpty()) {
            Map<String, Object> frame = frameContaining(name);   // innermost-first across nested calls
            if (frame != null) return frame.get(name);
            throw err("Variable '" + name + "' is not defined in local scope. Use ::" + name
                    + " to access the global variable.", ctx);
        }
        if (c.globalsView.containsKey(name)) return c.globalsView.get(name);
        if (builtinProps.containsKey(name)) return builtinProps.get(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    private Object lookupGlobal(String name, ParserRuleContext ctx) {
        ExecContext c = ec();
        if (c.globalsView.containsKey(name)) return c.globalsView.get(name);   // snapshot in WORKER/MONITOR
        if (builtinProps.containsKey(name)) return builtinProps.get(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    // ---- function calls ----------------------------------------------------

    private Object callFunction(FunctionCallContext fc) {
        List<Object> args = new ArrayList<>();
        for (ExpressionContext arg : fc.expression()) args.add(eval(arg));
        if (fc.moduleAlias != null) {
            ModuleInstance module = resolveCallModule(fc);
            UserFunction fn = module.functions.get(fc.funcName.getText());
            if (fn == null) {
                throw err("Unknown function '" + fc.moduleAlias.getText() + "::"
                        + fc.funcName.getText() + "'", fc);
            }
            if (ignoredFunctions.contains(fc.funcName.getText())) {
                throw err("'" + fc.funcName.getText() + "' is not available in this environment", fc);
            }
            // callUser performs the return copy when this crosses into the module context.
            return callUser(fn, args, fc);
        }
        return invokeNamed(fc.funcName.getText(), args, fc);
    }

    private ModuleInstance resolveCallModule(FunctionCallContext fc) {
        if (fc.moduleAlias == null) return ec().module;
        if (ec().module != null) {
            throw err("Imported modules cannot call entry import aliases", fc);
        }
        String alias = fc.moduleAlias.getText();
        ModuleInstance module = moduleAliases.get(alias);
        if (module == null) throw err("Unknown module alias '" + alias + "'", fc);
        return module;
    }

    /** Dispatch a call by name with already-evaluated args (shared by direct calls and multi workers). */
    private Object invokeNamed(String name, List<Object> args, ParserRuleContext ctx) {
        if (ignoredFunctions.contains(name)) {
            throw err("'" + name + "' is not available in this environment", ctx);
        }
        UserFunction fn = ec().module == null ? functions.get(name) : ec().module.functions.get(name);
        if (fn != null) return callUser(fn, args, ctx);
        if (name.equals("PRINT")) return doPrint(args);
        if (name.equals("PRINTF")) return doPrintf(args);
        if (name.equals("SLEEP")) return doSleep(args, ctx);
        // FAIL/UNWRAP (spec v0.10.0) live at this dispatch level — not in the builtin catalog —
        // so their errors carry the call site's line:col (catalog builtin errors are positionless).
        if (name.equals("FAIL")) return doFail(args, ctx);
        if (name.equals("UNWRAP")) return doUnwrap(args, ctx);
        ExternalFunction ext = externals.get(name);
        if (ext != null) {
            // value -> Result.ok, exception -> Result.error; blocking externals release the baton (§3.1)
            return ext.blocking() ? coop.blocking(() -> callExternal(name, ext, args)) : callExternal(name, ext, args);
        }
        if (builtins.has(name)) {
            // Built-in errors stay positionless ("Runtime Error: msg" — 67_sort_errors); do not add a
            // source position. Host-gated / blocking builtins release the baton (design §3.1).
            Builtins.Kind kind = builtins.kindOf(name);
            if (kind == Builtins.Kind.HOST_GATED || kind == Builtins.Kind.BLOCKING) {
                WorkerShellObservation observation = "SHELL".equals(name) ? ec().shellObservation : null;
                return coop.blocking(() -> builtins.invokeRaw(name, args, observation));
            }
            return builtins.invokeRaw(name, args);
        }
        throw err("Unknown function '" + name + "'", ctx);
    }

    /**
     * Run a host external function, wrapping its return/exception in the v1 Result pattern. Args and
     * the return value are deep-copied so a host mutating either (possibly off-baton, on another
     * carrier) cannot change script state (LANGUAGE.md §async externals).
     */
    private static Object callExternal(String name, ExternalFunction ext, List<Object> args) {
        List<Object> isolated = new ArrayList<>(args.size());
        for (Object a : args) isolated.add(Values.materialize(a));   // the host gets plain values (a FILE_LINES array read in full)
        try {
            Object v = ext.body().apply(isolated);
            String nonFinite = Values.hostNonFinite(v, name + "() result");   // an error Result, as a failure is (spec v0.36.0)
            if (nonFinite != null) return Result.error(nonFinite);
            return Result.ok(Values.deepCopy(v));
        } catch (TeeError e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Object callUser(UserFunction fn, List<Object> args, ParserRuleContext ctx) {
        if (fn.owner() != ec().module) {
            Object[] result = new Object[1];
            ExecContext moduleContext = ec().forModule(fn.owner());
            ScopedValue.where(CTX, moduleContext).run(() -> result[0] = callUserInContext(fn, args, ctx));
            return Values.deepCopy(result[0]);
        }
        return callUserInContext(fn, args, ctx);
    }

    private Object callUserInContext(UserFunction fn, List<Object> args, ParserRuleContext ctx) {
        if (args.size() > fn.params().size()) {
            throw err("Function '" + fn.name() + "' expects " + fn.params().size()
                    + " argument(s), but " + args.size() + " were provided", ctx);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        for (int i = 0; i < fn.params().size(); i++) {
            frame.put(fn.params().get(i), i < args.size() ? Values.deepCopy(args.get(i)) : Values.emptyObject());
        }
        // Debugger :where support (0.23.0): record the call while a handler is attached. The gate
        // is captured once so push/pop stay balanced even if the handler changes mid-call.
        boolean trackCall = debugHandler != null;
        if (trackCall) {
            ec().callStack.addLast(new DebugCallSite(fn.name(),
                    sourceId(ctx), ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine()));
        }
        ec().frames.push(frame);
        Object result;
        try {
            execBlock(fn.body());
            result = Values.emptyObject();        // no explicit return -> {}
        } catch (Signals.Return r) {
            result = r.value;
        } finally {
            ec().frames.pop();                    // error unwinding skips the record below
            if (trackCall) ec().callStack.pollLast();
        }
        recordStepReturn(fn.name(), result);      // after the pop: depth is the caller's
        return result;
    }

    /** SLEEP(ms) — cooperative pause (design §3, seam 1). Releases the baton so other fibers advance. */
    private Object doSleep(List<Object> args, ParserRuleContext ctx) {
        // the JavaScript runtime's messages (spec v0.36.0); a negative duration slept 0 here
        if (args.isEmpty() || !Values.isNumber(args.get(0))) throw err("SLEEP() requires a number argument", ctx);
        if (Values.toDouble(args.get(0)) < 0) throw err("SLEEP() duration cannot be negative", ctx);
        coop.sleep((long) Values.toDouble(args.get(0)));
        return Values.emptyObject();
    }

    /** FAIL(message) — spec v0.10.0: raise a runtime error at the call site (the escalation primitive). */
    private Object doFail(List<Object> args, ParserRuleContext ctx) {
        if (args.size() != 1) throw err("FAIL() requires a message argument", ctx);
        throw err(TeeFormat.display(args.get(0)), ctx);   // non-string coerced like TO_STRING
    }

    /**
     * UNWRAP(res[, message]) — spec v0.10.0: the value of an ok genuine Result; an error/running
     * Result escalates like FAIL with TO_STRING(res.value) (prefixed "message: " when given).
     * Only genuine Results (value/TeeResult — runtime- or OK/ERR-created) are accepted.
     */
    private Object doUnwrap(List<Object> args, ParserRuleContext ctx) {
        if (args.isEmpty() || args.size() > 2) throw err("UNWRAP() requires (result, [message])", ctx);
        if (!(args.get(0) instanceof TeeResult res)) throw err("UNWRAP() requires a Result", ctx);
        if (Boolean.TRUE.equals(res.get("ok"))) return res.get("value");
        String value = TeeFormat.display(res.get("value"));
        throw err(args.size() > 1 ? TeeFormat.display(args.get(1)) + ": " + value : value, ctx);
    }

    private Object doPrint(List<Object> args) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) line.append(' ');
            line.append(TeeFormat.display(args.get(i)));
        }
        out.line(line.toString());
        return Values.emptyObject();
    }

    /**
     * PRINTF(format, values...) — spec v0.30.0: one line, PRINT(FORMAT(format, values...)). It is
     * dispatched here only because it writes to the output sink; its errors are FORMAT's under the
     * PRINTF name and, like a catalog builtin's, carry no position.
     */
    private Object doPrintf(List<Object> args) {
        out.line(Builtins.format("PRINTF", args));
        return Values.emptyObject();
    }

    // ---- member access (read) ----------------------------------------------

    private Object readMember(Object base, AccessContext a, ParserRuleContext memberCtx) {
        if (base instanceof Map<?, ?> map) {
            String key = objectKey(a);
            if (!map.containsKey(key)) throw err("Property '" + key + "' does not exist", memberCtx);
            return map.get(key);
        }
        if (base instanceof List<?> list) {
            int idx = arrayIndex(a, memberCtx, "Array");
            if (idx < 1 || idx > list.size()) throw err("Array index out of bounds", memberCtx);
            return list.get(idx - 1);
        }
        if (base instanceof String s) {
            int idx = arrayIndex(a, memberCtx, "String");
            if (idx < 1 || idx > s.length()) throw err("String index out of bounds", memberCtx);
            return String.valueOf(s.charAt(idx - 1));
        }
        throw err("Property '" + objectKey(a) + "' does not exist", memberCtx);
    }

    private String objectKey(AccessContext a) {
        return switch (a) {
            case StaticAccessContext s -> s.ID().getText();
            case StringKeyAccessContext sk -> unescape(sk.STRING().getText());
            case ArrayAccessContext ar -> String.valueOf(parseIntLiteral(ar.INTEGER().getText(), ar)); // int key -> "1"
            case VarEvalAccessContext ve -> TeeFormat.display(evalVarEval(ve));
            case EvalAccessContext ev -> TeeFormat.display(eval(ev.expression()));
            default -> throw err("unsupported access", a);
        };
    }

    /**
     * The index of an array or string ({@code what}). Since spec v0.34.0 a fraction is an error, not
     * cut toward zero; a whole number too large for an int saturates and is then out of bounds.
     */
    private int arrayIndex(AccessContext a, ParserRuleContext ctx, String what) {
        if (a instanceof ArrayAccessContext ar) return (int) Math.min(parseIntLiteral(ar.INTEGER().getText(), ar), Integer.MAX_VALUE);
        Object v = switch (a) {
            case VarEvalAccessContext ve -> evalVarEval(ve);
            case EvalAccessContext ev -> eval(ev.expression());
            default -> throw err(what + " index must be a number", ctx);
        };
        if (!Values.isNumber(v)) throw err(what + " index must be a number", ctx);
        double d = Values.toDouble(v);
        if (d != Math.floor(d) || Double.isInfinite(d)) throw err(what + " index must be an integer", ctx);   // NaN too
        return (int) d;
    }

    private Object evalVarEval(VarEvalAccessContext ve) {
        String name = ve.ID().getText();
        return ve.GLOBAL_PREFIX() != null ? lookupGlobal(name, ve) : lookupVar(name, ve);
    }

    // ---- literals ----------------------------------------------------------

    private Object evalObject(ObjectLiteralContext o) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (ObjectEntryContext entry : o.objectEntry()) {
            map.put(objectKeyText(entry.objectKey()), eval(entry.expression()));
        }
        return map;
    }

    private String objectKeyText(ObjectKeyContext k) {
        if (k.STRING() != null) return unescape(k.STRING().getText());
        return String.valueOf(parseIntLiteral(k.INTEGER().getText(), k)); // integer key -> string
    }

    private Object evalArray(ArrayLiteralContext arr) {
        if (arr instanceof RangeArrayContext r) {
            Object step = r.rangeStep != null ? eval(r.rangeStep) : null;
            return Ranges.build(eval(r.rangeStart), eval(r.rangeEnd), step,
                    r.getStart().getLine(), r.getStart().getCharPositionInLine());
        }
        ListArrayContext la = (ListArrayContext) arr;
        List<Object> list = new ArrayList<>();
        for (ExpressionContext e : la.expression()) list.add(eval(e));
        return list;
    }

    // ====================================================================
    // helpers
    // ====================================================================

    private TeeError err(String message, ParserRuleContext ctx) {
        String id = sourceId(ctx);
        // Preserve the v1-compatible entry-script error text. Imported source identity is
        // included because otherwise an imported failure cannot be located unambiguously.
        String diagnosticSource = id.equals(entrySourceId) ? null : id;
        return new TeeError(message, diagnosticSource, ctx.getStart().getLine(),
                ctx.getStart().getCharPositionInLine());
    }

    private static String sourceId(ParserRuleContext ctx) {
        String id = ctx.getStart().getInputStream().getSourceName();
        return id == null || id.isBlank() || id.equals("<unknown>") ? "<memory>" : id;
    }

    /** Enforce host keyword hiding (73_keyword_ignore): a hidden keyword is a runtime error at its token. */
    private void requireKeyword(String keyword, Token token) {
        if (hiddenKeywords.contains(keyword)) {
            String id = token.getInputStream().getSourceName();
            throw new TeeError("'" + keyword + "' is not available in this environment",
                    id != null && id.equals(entrySourceId) ? null : id,
                    token.getLine(), token.getCharPositionInLine());
        }
    }

    /** Process string-literal escapes (LANGUAGE.md §Strings): the token text includes the quotes. */
    static String unescape(String quoted) {
        String s = quoted.substring(1, quoted.length() - 1);
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    default -> sb.append('\\').append(n); // unrecognized escape preserved as-is
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * In warn mode prints the v1 warning (error channel) and returns true = stop the loop; otherwise
     * throws. Every loop suggests {@code loop ... infinite do} (spec v0.35.0): this runtime suggested
     * {@code loop condition infinite do}, which reads wrong for a loop over an array or object.
     */
    private boolean loopLimitExceeded(ParserRuleContext ctx) {
        if (loopLimitWarns) {
            err.line("Warning: Loop exceeded maximum iterations (" + maxIterations + "), stopping loop");
            return true;
        }
        throw err("Loop exceeded maximum iterations (" + maxIterations
                + "). Use 'loop ... infinite do' if you need unlimited iterations.", ctx);
    }
}
