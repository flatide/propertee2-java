package com.flatide.propertee2.builtin;

import com.flatide.propertee2.host.FileErrors;
import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.value.DecimalDigits;
import com.flatide.propertee2.value.DisplayWidth;
import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.LazyLines;
import com.flatide.propertee2.value.Result;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeResult;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;
import com.flatide.propertee2.task.Task;
import com.flatide.propertee2.task.TaskRequest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The built-in function registry and dispatch (value-model-and-builtins.md §9;
 * catalog in LANGUAGE.md §Built-in Functions). Every builtin is classified:
 *
 * <ul>
 *   <li>{@link Kind#PURE} — non-blocking, evaluated in place while holding the baton.</li>
 *   <li>{@link Kind#OUTPUT} — {@code PRINT} (stdout; ordering matters for conformance).</li>
 *   <li>{@link Kind#HOST_GATED} — {@code ENV}, file I/O via a host PlatformProvider (disk I/O blocks).</li>
 *   <li>{@link Kind#BLOCKING} — {@code SHELL}, {@code HTTP*}.</li>
 * </ul>
 *
 * <p>PA ports the entire PURE catalog (math / type / string / string-matching / array+sort /
 * object / JSON / timing). HOST_GATED and BLOCKING builtins are registered in PC/PD where they
 * are wrapped in {@code Coop.blocking} (the "release baton → block → re-acquire" contract,
 * design §3.1); calling one before then fails loudly rather than silently halting the scheduler.
 *
 * <p>Return-type fidelity matters for output (Integer vs Double): {@code FLOOR}/{@code CEIL}/
 * {@code ROUND} return Integer; {@code SUM} stays Integer when all inputs are integers. Error
 * message strings are byte-exact where fixtures assert them (e.g. SORT errors — 67_sort_errors).
 */
public final class Builtins {

    public enum Kind { PURE, OUTPUT, HOST_GATED, BLOCKING }

    private record Entry(Kind kind, Builtin fn) {}

    private final Map<String, Entry> registry = new HashMap<>();
    private Shell shell;

    // FILE_LINES (spec v0.26.0): how its arrays reach the file. The interpreter installs the gate
    // that releases the scheduler baton around reads; the limits are host-configurable; the
    // rewrite counters let an array notice that this run rewrote its file (same size, same
    // millisecond — nothing else would tell).
    private LazyLines.Gate ioGate = LazyLines.Gate.DIRECT;
    private long fileLinesLoadLimit = LazyLines.DEFAULT_LOAD_LIMIT;
    private int fileLinesMaxLine = LazyLines.DEFAULT_MAX_LINE;
    private final Map<String, AtomicLong> rewrites = new ConcurrentHashMap<>();

    /** The gate every FILE_LINES read goes through (the interpreter's baton-releasing one). */
    public void setIoGate(LazyLines.Gate gate) { if (gate != null) this.ioGate = gate; }

    /** FILE_LINES limits: the whole-use limit and the longest line, in bytes (non-positive keeps the current). */
    public void setFileLinesLimits(long loadLimitBytes, int maxLineBytes) {
        if (loadLimitBytes > 0) this.fileLinesLoadLimit = loadLimitBytes;
        if (maxLineBytes > 0) this.fileLinesMaxLine = maxLineBytes;
    }

    private static String rewriteKey(String path) {
        try {
            return java.nio.file.Path.of(path).toAbsolutePath().normalize().toString();
        } catch (java.nio.file.InvalidPathException e) {
            return path;
        }
    }

    long rewriteCount(String path) {
        AtomicLong c = rewrites.get(rewriteKey(path));
        return c == null ? 0 : c.get();
    }

    void noteRewrite(String path) {
        rewrites.computeIfAbsent(rewriteKey(path), k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Progress seam for a SHELL call made by a monitored {@code multi} worker. The blocking SHELL
     * call samples its own bounded output; no separate sampler/watchdog thread is created.
     */
    public interface ShellObserver {
        default void starting(TaskRequest request) { }
        default void started(Task task) { }
        default void progress(Task task, String boundedOutput) { }
        default void completed(Task task, Object result, String boundedOutput) { }
    }

    public void register(String name, Kind kind, Builtin fn) {
        // Re-registering SHELL (Interpreter.addRawBuiltin allows it) also retires the monitor
        // observation seam; a stale seam would keep routing monitored workers to the original
        // TaskRunner-backed Shell instead of the replacement. registerShell() re-arms it after
        // this call.
        if ("SHELL".equals(name)) shell = null;
        registry.put(name, new Entry(kind, fn));
    }

    public boolean has(String name) { return registry.containsKey(name); }

    /** Snapshot of the registered builtin names (for host-side validation/linting). */
    public java.util.Set<String> names() { return java.util.Set.copyOf(registry.keySet()); }

    public Kind kindOf(String name) {
        Entry e = registry.get(name);
        return e == null ? null : e.kind();
    }

    /** Dispatch a PURE builtin. HOST_GATED/BLOCKING throw here; the interpreter routes those via Coop.blocking. */
    public Object call(String name, List<Object> args) {
        Entry e = registry.get(name);
        if (e == null) throw new TeeError("Unknown function '" + name + "'");
        if (e.kind() == Kind.HOST_GATED || e.kind() == Kind.BLOCKING) {
            throw new TeeError("'" + name + "' must be invoked through the Coop runtime");
        }
        return e.fn().invoke(args);
    }

    /** Invoke a builtin regardless of kind. The caller (interpreter) is responsible for Coop.blocking gating. */
    public Object invokeRaw(String name, List<Object> args) {
        Entry e = registry.get(name);
        if (e == null) throw new TeeError("Unknown function '" + name + "'");
        return e.fn().invoke(args);
    }

    /**
     * Invoke SHELL with monitor observation; all other builtin dispatch remains unchanged. The
     * seam is only armed while the registry entry is the original TaskRunner-backed Shell — a
     * host replacement (or an external named SHELL, which bypasses the registry entirely) runs
     * unobserved and the monitor's {@code shell} projection stays {@code {}}.
     */
    public Object invokeRaw(String name, List<Object> args, ShellObserver observer) {
        if ("SHELL".equals(name) && shell != null && observer != null) {
            return shell.run(args, observer);
        }
        return invokeRaw(name, args);
    }

    /** Registry pre-loaded with the full PURE catalog (no host integration). */
    public static Builtins standard() {
        Builtins b = new Builtins();
        registerMath(b);
        registerTypeAndString(b);
        registerStringMatching(b);
        registerArray(b);
        registerObject(b);
        registerJson(b);
        registerTiming(b);
        registerResult(b);
        return b;
    }

    /** Pure catalog + host-gated/blocking builtins; SHELL has no process execution (UnsupportedTaskRunner). */
    public static Builtins standard(PlatformProvider platform) {
        return standard(platform, new com.flatide.propertee2.task.UnsupportedTaskRunner(), null);
    }

    /** Pure catalog + host-gated/blocking builtins backed by a {@link PlatformProvider} and host TaskRunner (§3.1). */
    public static Builtins standard(PlatformProvider platform, com.flatide.propertee2.task.TaskRunner taskRunner) {
        return standard(platform, taskRunner, null);
    }

    /** As above, with a run id so SHELL tags each TaskRequest (lets a host group spawned tasks by run). */
    public static Builtins standard(PlatformProvider platform, com.flatide.propertee2.task.TaskRunner taskRunner, String runId) {
        Builtins b = standard();
        registerEnv(b, platform);
        registerFileIO(b, platform);
        registerHttp(b, platform);
        registerShell(b, platform, taskRunner, runId);
        return b;
    }

    // ---- Result (spec v0.10.0: genuine-Result constructors + observer) -----

    private static void registerResult(Builtins b) {
        // OK/ERR construct genuine Results (the runtime-origin brand — value/TeeResult);
        // a missing argument follows the "absence is {}" convention. ERR's value may be
        // any type (structured errors, like HTTP error Results).
        b.register("OK",  Kind.PURE, args -> Result.ok(args.isEmpty() ? Values.emptyObject() : args.get(0)));
        b.register("ERR", Kind.PURE, args -> Result.errorValue(args.isEmpty() ? Values.emptyObject() : args.get(0)));
        // IS_RESULT accepts any value and never errors (observability of the brand).
        b.register("IS_RESULT", Kind.PURE, args -> !args.isEmpty() && args.get(0) instanceof TeeResult);
    }

    // ---- Math (return types verified against 02_arithmetic) ----------------

    private static void registerMath(Builtins b) {
        b.register("SUM", Kind.PURE, args -> {
            List<Object> numbers = numbers("SUM", args);
            boolean allInt = true;
            for (Object a : numbers) {
                if (!Values.isInteger(a)) allInt = false;
            }
            if (allInt) {
                // Spec v0.33.0: integers add in order, like +, and a running total outside the
                // range is the overflow (spec v0.14.0) — so the total is exact in both runtimes.
                long i = 0;
                for (Object a : numbers) {
                    i += ((Number) a).longValue();
                    if (!Values.fitsInteger(i)) throw new TeeError(Values.INTEGER_OVERFLOW);
                }
                return Values.integer(i);
            }
            double d = 0;
            for (Object a : numbers) d += Values.toDouble(a);
            return (Object) Values.decimal(d);                        // too large is an error (spec v0.36.0)
        });
        b.register("MAX", Kind.PURE, args -> minMax("MAX", args, true));
        b.register("MIN", Kind.PURE, args -> minMax("MIN", args, false));
        b.register("ABS", Kind.PURE, args -> {
            Object n = arg("ABS", args, 0);
            number("ABS", n);
            if (Values.isInteger(n)) return Values.integer(Math.abs(((Number) n).longValue()));   // the range is symmetric
            return Math.abs((Double) n);
        });
        // FLOOR/CEIL/ROUND/TRUNC without digits return an integer (FLOOR(3.9)->3, CEIL(3.1)->4,
        // ROUND(3.5)->4, TRUNC(-3.9)->-3); a result outside the integer range is a loud error, never
        // a clamp (spec v0.13.0, v0.33.0). With digits (spec v0.30.0) they return a decimal rounded on the
        // digits PRINT shows — see rounding().
        b.register("FLOOR", Kind.PURE, args -> rounding("FLOOR", args, DecimalDigits.Mode.FLOOR));
        b.register("CEIL",  Kind.PURE, args -> rounding("CEIL", args, DecimalDigits.Mode.CEILING));
        b.register("ROUND", Kind.PURE, args -> rounding("ROUND", args, DecimalDigits.Mode.HALF_UP));
        b.register("TRUNC", Kind.PURE, args -> rounding("TRUNC", args, DecimalDigits.Mode.DOWN));
    }

    /**
     * {@code FN(n, [digits])} for FLOOR/CEIL/ROUND/TRUNC. A third argument is an error, not dropped
     * — before spec v0.30.0 the second one was silently ignored. Without digits the result is an
     * integer (ROUND's tie goes away from zero, so ROUND(-2.5) is -3); with digits it is a decimal:
     * DecimalDigits rounds the shortest digits at that place, places the number does not reach
     * change nothing, and a result too large to represent is an error (spec v0.36.0).
     */
    private static Object rounding(String fn, List<Object> args, DecimalDigits.Mode mode) {
        if (args.size() > 2) throw new TeeError(fn + "() takes at most 2 arguments");
        double n = Values.toDouble(number(fn, arg(fn, args, 0)));
        if (args.size() == 1) return intResult(wholeRound(n, mode));
        Object digits = args.get(1);
        if (!isWhole(digits)) throw new TeeError(fn + "() digits must be an integer");
        return Values.decimal(DecimalDigits.of(n).round(Values.toDouble(digits), mode).toDouble());   // CEIL(max, -308) (spec v0.36.0)
    }

    /** n rounded to a whole number; the same value as rounding its shown digits at place 0. */
    private static double wholeRound(double n, DecimalDigits.Mode mode) {
        return switch (mode) {
            case FLOOR -> Math.floor(n);
            case CEILING -> Math.ceil(n);
            case DOWN -> n < 0 ? Math.ceil(n) : Math.floor(n);
            case HALF_UP -> {
                // |n| - floor(|n|) is exact, so the tie test is too (Math.floor(|n| + 0.5) would
                // round 0.49999999999999994 up).
                double a = Math.abs(n);
                double f = Math.floor(a);
                double r = a - f >= 0.5 ? f + 1 : f;
                yield n < 0 ? -r : r;
            }
        };
    }

    /** A whole number inside the integer range (spec v0.33.0); NaN and the infinities are not. */
    private static boolean isRangeInteger(double d) {
        return d == Math.floor(d) && Math.abs(d) <= Values.MAX_INTEGER;
    }

    /** The integer range for integer-producing builtins (spec v0.13.0, v0.33.0). */
    private static Object intResult(double d) {
        if (!(Math.abs(d) <= Values.MAX_INTEGER)) throw new TeeError(Values.INTEGER_OVERFLOW);   // NaN too: it was cut to 0
        return Values.integer((long) d);
    }

    /**
     * The numbers SUM, MAX and MIN take (spec v0.36.0): each argument is a number or an array of
     * numbers, as a spreadsheet's MIN(A1:A10) takes a range — MIN(prices), MAX(sizes, 0). An
     * array inside an array is not a number. Before, an array was an error, so a script found a
     * list's least value with a loop that started from a huge number.
     */
    private static List<Object> numbers(String fn, List<Object> args) {
        List<Object> out = new ArrayList<>(args.size());
        for (Object a : args) {
            if (a instanceof List<?> list) {
                for (Object e : list) out.add(number(fn, e));
            } else {
                out.add(number(fn, a));
            }
        }
        return out;
    }

    private static Object minMax(String fn, List<Object> args, boolean max) {
        List<Object> numbers = numbers(fn, args);
        if (numbers.isEmpty()) throw new TeeError(fn + "() requires at least one number");
        Object best = null;
        double bestD = 0;
        for (Object a : numbers) {
            double d = Values.toDouble(a);
            if (best == null || (max ? d > bestD : d < bestD)) { best = a; bestD = d; }
        }
        return best; // preserves the selected element's Integer/Double type
    }

    // ---- Type / String -----------------------------------------------------

    private static void registerTypeAndString(Builtins b) {
        b.register("TYPE_OF",  Kind.PURE, args -> Values.typeName(arg("TYPE_OF", args, 0)));
        b.register("TO_STRING", Kind.PURE, args -> TeeFormat.display(arg("TO_STRING", args, 0)));
        b.register("TO_NUMBER", Kind.PURE, args -> toNumber(string("TO_NUMBER", arg("TO_NUMBER", args, 0))));
        b.register("FORMAT", Kind.PURE, args -> format("FORMAT", args));
        b.register("TEXT", Kind.PURE, Builtins::text);
        b.register("ALIGN_LEFT", Kind.PURE, args -> align("ALIGN_LEFT", args));
        b.register("ALIGN_RIGHT", Kind.PURE, args -> align("ALIGN_RIGHT", args));
        b.register("ALIGN_CENTER", Kind.PURE, args -> align("ALIGN_CENTER", args));

        b.register("LEN", Kind.PURE, args -> {
            Object v = arg("LEN", args, 0);
            if (v instanceof String s) return s.length();
            if (v instanceof List<?> l) return l.size();
            if (v instanceof Map<?, ?> m) return m.size();
            // Spec v0.7.0 (#7): non-collections are an error (was a silent 0 until v0.6.0).
            throw new TeeError("LEN() requires a string, array, or object argument");
        });
        b.register("UPPERCASE", Kind.PURE, args -> string("UPPERCASE", arg("UPPERCASE", args, 0)).toUpperCase());
        b.register("LOWERCASE", Kind.PURE, args -> string("LOWERCASE", arg("LOWERCASE", args, 0)).toLowerCase());
        b.register("TRIM",      Kind.PURE, args -> trimWhitespace(string("TRIM", arg("TRIM", args, 0))));

        // SUBSTRING(s, start, [length]) — start is 1-based; 3rd arg is LENGTH (13_strings).
        b.register("SUBSTRING", Kind.PURE, args -> {
            String s = string("SUBSTRING", arg("SUBSTRING", args, 0));
            int[] span = span("SUBSTRING", args, "length", s.length());
            return s.substring(span[0], span[1]);
        });
        // SPLIT keeps every field — leading, interior and trailing empties — so JOIN(SPLIT(s, d), d)
        // == s; the delimiter is literal and, since spec v0.24.0, non-empty (CHARS splits characters).
        b.register("SPLIT", Kind.PURE, args -> {
            String s = string("SPLIT", arg("SPLIT", args, 0));
            String delim = string("SPLIT", arg("SPLIT", args, 1));
            if (delim.isEmpty()) throw new TeeError("SPLIT() requires a non-empty delimiter");
            List<Object> out = new ArrayList<>();
            for (String part : s.split(Pattern.quote(delim), -1)) out.add(part);
            return out;
        });
        // WORDS (spec v0.24.0): the maximal runs of non-whitespace, in order — never an empty string.
        b.register("WORDS", Kind.PURE, args -> {
            String s = string("WORDS", arg("WORDS", args, 0));
            List<Object> out = new ArrayList<>();
            int i = 0;
            while (i < s.length()) {
                while (i < s.length() && isWhitespace(s.charAt(i))) i++;
                int start = i;
                while (i < s.length() && !isWhitespace(s.charAt(i))) i++;
                if (i > start) out.add(s.substring(start, i));
            }
            return out;
        });
        b.register("JOIN", Kind.PURE, args -> {
            List<Object> arr = array("JOIN", arg("JOIN", args, 0));
            String sep = args.size() > 1 ? string("JOIN", arg("JOIN", args, 1)) : "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                if (i > 0) sb.append(sep);
                sb.append(TeeFormat.display(arr.get(i)));
            }
            return sb.toString();
        });
        b.register("CHARS", Kind.PURE, args -> {
            String s = string("CHARS", arg("CHARS", args, 0));
            List<Object> out = new ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) out.add(String.valueOf(s.charAt(i)));
            return out;
        });
    }

    private static void registerStringMatching(Builtins b) {
        b.register("CONTAINS", Kind.PURE, args -> {
            Object coll = arg("CONTAINS", args, 0);
            Object needle = arg("CONTAINS", args, 1);
            if (coll instanceof List<?> list) {                          // array membership: any element == needle (deep, like ==)
                for (Object e : list) if (Values.valuesEqual(e, needle)) return true;
                return false;
            }
            if (coll instanceof String s) return s.contains(string("CONTAINS", needle));  // string substring (unchanged)
            throw new TeeError("CONTAINS() requires a string or array as its first argument");
        });
        b.register("STARTS_WITH", Kind.PURE, args -> { Pair p = str2("STARTS_WITH", args); return p.left.startsWith(p.right); });
        b.register("ENDS_WITH",   Kind.PURE, args -> { Pair p = str2("ENDS_WITH", args); return p.left.endsWith(p.right); });
        // FIND family (spec v0.17.0) — literal search, 1-based positions. FIND returns ALL
        // start positions ascending, overlapping included (advance by 1, not match length),
        // so FIND_FIRST/FIND_LAST are always its first/last element and FIND_LAST agrees
        // with lastIndexOf. Not found -> [] / 0; empty needles are rejected (every boundary
        // would "match").
        b.register("FIND", Kind.PURE, args -> {
            Pair p = findArgs("FIND", args);
            List<Object> out = new ArrayList<>();
            for (int i = p.left.indexOf(p.right); i >= 0; i = p.left.indexOf(p.right, i + 1)) out.add(i + 1);
            return out;
        });
        b.register("FIND_FIRST", Kind.PURE, args -> {
            Pair p = findArgs("FIND_FIRST", args);
            return p.left.indexOf(p.right) + 1;
        });
        b.register("FIND_LAST", Kind.PURE, args -> {
            Pair p = findArgs("FIND_LAST", args);
            return p.left.lastIndexOf(p.right) + 1;
        });
        b.register("MATCHES", Kind.PURE, args -> {
            Pair p = str2("MATCHES", args);
            return compile("MATCHES", p.right).matcher(p.left).find();
        });
        // REGEX_FIND / REGEX_FIND_ALL (spec v0.29.0) -> match objects (matchObject); no match -> {} / [].
        // ALL repeats Matcher.find(), which resumes where the last match ended (one further after an
        // empty match), so matches never overlap (141/142).
        b.register("REGEX_FIND", Kind.PURE, args -> {
            Pair p = str2("REGEX_FIND", args);
            Matcher m = compile("REGEX_FIND", p.right).matcher(p.left);
            return m.find() ? matchObject(m) : Values.emptyObject();
        });
        b.register("REGEX_FIND_ALL", Kind.PURE, args -> {
            Pair p = str2("REGEX_FIND_ALL", args);
            Matcher m = compile("REGEX_FIND_ALL", p.right).matcher(p.left);
            List<Object> out = new ArrayList<>();
            while (m.find()) out.add(matchObject(m));
            return out;
        });
        b.register("REPLACE", Kind.PURE, args -> {
            String s = string("REPLACE", arg("REPLACE", args, 0));
            String target = string("REPLACE", arg("REPLACE", args, 1));
            String repl = string("REPLACE", arg("REPLACE", args, 2));
            return s.replace(target, repl); // literal, replace-all
        });
    }

    // ---- Array -------------------------------------------------------------

    private static void registerArray(Builtins b) {
        b.register("PUSH", Kind.PURE, args -> {
            List<Object> out = copyArray("PUSH", arg("PUSH", args, 0));
            for (int i = 1; i < args.size(); i++) out.add(Values.deepCopy(args.get(i)));
            return out;
        });
        b.register("POP", Kind.PURE, args -> {
            List<Object> out = copyArray("POP", arg("POP", args, 0));
            if (!out.isEmpty()) out.remove(out.size() - 1);
            return out;
        });
        b.register("CONCAT", Kind.PURE, args -> {
            List<Object> out = new ArrayList<>();
            for (Object a : args) for (Object e : array("CONCAT", a)) out.add(Values.deepCopy(e));
            return out;
        });
        // SLICE(arr, start, [count]) — start is 1-based; the 3rd arg is a COUNT, unified with
        // SUBSTRING and READ_LINES (spec v0.7.0 #6; until v0.6.0 it was an end bound instead).
        b.register("SLICE", Kind.PURE, args -> {
            List<Object> arr = array("SLICE", arg("SLICE", args, 0));
            int[] span = span("SLICE", args, "count", arr.size());
            List<Object> out = new ArrayList<>(span[1] - span[0]);
            for (int i = span[0]; i < span[1]; i++) out.add(Values.deepCopy(arr.get(i)));
            return out;
        });
        b.register("SORT",      Kind.PURE, args -> sortScalar("SORT", arg("SORT", args, 0), false));
        b.register("SORT_DESC", Kind.PURE, args -> sortScalar("SORT_DESC", arg("SORT_DESC", args, 0), true));
        b.register("SORT_BY",      Kind.PURE, args -> sortBy(arg("SORT_BY", args, 0), string("SORT_BY", arg("SORT_BY", args, 1)), false));
        b.register("SORT_BY_DESC", Kind.PURE, args -> sortBy(arg("SORT_BY_DESC", args, 0), string("SORT_BY_DESC", arg("SORT_BY_DESC", args, 1)), true));
        b.register("REVERSE", Kind.PURE, args -> {
            List<Object> out = copyArray("REVERSE", arg("REVERSE", args, 0)); // no type restriction
            java.util.Collections.reverse(out);
            return out;
        });
    }

    private static List<Object> sortScalar(String fn, Object v, boolean desc) {
        if (!(v instanceof List)) throw new TeeError(fn + "() requires an array argument");
        List<Object> out = copyArray(fn, v);
        if (out.isEmpty()) return out;
        boolean allNum = out.stream().allMatch(Values::isNumber);
        boolean allStr = out.stream().allMatch(e -> e instanceof String);
        if (!allNum && !allStr) {
            throw new TeeError("SORT() requires all elements to be the same type (number or string)");
        }
        out.sort((x, y) -> allNum ? Double.compare(Values.toDouble(x), Values.toDouble(y))
                                  : ((String) x).compareTo((String) y));
        if (desc) java.util.Collections.reverse(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sortBy(Object v, String key, boolean desc) {
        if (!(v instanceof List)) throw new TeeError("SORT_BY() requires an array argument");
        List<Object> out = copyArray("SORT_BY", v);
        for (int i = 0; i < out.size(); i++) {
            Object e = out.get(i);
            if (!(e instanceof Map) || !((Map<String, Object>) e).containsKey(key)) {
                throw new TeeError("Property '" + key + "' does not exist in array element at index " + (i + 1));
            }
        }
        out.sort((a, c) -> {
            Object ka = ((Map<String, Object>) a).get(key);
            Object kc = ((Map<String, Object>) c).get(key);
            int cmp = (Values.isNumber(ka) && Values.isNumber(kc))
                    ? Double.compare(Values.toDouble(ka), Values.toDouble(kc))
                    : String.valueOf(ka).compareTo(String.valueOf(kc));
            return desc ? -cmp : cmp;
        });
        return out;
    }

    // ---- Object ------------------------------------------------------------

    private static void registerObject(Builtins b) {
        b.register("KEYS", Kind.PURE, args -> new ArrayList<Object>(object("KEYS", arg("KEYS", args, 0)).keySet()));
        b.register("VALUES", Kind.PURE, args -> {
            Map<String, Object> m = object("VALUES", arg("VALUES", args, 0));
            List<Object> out = new ArrayList<>(m.size());
            for (Object v : m.values()) out.add(Values.deepCopy(v));
            return out;
        });
        b.register("HAS_KEY", Kind.PURE, args -> object("HAS_KEY", arg("HAS_KEY", args, 0))
                .containsKey(string("HAS_KEY", arg("HAS_KEY", args, 1))));
        // ENTRIES -> [ {"key":k, "value":v}, ... ] in insertion order (82_map_extensions).
        b.register("ENTRIES", Kind.PURE, args -> {
            Map<String, Object> m = object("ENTRIES", arg("ENTRIES", args, 0));
            List<Object> out = new ArrayList<>(m.size());
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("key", e.getKey());
                entry.put("value", Values.deepCopy(e.getValue()));
                out.add(entry);
            }
            return out;
        });
        b.register("MERGE", Kind.PURE, args -> {
            Map<String, Object> out = new LinkedHashMap<>();
            copyInto(out, object("MERGE", arg("MERGE", args, 0)));
            copyInto(out, object("MERGE", arg("MERGE", args, 1))); // second overrides first
            return out;
        });
        b.register("REMOVE_KEY", Kind.PURE, args -> {
            Map<String, Object> src = object("REMOVE_KEY", arg("REMOVE_KEY", args, 0));
            String key = string("REMOVE_KEY", arg("REMOVE_KEY", args, 1));
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                if (!e.getKey().equals(key)) out.put(e.getKey(), Values.deepCopy(e.getValue()));
            }
            return out;
        });
    }

    private static void copyInto(Map<String, Object> dst, Map<String, Object> src) {
        for (Map.Entry<String, Object> e : src.entrySet()) dst.put(e.getKey(), Values.deepCopy(e.getValue()));
    }

    // ---- JSON --------------------------------------------------------------

    private static void registerJson(Builtins b) {
        b.register("JSON_FORMAT", Kind.PURE, args -> json("JSON_FORMAT", "value", arg("JSON_FORMAT", args, 0)));
        b.register("JSON_PARSE", Kind.PURE, args -> parsedJson("JSON_PARSE", string("JSON_PARSE", arg("JSON_PARSE", args, 0))));
    }

    /**
     * JSON_PARSE's and READ_JSON_FILE's Result for {@code text}. A numeral too large to represent,
     * which the parser reads as Infinity, is an error naming the first one and where it is (spec
     * v0.35.0): {@code JSON_PARSE() value.rows.2.size is a number too large to represent}. Before,
     * it came through as Infinity and failed only when written back as JSON.
     */
    private static Object parsedJson(String fn, String text) {
        Object v;
        try {
            v = JsonParser.parse(text);
        } catch (TeeError e) {
            return Result.error(e.getMessage());
        }
        TeeFormat.NonFinite big = TeeFormat.firstNonFinite(v, "value");   // JSON has no NaN to read
        if (big != null) return Result.error(fn + "() " + big.path() + " is a number too large to represent");
        return Result.ok(v);
    }


    /**
     * {@code v} as JSON text for {@code fn}, which calls it {@code name} ("value", "body"). JSON
     * has no form for Infinity, -Infinity or NaN (spec v0.32.0), so a value holding one is an
     * error naming the first and the path a script reaches it by:
     * {@code JSON_FORMAT() value.list.2 is NaN, which JSON cannot represent}.
     */
    private static String json(String fn, String name, Object v) {
        try {
            return TeeFormat.json(v);
        } catch (TeeFormat.NotJsonException e) {
            TeeFormat.NonFinite found = TeeFormat.firstNonFinite(v, name);
            if (found == null) throw new TeeError(fn + "() " + e.getMessage());   // not found again: still name the number
            throw new TeeError(fn + "() " + found.path() + " is " + found.number() + ", which JSON cannot represent");
        }
    }

    // ---- Environment (host-gated via Coop.blocking) ------------------------

    private static void registerEnv(Builtins b, PlatformProvider platform) {
        // ENV(name) -> value, or {} if unset. ENV(name, default) -> value, or `default` if unset (83_type_env).
        b.register("ENV", Kind.HOST_GATED, args -> {
            String name = string("ENV", arg("ENV", args, 0));
            String value = platform.env(name);
            if (value != null) return value;
            return args.size() > 1 ? args.get(1) : Values.emptyObject();
        });
    }

    // ---- File I/O (host-gated via Coop.blocking) — returns Result; verified vs 85_file_io ----

    @FunctionalInterface private interface IoCall { Object call() throws IOException; }

    private static Object ioResult(IoCall c) {
        try {
            return c.call();
        } catch (IOException e) {
            return Result.error(FileErrors.message(e));
        } catch (UncheckedIOException e) {
            // Files.lines()/Files.list() defer I/O — a read failure during lazy consumption
            // (e.g. malformed UTF-8, a directory vanishing mid-iteration) surfaces here, not as a
            // checked IOException. Surface it as the v1 Result.error rather than a raw runtime error.
            IOException cause = e.getCause();
            return Result.error(cause != null ? FileErrors.message(cause) : e.getMessage());
        }
    }

    private static void registerFileIO(Builtins b, PlatformProvider platform) {
        b.register("FILE_EXISTS", Kind.HOST_GATED, args ->
                platform.fileExists(string("FILE_EXISTS", arg("FILE_EXISTS", args, 0))));   // boolean, not Result

        b.register("MKDIR", Kind.HOST_GATED, args -> ioResult(() -> {
            platform.mkdir(string("MKDIR", arg("MKDIR", args, 0)));
            return Result.ok(Values.emptyObject());
        }));

        b.register("WRITE_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            String path = string("WRITE_FILE", arg("WRITE_FILE", args, 0));
            platform.writeFile(path, string("WRITE_FILE", arg("WRITE_FILE", args, 1)));
            b.noteRewrite(path);
            return Result.ok(Values.emptyObject());
        }));

        b.register("APPEND_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            platform.appendFile(string("APPEND_FILE", arg("APPEND_FILE", args, 0)),
                    string("APPEND_FILE", arg("APPEND_FILE", args, 1)));
            return Result.ok(Values.emptyObject());
        }));

        b.register("WRITE_LINES", Kind.HOST_GATED, args -> ioResult(() -> {
            String path = string("WRITE_LINES", arg("WRITE_LINES", args, 0));
            List<Object> lines = array("WRITE_LINES", arg("WRITE_LINES", args, 1));
            StringBuilder sb = new StringBuilder();
            for (Object line : lines) sb.append(string("WRITE_LINES", line)).append('\n'); // each followed by newline
            platform.writeFile(path, sb.toString());
            b.noteRewrite(path);
            return Result.ok(Values.emptyObject());
        }));

        // READ_FILE (spec v0.18.0): the whole file as one string — WRITE_FILE's counterpart.
        // Content-exact (no BOM stripping — fidelity is the point); large files should keep
        // using the windowed READ_LINES.
        b.register("READ_FILE", Kind.HOST_GATED, args -> ioResult(() ->
                Result.ok(platform.readFile(string("READ_FILE", arg("READ_FILE", args, 0))))));

        // WRITE_JSON_FILE (spec v0.18.0): READ_JSON_FILE's counterpart — serialize exactly as
        // JSON_FORMAT would and write it (overwrite) with a trailing newline (a POSIX text
        // file). The round-trip through READ_JSON_FILE is lossless, first-class null included.
        b.register("WRITE_JSON_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            String path = string("WRITE_JSON_FILE", arg("WRITE_JSON_FILE", args, 0));
            platform.writeFile(path, json("WRITE_JSON_FILE", "value", arg("WRITE_JSON_FILE", args, 1)) + "\n");
            b.noteRewrite(path);
            return Result.ok(Values.emptyObject());
        }));

        // READ_JSON_FILE (spec v0.18.0): read + JSON-parse in one call — replaces the
        // READ_LINES + JOIN + JSON_PARSE idiom. ok -> the parsed value (same conversion as
        // JSON_PARSE, first-class null included); error -> the read failure's or the parser's
        // message. A leading UTF-8 BOM is ignored (RFC 8259 permits it; Windows editors add it).
        b.register("READ_JSON_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            String content = platform.readFile(string("READ_JSON_FILE", arg("READ_JSON_FILE", args, 0)));
            if (content.startsWith("\uFEFF")) content = content.substring(1);
            return parsedJson("READ_JSON_FILE", content);
        }));

        b.register("READ_LINES", Kind.HOST_GATED, args -> {
            String path = string("READ_LINES", arg("READ_LINES", args, 0));
            long start = 1;
            if (args.size() > 1) {
                Object s = args.get(1);
                if (!isWhole(s) || Values.toDouble(s) < 1) return Result.error("READ_LINES start must be a positive integer");
                start = (long) Values.toDouble(s);
            }
            long count = Long.MAX_VALUE;   // default: to end of file
            if (args.size() > 2) {
                Object c = args.get(2);
                if (!isWhole(c) || Values.toDouble(c) < 1) return Result.error("READ_LINES count must be a positive integer");
                count = (long) Values.toDouble(c);
            }
            final long from = start, limit = count;
            // The provider streams + skips + limits — a large file is not loaded whole (LANGUAGE.md §File I/O).
            return ioResult(() -> Result.ok(new ArrayList<Object>(platform.readLines(path, from, limit))));
        });

        b.register("FILE_INFO", Kind.HOST_GATED, args -> ioResult(() -> {
            PlatformProvider.FileStat s = platform.fileInfo(string("FILE_INFO", arg("FILE_INFO", args, 0)));
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("type", s.type());
            info.put("size", Values.fromLong(s.size()));
            info.put("modified", Values.fromLong(s.modified()));   // epoch millis: an integer (spec v0.33.0)
            return Result.ok(info);
        }));

        b.register("LIST_DIR", Kind.HOST_GATED, args -> {
            String path = string("LIST_DIR", arg("LIST_DIR", args, 0));
            String pattern = args.size() > 1 ? string("LIST_DIR", args.get(1)) : null;
            String problem = pattern != null ? patternProblem("LIST_DIR", pattern) : null;
            if (problem != null) return Result.error(problem);
            return ioResult(() -> {
                // Without a pattern, the one-argument call a provider has always implemented.
                List<PlatformProvider.DirEntry> entries = pattern == null
                        ? platform.listDir(path) : platform.listDir(path, pattern);
                List<Object> out = new ArrayList<>();
                for (PlatformProvider.DirEntry e : entries) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.name());
                    m.put("type", e.type());
                    m.put("size", Values.fromLong(e.size()));
                    out.add(m);
                }
                return Result.ok(out);
            });
        });

        // LIST_DIR_NAMES (spec v0.28.0): each entry's name and its own type. Links are not followed
        // — a link is a "link" — so a large directory of links lists without touching what they
        // point to.
        b.register("LIST_DIR_NAMES", Kind.HOST_GATED, args -> {
            String path = string("LIST_DIR_NAMES", arg("LIST_DIR_NAMES", args, 0));
            String pattern = args.size() > 1 ? string("LIST_DIR_NAMES", args.get(1)) : null;
            String problem = pattern != null ? patternProblem("LIST_DIR_NAMES", pattern) : null;
            if (problem != null) return Result.error(problem);
            return ioResult(() -> {
                List<Object> out = new ArrayList<>();
                for (PlatformProvider.DirEntry e : platform.listDirNames(path, pattern)) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", e.name());
                    m.put("type", e.type());
                    out.add(m);
                }
                return Result.ok(out);
            });
        });

        b.register("DELETE_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            String path = string("DELETE_FILE", arg("DELETE_FILE", args, 0));
            platform.deleteFile(path);
            b.noteRewrite(path);
            return Result.ok(Values.emptyObject());
        }));

        // FILE_LINES (spec v0.26.0): the file's lines as an array that reads the file as it is used
        // (value/LazyLines). Not a Result — a file that cannot be read is a runtime error, which,
        // raised here in the catalog, is positionless like every other builtin error. The call
        // itself only checks the file (existence, type, a content sample); a loop later streams it.
        LazyLines.Io io = new LazyLines.Io() {
            @Override public LazyLines.Stat stat(String path) throws IOException {
                if (!platform.fileExists(path)) return LazyLines.Stat.MISSING;
                PlatformProvider.FileStat s;
                try {
                    s = platform.fileInfo(path);
                } catch (java.nio.file.NoSuchFileException e) {
                    return LazyLines.Stat.MISSING;          // gone between the two calls
                }
                return new LazyLines.Stat(true, "dir".equals(s.type()), s.size(), s.modified());
            }
            @Override public int readAt(String path, long offset, byte[] buf, int len) throws IOException {
                return platform.readAt(path, offset, buf, len);
            }
            @Override public <T> T blocking(LazyLines.Work<T> work) throws IOException {
                return b.ioGate.run(work);
            }
            @Override public long rewrites(String path) { return b.rewriteCount(path); }
            @Override public long loadLimit() { return b.fileLinesLoadLimit; }
            @Override public int maxLine() { return b.fileLinesMaxLine; }
        };
        b.register("FILE_LINES", Kind.HOST_GATED, args -> {
            String path = string("FILE_LINES", arg("FILE_LINES", args, 0));
            try {
                return LazyLines.of(path, io);
            } catch (IOException e) {
                throw new TeeError("FILE_LINES() cannot read '" + path + "': " + e.getMessage());
            }
        });
    }

    /**
     * Why a LIST_DIR / LIST_DIR_NAMES name pattern cannot be used, or null when it can. A pattern
     * is a glob over entry names, so an empty one or one with '/' could never match: an error
     * Result says so rather than an empty listing.
     */
    private static String patternProblem(String fn, String pattern) {
        if (pattern.isEmpty()) return fn + "() pattern must not be empty";
        if (pattern.indexOf('/') >= 0) {
            return fn + "() pattern is matched against entry names and cannot contain a slash: " + pattern;
        }
        try {
            FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        } catch (PatternSyntaxException e) {
            return fn + "() invalid pattern " + pattern + ": " + e.getDescription();
        }
        return null;
    }

    private static boolean isWhole(Object v) {
        return Values.isNumber(v) && Values.toDouble(v) == Math.floor(Values.toDouble(v))
                && !Double.isInfinite(Values.toDouble(v));
    }

    // ---- HTTP (host-backed blocking I/O; v1 Result shape) -----------------

    private static void registerHttp(Builtins b, PlatformProvider platform) {
        // A completed request -> value={status,body,headers}, ok=(2xx). A transport-level failure
        // -> ok=false with value.status=0, so callers can always inspect res.value.status/body.
        b.register("HTTP", Kind.BLOCKING, args -> {
            if (args.size() < 2 || !(args.get(0) instanceof String) || !(args.get(1) instanceof String)) {
                return Result.error("HTTP() requires (method, url, [options])");
            }
            Map<String, Object> options = options(args, 2);
            return doHttp(platform, (String) args.get(0), (String) args.get(1),
                    optionsHeaders(options), optionsBody("HTTP", options), optionsTimeout(options));
        });

        b.register("HTTP_GET", Kind.BLOCKING, args -> {
            if (args.isEmpty() || !(args.get(0) instanceof String)) {
                return Result.error("HTTP_GET() requires (url, [options])");
            }
            Map<String, Object> options = options(args, 1);
            return doHttp(platform, "GET", (String) args.get(0), optionsHeaders(options), null, optionsTimeout(options));
        });

        b.register("HTTP_POST", Kind.BLOCKING, args -> {
            if (args.size() < 2 || !(args.get(0) instanceof String)) {
                return Result.error("HTTP_POST() requires (url, body, [options])");
            }
            Map<String, Object> options = options(args, 2);
            String body = coerceBody("HTTP_POST", args.get(1));
            return doHttp(platform, "POST", (String) args.get(0), optionsHeaders(options),
                    body != null ? body : "", optionsTimeout(options));
        });
    }

    private static Object doHttp(PlatformProvider platform, String method, String url,
                                 Map<String, String> headers, String body, int timeoutMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        try {
            PlatformProvider.HttpResponse resp = platform.httpRequest(method, url, headers, body, timeoutMs);
            value.put("status", Values.fromLong(resp.status()));
            value.put("body", resp.body() != null ? resp.body() : "");
            Map<String, Object> hdrs = new LinkedHashMap<>();
            if (resp.headers() != null) {
                for (Map.Entry<String, String> e : resp.headers().entrySet()) {
                    hdrs.put(e.getKey(), e.getValue());
                }
            }
            value.put("headers", hdrs);
            return httpEnvelope(resp.status() >= 200 && resp.status() < 300, value);
        } catch (Exception e) {
            value.put("status", 0);
            value.put("body", e.getMessage() != null ? e.getMessage() : "HTTP request failed");
            value.put("headers", new LinkedHashMap<String, Object>());
            return httpEnvelope(false, value);
        }
    }

    private static Object httpEnvelope(boolean ok, Object value) {
        // Via the Result factory so HTTP results carry the genuine-Result brand (spec v0.10.0).
        return ok ? Result.ok(value) : Result.errorValue(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> options(List<Object> args, int index) {
        return args.size() > index && args.get(index) instanceof Map
                ? (Map<String, Object>) args.get(index) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> optionsHeaders(Map<String, Object> options) {
        if (options == null) return null;
        Object h = options.get("headers");
        if (!(h instanceof Map)) h = options.get("header"); // v1 accepted the common singular alias
        if (!(h instanceof Map)) return null;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) h).entrySet()) {
            result.put(String.valueOf(e.getKey()), e.getValue() != null ? String.valueOf(e.getValue()) : "");
        }
        return result;
    }

    private static String optionsBody(String fn, Map<String, Object> options) {
        if (options == null) return null;
        return coerceBody(fn, options.get("body"));
    }

    private static int optionsTimeout(Map<String, Object> options) {
        if (options == null) return 0;
        Object t = options.get("timeout");
        return Values.isNumber(t) ? (int) Values.toDouble(t) : 0;
    }

    private static String coerceBody(String fn, Object body) {
        if (body == null) return null;
        if (body instanceof String s) return s;
        return json(fn, "body", body);
    }

    // ---- Shell (executes via a host TaskRunner; default UnsupportedTaskRunner — 72/78/80) ----

    private static void registerShell(Builtins b, PlatformProvider platform,
                                      com.flatide.propertee2.task.TaskRunner taskRunner, String runId) {
        // SHELL runs a process through the host TaskRunner (the v1 com.flatide.propertee2.task contract). With the
        // default UnsupportedTaskRunner this surfaces as Result.error("...requires a host-provided TaskRunner...").
        Shell shell = new Shell(taskRunner, runId);
        b.register("SHELL", Kind.BLOCKING, shell::run);
        b.shell = shell;   // after register(): registering "SHELL" retires any previous seam

        // SHELL_CTX(cwd, [env], [timeout]) — builds a context after validating the cwd IS a directory
        // and the optional env (object) / timeout (number) types.
        b.register("SHELL_CTX", Kind.HOST_GATED, args -> {
            String cwd = string("SHELL_CTX", arg("SHELL_CTX", args, 0));
            try {
                if (!platform.fileInfo(cwd).type().equals("dir")) {
                    return Result.error("SHELL_CTX: not a directory: " + cwd);
                }
            } catch (IOException e) {
                return Result.error("SHELL_CTX: directory does not exist: " + cwd);
            }
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("cwd", cwd);
            if (args.size() > 1) {
                if (!(args.get(1) instanceof Map)) return Result.error("SHELL_CTX: env must be an object");
                ctx.put("env", Values.deepCopy(args.get(1)));
            }
            if (args.size() > 2) {
                if (!Values.isNumber(args.get(2))) return Result.error("SHELL_CTX: timeout must be a number");
                ctx.put("timeout", args.get(2));
            }
            return Result.ok(ctx);
        });
    }

    // ---- Timing (non-blocking; nondeterministic — baton-safe, so PURE) -----

    private static void registerTiming(Builtins b) {
        b.register("RANDOM", Kind.PURE, args -> {
            if (args.isEmpty()) return Math.random();                 // [0.0, 1.0)
            if (args.size() == 1) {
                // Spec v0.7.0 (#5): the single-argument form (0 .. max-1) was REMOVED — its bounds
                // convention clashed with the inclusive two-argument form. Fails loudly by design.
                throw new TeeError("RANDOM() requires zero or two arguments");
            }
            // Spec v0.33.0: the bounds are integers in the range — before, a bound past 32 bits was
            // cut to 2147483647 and a fraction cut toward zero — and min may not be greater than max
            // (this runtime used to return a number outside them).
            double min = intArg("RANDOM", args, 0), max = intArg("RANDOM", args, 1);
            if (!(isRangeInteger(min) && isRangeInteger(max))) {
                throw new TeeError("RANDOM() bounds must be integers within ±9,007,199,254,740,991");
            }
            long lo = (long) min, hi = (long) max;
            if (lo > hi) throw new TeeError("RANDOM() min cannot be greater than max");
            // An integer draw: Math.random() * (hi - lo + 1) skipped integers once the span passed
            // 2^53 (the whole range gave only odd numbers).
            return Values.integer(ThreadLocalRandom.current().nextLong(lo, hi + 1));   // lo .. hi inclusive
        });
        b.register("MILTIME", Kind.PURE, args -> Values.integer(System.currentTimeMillis()));   // an integer (spec v0.33.0)
        b.register("DATE", Kind.PURE, args -> LocalDate.now().toString());              // yyyy-MM-dd
        b.register("TIME", Kind.PURE, args -> LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    // ---- FORMAT / PRINTF / TEXT / ALIGN_* (spec v0.31.0) -------------------

    // A number pattern draws how a number is written, as a spreadsheet's number format does: `0` is
    // a digit always written, `#` a digit written only when there is one, `.` the decimal point, a
    // `,` in the integer part groups the digits by three, and a final `%` writes the number times
    // 100. The integer part is `#`s then `0`s, a single comma allowed between any two of them; the
    // decimals are `0`s then `#`s — (#(,?#)*(,?0)*|0(,?0)*)(\.(0+#*|#+))?%? as a regex. Spec
    // v0.30.0's Python-style {:spec} was replaced as hard to read (v0.31.0).

    /** A parsed number pattern: how many integer digits and decimals it always writes, and at most. */
    private record NumberPattern(int minInt, boolean group, int minFrac, int maxFrac, boolean percent) {
        /**
         * The pattern, or null if {@code text} is not one. A plain scan rather than that regex:
         * java.util.regex recurses once per repetition of a group, so a pattern of a few thousand
         * symbols overflowed the stack.
         */
        static NumberPattern parse(String text) {
            int n = text.length();
            int i = 0;
            int digits = 0;
            int zeros = 0;
            boolean group = false;
            boolean comma = false;                                 // the last symbol was a comma
            for (; i < n; i++) {
                char c = text.charAt(i);
                if (c == ',') {
                    if (digits == 0 || comma) return null;         // a comma first, or two in a row
                    group = true;
                    comma = true;
                } else if (c == '0' || c == '#') {
                    if (c == '#' && zeros > 0) return null;        // a # after a 0
                    if (c == '0') zeros++;
                    digits++;
                    comma = false;
                } else {
                    break;
                }
            }
            if (digits == 0 || comma) return null;                 // no digit, or a comma last
            int minFrac = 0;
            int maxFrac = 0;
            if (i < n && text.charAt(i) == '.') {
                for (i++; i < n && (text.charAt(i) == '0' || text.charAt(i) == '#'); i++) {
                    if (text.charAt(i) == '0') {
                        if (maxFrac > minFrac) return null;        // a 0 after a #
                        minFrac++;
                    }
                    maxFrac++;
                }
                if (maxFrac == 0) return null;                     // a point with no decimal after it
            }
            boolean percent = i < n && text.charAt(i) == '%';
            if (percent) i++;
            return i == n ? new NumberPattern(zeros, group, minFrac, maxFrac, percent) : null;
        }
    }

    /** {@code {}} (pattern null) or {@code {pattern}}; {@code text} is the placeholder as written. */
    private record Placeholder(String text, NumberPattern pattern) {}

    /**
     * FORMAT(format, values...) — also PRINTF's text, under PRINTF's name. Each {@code {}} or
     * {@code {pattern}} takes the next value; {@code {{} and {@code }}} are literal braces. The
     * format is checked whole before any value is used: an invalid format, then a count mismatch,
     * then a value that is not a number under a pattern.
     */
    public static String format(String fn, List<Object> args) {
        Object f = arg(fn, args, 0);
        if (!(f instanceof String format)) throw new TeeError(fn + "() requires a string format");
        List<Object> parts = parseFormat(fn, format);
        int placeholders = 0;
        for (Object part : parts) if (part instanceof Placeholder) placeholders++;
        int values = args.size() - 1;
        if (placeholders != values) {
            throw new TeeError(fn + "() format has " + count(placeholders, "placeholder") + ", got " + count(values, "value"));
        }
        StringBuilder out = new StringBuilder();
        int next = 1;
        for (Object part : parts) {
            if (part instanceof Placeholder p) out.append(formatValue(fn, p, args.get(next++)));
            else out.append((String) part);
        }
        return out.toString();
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /** The format as literal text and placeholders, in order. */
    private static List<Object> parseFormat(String fn, String format) {
        List<Object> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < format.length()) {
            char c = format.charAt(i);
            if (c == '{' && format.startsWith("{{", i)) { literal.append('{'); i += 2; continue; }
            if (c == '}' && format.startsWith("}}", i)) { literal.append('}'); i += 2; continue; }
            if (c == '}') throw new TeeError(fn + "() invalid format: unmatched }");
            if (c != '{') { literal.append(c); i++; continue; }
            int close = format.indexOf('}', i + 1);
            if (close < 0) throw new TeeError(fn + "() invalid format: unclosed {");
            String text = format.substring(i, close + 1);
            String inner = text.substring(1, text.length() - 1);
            NumberPattern pattern = inner.isEmpty() ? null : NumberPattern.parse(inner);
            if (!inner.isEmpty() && pattern == null) throw new TeeError(fn + "() invalid format: " + text);
            if (literal.length() > 0) { parts.add(literal.toString()); literal.setLength(0); }
            parts.add(new Placeholder(text, pattern));
            i = close + 1;
        }
        if (literal.length() > 0) parts.add(literal.toString());
        return parts;
    }

    /** One value under its placeholder: {@code {}} is the value as PRINT shows it. */
    private static String formatValue(String fn, Placeholder p, Object v) {
        if (p.pattern() == null) return TeeFormat.display(v);
        if (!Values.isNumber(v)) {
            throw new TeeError(fn + "() " + p.text() + " requires a number, got " + Values.typeName(v));
        }
        return shape(Values.toDouble(v), p.pattern());
    }

    /**
     * {@code n} written as the pattern draws it. The digits PRINT shows are rounded to the
     * pattern's decimals as ROUND(n, decimals) rounds them, a tie away from zero, after a `%`
     * moves the point two places — so 0.145 is 15%, where the binary value times 100 would give
     * 14%. The integer digits are never cut and at least one is written; zeros fill up to the
     * pattern's `0`s, after the sign. A `#` decimal drops a trailing zero, and the point goes when
     * no decimal is left. A value rounded to zero has no sign, and no exponent is ever written.
     * Infinity and NaN are written as PRINT shows them.
     */
    private static String shape(double n, NumberPattern p) {
        if (!Double.isFinite(n)) return TeeFormat.display(n);
        DecimalDigits d = DecimalDigits.of(n);
        if (p.percent()) d = d.scaled(2);
        d = d.round(p.maxFrac(), DecimalDigits.Mode.HALF_UP);
        String text = d.fixed(p.maxFrac());
        int point = text.indexOf('.');
        String whole = point < 0 ? text : text.substring(0, point);
        String frac = point < 0 ? "" : text.substring(point + 1);
        if (whole.length() < p.minInt()) whole = "0".repeat(p.minInt() - whole.length()) + whole;
        if (p.group()) whole = groupThousands(whole);
        int end = frac.length();
        while (end > p.minFrac() && frac.charAt(end - 1) == '0') end--;
        return (d.negative() && !d.isZero() ? "-" : "") + whole + (end > 0 ? "." + frac.substring(0, end) : "")
                + (p.percent() ? "%" : "");
    }

    /** "1234567" → "1,234,567". */
    private static String groupThousands(String whole) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < whole.length(); i++) {
            if (i > 0 && (whole.length() - i) % 3 == 0) sb.append(',');
            sb.append(whole.charAt(i));
        }
        return sb.toString();
    }

    /**
     * TEXT(n, pattern): one number written as the pattern draws it — what {@code {pattern}} writes
     * in a format. Like a format, the pattern is checked before the value.
     */
    private static String text(List<Object> args) {
        if (args.size() > 2) throw new TeeError("TEXT() takes at most 2 arguments");
        Object n = arg("TEXT", args, 0);
        if (!(arg("TEXT", args, 1) instanceof String text)) throw new TeeError("TEXT() requires a string pattern");
        NumberPattern pattern = NumberPattern.parse(text);
        if (pattern == null) throw new TeeError("TEXT() invalid pattern: '" + text + "'");
        if (!Values.isNumber(n)) throw new TeeError("TEXT() requires a number, got " + Values.typeName(n));
        return shape(Values.toDouble(n), pattern);
    }

    /**
     * ALIGN_LEFT/ALIGN_RIGHT/ALIGN_CENTER(value, width): the value as PRINT shows it, padded with
     * spaces to {@code width} columns — a Hangul syllable or another wide character takes two
     * (DisplayWidth). Longer text is not cut, and centering puts an odd space on the right. A third
     * argument is an error, so a fill character can be added later.
     */
    private static String align(String fn, List<Object> args) {
        if (args.size() > 2) throw new TeeError(fn + "() takes at most 2 arguments");
        String text = TeeFormat.display(arg(fn, args, 0));
        Object width = arg(fn, args, 1);
        if (!isWhole(width) || Values.toDouble(width) < 0 || Values.toDouble(width) > 999) {
            throw new TeeError(fn + "() width must be an integer from 0 to 999");
        }
        int fill = (int) Values.toDouble(width) - DisplayWidth.of(text);
        if (fill <= 0) return text;
        return switch (fn) {
            case "ALIGN_LEFT" -> text + " ".repeat(fill);
            case "ALIGN_RIGHT" -> " ".repeat(fill) + text;
            default -> " ".repeat(fill / 2) + text + " ".repeat(fill - fill / 2);
        };
    }

    // ---- arg helpers -------------------------------------------------------

    private static Object arg(String fn, List<Object> args, int i) {
        if (i >= args.size()) throw new TeeError(fn + "() missing argument " + (i + 1));
        return args.get(i);
    }

    private static Object number(String fn, Object v) {
        if (!Values.isNumber(v)) throw new TeeError(fn + "() requires numeric arguments");
        return v;
    }

    private static double intArg(String fn, List<Object> args, int i) {
        return Values.toDouble(number(fn, arg(fn, args, i)));
    }

    private static String string(String fn, Object v) {
        if (!(v instanceof String s)) throw new TeeError(fn + "() requires a string argument");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String fn, Object v) {
        if (!(v instanceof Map)) throw new TeeError(fn + "() argument must be an object");
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(String fn, Object v) {
        if (!(v instanceof List)) throw new TeeError(fn + "() requires an array argument");
        return (List<Object>) v;
    }

    private static List<Object> copyArray(String fn, Object v) {
        List<Object> src = array(fn, v);
        List<Object> out = new ArrayList<>(src.size());
        for (Object e : src) out.add(Values.deepCopy(e));
        return out;
    }

    /**
     * The from..to span of SUBSTRING and SLICE (spec v0.34.0): start must be an integer of 1 or more
     * and the length or count one of 0 or more, but past the end there is just less. Positions stay
     * doubles until they are cut to the size, so one past 32 bits — SUBSTRING(s, 2, 3000000000) —
     * cannot wrap around.
     */
    private static int[] span(String fn, List<Object> args, String countName, int size) {
        int from = (int) Math.min(position(fn, args, 1, "start", 1) - 1, size);
        if (args.size() <= 2) return new int[] {from, size};
        return new int[] {from, (int) Math.min(from + position(fn, args, 2, countName, 0), size)};
    }

    private static double position(String fn, List<Object> args, int i, String name, int min) {
        Object v = arg(fn, args, i);
        if (!isWhole(v) || Values.toDouble(v) < min) {
            throw new TeeError(fn + "() " + name + " must be an integer of " + min + " or more");
        }
        return Values.toDouble(v);
    }

    // Spec v0.24.0 whitespace (TRIM, WORDS): exactly space, \t, \n, VT, \f, \r. String.trim()
    // would also strip the other C0 controls — characters the JavaScript runtime keeps.
    private static boolean isWhitespace(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    private static String trimWhitespace(String s) {
        int from = 0, to = s.length();
        while (from < to && isWhitespace(s.charAt(from))) from++;
        while (to > from && isWhitespace(s.charAt(to - 1))) to--;
        return s.substring(from, to);
    }

    // A regex match as {match, position, groups, named}: position is 1-based (UTF-16 units, like
    // FIND), groups.N is group N, named lists the named groups in pattern order; a group that took
    // no part in the match is {}.
    private static Map<String, Object> matchObject(Matcher m) {
        List<Object> groups = new ArrayList<>(m.groupCount());
        for (int g = 1; g <= m.groupCount(); g++) groups.add(groupValue(m, g));
        // namedGroups() maps names to numbers in no particular order.
        List<Map.Entry<String, Integer>> names = new ArrayList<>(m.namedGroups().entrySet());
        names.sort(Map.Entry.comparingByValue());
        Map<String, Object> named = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : names) named.put(e.getKey(), groupValue(m, e.getValue()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("match", m.group());
        out.put("position", m.start() + 1);
        out.put("groups", groups);
        out.put("named", named);
        return out;
    }

    private static Object groupValue(Matcher m, int g) {
        String s = m.group(g);
        return s == null ? Values.emptyObject() : s;
    }

    private static Pattern compile(String fn, String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            throw new TeeError(fn + "() invalid regex pattern: " + pattern);
        }
    }

    private record Pair(String left, String right) {}

    private static Pair str2(String fn, List<Object> args) {
        return new Pair(string(fn, arg(fn, args, 0)), string(fn, arg(fn, args, 1)));
    }

    private static Pair findArgs(String fn, List<Object> args) {
        Pair p = str2(fn, args);
        if (p.right.isEmpty()) throw new TeeError(fn + "() requires a non-empty search string");
        return p;
    }

    // Spec v0.25.0: TO_NUMBER reads exactly a decimal numeral (sign, ASCII digits with an optional
    // fraction — ".5" and "5." included — and an optional exponent) inside the TRIM whitespace.
    // Validating first keeps Long.parseLong's non-ASCII digits and Double.parseDouble's "1f",
    // "0x1p3", "NaN" and "Infinity" out; the JavaScript runtime's Number() accepts a different set.
    private static final Pattern DECIMAL_NUMERAL =
            Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private static Object toNumber(String s) {
        String t = trimWhitespace(s);
        if (!DECIMAL_NUMERAL.matcher(t).matches()) throw new TeeError("TO_NUMBER() cannot convert '" + s + "' to number");
        boolean fractional = t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0;
        if (!fractional) {
            try {
                long v = Long.parseLong(t);
                if (Values.fitsInteger(v)) return Values.integer(v);
            } catch (NumberFormatException outsideLong) {
                // longer still
            }
            // integer text beyond the integer range is a decimal (spec v0.13.0, v0.33.0)
        }
        double d = Double.parseDouble(t);
        if (Double.isInfinite(d)) throw new TeeError("TO_NUMBER() value out of range: '" + s + "'");
        return d;
    }
}
