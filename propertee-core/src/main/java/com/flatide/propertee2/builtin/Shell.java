package com.flatide.propertee2.builtin;

import com.flatide.propertee2.value.Result;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;
import com.flatide.task.Task;
import com.flatide.task.TaskObservation;
import com.flatide.task.TaskRequest;
import com.flatide.task.TaskRunner;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * {@code SHELL(...)} — executes a shell command via a host-provided {@link TaskRunner}, returning the
 * v1 Result pattern (ported from propertee-java v1.0.0 BuiltinFunctions). The default
 * {@code UnsupportedTaskRunner} throws, which surfaces as
 * {@code Result.error("SHELL() requires a host-provided TaskRunner in this environment")} (72/78/80).
 * The interpreter invokes this off the baton (SHELL is BLOCKING — design §3.1).
 */
final class Shell {
    private static final int OUTPUT_CAP_BYTES = 10 * 1024 * 1024;

    private final TaskRunner taskRunner;
    private final String runId;   // tags each TaskRequest so a host can group tasks by run (null when unset)

    Shell(TaskRunner taskRunner, String runId) { this.taskRunner = taskRunner; this.runId = runId; }

    Object run(List<Object> args) {
        if (args.isEmpty()) return Result.error("SHELL() requires at least 1 argument");
        Task task;
        try {
            task = taskRunner.execute(buildRequest(args));   // UnsupportedTaskRunner throws here (no task created)
        } catch (RuntimeException e) {                        // no runner, or bad args (TeeError) -> Result.error
            return Result.error(e.getMessage());
        }
        // Once a task exists, always release it (finally) so a lightweight TaskRunner doesn't retain its
        // task map / output buffer / process handle on an exception or interrupt path.
        try {
            Task completed = taskRunner.waitForCompletion(task.taskId, 0);
            if (completed == null) return Result.error("Unknown task: " + task.taskId);
            return buildResult(task);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            try { taskRunner.killTask(task.taskId); } catch (RuntimeException ignore) { /* best-effort */ }
            return Result.error("interrupted");
        } catch (RuntimeException e) {                        // observe/getCombinedOutput/getExitCode failure
            return Result.error(e.getMessage());
        } finally {
            try { taskRunner.releaseTask(task.taskId); } catch (RuntimeException ignore) { /* best-effort */ }
        }
    }

    @SuppressWarnings("unchecked")
    private TaskRequest buildRequest(List<Object> args) {
        TaskRequest request = new TaskRequest();
        request.runId = runId;             // associate the task with the host's run (v1 createBaseTaskRequest)
        request.mergeErrorToStdout = true;

        if (args.size() == 1) {
            if (!(args.get(0) instanceof String cmd)) throw new TeeError("SHELL() argument must be a string command");
            request.command = cmd;
            return request;
        }
        if (args.get(0) instanceof String cmd) {                       // SHELL(command, options)
            request.command = cmd;
            if (!(args.get(1) instanceof Map))
                throw new TeeError("SHELL() second argument must be an options object (e.g. {\"timeout\": 5000})");
            applyContext((Map<String, Object>) args.get(1), request);
            return request;
        }
        if (!(args.get(0) instanceof Map))                             // SHELL(ctx, command)
            throw new TeeError("SHELL() first argument must be a string command or context object from SHELL_CTX()");
        Map<String, Object> ctx = unwrapContext((Map<String, Object>) args.get(0));
        if (!(args.get(1) instanceof String command)) throw new TeeError("SHELL() second argument must be a string command");
        request.command = command;
        applyContext(ctx, request);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> unwrapContext(Map<String, Object> ctx) {
        // SHELL_CTX returns a Result {ok, value:{...}} — unwrap to the inner context map.
        if (ctx.containsKey("ok") && ctx.containsKey("value")) {
            if (ctx.get("ok") instanceof Boolean ok && !ok) {
                throw new TeeError("SHELL() received a failed context: " + ctx.get("value"));
            }
            if (ctx.get("value") instanceof Map) return (Map<String, Object>) ctx.get("value");
        }
        return ctx;
    }

    private void applyContext(Map<String, Object> ctx, TaskRequest request) {
        if (ctx.get("cwd") instanceof String cwd) {
            File dir = new File(cwd);
            if (!dir.exists() || !dir.isDirectory()) throw new TeeError("SHELL() directory does not exist: " + cwd);
            request.cwd = dir.getAbsolutePath();
        }
        Object timeout = ctx.get("timeout");
        if (timeout != null) {
            if (!Values.isNumber(timeout)) throw new TeeError("SHELL() timeout must be a number");
            request.timeoutMs = (long) Values.toDouble(timeout);
        }
        if (ctx.get("env") instanceof Map<?, ?> env) {
            for (Map.Entry<?, ?> e : env.entrySet()) {
                request.env.put(String.valueOf(e.getKey()), TeeFormat.display(e.getValue()));
            }
        }
    }

    private Object buildResult(Task task) {
        TaskObservation obs = taskRunner.observe(task.taskId);
        if (obs == null) return Result.error("Unknown task: " + task.taskId);
        if (obs.alive) return Result.running();
        String output = normalize(taskRunner.getCombinedOutput(task.taskId, OUTPUT_CAP_BYTES));
        Integer exitCode = taskRunner.getExitCode(task.taskId);
        if (exitCode != null && exitCode == 0) return Result.ok(output);
        if (output.isEmpty()) {
            if ("killed".equals(obs.status)) output = "killed";
            else if (exitCode != null) output = "Task failed with exitCode " + exitCode;
            else output = obs.status;
        }
        return Result.error(output);
    }

    private static String normalize(String output) {
        if (output == null) return "";
        while (output.endsWith("\n") || output.endsWith("\r")) output = output.substring(0, output.length() - 1);
        return output;
    }
}
