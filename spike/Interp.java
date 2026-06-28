import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Spike — a plain recursive tree-walk evaluator (design §2). There is NO stepper,
 * NO SchedulerCommand, NO AsyncPendingException replay. When eval() hits SleepMs /
 * BlockingCall deep inside nested Add / CallFn frames, the fiber's vthread parks with
 * the entire Java stack intact and later resumes exactly there.
 */
final class Interp {
    private final Coop coop;
    private final Map<String, FnDef> fns;

    Interp(Coop coop, Map<String, FnDef> fns) {
        this.coop = coop;
        this.fns = fns;
    }

    /** user function definition: parameter names + body */
    record FnDef(List<String> params, Node body) { }

    /** non-local unwind for `return` */
    private static final class ReturnSignal extends RuntimeException {
        final Object value;
        ReturnSignal(Object v) { super(null, null, false, false); this.value = v; }
    }

    Object eval(Node n, Map<String, Object> env) {
        return switch (n) {
            case Lit l   -> l.v();
            case Var v   -> env.getOrDefault(v.name(), Result.emptyObject());   // no null — missing => {}
            case Add a   -> num(eval(a.a(), env)) + num(eval(a.b(), env));      // evaluates b (maybe f()) mid-expression
            case Assign as -> {
                Object val = eval(as.expr(), env);                              // `x = f()` suspends inside eval here
                env.put(as.name(), val);
                yield val;
            }
            case CallFn c -> {
                FnDef def = fns.get(c.fn());
                if (def == null) throw new RuntimeException("undefined function: " + c.fn());
                Map<String, Object> frame = new HashMap<>();
                for (int i = 0; i < def.params().size(); i++) {
                    Object arg = i < c.args().size() ? eval(c.args().get(i), env) : Result.emptyObject();
                    frame.put(def.params().get(i), arg);
                }
                try {
                    yield eval(def.body(), frame);                             // recurse into the body — a new stack frame
                } catch (ReturnSignal rs) {
                    yield rs.value;                                            // `return f()` unwinds to here
                }
            }
            case SleepMs s     -> { coop.sleep(s.ms()); yield 0.0; }            // cooperative suspend, stack preserved
            case BlockingCall b -> coop.blocking(() -> { sleepReal(b.ms()); return b.ret(); });
            case SideEffect se -> { se.eff().run(); yield se.ret(); }
            case Print p       -> { Object v = eval(p.expr(), env); Log.line(p.label() + " = " + v); yield v; }
            case Return r      -> throw new ReturnSignal(eval(r.expr(), env));
            case Seq sq        -> {
                Object last = Result.emptyObject();
                for (Node s : sq.stmts()) last = eval(s, env);
                yield last;
            }
        };
    }

    private static double num(Object o) {
        if (o instanceof Number num) return num.doubleValue();
        throw new RuntimeException("arithmetic on non-number: " + o);
    }

    /** A genuine blocking call (e.g. host I/O). MUST only ever run inside Coop.blocking. */
    private static void sleepReal(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
