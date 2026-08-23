package com.flatide.propertee2.interp;

import com.flatide.propertee2.builtin.Builtins;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;
import com.flatide.propertee2.value.Values;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One replace-on-next-call SHELL observation slot for a logical {@code multi} worker.
 *
 * <p>The SHELL worker updates this object while it is off the cooperative baton; the monitor fiber
 * consumes immutable map snapshots on the baton. Synchronized publication keeps those two views
 * consistent without exposing the registry as a script builtin or retaining per-SHELL history.
 */
final class WorkerShellObservation implements Builtins.ShellObserver {
    private final int threadId;
    private final String functionName;
    private final String resultKey;

    private boolean hasShell;
    private boolean active;
    private String taskId;
    private int pid;
    private String status;
    private Integer exitCode;
    private long startedAt;
    private long endedAt;
    private Object result;
    private String boundedOutput = "";
    private String lastLine = "";
    private long outputRevision;
    private long deliveredRevision;

    WorkerShellObservation(int threadId, String functionName, String resultKey) {
        this.threadId = threadId;
        this.functionName = functionName;
        this.resultKey = resultKey;
    }

    @Override
    public synchronized void starting(TaskRequest request) {
        request.threadId = Integer.valueOf(threadId);
        request.threadName = functionName;
        hasShell = true;
        active = true;
        taskId = null;
        pid = 0;
        status = "starting";
        exitCode = null;
        startedAt = System.currentTimeMillis();
        endedAt = 0L;
        result = null;
        boundedOutput = "";
        lastLine = "";
        outputRevision = 0L;
        deliveredRevision = 0L;
    }

    @Override
    public synchronized void started(Task task) {
        updateTask(task);
        active = task == null || task.alive;
        if (status == null || "starting".equals(status)) status = "running";
    }

    @Override
    public synchronized void progress(Task task, String output) {
        updateTask(task);
        active = task != null && task.alive;
        updateOutput(output);
    }

    @Override
    public synchronized void completed(Task task, Object shellResult, String output) {
        updateTask(task);
        updateOutput(output);
        active = false;
        endedAt = System.currentTimeMillis();
        result = Values.deepCopy(shellResult);
        if (task == null || task.status == null) {
            status = resultOk(shellResult) ? "completed" : "failed";
        }
    }

    /** Build and consume this worker's monitor-only value projection. */
    synchronized Map<String, Object> monitorValue(String threadState, Object returnValue) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("id", Integer.valueOf(threadId));
        info.put("key", resultKey);
        info.put("functionName", functionName);
        info.put("state", threadState);
        info.put("returnValue", Values.deepCopy(returnValue));

        if (!hasShell) {
            info.put("shell", Values.emptyObject());
            return info;
        }

        boolean updated = outputRevision > deliveredRevision;
        if (updated) deliveredRevision = outputRevision;

        Map<String, Object> shell = new LinkedHashMap<>();
        shell.put("active", Boolean.valueOf(active));
        shell.put("taskId", taskId != null ? taskId : Values.emptyObject());
        shell.put("pid", pid > 0 ? Integer.valueOf(pid) : Values.emptyObject());
        shell.put("status", status != null ? status : "running");
        shell.put("elapsedMs", Integer.valueOf(elapsedMs()));
        shell.put("exitCode", exitCode != null ? exitCode : Values.emptyObject());
        shell.put("output", updated ? lastLine : "");
        shell.put("updated", Boolean.valueOf(updated));
        shell.put("result", result != null ? Values.deepCopy(result) : Values.emptyObject());
        info.put("shell", shell);
        return info;
    }

    private void updateTask(Task task) {
        if (task == null) return;
        if (task.taskId != null) taskId = task.taskId;
        if (task.pid > 0) pid = task.pid;
        if (task.status != null) status = task.status.value();
        if (task.exitCode != null) exitCode = task.exitCode;
        if (task.startTime > 0) startedAt = task.startTime;
    }

    private void updateOutput(String output) {
        String next = output != null ? output : "";
        if (next.equals(boundedOutput)) return;
        boundedOutput = next;
        lastLine = lastLine(next);
        outputRevision++;
    }

    private int elapsedMs() {
        long end = endedAt > 0 ? endedAt : System.currentTimeMillis();
        long elapsed = startedAt > 0 ? Math.max(0L, end - startedAt) : 0L;
        return (int) Math.min((long) Integer.MAX_VALUE, elapsed);
    }

    private static boolean resultOk(Object value) {
        return value instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("ok"));
    }

    private static String lastLine(String output) {
        if (output.isEmpty()) return "";

        // A single trailing line ending terminates the latest line; it is not itself an extra
        // empty line. Two consecutive endings do represent a newly emitted empty logical line.
        int end = output.length();
        char finalChar = output.charAt(end - 1);
        if (finalChar == '\n') {
            end--;
            if (end > 0 && output.charAt(end - 1) == '\r') end--;
        } else if (finalChar == '\r') {
            end--;
        }
        if (end == 0) return "";

        int lf = output.lastIndexOf('\n', end - 1);
        int cr = output.lastIndexOf('\r', end - 1);
        int cut = Math.max(lf, cr);
        return output.substring(cut + 1, end);
    }
}
