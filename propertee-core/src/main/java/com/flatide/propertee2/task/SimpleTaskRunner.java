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
 * failure surfaces as the task's error output) and captures the latest stdout/stderr in rolling
 * in-memory tails capped at 1&nbsp;MB per stream. {@link #releaseTask} frees a task's state
 * immediately — the SHELL builtin calls it once the result is extracted, so a long session does
 * not accumulate task state.
 * Hosts that need persistence, kill-by-run, or restart survival provide their own runner
 * (TeeBox's ManagedTaskEngine).
 */
public class SimpleTaskRunner implements TaskRunner {
    private static final int MAX_OUTPUT_BYTES = 1024 * 1024; // 1 MB cap per stream

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);

    private static final class TaskState {
        final String taskId;
        final String runId;
        final Integer threadId;
        final String threadName;
        final String command;
        final long startTime = System.currentTimeMillis();
        Process process;
        int pid;
        final BoundedOutputBuffer stdout = new BoundedOutputBuffer(MAX_OUTPUT_BYTES);
        final BoundedOutputBuffer stderr = new BoundedOutputBuffer(MAX_OUTPUT_BYTES);
        Thread stdoutDrainer;
        Thread stderrDrainer;
        Integer exitCode;
        volatile boolean alive = true;

        TaskState(String taskId, TaskRequest request) {
            this.taskId = taskId;
            this.runId = request.runId;
            this.threadId = request.threadId;
            this.threadName = request.threadName;
            this.command = request.command;
        }
    }

    /** Byte-accurate rolling tail; once full, oldest bytes are evicted and new output is retained. */
    private static final class BoundedOutputBuffer {
        private final byte[] bytes;
        private int start;
        private int size;

        BoundedOutputBuffer(int capacity) {
            bytes = new byte[capacity];
        }

        synchronized void append(byte[] source, int offset, int length) {
            if (length <= 0) return;
            if (length >= bytes.length) {
                System.arraycopy(source, offset + length - bytes.length, bytes, 0, bytes.length);
                start = 0;
                size = bytes.length;
                return;
            }

            int overflow = Math.max(0, size + length - bytes.length);
            start = (start + overflow) % bytes.length;
            size -= overflow;

            int end = (start + size) % bytes.length;
            int first = Math.min(length, bytes.length - end);
            System.arraycopy(source, offset, bytes, end, first);
            if (first < length) {
                System.arraycopy(source, offset + first, bytes, 0, length - first);
            }
            size += length;
        }

        synchronized void append(String text) {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            append(encoded, 0, encoded.length);
        }

        synchronized String snapshot() {
            byte[] ordered = new byte[size];
            int first = Math.min(size, bytes.length - start);
            System.arraycopy(bytes, start, ordered, 0, first);
            if (first < size) System.arraycopy(bytes, 0, ordered, first, size - first);

            // Eviction may land in the middle of a UTF-8 sequence. Skip continuation bytes so the
            // visible tail never begins with a replacement character solely because of the cap.
            int offset = 0;
            while (offset < ordered.length && (ordered[offset] & 0xC0) == 0x80) offset++;
            return new String(ordered, offset, ordered.length - offset, StandardCharsets.UTF_8);
        }
    }

    @Override
    public Task execute(TaskRequest request) {
        String taskId = "task-" + nextId.incrementAndGet();
        TaskState state = new TaskState(taskId, request);

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
            long processId = state.process.pid();
            state.pid = processId <= Integer.MAX_VALUE ? (int) processId : 0;
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
        return toTask(state);
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
        if (maxBytes <= 0) return combined;
        byte[] encoded = combined.getBytes(StandardCharsets.UTF_8);
        if (encoded.length <= maxBytes) return combined;
        int offset = encoded.length - maxBytes;
        while (offset < encoded.length && (encoded[offset] & 0xC0) == 0x80) offset++;
        return new String(encoded, offset, encoded.length - offset, StandardCharsets.UTF_8);
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
        BoundedOutputBuffer buf = isStderr ? state.stderr : state.stdout;
        return buf.snapshot();
    }

    private static Thread startDrainer(InputStream is, BoundedOutputBuffer buf, String name) {
        Thread t = new Thread(() -> {
            try {
                byte[] tmp = new byte[4096];
                int len;
                while ((len = is.read(tmp)) != -1) {
                    buf.append(tmp, 0, len);
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
        task.runId = state.runId;
        task.threadId = state.threadId;
        task.threadName = state.threadName;
        task.command = state.command;
        task.pid = state.pid;
        task.alive = state.alive;
        task.exitCode = state.exitCode;
        task.startTime = state.startTime;
        task.status = state.alive ? TaskStatus.RUNNING
                : (state.exitCode != null && state.exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
        return task;
    }
}
