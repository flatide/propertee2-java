/** Spike — timestamped, baton-serialized logging so timing-overlap proofs are visible. */
final class Log {
    private static volatile long start = System.nanoTime();

    static void reset() { start = System.nanoTime(); }

    static synchronized void line(String msg) {
        long ms = (System.nanoTime() - start) / 1_000_000L;
        System.out.printf("    [+%4dms] %s%n", ms, msg);
    }
}
