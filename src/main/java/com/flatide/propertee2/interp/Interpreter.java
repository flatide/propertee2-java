package com.flatide.propertee2.interp;

import com.flatide.propertee2.builtin.Builtins;
import com.flatide.propertee2.parser.ProperTeeParser.*;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;

import org.antlr.v4.runtime.ParserRuleContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The recursive tree-walk interpreter (design §2 — NO stepper / replay machine).
 * Covers the SEQUENTIAL language: scopes, arithmetic/comparison/logic, access &amp; indexing,
 * literals, ranges, control flow, functions, builtins, and v1-exact value semantics
 * (no-null/{}, 1-based, deepCopy/COW, strict types). Cooperative concurrency
 * (multi/thread/SLEEP/monitor) and host-gated/blocking builtins land in PC/PD.
 */
public final class Interpreter {

    private final StringBuilder out;
    private final Map<String, Object> globals = new LinkedHashMap<>();
    private final Map<String, Object> builtinProps;          // host-injected (-p), incl. _PROPS
    private final Map<String, UserFunction> functions = new LinkedHashMap<>();
    private final Deque<Map<String, Object>> frames = new ArrayDeque<>(); // one per active function call
    private final Builtins builtins = Builtins.standard();

    public Interpreter(StringBuilder out, Map<String, Object> props) {
        this.out = out;
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
        try {
            for (StatementContext s : root.statement()) exec(s);
        } catch (Signals.Return r) {
            // top-level return: stop execution
        } catch (Signals.Break | Signals.Continue b) {
            // break/continue outside a loop: ignore
        }
    }

