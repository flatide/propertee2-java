import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Spike harness (design §10.1) — validates the core assumptions of the Java 25
 * virtual-thread cooperative runtime BEFORE the PA–PF investment. Each check runs on
 * its own fresh single-baton cooperative system and prints PASS/FAIL.
 *
 *   check 1 — Coop/scheduler: deterministic round-robin handoff (step 1)
 *   check 2 — recursive interpreter suspends anywhere in the call stack (step 2)
 *   check 3 — Coop.blocking removes async statement-replay (step 3)
 *   check 4 — hand-rolled multi: result format + live monitor + worker purity (step 4)
 *   check 5 — Coop.blocking re-acquires the baton even when the blocking call throws (regression)
 */
public class SpikeMain {
    private static int failures = 0;

    public static void main(String[] args) {
        System.out.println("ProperTee2 spike — Java " + System.getProperty("java.version") + "\n");
        check1_roundRobin();
        check2_suspendAnywhere();
        check3_noReplay();
        check4_multiMonitorPurity();
        check5_blockingThrows();
        System.out.println();
        if (failures == 0) {
            System.out.println("ALL SPIKE CHECKS PASSED");
        } else {
            System.out.println(failures + " SPIKE CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---- check 1: deterministic round-robin --------------------------------

    private static void check1_roundRobin() {
        banner("1", "deterministic round-robin handoff (scheduler)");
        Log.reset();
        Coop coop = new Coop();
        StringBuilder order = new StringBuilder();

        coop.run("root", () -> {
            List<Coop.Spec> specs = new ArrayList<>();
            for (String key : List.of("A", "B", "C")) {
                specs.add(new Coop.Spec(key, g -> {
                    for (int i = 0; i < 3; i++) {
                        synchronized (order) { order.append(key); }   // baton-serialized anyway; sync for visibility
                        coop.yield_();
                    }
                    return key;
                }));
            }
            coop.multi(specs, null, Map.of());
        });

        String got = order.toString();
        Log.line("interleaving = " + got);
        // 3 fibers spawned in id order, yielding each step => strict A,B,C round-robin.
        assertEq("ABCABCABC", got, "round-robin interleaving");
    }

    // ---- check 2: suspend anywhere in the call stack -----------------------

    private static void check2_suspendAnywhere() {
        banner("2", "recursive interpreter suspends ANYWHERE in the call stack");

        // f() -> g() -> SLEEP(100) -> 1.0   (the suspend point is two frames deep)
        Map<String, Interp.FnDef> fns = new LinkedHashMap<>();
        fns.put("g", new Interp.FnDef(List.of(), new Seq(List.of(new SleepMs(100), new Return(new Lit(1.0))))));
        fns.put("f", new Interp.FnDef(List.of(), new Return(new CallFn("g", List.of()))));

        // three seam shapes, each with the sleep buried inside f()->g()
        Map<String, Node> shapes = new LinkedHashMap<>();
        shapes.put("x = f()",   new Assign("x", new CallFn("f", List.of())));
        shapes.put("a + f()",   new Add(new Lit(1.0), new CallFn("f", List.of())));
        shapes.put("return f()",new Return(new CallFn("f", List.of())));

        boolean allOverlapped = true;
        for (Map.Entry<String, Node> shape : shapes.entrySet()) {
            long elapsed = runShapeOnNWorkers(fns, shape.getValue(), 4);
            boolean overlapped = elapsed < 250;   // 4×100ms serial would be ~400ms; overlap ~100ms
            Log.line(String.format("shape %-11s  4 workers × SLEEP(100ms)  ->  %dms  %s",
                    "'" + shape.getKey() + "'", elapsed, overlapped ? "(overlap)" : "(SERIAL!)"));
            allOverlapped &= overlapped;
        }
        assertTrue(allOverlapped, "all seam shapes overlapped (~1x, not serial ~4x)");
    }

    /** Run `shape` on N cooperative workers; return wall-clock ms of the multi(). */
    private static long runShapeOnNWorkers(Map<String, Interp.FnDef> fns, Node shape, int n) {
        Coop coop = new Coop();
        Interp interp = new Interp(coop, fns);
        AtomicReference<Long> elapsed = new AtomicReference<>(0L);
        coop.run("root", () -> {
            List<Coop.Spec> specs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                specs.add(new Coop.Spec("w" + i, g -> interp.eval(shape, new LinkedHashMap<>())));
            }
            long t0 = System.nanoTime();
            coop.multi(specs, null, Map.of());
            elapsed.set((System.nanoTime() - t0) / 1_000_000L);
        });
        return elapsed.get();
    }

    // ---- check 3: Coop.blocking removes async statement-replay --------------

    private static void check3_noReplay() {
        banner("3", "Coop.blocking removes async statement-replay (leading side effect runs once)");
        Log.reset();
        Coop coop = new Coop();
        AtomicInteger sideEffectRuns = new AtomicInteger(0);

        // Single statement:  x = SIDE_EFFECT(+10)  +  BLOCKING(80ms -> 5)
        // v1's async replay would re-run the whole statement on resume => SIDE_EFFECT twice.
        // With Coop.blocking the Java stack is preserved => SIDE_EFFECT exactly once.
        Node program = new Assign("x", new Add(
                new SideEffect(sideEffectRuns::incrementAndGet, 10.0),
                new BlockingCall(80, 5.0)));

        Interp interp = new Interp(coop, Map.of());
        AtomicReference<Object> xVal = new AtomicReference<>();
        coop.run("root", () -> {
            Map<String, Object> env = new LinkedHashMap<>();
            interp.eval(program, env);
            xVal.set(env.get("x"));
        });

        Log.line("side-effect executions = " + sideEffectRuns.get() + " (v1 replay would be 2)");
        Log.line("x = " + xVal.get());
        assertEq(1, sideEffectRuns.get(), "leading side effect executed exactly once");
        assertEq(15.0, xVal.get(), "x == 10 + 5");
    }

    // ---- check 4: multi result format + live monitor + purity --------------

    private static void check4_multiMonitorPurity() {
        banner("4", "hand-rolled multi: result {status,ok,value} + live monitor + worker purity");
        Log.reset();
        Coop coop = new Coop();
        Map<String, Object> globals = Map.of("base", 100.0);

        List<Integer> settledPerTick = new ArrayList<>();
        AtomicReference<Map<String, Result>> out = new AtomicReference<>();

        coop.run("root", () -> {
            List<Coop.Spec> specs = new ArrayList<>();
            specs.add(new Coop.Spec("fast", g -> { coop.sleep(40);  return (double) g.get("base") + 1; }));
            specs.add(new Coop.Spec("mid",  g -> { coop.sleep(90);  return (double) g.get("base") + 2; }));
            specs.add(new Coop.Spec("slow", g -> { coop.sleep(140); return (double) g.get("base") + 3; }));
            specs.add(new Coop.Spec("rogue",g -> { coop.sleep(20);  g.put("base", 0.0); return 0.0; })); // purity violation

            Coop.Monitor monitor = new Coop.Monitor(30, live -> {
                long settled = live.values().stream().filter(r -> !r.status().equals("running")).count();
                settledPerTick.add((int) settled);
                Log.line("monitor tick: " + live);
            });

            out.set(coop.multi(specs, monitor, globals));
        });

        Map<String, Result> results = out.get();
        Log.line("final results = " + results);
        Log.line("monitor settled-per-tick = " + settledPerTick);

        // result format & values
        assertEq("done",  results.get("fast").status(), "fast done");
        assertEq(101.0,   results.get("fast").value(),  "fast value = base+1");
        assertEq("done",  results.get("slow").status(), "slow done");
        assertEq("error", results.get("rogue").status(), "rogue top-level global write -> error result");
        // deterministic iteration order = spec order
        assertEq("[fast, mid, slow, rogue]", results.keySet().toString(), "result order = spec order");
        // live monitor: saw a partial (intermediate) state, monotonic, and the FINAL tick (settled)
        // landed before the parent resumed (fixture-56 ordering — multi waits for the monitor).
        boolean sawPartial = settledPerTick.stream().anyMatch(c -> c > 0 && c < 4);
        boolean monotonic  = isNonDecreasing(settledPerTick);
        int lastTick = settledPerTick.get(settledPerTick.size() - 1);
        assertTrue(sawPartial, "monitor observed a live intermediate state");
        assertTrue(monotonic,  "monitor settled-count never went backwards");
        assertEq(4, lastTick,  "monitor's final settled tick landed BEFORE parent resumed");
    }

    // ---- check 5: Coop.blocking re-acquires the baton even on exception -----

    private static void check5_blockingThrows() {
        banner("5", "Coop.blocking re-acquires the baton even when the blocking call throws");
        Log.reset();
        Coop coop = new Coop();
        AtomicReference<Map<String, Result>> out = new AtomicReference<>();

        // If blocking() skipped re-acquire on exception, the throwing worker would complete()
        // off-baton, corrupt the scheduler, and this run would DEADLOCK (never return).
        coop.run("root", () -> {
            List<Coop.Spec> specs = List.of(
                    new Coop.Spec("ok",   g -> coop.blocking(() -> 7.0)),
                    new Coop.Spec("boom", g -> coop.blocking(() -> { throw new RuntimeException("io failed"); })));
            out.set(coop.multi(specs, null, Map.of()));
        });

        Map<String, Result> r = out.get();
        Log.line("results = " + r);
        assertEq("done",  r.get("ok").status(),   "non-throwing blocking worker completed");
        assertEq(7.0,     r.get("ok").value(),    "ok value = 7");
        assertEq("error", r.get("boom").status(), "throwing blocking -> error result (baton reacquired, no deadlock)");
        assertEq("io failed", r.get("boom").value(), "error message propagated");
    }

    // ---- assertions / output ----------------------------------------------

    private static void banner(String num, String title) {
        System.out.println("── check " + num + ": " + title);
    }

    private static boolean isNonDecreasing(List<Integer> xs) {
        for (int i = 1; i < xs.size(); i++) if (xs.get(i) < xs.get(i - 1)) return false;
        return true;
    }

    private static void assertEq(Object expected, Object actual, String what) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        report(ok, what + (ok ? "" : "  (expected " + expected + ", got " + actual + ")"));
    }

    private static void assertTrue(boolean cond, String what) { report(cond, what); }

    private static void report(boolean ok, String what) {
        System.out.println("   " + (ok ? "PASS " : "FAIL ") + what);
        if (!ok) failures++;
    }
}
