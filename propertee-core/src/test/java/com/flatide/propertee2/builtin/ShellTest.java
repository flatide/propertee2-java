package com.flatide.propertee2.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.interp.Engine;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskObservation;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.task.TaskRunner;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** SHELL routes through the host TaskRunner (com.flatide.propertee2.task contract); default = unsupported. */
class ShellTest {

    /** Minimal in-memory TaskRunner: one finished task with fixed output + exit code. */
    private static final class StubRunner implements TaskRunner {
        private final String output;
        private final int exitCode;
        StubRunner(String output, int exitCode) { this.output = output; this.exitCode = exitCode; }

        @Override public Task execute(TaskRequest r) { Task t = new Task(); t.taskId = "t1"; t.alive = false; t.exitCode = exitCode; return t; }
        @Override public Task waitForCompletion(String id, long ms) { Task t = new Task(); t.taskId = id; t.alive = false; t.exitCode = exitCode; return t; }
        @Override public TaskObservation observe(String id) { TaskObservation o = new TaskObservation(); o.taskId = id; o.alive = false; o.status = exitCode == 0 ? "completed" : "failed"; return o; }
        @Override public String getCombinedOutput(String id, int maxBytes) { return output; }
        @Override public Integer getExitCode(String id) { return exitCode; }
        @Override public Task getTask(String id) { return null; }
        @Override public boolean killTask(String id) { return false; }
        @Override public String getStdout(String id) { return output; }
        @Override public String getStderr(String id) { return ""; }
        @Override public String getCombinedOutput(String id) { return output; }
        @Override public Map<String, Object> getStatusMap(String id) { return Map.of(); }
        @Override public void releaseTask(String id) { }
        @Override public void shutdown() { }
    }

    @Test void shellRunsViaRegisteredTaskRunner() {
        String out = new Engine().setTaskRunner(new StubRunner("hello world\n", 0)).run("""
                r = SHELL("echo hello world")
                PRINT(r.ok)
                PRINT(r.value)
                """);
        assertEquals("true\nhello world\n", out);   // trailing newline trimmed by SHELL
    }

    @Test void shellReportsFailureExitCode() {
        String out = new Engine().setTaskRunner(new StubRunner("boom\n", 1)).run("""
                r = SHELL("false")
                PRINT(r.ok)
                PRINT(r.value)
                """);
        assertEquals("false\nboom\n", out);
    }

    @Test void shellWithoutRunnerErrors() {
        String out = new Engine().run("r = SHELL(\"echo hi\")\nPRINT(r.ok)\nPRINT(r.value)\n");
        assertEquals("false\nSHELL() requires a host-provided TaskRunner in this environment\n", out);
    }

    /**
     * Command-aware, thread-safe runner: each task gets a distinct id and echoes back its command's
     * argument, so concurrent multi/thread SHELL workers receive distinct outputs. (The default
     * {@link StubRunner} returns one fixed output for every task, which can't tell parallel workers apart.)
     */
    private static final class EchoRunner implements TaskRunner {
        private final AtomicInteger seq = new AtomicInteger();
        private final Map<String, String> outById = new ConcurrentHashMap<>();

        @Override public Task execute(TaskRequest r) {
            Task t = new Task();
            t.taskId = "task-" + seq.incrementAndGet();
            t.alive = false;
            t.exitCode = 0;
            String cmd = r.command != null ? r.command : "";
            outById.put(t.taskId, (cmd.startsWith("echo ") ? cmd.substring(5) : cmd) + "\n");
            return t;
        }
        @Override public Task waitForCompletion(String id, long ms) { Task t = new Task(); t.taskId = id; t.alive = false; t.exitCode = 0; return t; }
        @Override public TaskObservation observe(String id) { TaskObservation o = new TaskObservation(); o.taskId = id; o.alive = false; o.status = "completed"; return o; }
        @Override public String getCombinedOutput(String id, int maxBytes) { return outById.getOrDefault(id, ""); }
        @Override public Integer getExitCode(String id) { return 0; }
        @Override public Task getTask(String id) { return null; }
        @Override public boolean killTask(String id) { return false; }
        @Override public String getStdout(String id) { return outById.getOrDefault(id, ""); }
        @Override public String getStderr(String id) { return ""; }
        @Override public String getCombinedOutput(String id) { return outById.getOrDefault(id, ""); }
        @Override public Map<String, Object> getStatusMap(String id) { return Map.of(); }
        @Override public void releaseTask(String id) { }
        @Override public void shutdown() { }
    }