    private boolean inFunction() { return !frames.isEmpty(); }
    private Map<String, Object> localTop() { return frames.peek(); }
    private Map<String, Object> currentScope() { return inFunction() ? localTop() : globals; }

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
            case ParallelExecStmtContext p -> throw err("multi blocks are not supported yet (PD)", p);
            case SpawnExecStmtContext sp   -> throw err("thread can only be used inside multi blocks", sp);
            default -> throw err("unsupported statement", s);
        }
    }

    private void execBlock(BlockContext block) {
        for (StatementContext s : block.statement()) exec(s);
    }

    private void execAssign(AssignmentContext a) {
        Object value = eval(a.expression());
        assign(a.lvalue(), value);
    }

    private void execIf(IfStatementContext i) {
        if (Values.isTruthy(eval(i.condition))) {
            execBlock(i.thenBody);
        } else if (i.elseBody != null) {
            execBlock(i.elseBody);
        }
    }

    private void execFlow(FlowControlContext f) {
        switch (f) {
            case BreakStmtContext b    -> throw Signals.Break.INSTANCE;
            case ContinueStmtContext c -> throw Signals.Continue.INSTANCE;
            case ReturnStmtContext r   -> throw new Signals.Return(r.expression() != null ? eval(r.expression()) : Values.emptyObject());
            case DebugStmtContext d    -> { /* no-op outside the debugger (70_debug_statement) */ }
            default -> throw err("unsupported flow statement", f);
        }
    }

    private void defineFunction(FunctionDefContext f) {
        List<String> params = new ArrayList<>();
        if (f.parameterList() != null) {
            for (var id : f.parameterList().ID()) params.add(id.getText());
        }
        functions.put(f.funcName.getText(), new UserFunction(f.funcName.getText(), params, f.block()));
    }

    // ---- loops -------------------------------------------------------------

    private static final int LOOP_LIMIT = 1000;

    private void execLoop(IterationStmtContext loop) {
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
            if (!infinite && count >= LOOP_LIMIT) throw loopLimit(c);
            if (runBody(c.block())) break;
            count++;
        }
    }

    private void valueLoop(ValueLoopContext v) {
        boolean infinite = v.K_INFINITE() != null;
        String var = v.value.getText();
        int count = 0;
        for (Object element : iterableValues(eval(v.expression()), v)) {
            if (!infinite && count >= LOOP_LIMIT) throw loopLimit(v);
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
            if (!infinite && count >= LOOP_LIMIT) throw loopLimit(kv);
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
        switch (lv) {
            case VarLValueContext v -> currentScope().put(v.ID().getText(), Values.deepCopy(value));
            case GlobalVarLValueContext g -> globals.put(g.ID().getText(), Values.deepCopy(value));
            case PropLValueContext p -> {
                Object container = resolveContainer(p.lvalue());
                setMember(container, p.access(), value, p);
            }
            default -> throw err("unsupported assignment target", lv);
        }
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
        if (inFunction()) {
            Map<String, Object> frame = frameContaining(name);
            if (frame != null) return frame.get(name);
            throw err("Variable '" + name + "' is not defined in local scope. Use ::" + name
                    + " to access the global variable.", ctx);
        }
        if (globals.containsKey(name)) return globals.get(name);
        if (builtinProps.containsKey(name)) return promoteProp(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    private Object globalActual(String name, ParserRuleContext ctx) {
        if (globals.containsKey(name)) return globals.get(name);
        if (builtinProps.containsKey(name)) return promoteProp(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    /** Innermost-first search of active call frames (dynamic local scope, LANGUAGE.md §Lookup Order). */
    private Map<String, Object> frameContaining(String name) {
        for (Map<String, Object> frame : frames) {   // ArrayDeque iterates head(innermost) -> tail(outermost)
            if (frame.containsKey(name)) return frame;
        }
        return null;
    }

    /**
     * Built-in properties are READ-ONLY (LANGUAGE.md §Built-in Properties). The first write through
     * a property copies it into a global that then takes precedence in the lookup chain — the host
     * snapshot is never mutated in place.
     */
    private Object promoteProp(String name) {
        Object copy = Values.deepCopy(builtinProps.get(name));
        globals.put(name, copy);
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
        if (inFunction()) {
            Map<String, Object> frame = frameContaining(name);   // innermost-first across nested calls
            if (frame != null) return frame.get(name);
            throw err("Variable '" + name + "' is not defined in local scope. Use ::" + name
                    + " to access the global variable.", ctx);
        }
        if (globals.containsKey(name)) return globals.get(name);
        if (builtinProps.containsKey(name)) return builtinProps.get(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    private Object lookupGlobal(String name, ParserRuleContext ctx) {
        if (globals.containsKey(name)) return globals.get(name);
        if (builtinProps.containsKey(name)) return builtinProps.get(name);
        throw err("Variable '" + name + "' is not defined", ctx);
    }

    // ---- function calls ----------------------------------------------------

    private Object callFunction(FunctionCallContext fc) {
        String name = fc.funcName.getText();
        List<Object> args = new ArrayList<>();
        for (ExpressionContext arg : fc.expression()) args.add(eval(arg));

        UserFunction fn = functions.get(name);
        if (fn != null) return callUser(fn, args, fc);
        if (name.equals("PRINT")) return doPrint(args);
        if (builtins.has(name)) {
            try {
                return builtins.call(name, args);
            } catch (TeeError e) {
                throw e.at(fc.getStart().getLine(), fc.getStart().getCharPositionInLine());
            }
        }
        throw err("Unknown function '" + name + "'", fc);
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
        frames.push(frame);
        try {
            execBlock(fn.body());
            return Values.emptyObject();          // no explicit return -> {}
        } catch (Signals.Return r) {
            return r.value;
        } finally {
            frames.pop();
        }
    }

    private Object doPrint(List<Object> args) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) line.append(' ');
            line.append(TeeFormat.display(args.get(i)));
        }
        out.append(line).append('\n');
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
        return err("Loop exceeded maximum iterations (" + LOOP_LIMIT
                + "). Use 'loop condition infinite do' if you need unlimited iterations.", ctx);
    }
}
