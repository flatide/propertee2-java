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

    // ---- SimpleTaskRunner(live): a task's output is also written to a stream while it runs ----

    private static String text(java.io.ByteArrayOutputStream bytes) {
        return bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A command that waits, after {@code before}, until the returned file exists. */
    private static java.nio.file.Path gate() throws java.io.IOException {
        java.nio.file.Path gate = java.nio.file.Files.createTempFile("shell-gate", ".tmp");
        java.nio.file.Files.delete(gate);
        return gate;
    }

    private static String waitFor(java.nio.file.Path gate) {
        return "while [ ! -e '" + gate + "' ]; do sleep 0.05; done";
    }

    private static void await(java.util.function.BooleanSupplier reached) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!reached.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
    }

    @Test void liveOutputArrivesWhileTheCommandRuns() throws Exception {
        java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream();
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner(live);
        java.nio.file.Path gate = gate();
        try {
            TaskRequest request = new TaskRequest();
            request.command = "echo first; " + waitFor(gate) + "; echo second";
            request.mergeErrorToStdout = true;
            Task task = runner.execute(request);
            await(() -> live.size() > 0);
            assertEquals("first\n", text(live));
            assertEquals(true, runner.getTask(task.taskId).alive, "the command has not ended yet");

            java.nio.file.Files.createFile(gate);
            runner.waitForCompletion(task.taskId, 0);
            assertEquals("first\nsecond\n", text(live));
            assertEquals("first\nsecond\n", runner.getStdout(task.taskId), "and it is captured as before");
        } finally {
            runner.shutdown();
            java.nio.file.Files.deleteIfExists(gate);
        }
    }

    @Test void liveOutputLeavesTheShellResultAsItWas() {
        String script = """
                r = SHELL("echo out; echo err 1>&2; printf 'no line break'")
                PRINT(r.ok)
                PRINT(r.value)
                f = SHELL("echo before; exit 3")
                PRINT(f.ok)
                PRINT(f.value)
                """;
        String expected = "true\nout\nerr\nno line break\nfalse\nbefore\n";
        com.flatide.propertee2.task.SimpleTaskRunner plain = new com.flatide.propertee2.task.SimpleTaskRunner();
        java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream();
        com.flatide.propertee2.task.SimpleTaskRunner shown = new com.flatide.propertee2.task.SimpleTaskRunner(live);
        try {
            assertEquals(expected, new Engine().setTaskRunner(plain).run(script));
            assertEquals(expected, new Engine().setTaskRunner(shown).run(script));
            // stderr is shown with stdout, and a last line without a line break gets one
            assertEquals("out\nerr\nno line break\nbefore\n", text(live));
        } finally {
            plain.shutdown();
            shown.shutdown();
        }
    }

    @Test void liveOutputOfCommandsRunningTogetherStaysInWholeLines() {
        java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream();
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner(live);
        try {
            // each line is written in two parts, so the parts of different commands reach the runner mixed
            new Engine().setTaskRunner(runner).run("""
                    function job(name) do
                        return SHELL("i=1; while [ $i -le 200 ]; do printf '%s-%s-' " + name + " $i; printf 'end\\n'; i=$((i+1)); done")
                    end
                    multi result do
                        thread a: job("a")
                        thread b: job("b")
                        thread c: job("c")
                    end
                    """);
            Map<String, Integer> next = new java.util.HashMap<>(Map.of("a", 1, "b", 1, "c", 1));
            String[] lines = text(live).split("\n");
            assertEquals(600, lines.length);
            for (String line : lines) {
                String[] parts = line.split("-");
                assertEquals(3, parts.length, line);
                assertEquals("end", parts[2], line);
                assertEquals(next.get(parts[0]), Integer.valueOf(parts[1]), "each command's lines in its order: " + line);
                next.put(parts[0], next.get(parts[0]) + 1);
            }
        } finally {
            runner.shutdown();
        }
    }

    @Test void aLongLineIsShownInPieces() throws Exception {
        java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream();
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner(live);
        java.nio.file.Path gate = gate();
        try {
            TaskRequest request = new TaskRequest();
            request.command = "head -c 100000 /dev/zero | tr '\\0' x; " + waitFor(gate) + "; echo; echo next";
            request.mergeErrorToStdout = true;
            Task task = runner.execute(request);
            await(() -> live.size() >= 64 * 1024);
            assertEquals(true, live.size() >= 64 * 1024, "the line does not wait for its end: " + live.size());
            assertEquals(true, runner.getTask(task.taskId).alive);

            java.nio.file.Files.createFile(gate);
            runner.waitForCompletion(task.taskId, 0);
            assertEquals("x".repeat(100000) + "\nnext\n", text(live));
        } finally {
            runner.shutdown();
            java.nio.file.Files.deleteIfExists(gate);
        }
    }

    @Test void aLastLineThatWentOutInPiecesStillGetsItsLineBreak() {
        // 64 KiB without a line break: all of it has gone out as a piece when the output ends
        java.io.ByteArrayOutputStream live = new java.io.ByteArrayOutputStream();
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner(live);
        try {
            String out = new Engine().setTaskRunner(runner).run("""
                    r = SHELL("head -c 65536 /dev/zero | tr '\\0' x")
                    PRINT(LEN(r.value))
                    """);
            assertEquals("65536\n", out);
            assertEquals("x".repeat(65536) + "\n", text(live));
        } finally {
            runner.shutdown();
        }
    }

    @Test void aStreamThatCannotBeWrittenToDoesNotLoseTheOutput() {
        AtomicInteger writes = new AtomicInteger();
        java.io.OutputStream closed = new java.io.OutputStream() {
            @Override public void write(int b) throws java.io.IOException {
                throw new java.io.IOException("closed");
            }
            @Override public void write(byte[] b, int off, int len) throws java.io.IOException {
                writes.incrementAndGet();
                throw new java.io.IOException("closed");
            }
        };
        com.flatide.propertee2.task.SimpleTaskRunner runner = new com.flatide.propertee2.task.SimpleTaskRunner(closed);
        try {
            String out = new Engine().setTaskRunner(runner).run("""
                    r = SHELL("echo one; sleep 0.3; echo two; sleep 0.3; echo three")
                    PRINT(r.ok)
                    PRINT(r.value)
                    """);
            assertEquals("true\none\ntwo\nthree\n", out);
            assertEquals(1, writes.get(), "the command's output is not passed on after the failure");
        } finally {
            runner.shutdown();
        }
    }
}
