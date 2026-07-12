package com.flatide.propertee2.scheduler;

import java.util.concurrent.Future;

/**
 * v1 API surface (com.flatide.propertee2.scheduler.ThreadContext) — the per-logical-thread snapshot passed to a
 * {@link SchedulerListener}. In propertee2 the cooperative engine owns scheduling internally, so this
 * is the host-facing observation type (the fields a host reads); it carries no stepper/scope machinery.
 */
public class ThreadContext {
    public int id;
    public String name;
    public ThreadState state = ThreadState.READY;
    public boolean inThreadContext = false;
    public Object result = null;
    public Throwable error = null;
    public Integer parentId = null;
    public String resultKeyName = null;
    public Long sleepUntil = null;
    public Future<Object> asyncFuture = null;
}
