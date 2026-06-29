package com.flatide.scheduler;

// v1 API surface (com.flatide.scheduler.SchedulerListener) — host hook for logical-thread lifecycle events.
public interface SchedulerListener {
    void onThreadCreated(ThreadContext thread);
    void onThreadUpdated(ThreadContext thread);
    void onThreadCompleted(ThreadContext thread);
    void onThreadError(ThreadContext thread);
}
