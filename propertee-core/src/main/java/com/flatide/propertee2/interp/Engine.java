package com.flatide.propertee2.interp;

import com.flatide.propertee2.Parsing;
import com.flatide.propertee2.coop.Coop;
import com.flatide.propertee2.host.DefaultPlatformProvider;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.parser.ProperTeeParser;
import com.flatide.propertee2.value.TeeError;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Top-level entry point: parse a ProperTee script and run it as the ROOT fiber of a {@link Coop}
 * cooperative runtime (design §1 — the main program is one logical thread = one vthread). Returns
 * stdout; a {@link TeeError} surfaces as the v1 line
 * {@code Runtime error: Runtime Error at line L:C: <msg>}, appended after whatever was printed.
 *
 * <p>Optional host integration (chainable, configured before {@code run}): external functions
 * ({@link #registerExternal}/{@link #registerExternalAsync}) and hidden keywords / ignored
 * functions ({@link #setHiddenKeywords}/{@link #setIgnoredFunctions}).
 */
public final class Engine {

    private final Map<String, ExternalFunction> externals = new LinkedHashMap<>();
    private Set<String> hiddenKeywords = Set.of();
    private Set<String> ignoredFunctions = Set.of();
    private com.flatide.propertee2.task.TaskRunner taskRunner = new com.flatide.propertee2.task.UnsupportedTaskRunner();

    /** Host process executor for SHELL (the v1 com.flatide.propertee2.task contract). Default: none (SHELL errors). */
    public Engine setTaskRunner(com.flatide.propertee2.task.TaskRunner taskRunner) {
        this.taskRunner = taskRunner != null ? taskRunner : new com.flatide.propertee2.task.UnsupportedTaskRunner();
        return this;
    }

    /**
     * Register an external function (return value -> Result.ok, exception -> Result.error). Runs
     * through Coop.blocking — a host function may block, and the design unifies external registration
     * onto the single Coop.blocking contract (§3.1). Use {@link #registerPure} for a guaranteed
     * non-blocking function that may run on the baton.
     */
    public Engine registerExternal(String name, Function<List<Object>, Object> body) {
        externals.put(requireReplaceableName(name), new ExternalFunction(body, true));
        return this;
    }

    /** Alias of {@link #registerExternal} kept for v1 API familiarity (both honor the §3.1 contract). */
    public Engine registerExternalAsync(String name, Function<List<Object>, Object> body) {
        externals.put(requireReplaceableName(name), new ExternalFunction(body, true));
        return this;
    }

    /** Register a host function the host guarantees is non-blocking — runs in place, keeping the baton. */
    public Engine registerPure(String name, Function<List<Object>, Object> body) {
        externals.put(requireReplaceableName(name), new ExternalFunction(body, false));
        return this;
    }

    /** Names dispatched by the interpreter itself (positioned errors), not via the builtin catalog. */
    public static final Set<String> INTERPRETER_DISPATCHED_NAMES = Set.of("PRINT", "SLEEP", "FAIL", "UNWRAP");

    /**
     * Interpreter-dispatched names cannot be replaced by externals — registering one is a host-API
     * error (spec v0.13.0; previously PRINT/SLEEP were implementation-defined and FAIL/UNWRAP were
     * silently ignored).
     */
    public static String requireReplaceableName(String name) {
        if (INTERPRETER_DISPATCHED_NAMES.contains(name)) {
            throw new IllegalArgumentException("Cannot register an external function named '" + name
                    + "': interpreter-dispatched names (PRINT, SLEEP, FAIL, UNWRAP) cannot be replaced");
        }
        return name;
    }

    /** Keywords made unavailable in this environment (using one is a runtime error). */
    public Engine setHiddenKeywords(Set<String> keywords) {
        this.hiddenKeywords = Set.copyOf(keywords);
        return this;
    }

    /** Function names made unavailable in this environment (calling one is a runtime error). */
    public Engine setIgnoredFunctions(Set<String> functions) {
        this.ignoredFunctions = Set.copyOf(functions);
        return this;
    }

    /**
     * Opt-in <b>static validation</b> of the configured restrictions (ProperTee issue #9): scan the
     * whole parse tree — dead branches included — and return one {@code "line L:C: 'X' is not
     * available in this environment"} entry per hidden-keyword construct / ignored-function call
     * (empty = clean). Syntax errors throw {@link Parsing.SyntaxException} as usual. Running the
     * script still enforces the same restrictions at runtime (backstop) — this pass just moves
     * detection before execution for sandboxing hosts.
     */
    public List<String> validate(String source) {
        return Validator.validate(Parsing.parse(source), hiddenKeywords, ignoredFunctions);
    }

    /** Run with no host-injected properties and the default platform. */
    public String run(String source) {
        return run(source, Map.of());
    }

    /** Run with host-injected built-in properties ({@code -p} / {@code _PROPS}) and the default platform. */
    public String run(String source, Map<String, Object> props) {
        return run(source, props, new DefaultPlatformProvider());
    }

    /** Run with explicit host integration (used by the conformance harness for ENV / file I/O). */
    public String run(String source, Map<String, Object> props, PlatformProvider platform) {
        StringBuilder out = new StringBuilder();
        Interpreter.Sink sink = line -> out.append(line).append('\n');   // buffer each line (conformance)
        try {
            run(source, props, platform, sink, sink);   // both channels merge in order (the fixtures' shape)
        } catch (TeeError e) {
            out.append("Runtime error: ").append(e.positioned()).append('\n');
        }
        return out.toString();
    }

    /** Run with live sinks and the default platform (the CLI's form). */
    public void run(String source, Map<String, Object> props, Interpreter.Sink out, Interpreter.Sink err) {
        run(source, props, new DefaultPlatformProvider(), out, err);
    }

    /**
     * Run with live host sinks: program output ({@code PRINT}) on {@code out}; runtime diagnostics —
     * v1's stderr channel ({@code [THREAD ERROR]}, {@code [MONITOR ERROR]}, loop-limit warnings) —
     * on {@code err}. Lines are emitted in program order across both sinks (everything prints on the
     * baton). Unlike the buffered overloads, a script runtime error propagates as {@link TeeError}
     * for the caller to present.
     */
    public void run(String source, Map<String, Object> props, PlatformProvider platform,
                    Interpreter.Sink out, Interpreter.Sink err) {
        ProperTeeParser.RootContext root = Parsing.parse(source);
        // Load-time rejection (spec v0.14.0): a script that names any blocked construct — hidden
        // keyword or ignored function, dead branches included — does not run at all. Refuse before
        // executing a single statement, failing on the first violation in document order (the runtime
        // checks stay as unreachable backstops). Skip the scan entirely when nothing is restricted.
        if (!hiddenKeywords.isEmpty() || !ignoredFunctions.isEmpty()) {
            TeeError blocked = Validator.firstViolation(root, hiddenKeywords, ignoredFunctions);
            if (blocked != null) throw blocked;
        }
        Coop coop = new Coop();
        Interpreter interp = new Interpreter(out, props, coop, platform,
                externals, hiddenKeywords, ignoredFunctions, taskRunner);
        interp.setErrorSink(err);
        coop.run("main", () -> interp.run(root));   // the program is the root logical thread
    }
}
