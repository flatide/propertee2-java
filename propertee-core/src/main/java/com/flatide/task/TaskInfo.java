package com.flatide.task;

import java.util.List;

// Ported verbatim from propertee-java v1.0.0 (com.flatide.task) — host-task API reused as-is (design §7).
public class TaskInfo {
    public String taskId;
    public String runId;
    public Integer threadId;
    public String threadName;
    public String command;
    public int pid;
    public int pgid;
    public String status;
    public boolean alive;
    public boolean archived;
    public long elapsedMs;
    public Long lastStdoutAt;
    public Long lastStderrAt;
    public Long lastOutputAgeMs;
    public boolean timeoutExceeded;
    public Integer exitCode;
    public String cwd;
    public String hostInstanceId;
    public List<String> healthHints;
}