    /**
     * Multi-SHELL: three SHELL tasks run in parallel via multi/thread, each through the host TaskRunner.
     * Conformance fixtures 72/78/80 already cover this language path in core-only mode (no runner →
     * the unsupported-runner error); this asserts that WITH a runner each parallel worker's result
     * carries its own command's output. Results are printed by key, so the output is deterministic
     * regardless of worker scheduling order.
     */
    @Test void multiThreadShellRunsParallelTasksViaRunnerWithDistinctResults() {
        String out = new Engine().setTaskRunner(new EchoRunner()).run("""
                function worker(name) do
                    return SHELL("echo " + name)
                end
                multi result do
                    thread alpha: worker("alpha")
                    thread beta: worker("beta")
                    thread gamma: worker("gamma")
                end
                PRINT(result.alpha.value.ok)
                PRINT(result.alpha.value.value)
                PRINT(result.beta.value.value)
                PRINT(result.gamma.value.value)
                """);
        assertEquals("true\nalpha\nbeta\ngamma\n", out);
    }

    /** A monitor makes the existing SHELL wait publish bounded progress from that same worker. */
    @Test void monitoredShellPublishesLatestLineAndWorkerMetadataWithoutAnotherWatchdog() {
        AtomicReference<TaskRequest> request = new AtomicReference<>();
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger boundedReads = new AtomicInteger();
        TaskRunner runner = new TaskRunner() {
            private volatile String output = "";

            @Override public Task execute(TaskRequest r) {
                request.set(r);
                Task t = task(true);
                t.pid = 4321;
                return t;
            }
            @Override public Task waitForCompletion(String id, long timeoutMs) {
                assertEquals(100L, timeoutMs, "monitored SHELL must use bounded waits");
                int n = waits.incrementAndGet();
                output = n == 1 ? "first\n" : "first\nsecond\n";
                Task t = task(n == 1);
                t.pid = 4321;
                return t;
            }
            @Override public TaskObservation observe(String id) {
                TaskObservation o = new TaskObservation();
                o.taskId = id;
                o.alive = false;
                o.status = "completed";
                return o;
            }
            @Override public String getCombinedOutput(String id, int maxBytes) {
                boundedReads.incrementAndGet();
                return output;
            }
            @Override public Integer getExitCode(String id) { return 0; }
            @Override public Task getTask(String id) { return null; }
            @Override public boolean killTask(String id) { return false; }
            @Override public String getStdout(String id) { return output; }
            @Override public String getStderr(String id) { return ""; }
            @Override public String getCombinedOutput(String id) { return output; }
            @Override public Map<String, Object> getStatusMap(String id) { return Map.of(); }
            @Override public void releaseTask(String id) { }
            @Override public void shutdown() { }

            private Task task(boolean alive) {
                Task t = new Task();
                t.taskId = "progress-task";
                t.alive = alive;
                t.exitCode = alive ? null : 0;
                t.status = alive ? com.flatide.propertee2.task.TaskStatus.RUNNING
                        : com.flatide.propertee2.task.TaskStatus.COMPLETED;
                return t;
            }
        };

        String out = new Engine().setTaskRunner(runner).run("""
                function worker() do
                    return SHELL("ignored")
                end
                multi result do
                    thread job: worker()
                monitor 1000
                    PRINT(result.job.value.shell.pid)
                    PRINT(result.job.value.shell.output)
                    PRINT(result.job.value.shell.updated)
                end
                PRINT(result.job.value.value)
                """);

        assertEquals("4321\nsecond\ntrue\nfirst\nsecond\n", out);
        assertEquals(2, waits.get());
        org.junit.jupiter.api.Assertions.assertTrue(boundedReads.get() >= 3);
        assertEquals(Integer.valueOf(1), request.get().threadId);
        assertEquals("worker", request.get().threadName);
    }

