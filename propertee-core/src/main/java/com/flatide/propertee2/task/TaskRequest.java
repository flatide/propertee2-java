package com.flatide.propertee2.task;

import java.util.LinkedHashMap;
import java.util.Map;

// Ported verbatim from propertee-java v1.0.0 (com.flatide.propertee2.task) — host-task API reused as-is (design §7).
public class TaskRequest {
    public String runId;
    public Integer threadId;
    public String threadName;
    public String command;
    public long timeoutMs;
    public String cwd;
    public Map<String, String> env = new LinkedHashMap<String, String>();
    public boolean mergeErrorToStdout;
}
