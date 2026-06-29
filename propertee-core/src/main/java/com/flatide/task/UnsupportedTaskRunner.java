package com.flatide.task;

import java.util.Map;

/**
 * Ported verbatim from propertee-java v1.0.0 (com.flatide.task) — host-task API reused as-is (design §7).
 *
 * Default TaskRunner used when a host application does not provide external
 * process execution support.
 */
public class UnsupportedTaskRunner implements TaskRunner {
    private static final String MESSAGE =
            "SHELL() requires a host-provided TaskRunner in this environment";

    @Override
    public Task execute(TaskRequest request) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Task getTask(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Task waitForCompletion(String taskId, long timeoutMs) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public boolean killTask(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public TaskObservation observe(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public String getStdout(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public String getStderr(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public String getCombinedOutput(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public String getCombinedOutput(String taskId, int maxBytes) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Integer getExitCode(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public Map<String, Object> getStatusMap(String taskId) {
        throw new UnsupportedOperationException(MESSAGE);
    }

    @Override
    public void releaseTask(String taskId) {
        // no-op — no state to release
    }

    @Override
    public void shutdown() {
        // no-op
    }
}
