package com.flatide.propertee2.interp;

import com.flatide.propertee2.builtin.Builtins;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.coop.Fiber;
import com.flatide.propertee2.coop.Scheduler;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.parser.ProperTeeParser.*;
import com.flatide.propertee2.value.Result;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final Coop coop;
    private final Map<String, Object> globals = new LinkedHashMap<>();   // the program's top-level globals
    private final Map<String, Object> builtinProps;          // host-injected (-p), incl. _PROPS
    private final Map<String, UserFunction> functions = new LinkedHashMap<>();
    private final Builtins builtins;
    private final Map<String, ExternalFunction> externals;   // host-registered external functions
    private final Set<String> hiddenKeywords;                // keywords made unavailable in this environment
    private final Set<String> ignoredFunctions;              // function names made unavailable
    private int maxIterations = 1000;                        // max loop iterations (configurable via façade)
    private boolean hasExplicitReturn = false;               // did the program hit a top-level `return`?
    private Object returnValue = Values.emptyObject();        // the top-level return value (if any)

    // Per-fiber execution state (frames, globals view, mode) — isolated across multi workers (§5).
    private static final ScopedValue<ExecContext> CTX = ScopedValue.newInstance();
    private ExecContext ec() { return CTX.get(); }

    /** Façade-friendly constructor: no host externals / hidden keywords (custom builtins go via addRawBuiltin). */
    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       com.flatide.task.TaskRunner taskRunner, String runId) {
        this(out, props, coop, platform, new LinkedHashMap<>(), Set.of(), Set.of(), taskRunner, runId);
    }

    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       Map<String, ExternalFunction> externals, Set<String> hiddenKeywords,
                       Set<String> ignoredFunctions, com.flatide.task.TaskRunner taskRunner) {
        this(out, props, coop, platform, externals, hiddenKeywords, ignoredFunctions, taskRunner, null);
    }

    public Interpreter(Sink out, Map<String, Object> props, Coop coop, PlatformProvider platform,
                       Map<String, ExternalFunction> externals, Set<String> hiddenKeywords,
                       Set<String> ignoredFunctions, com.flatide.task.TaskRunner taskRunner, String runId) {
        this.out = out;
        this.coop = coop;
        this.builtins = Builtins.standard(platform, taskRunner, runId);
        this.externals = externals;
        this.hiddenKeywords = hiddenKeywords;
        this.ignoredFunctions = ignoredFunctions;
        this.builtinProps = new LinkedHashMap<>();
        if (props != null) {
            for (Map.Entry<String, Object> e : props.entrySet()) builtinProps.put(e.getKey(), Values.deepCopy(e.getValue()));
            // _PROPS: all inputs as one object (LANGUAGE.md §_PROPS), unless the caller supplied it.
            if (!builtinProps.containsKey("_PROPS")) {
                Map<String, Object> all = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : props.entrySet()) all.put(e.getKey(), Values.deepCopy(e.getValue()));
                builtinProps.put("_PROPS", all);
            }
        }
    }

    public void run(RootContext root) {
        ScopedValue.where(CTX, ExecContext.normal(globals)).run(() -> execProgram(root));
    }

    private void execProgram(RootContext root) {
        try {
            for (StatementContext s : root.statement()) { exec(s); coop.yield_(); }
        } catch (Signals.Return r) {
            hasExplicitReturn = true;          // top-level return: stop, remember the value (value-model §8)
            returnValue = r.value;
        } catch (Signals.Break | Signals.Continue b) {
            // break/continue outside a loop: ignore
        }
    }

    // ---- Façade hooks (used by the v1-compat com.flatide.interpreter layer) -----------------------

    /** The program's top-level globals — a host may inject before {@link #run} and read after (e.g. `result`). */
    public Map<String, Object> globals() { return globals; }

    /** True if the program ended with an explicit top-level {@code return}. */
    public boolean hasExplicitReturn() { return hasExplicitReturn; }

    /** The top-level return value (or empty object if none). */
    public Object returnValue() { return returnValue; }

    /** Configure the maximum loop iterations (v1 maxIterations). */
    public void setLoopLimit(int limit) { if (limit > 0) this.maxIterations = limit; }

    /** Register a host builtin whose return value is used as-is (no Result wrapper), e.g. STREAM_FILE. */
    public void addRawBuiltin(String name, java.util.function.Function<List<Object>, Object> fn) {
        builtins.register(name, Builtins.Kind.PURE, fn::apply);
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
        List<Object> args = new ArrayList<>();
        for (ExpressionContext arg : fc.expression()) args.add(Values.deepCopy(eval(arg))); // captured at spawn time

        String key = k.access() != null ? objectKey(k.access()) : "";     // no key or "" -> unnamed (#N)
        if (key.isEmpty()) {
            // auto-key conflicts report at the function-call position (61_duplicate_auto_key)
            builder.addUnnamed(funcName, args, fc, fc.getStart().getLine(), fc.getStart().getCharPositionInLine());
        } else {
            builder.addNamed(key, funcName, args, fc, sp.getStart().getLine(), sp.getStart().getCharPositionInLine());
        }
    }

    private void execMulti(ParallelStmtContext m) {
        requireKeyword("multi", m.getStart());
        ExecContext c = ec();

        // 1. setup phase: isolated scope, collect thread statements
        MultiBuilder builder = new MultiBuilder();
        c.frames.push(new LinkedHashMap<>());
        c.setupBuilders.push(builder);
        try {
            execBlock(m.block());
        } finally {
            c.setupBuilders.pop();
            c.frames.pop();
        }

        // 2. read-only global snapshot all threads share (design §4, thread purity)
        @SuppressWarnings("unchecked")
        Map<String, Object> snapshot = (Map<String, Object>) Values.deepCopy(c.globalsView);

        // 3. pre-build the result collection with "running" entries, in spawn order
        Map<String, Object> collection = new LinkedHashMap<>();
        for (MultiBuilder.Pending p : builder.pending) collection.put(p.key(), Result.running());

        // 4. spawn workers (+ optional monitor), wait for all, in-place result updates
        runThreads(builder.pending, collection, snapshot, m);

        // 5. assign the collection to resultVar (current scope) — global at top level, local in a function
        if (m.resultVar != null) currentScope().put(m.resultVar.getText(), collection);
    }

    private void runThreads(List<MultiBuilder.Pending> pending, Map<String, Object> collection,
                            Map<String, Object> snapshot, ParallelStmtContext m) {
        Scheduler s = coop.scheduler();
        Fiber parent = s.self();
        boolean hasMonitor = m.monitorClause() != null;
        AtomicInteger remaining = new AtomicInteger(pending.size());                               // workers only
        AtomicInteger parentPending = new AtomicInteger(pending.size() + (hasMonitor ? 1 : 0));    // gate parent resume
        Runnable signalParent = () -> { if (parentPending.decrementAndGet() == 0) s.wake(parent); };

        for (MultiBuilder.Pending p : pending) {
            int tid = workerIdSeq.incrementAndGet();              // 1, 2, ... (the main thread is the host's id 0)
            // Announce the worker before it runs so a host observer sees it during execution; its key in the
            // result collection is the worker's result-key name (a host groups run threads by it).
            if (threadObserver != null) threadObserver.created(tid, p.funcName(), 0, p.key());
            s.spawn(p.key(), () -> ScopedValue.where(CTX, ExecContext.worker(snapshot)).run(() -> {
                try {
                    Object r = invokeNamed(p.funcName(), p.args(), p.call());
                    collection.put(p.key(), Result.ok(r));
                    if (threadObserver != null) threadObserver.completed(tid, r);
                } catch (RuntimeException e) {
                    // TeeError, or a stray control-flow signal / other failure: never leave the entry "running"
                    String pos = failureText(e);
                    out.line("[THREAD ERROR] " + pos);
                    collection.put(p.key(), Result.error(pos));
                    if (threadObserver != null) threadObserver.error(tid, e);
                } finally {
                    remaining.decrementAndGet();
                    signalParent.run();
                }
            }));
        }

        if (hasMonitor) {
            MonitorClauseContext mc = m.monitorClause();
            long interval = Long.parseLong(mc.INTEGER().getText());
            String resultVarName = m.resultVar != null ? m.resultVar.getText() : null;
            s.spawn("monitor", () -> {
                try {
                    Map<String, Object> injected = new LinkedHashMap<>();
                    if (resultVarName != null) injected.put(resultVarName, collection);
                    Runnable tick = () -> ScopedValue.where(CTX, ExecContext.monitor(snapshot, injected)).run(() -> {
                        try {
                            execBlock(mc.block());
                        } catch (TeeError e) {
                            out.line("[MONITOR ERROR] " + e.positioned());
                            throw e;
                        }
                    });
                    runMonitor(interval, remaining, tick);
                } finally {
                    signalParent.run();   // always release the parent, even if a monitor tick threw
                }
            });
        }

        s.awaitChildren(() -> parentPending.get() == 0);
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
        if (Values.isTruthy(eval(i.condition))) {
            execBlock(i.thenBody);
        } else if (i.elseBody != null) {
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
            case DebugStmtContext d    -> requireKeyword("debug", f.getStart()); // else no-op (70_debug_statement)
            default -> throw err("unsupported flow statement", f);
        }
    }

    private void defineFunction(FunctionDefContext f) {
        requireKeyword("function", f.getStart());
        List<String> params = new ArrayList<>();
        if (f.parameterList() != null) {
            for (var id : f.parameterList().ID()) params.add(id.getText());
        }
        functions.put(f.funcName.getText(), new UserFunction(f.funcName.getText(), params, f.block()));
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
        while (Values.isTruthy(eval(c.expression()))) {
            if (!infinite && count >= maxIterations) throw loopLimit(c);
            if (runBody(c.block())) break;
            count++;
        }
    }

    private void valueLoop(ValueLoopContext v) {
        boolean infinite = v.K_INFINITE() != null;
        String var = v.value.getText();
        int count = 0;
        for (Object element : iterableValues(eval(v.expression()), v)) {
            if (!infinite && count >= maxIterations) throw loopLimit(v);
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
        int count = 0;
        for (Map.Entry<Object, Object> entry : iterableEntries(coll, kv)) {
            if (!infinite && count >= maxIterations) throw loopLimit(kv);
            bindLocal(keyVar, entry.getKey());
            bindLocal(valVar, entry.getValue());
            if (runBody(kv.block())) break;
            count++;
        }
    }

    /** Run a loop body; returns true if a {@code break} was hit. */
    private boolean runBody(BlockContext body) {
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
        if (c.mode == ExecContext.Mode.MONITOR) {
            throw err("Cannot assign variables in monitor block (read-only)", lv);
        }
        if (c.mode == ExecContext.Mode.WORKER && rootLvalue(lv) instanceof GlobalVarLValueContext g) {
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
            int idx = arrayIndex(a, ctx);
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
            case AndExprContext a -> logical("AND", "and", eval(a.expression(0)), eval(a.expression(1)), a);
            case OrExprContext o -> logical("OR", "or", eval(o.expression(0)), eval(o.expression(1)), o);
            default -> throw err("unsupported expression", e);
        };
    }

    private Object evalAtom(AtomContext a) {
        return switch (a) {
            case FuncAtomContext f -> callFunction(f.functionCall());
            case GlobalVarReferenceContext g -> lookupGlobal(g.ID().getText(), g);
            case VarReferenceContext v -> lookupVar(v.ID().getText(), v);
            case DecimalAtomContext d -> Double.parseDouble(d.INTEGER(0).getText() + "." + d.INTEGER(1).getText());
            case IntegerAtomContext i -> Integer.parseInt(i.INTEGER().getText());
            case StringAtomContext s -> unescape(s.STRING().getText());
            case BooleanAtomContext b -> b.K_TRUE() != null;
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
            if (Values.isNumber(l) && Values.isNumber(r)) return numeric(l, r, '+');
            throw err("Addition requires numeric or string operands. Got "
                    + Values.typeName(l) + " + " + Values.typeName(r), ad);
        }
        requireNumbers("-", l, r, ad);
        return numeric(l, r, '-');
    }

    private Object multiplicative(MultiplicativeExprContext mu) {
        Object l = eval(mu.expression(0)), r = eval(mu.expression(1));
        char op = mu.getChild(1).getText().charAt(0);
        requireNumbers(String.valueOf(op), l, r, mu);
        if (op == '/') {
            if (Values.toDouble(r) == 0.0) throw err("Division by zero", mu);
            return Values.toDouble(l) / Values.toDouble(r);                       // always Double
        }
        if (op == '%' && Values.toDouble(r) == 0.0) throw err("Division by zero", mu);
        return numeric(l, r, op);
    }

    /** +,-,*,% with v1 type rule: both Integer -> Integer, otherwise Double. */
    private static Object numeric(Object l, Object r, char op) {
        if (l instanceof Integer li && r instanceof Integer ri) {
            return switch (op) {
                case '+' -> li + ri;
                case '-' -> li - ri;
                case '*' -> li * ri;
                case '%' -> li % ri;
                default -> throw new IllegalStateException();
            };
        }
        double a = Values.toDouble(l), b = Values.toDouble(r);
        return switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            case '%' -> a % b;
            default -> throw new IllegalStateException();
        };
    }

    private Object negate(Object v, ParserRuleContext ctx) {
        if (v instanceof Integer i) return -i;
        if (v instanceof Double d) return -d;
        throw err("Unary minus requires a numeric operand. Got " + Values.typeName(v), ctx);
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

    private Object logical(String name, String word, Object l, Object r, ParserRuleContext ctx) {
        if (!(l instanceof Boolean lb) || !(r instanceof Boolean rb)) {
            throw err("Logical " + name + " requires boolean operands. Got "
                    + Values.typeName(l) + " " + word + " " + Values.typeName(r), ctx);
        }
        return name.equals("AND") ? (lb && rb) : (lb || rb);
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
        if (c.mode == ExecContext.Mode.MONITOR) {
            // monitor scope: injected result var, then globals (snapshot), then built-in properties
            if (c.injected != null && c.injected.containsKey(name)) return c.injected.get(name);
            if (c.globalsView.containsKey(name)) return c.globalsView.get(name);
            if (builtinProps.containsKey(name)) return builtinProps.get(name);
            throw err("Variable '" + name + "' is not defined", ctx);
        }
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
        return invokeNamed(fc.funcName.getText(), args, fc);
    }

    /** Dispatch a call by name with already-evaluated args (shared by direct calls and multi workers). */
    private Object invokeNamed(String name, List<Object> args, ParserRuleContext ctx) {
        if (ignoredFunctions.contains(name)) {
            throw err("'" + name + "' is not available in this environment", ctx);
        }
        UserFunction fn = functions.get(name);
        if (fn != null) return callUser(fn, args, ctx);
        if (name.equals("PRINT")) return doPrint(args);
        if (name.equals("SLEEP")) return doSleep(args, ctx);
        ExternalFunction ext = externals.get(name);
        if (ext != null) {
            // value -> Result.ok, exception -> Result.error; blocking externals release the baton (§3.1)
            return ext.blocking() ? coop.blocking(() -> callExternal(ext, args)) : callExternal(ext, args);
        }
        if (builtins.has(name)) {
            // Built-in errors stay positionless ("Runtime Error: msg" — 67_sort_errors); do not add a
            // source position. Host-gated / blocking builtins release the baton (design §3.1).
            Builtins.Kind kind = builtins.kindOf(name);
            if (kind == Builtins.Kind.HOST_GATED || kind == Builtins.Kind.BLOCKING) {
                return coop.blocking(() -> builtins.invokeRaw(name, args));
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
    private static Object callExternal(ExternalFunction ext, List<Object> args) {
        List<Object> isolated = new ArrayList<>(args.size());
        for (Object a : args) isolated.add(Values.deepCopy(a));
        try {
            return Result.ok(Values.deepCopy(ext.body().apply(isolated)));
        } catch (TeeError e) {
            return Result.error(e.getMessage());
        } catch (RuntimeException e) {
            return Result.error(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private Object callUser(UserFunction fn, List<Object> args, ParserRuleContext ctx) {
        if (args.size() > fn.params().size()) {
            throw err("Function '" + fn.name() + "' expects " + fn.params().size()
                    + " argument(s), but " + args.size() + " were provided", ctx);
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        for (int i = 0; i < fn.params().size(); i++) {
            frame.put(fn.params().get(i), i < args.size() ? Values.deepCopy(args.get(i)) : Values.emptyObject());
        }
        ec().frames.push(frame);
        try {
            execBlock(fn.body());
            return Values.emptyObject();          // no explicit return -> {}
        } catch (Signals.Return r) {
            return r.value;
        } finally {
            ec().frames.pop();
        }
    }

    /** SLEEP(ms) — cooperative pause (design §3, seam 1). Releases the baton so other fibers advance. */
    private Object doSleep(List<Object> args, ParserRuleContext ctx) {
        if (args.isEmpty() || !Values.isNumber(args.get(0))) {
            throw err("SLEEP() requires a numeric millisecond argument", ctx);
        }
        coop.sleep((long) Values.toDouble(args.get(0)));
        return Values.emptyObject();
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

    // ---- member access (read) ----------------------------------------------

    private Object readMember(Object base, AccessContext a, ParserRuleContext memberCtx) {
        if (base instanceof Map<?, ?> map) {
            String key = objectKey(a);
            if (!map.containsKey(key)) throw err("Property '" + key + "' does not exist", memberCtx);
            return map.get(key);
        }
        if (base instanceof List<?> list) {
            int idx = arrayIndex(a, memberCtx);
            if (idx < 1 || idx > list.size()) throw err("Array index out of bounds", memberCtx);
            return list.get(idx - 1);
        }
        if (base instanceof String s) {
            int idx = arrayIndex(a, memberCtx);
            if (idx < 1 || idx > s.length()) throw err("Array index out of bounds", memberCtx);
            return String.valueOf(s.charAt(idx - 1));
        }
        throw err("Property '" + objectKey(a) + "' does not exist", memberCtx);
    }

    private String objectKey(AccessContext a) {
        return switch (a) {
            case StaticAccessContext s -> s.ID().getText();
            case StringKeyAccessContext sk -> unescape(sk.STRING().getText());
            case ArrayAccessContext ar -> String.valueOf(Integer.parseInt(ar.INTEGER().getText())); // int key -> "1"
            case VarEvalAccessContext ve -> TeeFormat.display(evalVarEval(ve));
            case EvalAccessContext ev -> TeeFormat.display(eval(ev.expression()));
            default -> throw err("unsupported access", a);
        };
    }

    private int arrayIndex(AccessContext a, ParserRuleContext ctx) {
        if (a instanceof ArrayAccessContext ar) return Integer.parseInt(ar.INTEGER().getText());
        Object v = switch (a) {
            case VarEvalAccessContext ve -> evalVarEval(ve);
            case EvalAccessContext ev -> eval(ev.expression());
            default -> throw err("Array index must be a number", ctx);
        };
        if (!Values.isNumber(v)) throw err("Array index must be a number", ctx);
        return (int) Values.toDouble(v);
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
        return String.valueOf(Integer.parseInt(k.INTEGER().getText())); // integer key -> string
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

    private static TeeError err(String message, ParserRuleContext ctx) {
        return new TeeError(message, ctx.getStart().getLine(), ctx.getStart().getCharPositionInLine());
    }

    /** Enforce host keyword hiding (73_keyword_ignore): a hidden keyword is a runtime error at its token. */
    private void requireKeyword(String keyword, Token token) {
        if (hiddenKeywords.contains(keyword)) {
            throw new TeeError("'" + keyword + "' is not available in this environment",
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

    private TeeError loopLimit(ParserRuleContext ctx) {
        return err("Loop exceeded maximum iterations (" + maxIterations
                + "). Use 'loop condition infinite do' if you need unlimited iterations.", ctx);
    }
}
