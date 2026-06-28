import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spike — one ProperTee logical thread == one Java virtual thread (design §1).
 * The Java call stack of this vthread IS the continuation: when its body parks
 * mid-expression, the whole stack is preserved and resumes exactly in place.
 */
final class Fiber {
    final int id;
    final String name;
    final Thread vthread;          // the carrier-independent virtual thread

    volatile FiberState state = FiberState.NEW;
    long wakeNanos;                // valid while SLEEPING

    // Per-fiber interpreter context (design §5 says ScopedValue in production;
    // a plain map is enough for the spike since the baton serializes access).
    final Map<String, Object> locals = new LinkedHashMap<>();

    Fiber(int id, String name, Thread vthread) {
        this.id = id;
        this.name = name;
        this.vthread = vthread;
    }

    @Override public String toString() { return "f" + id + "(" + name + "," + state + ")"; }
}
