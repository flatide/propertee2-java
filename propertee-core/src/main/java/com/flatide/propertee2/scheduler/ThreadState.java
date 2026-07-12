package com.flatide.propertee2.scheduler;

// v1 API surface (com.flatide.propertee2.scheduler.ThreadState) — the lifecycle states a host listener may observe.
public enum ThreadState {
    READY,
    RUNNING,
    SLEEPING,
    WAITING,
    BLOCKED,
    COMPLETED,
    ERROR
}
