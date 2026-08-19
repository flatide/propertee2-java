package com.flatide.propertee2.task;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ported from propertee-java 1.7.0 (same class, same semantics) — the lightweight in-memory
 * TaskRunner for CLI/standalone use, referenced by the {@link TaskRunner} javadoc. No disk
 * persistence, no process group management, no archival: runs shell commands via
 * {@code /bin/sh -c} (Unix — Linux/macOS; on a platform without {@code /bin/sh} the launch
 * failure surfaces as the task's error output) and captures stdout/stderr in memory, capped at
 * 1&nbsp;MB per stream. {@link #releaseTask} frees a task's state immediately — the SHELL builtin
 * calls it once the result is extracted, so a long session does not accumulate task state.
 * Hosts that need persistence, kill-by-run, or restart survival provide their own runner
 * (TeeBox's ManagedTaskEngine).
 */
public class SimpleTaskRunner implements TaskRunner {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024; // 1 MB cap per stream

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    private static final class TaskState {
        final String taskId;
        final String command;
        final long startTime = System.currentTimeMillis();
        Process process;
        final StringBuilder stdout = new StringBuilder();
        final StringBuilder stderr = new StringBuilder();
        Thread stdoutDrainer;
        Thread stderrDrainer;
        Integer exitCode;
        volatile boolean alive = true;

        TaskState(String taskId, String command) {
            this.taskId = taskId;
            this.command = command;
        }
    }

    @Override
    public Task execute(TaskRequest request) {
        String taskId = "task-" + nextId.incrementAndGet();
        TaskState state = new TaskState(taskId, request.command);

        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("/bin/sh");
            cmd.add("-c");
            cmd.add(request.command);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (request.cwd != null) {
                pb.directory(new File(request.cwd));
            }
            if (request.env != null && !request.env.isEmpty()) {
                pb.environment().putAll(request.env);
            }
            if (request.mergeErrorToStdout) {
                pb.redirectErrorStream(true);
            }
            state.process = pb.start();
            // Start drainer threads immediately to prevent OS pipe deadlock
            state.stdoutDrainer = startDrainer(state.process.getInputStream(), state.stdout, "drain-stdout-" + taskId);
            if (!request.mergeErrorToStdout) {
                state.stderrDrainer = startDrainer(state.process.getErrorStream(), state.stderr, "drain-stderr-" + taskId);
            }
        } catch (IOException e) {
            state.alive = false;
            state.exitCode = -1;
            state.stderr.append(e.getMessage());
        }

        tasks.put(taskId, state);

        Task task = new Task();
        task.taskId = taskId;
        task.command = request.command;
        task.status = TaskStatus.RUNNING;
        task.alive = true;
        task.startTime = state.startTime;
        return task;
    }

    @Override
    public Task getTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return null;
        return toTask(state);
    }

    @Override
    public Task waitForCompletion(String taskId, long timeoutMs) throws InterruptedException {
        TaskState state = tasks.get(taskId);
        if (state == null || state.process == null) return toTask(state);

        if (timeoutMs > 0) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (state.alive && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
                checkProcess(state);
            }
        } else {
            state.process.waitFor();
            checkProcess(state);
        }
        return toTask(state);
    }

    @Override
    public boolean killTask(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null || state.process == null || !state.alive) return false;
        state.process.destroyForcibly();
        checkProcess(state);
        return true;
    }

    @Override
    public TaskObservation observe(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return null;
        checkProcess(state);
        TaskObservation obs = new TaskObservation();
        obs.taskId = taskId;
        obs.status = state.alive ? "running" : (state.exitCode != null && state.exitCode == 0 ? "completed" : "failed");
        obs.alive = state.alive;
        obs.elapsedMs = System.currentTimeMillis() - state.startTime;
        return obs;
    }

    @Override
    public String getStdout(String taskId) {
        TaskState state = tasks.get(taskId);
        return state != null ? readOutput(state, false) : "";
    }

    @Override
    public String getStderr(String taskId) {
        TaskState state = tasks.get(taskId);
        return state != null ? readOutput(state, true) : "";
    }

    @Override
    public String getCombinedOutput(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return "";
        String out = readOutput(state, false);
        String err = readOutput(state, true);
        if (err.isEmpty()) return out;
        if (out.isEmpty()) return err;
        return out + "\n" + err;
    }

    @Override
    public String getCombinedOutput(String taskId, int maxBytes) {
        // The in-memory buffers are already capped (1 MB per stream), so the full read is
        // bounded. Trim to the tail if the caller wants less.
        String combined = getCombinedOutput(taskId);
        if (maxBytes <= 0 || combined.length() <= maxBytes) return combined;
        return combined.substring(combined.length() - maxBytes);
    }

    @Override
    public Integer getExitCode(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) return null;
        checkProcess(state);
        return state.exitCode;
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        TaskObservation obs = observe(taskId);
        return obs != null ? obs.toMap() : new LinkedHashMap<>();
    }

    @Override
    public void releaseTask(String taskId) {
        if (taskId == null) return;
        TaskState state = tasks.remove(taskId);
        if (state != null) {
            // Cancel drainers and clear the process reference to release native resources
            joinQuietly(state.stdoutDrainer, 100);
            joinQuietly(state.stderrDrainer, 100);
            state.process = null;
        }
    }

    @Override
    public void shutdown() {
        for (TaskState state : tasks.values()) {
            if (state.process != null && state.alive) {
                state.process.destroyForcibly();
            }
            joinQuietly(state.stdoutDrainer, 200);
            joinQuietly(state.stderrDrainer, 200);
        }
        tasks.clear();
    }

    private void checkProcess(TaskState state) {
        if (!state.alive || state.process == null) return;
        try {
            if (!state.process.isAlive()) {
                state.exitCode = state.process.exitValue();
                state.alive = false;
                // Wait briefly for drainer threads to finish reading remaining output
                joinQuietly(state.stdoutDrainer, 500);
                joinQuietly(state.stderrDrainer, 500);
            }
        } catch (RuntimeException e) {
            // ignore
        }
    }

    private String readOutput(TaskState state, boolean isStderr) {
        checkProcess(state);
        StringBuilder buf = isStderr ? state.stderr : state.stdout;
        synchronized (buf) {
            return buf.toString();
        }
    }

    private static Thread startDrainer(InputStream is, StringBuilder buf, String name) {
        Thread t = new Thread(() -> {
            try {
                byte[] tmp = new byte[4096];
                int len;
                while ((len = is.read(tmp)) != -1) {
                    synchronized (buf) {
                        int room = MAX_OUTPUT_BYTES - buf.length();
                        if (room <= 0) continue; // drain & discard once cap hit
                        int writeLen = Math.min(len, room);
                        buf.append(new String(tmp, 0, writeLen, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignore) {
                // stream closed
            } finally {
                try { is.close(); } catch (IOException ignore) { /* best-effort */ }
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void joinQuietly(Thread t, long timeoutMs) {
        if (t == null) return;
        try {
            t.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Task toTask(TaskState state) {
        if (state == null) return null;
        checkProcess(state);
        Task task = new Task();
        task.taskId = state.taskId;
        task.command = state.command;
        task.alive = state.alive;
        task.exitCode = state.exitCode;
        task.startTime = state.startTime;
        task.status = state.alive ? TaskStatus.RUNNING
                : (state.exitCode != null && state.exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
        return task;
    }
}
