import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spike — the v1 Result shape (value-model §7): {status, ok, value}.
 * status is one of "running" / "done" / "error". This is part of the value contract
 * the new engine must reproduce byte-for-byte, so it is modeled here, not invented.
 */
record Result(String status, boolean ok, Object value) {
    static Result running()        { return new Result("running", false, emptyObject()); }
    static Result ok(Object v)     { return new Result("done", true, v == null ? emptyObject() : v); }
    static Result error(String m)  { return new Result("error", false, m == null ? "" : m); }

    /** "no null" — absence is {} (empty object), never null (value-model §1). */
    static Map<String, Object> emptyObject() { return new LinkedHashMap<>(); }

    @Override public String toString() {
        return "{status:" + status + ", ok:" + ok + ", value:" + value + "}";
    }
}
