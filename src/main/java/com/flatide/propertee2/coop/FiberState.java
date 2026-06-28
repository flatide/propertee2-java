package com.flatide.propertee2.coop;

/**
 * Fiber lifecycle states (design §2: "scheduler is repurposed, not deleted").
 * The design names READY / SLEEPING / BLOCKED / WAITING; RUNNING (holds the baton),
 * NEW (spawned, not yet selected) and DONE complete the lifecycle.
 */
public enum FiberState {
    NEW,       // created, vthread parked, never selected yet
    READY,     // wants the baton, waiting to be selected
    RUNNING,   // holds the baton — the single fiber executing interpreter code
    SLEEPING,  // Coop.sleep: in the wake queue, baton released
    BLOCKED,   // Coop.blocking: doing a real blocking call OFF the baton
    WAITING,   // Coop.multi parent: parked until its worker children finish (PD)
    DONE        // body returned, baton released for the last time
}
