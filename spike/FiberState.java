/**
 * Spike — fiber lifecycle states (design §2: "scheduler is repurposed, not deleted").
 * The design names READY / SLEEPING / BLOCKED / WAITING; RUNNING (holds the baton),
 * NEW (spawned, not yet selected) and DONE are added for a complete lifecycle.
 */
enum FiberState {
    NEW,       // created, vthread parked, never selected yet
    READY,     // wants the baton, waiting to be selected
    RUNNING,   // holds the baton — the single fiber executing interpreter code
    SLEEPING,  // Coop.sleep: in the wake queue, baton released
    BLOCKED,   // Coop.blocking: doing a real blocking call OFF the baton
    WAITING,   // Coop.multi parent: parked until its worker children finish
    DONE        // body returned, baton released for the last time
}
