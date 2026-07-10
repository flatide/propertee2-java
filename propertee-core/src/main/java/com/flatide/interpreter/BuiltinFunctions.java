package com.flatide.interpreter;

import com.flatide.platform.PlatformProvider;
import com.flatide.task.TaskRunner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v1 API surface (com.flatide.interpreter.BuiltinFunctions) over the propertee2 engine.
 *
 * <p>In v1 this class both held the builtin catalog and ran it. In propertee2 the catalog lives in the
 * engine ({@code com.flatide.propertee2.builtin.Builtins}); this façade is the <em>configuration</em> a
 * host supplies — the stdout/stderr print sinks, the run id, the host {@link TaskRunner} (for SHELL) and
 * {@link PlatformProvider} (for file/env builtins), plus any extra host builtins registered via
 * {@link #register}. {@link ProperTeeInterpreter} reads this config when it drives a run.
 */
public class BuiltinFunctions {

    /** A host-registered builtin whose return value is used as-is (no Result wrapper), e.g. STREAM_FILE. */
    public interface BuiltinFunction {
        Object call(List<Object> args);
    }

    /** Output sink: receives the raw PRINT arguments (the host formats/streams them). */
    public interface PrintFunction {
        void print(Object[] args);
    }

    final PrintFunction stdout;
    final PrintFunction stderr;
    final String runId;
    final TaskRunner taskRunner;
    final PlatformProvider platform;
    final Map<String, BuiltinFunction> custom = new LinkedHashMap<>();
    final Map<String, BuiltinFunction> blocking = new LinkedHashMap<>();

    public BuiltinFunctions(PrintFunction stdout, PrintFunction stderr) {
        this(stdout, stderr, null, null, null);
    }

    public BuiltinFunctions(PrintFunction stdout, PrintFunction stderr, String runId, TaskRunner taskRunner) {
        this(stdout, stderr, runId, taskRunner, null);
    }

    public BuiltinFunctions(PrintFunction stdout, PrintFunction stderr, String runId,
                            TaskRunner taskRunner, PlatformProvider platform) {
        this.stdout = stdout;
        this.stderr = stderr;
        this.runId = runId;
        this.taskRunner = taskRunner;
        this.platform = platform;
    }

    /** Register an extra host builtin whose return value is used as-is (no Result wrapper). Runs on the
     *  cooperative baton — only for cheap, non-blocking work (e.g. STREAM_FILE returning a descriptor). */
    public void register(String name, BuiltinFunction fn) {
        // Interpreter-dispatched names are a registration-time error (spec v0.13.0), as in v1 1.6.0.
        custom.put(com.flatide.propertee2.interp.Engine.requireReplaceableName(name), fn);
    }

    /** Register a host builtin that may block (image/network/disk work). It runs OFF the baton and its
     *  return value becomes Result.ok(value) / a thrown exception becomes Result.error(message). Prefer
     *  this over {@link #register} for anything that does real I/O or heavy CPU (e.g. THUMBNAIL). */
    public void registerBlocking(String name, BuiltinFunction fn) {
        blocking.put(com.flatide.propertee2.interp.Engine.requireReplaceableName(name), fn);
    }

    /**
     * Every function name a run configured with this object can call: the interpreter-dispatched
     * names (PRINT/SLEEP/FAIL/UNWRAP), the full engine catalog (incl. host-gated/blocking — ENV,
     * file I/O, HTTP, SHELL), and the host builtins registered on this instance. Lets a host lint
     * scripts for unknown ALL-CAPS calls before running them: all-uppercase names are reserved for
     * builtins/host functions (spec v0.12.0), so an unknown one can never be a script function and
     * is guaranteed to fail at call time.
     */
    public java.util.Set<String> knownFunctionNames() {
        java.util.Set<String> names = new java.util.TreeSet<>();
        names.addAll(com.flatide.propertee2.interp.Engine.INTERPRETER_DISPATCHED_NAMES);
        // The name set is independent of the actual host wiring, so enumerate a bare catalog.
        names.addAll(com.flatide.propertee2.builtin.Builtins
                .standard((com.flatide.propertee2.host.PlatformProvider) null).names());
        names.addAll(custom.keySet());
        names.addAll(blocking.keySet());
        return names;
    }

    /** Release host resources (e.g. the TaskRunner's process pool). Called by the host after a run. */
    public void shutdown() {
        if (taskRunner != null) taskRunner.shutdown();
    }
}
