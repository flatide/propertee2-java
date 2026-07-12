package com.flatide.propertee2.scheduler;

// v1 API surface (com.flatide.propertee2.scheduler.SchedulerListener) — host hook for logical-thread lifecycle events.
public interface SchedulerListener {
    void onThreadCreated(ThreadContext thread);
    void onThreadUpdated(ThreadContext thread);
    void onThreadCompleted(ThreadContext thread);
    void onThreadError(ThreadContext thread);
}
