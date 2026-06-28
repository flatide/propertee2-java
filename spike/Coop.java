import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Spike — the Coop primitive surface (design §3) over one {@link Scheduler}.
 * Every potential blocking point goes through here so the "no blocking while holding
 * the baton" invariant (design §1) is structurally enforced, never bypassed.
 */
final class Coop {
    final Scheduler scheduler = new Scheduler();

    void run(String rootName, Runnable rootBody) { scheduler.runToCompletion(rootName, rootBody); }

    // ---- primitives ------------------------------------------------------

    void yield_()                  { scheduler.yield_(); }
    void sleep(long ms)            { scheduler.sleep(ms); }
    <T> T blocking(java.util.function.Supplier<T> work) { return scheduler.blocking(work); }

    // ---- multi (hand-rolled — STS avoided, confirmed still preview in step 0) ----

    /** One worker of a multi: a key plus a body that receives the read-only globals snapshot. */
    record Spec(String key, Function<Map<String, Object>, Object> worker) { }

    /** A monitor block: read-only, fired on an interval while workers run (design §4). */
    record Monitor(long intervalMs, Consumer<Map<String, Result>> tick) { }

    /**
     * Run workers as cooperative child fibers, collect {status,ok,value} results in spec order,
     * fire the optional monitor on its interval against the live result map, and return once all
     * workers AND the monitor have finished — so the monitor's final tick is guaranteed to land
     * before the parent resumes (fixture 56_monitor_reads_result orders the final monitor output
     * ahead of post-multi output). In production this ordering is the scheduler's responsibility.
     *
     * Globals are passed as a snapshot. NOTE: this only blocks TOP-LEVEL writes — nested
     * object/list mutation is NOT prevented here. Real purity (read-only globals + COW/deepCopy,
     * value-model §5/§6) is enforced at the value layer in PA–PE, not by this spike.
     */
    Map<String, Result> multi(List<Spec> specs, Monitor monitor, Map<String, Object> globals) {
        Fiber parent = scheduler.self();
        Map<String, Object> snapshot = Collections.unmodifiableMap(new LinkedHashMap<>(globals));

        // Seed in spec order so the result map iterates deterministically; workers start "running".
        Map<String, Result> results = new LinkedHashMap<>();
        for (Spec s : specs) results.put(s.key(), Result.running());

        AtomicInteger remaining = new AtomicInteger(specs.size());                 // gates the monitor loop
        AtomicInteger parentPending = new AtomicInteger(specs.size() + (monitor != null ? 1 : 0)); // gates parent resume
        Runnable signalParent = () -> { if (parentPending.decrementAndGet() == 0) scheduler.wake(parent); };

        for (Spec s : specs) {
            scheduler.spawn(s.key(), () -> {
                try {
                    Object v = s.worker().apply(snapshot);
                    results.put(s.key(), Result.ok(v));
                } catch (Throwable t) {              // worker error (incl. illegal global write) -> error result
                    results.put(s.key(), Result.error(t.getMessage()));
                } finally {
                    remaining.decrementAndGet();
                    signalParent.run();
                }
            });
        }

        if (monitor != null) {
            scheduler.spawn("monitor", () -> {
                try {
                    while (remaining.get() > 0) {
                        scheduler.sleep(monitor.intervalMs());
                        monitor.tick().accept(Collections.unmodifiableMap(results));
                    }
                    monitor.tick().accept(Collections.unmodifiableMap(results));   // final settled read
                } finally {
                    signalParent.run();                                            // parent waits for this
                }
            });
        }

        scheduler.awaitChildren(() -> parentPending.get() == 0);
        return results;
    }
}