    /** Re-registering SHELL retires the observation seam — the replacement runs, unobserved. */
    @Test void reRegisteringShellRetiresTheMonitorObservationSeam() {
        Builtins b = Builtins.standard(new com.flatide.propertee2.host.DefaultPlatformProvider(),
                new com.flatide.propertee2.task.UnsupportedTaskRunner(), null);
        b.register("SHELL", Builtins.Kind.PURE, args -> "replaced");

        AtomicInteger observed = new AtomicInteger();
        Builtins.ShellObserver observer = new Builtins.ShellObserver() {
            @Override public void starting(TaskRequest request) { observed.incrementAndGet(); }
        };
        // A stale seam would route to the original UnsupportedTaskRunner-backed Shell (a Result
        // error map, observer engaged) instead of the replacement.
        assertEquals("replaced", b.invokeRaw("SHELL", java.util.List.of("cmd"), observer));
        assertEquals(0, observed.get());
    }

    // ---- SimpleTaskRunner (0.19.0): the in-process runner the CLI wires — real /bin/sh -c ----

    @Test void simpleTaskRunnerExecutesRealCommands() {
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            String out = new Engine().setTaskRunner(runner).run("""
                    r = SHELL("echo hello from sh")
                    PRINT(r.ok)
                    PRINT(r.value)
                    f = SHELL("exit 3")
                    PRINT(f.ok)
                    PRINT(f.value)
                    """);
            assertEquals("true\nhello from sh\nfalse\nTask failed with exitCode 3\n", out);
        } finally {
            runner.shutdown();
        }
    }

    @Test void simpleTaskRunnerHonorsCwdEnvAndMergesStderr() {
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            String out = new Engine().setTaskRunner(runner).run("""
                    ctx = SHELL_CTX("/", {"GREETING": "hi-from-env"})
                    r = SHELL(ctx, "echo $GREETING in $(pwd)")
                    PRINT(r.value)
                    e = SHELL("echo to-stderr 1>&2")
                    PRINT(e.ok)
                    PRINT(e.value)
                    """);
            // mergeErrorToStdout: stderr lands in the combined output, exit 0 -> ok
            assertEquals("hi-from-env in /\ntrue\nto-stderr\n", out);
        } finally {
            runner.shutdown();
        }
    }

    /**
     * The case that motivated 0.19.0: SHELL called from multi/thread workers, through the REAL
     * in-process runner (the stub-runner multi test above can't catch runner-level concurrency
     * issues). Each worker's Result carries its own command's output, and the three 400 ms sleeps
     * overlap (SHELL is BLOCKING → Coop.blocking runs the wait off-baton on each worker's own
     * vthread) — a serializing regression would take 1200 ms+ and trip the elapsed guard.
     */
    @Test void simpleTaskRunnerRunsParallelShellsInMultiThreadWorkers() {
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            long start = System.nanoTime();
            String out = new Engine().setTaskRunner(runner).run("""
                    function job(name) do
                        return SHELL("sleep 0.4 && echo done-" + name)
                    end
                    multi result do
                        thread a: job("a")
                        thread b: job("b")
                        thread c: job("c")
                    end
                    PRINT(result.a.value.ok)
                    PRINT(result.a.value.value)
                    PRINT(result.b.value.value)
                    PRINT(result.c.value.value)
                    """);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals("true\ndone-a\ndone-b\ndone-c\n", out);
            org.junit.jupiter.api.Assertions.assertTrue(elapsedMs < 1100,
                    "three 400ms SHELLs should overlap, took " + elapsedMs + "ms");
        } finally {
            runner.shutdown();
        }
    }

    @Test void simpleTaskRunnerReleasesTaskStateAfterShell() {
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            new Engine().setTaskRunner(runner).run("r = SHELL(\"echo x\")\n");
            // SHELL releases the task once the result is extracted; a lightweight runner drops it
            assertEquals(null, runner.getTask("task-1"));
        } finally {
            runner.shutdown();
        }
    }

    @Test void simpleTaskRunnerKeepsNewestOutputAfterItsOneMiBCap() {
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner();
        try {
            String out = new Engine().setTaskRunner(runner).run("""
                    r = SHELL("yes 0123456789 | head -c 1100000; printf '\\nTAIL-MARKER\\n'")
                    PRINT(CONTAINS(r.value, "TAIL-MARKER"))
                    """);
            assertEquals("true\n", out);
        } finally {
            runner.shutdown();
        }
    }
}
