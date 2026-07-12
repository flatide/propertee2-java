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
}
