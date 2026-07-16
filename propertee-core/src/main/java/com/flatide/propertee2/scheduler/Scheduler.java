package com.flatide.propertee2.scheduler;

import com.flatide.propertee2.interpreter.ProperTeeInterpreter;

/**
 * v1 API surface (com.flatide.propertee2.scheduler.Scheduler). v1's scheduler drove the stepper; propertee2 owns
 * cooperative scheduling inside the engine, so this façade simply hands the run to the interpreter and
 * relays the result. The optional {@link SchedulerListener} receives logical-thread lifecycle events.
 */
public class Scheduler {

    private final ProperTeeInterpreter visitor;
    private final SchedulerListener listener;

    public Scheduler(ProperTeeInterpreter visitor) {
        this(visitor, null);
    }

    public Scheduler(ProperTeeInterpreter visitor, SchedulerListener listener) {
        this.visitor = visitor;
        this.listener = listener;
    }

    public Object run(ProperTeeInterpreter.RootStepper mainStepper) {
        return visitor.execute(mainStepper, listener);
    }

    /**
     * Cooperatively abort the running program — see {@link ProperTeeInterpreter#abort()}.
     * Thread-safe; the aborted {@link #run} throws {@code ProperTeeAborted}.
     */
    public void abort() {
        visitor.abort();
    }
}
