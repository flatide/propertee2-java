import java.util.List;

/**
 * Spike — a minimal sealed AST. No ANTLR: the point is only to prove that a plain
 * recursive tree-walk (design §2 "restore as recursive tree-walk") can cooperatively
 * suspend ANYWHERE in the call stack, because the Java stack itself is the continuation.
 */
sealed interface Node
        permits Lit, Var, Add, Assign, CallFn, SleepMs, BlockingCall, SideEffect, Print, Return, Seq { }

/** literal value */
record Lit(Object v) implements Node { }
/** read a local variable */
record Var(String name) implements Node { }
/** a + b  — proves suspension MID-EXPRESSION when b is f() that sleeps/blocks */
record Add(Node a, Node b) implements Node { }
/** x = expr — proves suspension during `x = f()` */
record Assign(String name, Node expr) implements Node { }
/** f(args...) — proves suspension during `return f()` and across nested frames */
record CallFn(String fn, List<Node> args) implements Node { }
/** cooperative SLEEP (seam 1) */
record SleepMs(long ms) implements Node { }
/** a host blocking call routed through Coop.blocking (seam 4) — sleeps `ms` then returns `ret` */
record BlockingCall(long ms, Object ret) implements Node { }
/** a leading side effect (runs eff, returns ret) — used to prove it executes exactly once */
record SideEffect(Runnable eff, Object ret) implements Node { }
/** print the evaluated value with a timestamp */
record Print(String label, Node expr) implements Node { }
/** return expr */
record Return(Node expr) implements Node { }
/** statement sequence; returns the last value (or whatever Return unwinds to) */
record Seq(List<Node> stmts) implements Node { }
