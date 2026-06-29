package com.flatide.scheduler;

// v1 API surface (com.flatide.scheduler.ThreadState) — the lifecycle states a host listener may observe.
public enum ThreadState {
    READY,
    RUNNING,
    SLEEPING,
    WAITING,
    BLOCKED,
    COMPLETED,
    ERROR
}
