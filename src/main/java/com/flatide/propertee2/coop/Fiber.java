package com.flatide.propertee2.coop;

/**
 * One ProperTee logical thread == one Java virtual thread (design §1). The Java call
 * stack of this vthread IS the continuation: when its body parks mid-expression, the
 * whole stack is preserved and resumes exactly in place.
 */
public final class Fiber {
    final int id;
    final String name;
    final Thread vthread;          // the carrier-independent virtual thread

    volatile FiberState state = FiberState.NEW;
    long wakeNanos;                // valid while SLEEPING

    Fiber(int id, String name, Thread vthread) {
        this.id = id;
        this.name = name;
        this.vthread = vthread;
    }

    public int id() { return id; }
    public String name() { return name; }
    public FiberState state() { return state; }

    @Override public String toString() { return "f" + id + "(" + name + "," + state + ")"; }
}
