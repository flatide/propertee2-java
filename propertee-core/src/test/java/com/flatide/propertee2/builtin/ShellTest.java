package com.flatide.propertee2.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.flatide.propertee2.interp.Engine;
import com.flatide.task.Task;
import com.flatide.task.TaskObservation;
import com.flatide.task.TaskRequest;
import com.flatide.task.TaskRunner;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SHELL routes through the host TaskRunner (com.flatide.task contract); default = unsupported. */
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
}
