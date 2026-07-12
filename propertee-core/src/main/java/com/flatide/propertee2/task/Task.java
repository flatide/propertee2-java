package com.flatide.propertee2.task;

import java.io.File;

// Ported verbatim from propertee-java v1.0.0 (com.flatide.propertee2.task) — host-task API reused as-is (design §7).
public class Task {
    public String taskId;
    public String runId;
    public Integer threadId;
    public String threadName;
    public String command;
    public int pid;
    public int pgid;
    public long pidStartTime;
    public TaskStatus status;
    public boolean alive;
    public long startTime;
    public Long endTime;
    public Integer exitCode;
    public long timeoutMs;
    public String cwd;
    public String hostInstanceId;
    public Long lastStdoutAt;
    public Long lastStderrAt;
    public boolean archived;
    public String stdoutTail;
    public String stderrTail;

    public transient File taskDir;
    public transient File metaFile;
    public transient File archiveFile;
    public transient File stdoutFile;
    public transient File stderrFile;
    public transient File exitCodeFile;
    public transient File commandPidFile;
    public transient File commandFile;

    public void bindFiles(File taskDirectory) {
        this.taskDir = taskDirectory;
        this.metaFile = new File(taskDirectory, "meta.json");
        this.archiveFile = new File(taskDirectory, "archive.json");
        this.stdoutFile = new File(taskDirectory, "stdout.log");
        this.stderrFile = new File(taskDirectory, "stderr.log");
        this.exitCodeFile = new File(taskDirectory, "exit.code");
        this.commandPidFile = new File(taskDirectory, "command.pid");
        this.commandFile = new File(taskDirectory, "command.sh");
    }
}
