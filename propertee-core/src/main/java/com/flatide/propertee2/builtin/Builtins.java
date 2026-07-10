package com.flatide.propertee2.builtin;

import com.flatide.propertee2.host.PlatformProvider;
import com.flatide.propertee2.value.JsonParser;
import com.flatide.propertee2.value.Result;
import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeResult;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void register(String name, Kind kind, Builtin fn) {
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
        return standard(platform, new com.flatide.task.UnsupportedTaskRunner(), null);
    }

    /** Pure catalog + host-gated/blocking builtins backed by a {@link PlatformProvider} and host TaskRunner (§3.1). */
    public static Builtins standard(PlatformProvider platform, com.flatide.task.TaskRunner taskRunner) {
        return standard(platform, taskRunner, null);
    }

    /** As above, with a run id so SHELL tags each TaskRequest (lets a host group spawned tasks by run). */
    public static Builtins standard(PlatformProvider platform, com.flatide.task.TaskRunner taskRunner, String runId) {
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
            boolean allInt = true;
            double d = 0;
            long i = 0;                         // widen the integer path so overflow is detected, not wrapped
            for (Object a : args) {
                number("SUM", a);
                if (a instanceof Integer n) { i += n; d += n; } else { allInt = false; d += (Double) a; }
            }
            if (allInt) {
                if (i < Integer.MIN_VALUE || i > Integer.MAX_VALUE) throw new TeeError("Integer overflow");  // spec v0.14.0, fail-loud like +/*
                return (Object) (int) i;
            }
            return (Object) d;
        });
        b.register("MAX", Kind.PURE, args -> minMax("MAX", args, true));
        b.register("MIN", Kind.PURE, args -> minMax("MIN", args, false));
        b.register("ABS", Kind.PURE, args -> {
            Object n = arg("ABS", args, 0);
            number("ABS", n);
            if (n instanceof Integer i) {
                if (i == Integer.MIN_VALUE) throw new TeeError("Integer overflow");   // |MIN| doesn't fit (v0.13.0)
                return Math.abs(i);
            }
            return Math.abs((Double) n);
        });
        // FLOOR/CEIL/ROUND always return an Integer (FLOOR(3.9)->3, CEIL(3.1)->4, ROUND(3.5)->4).
        // A result outside the 32-bit range is a loud error, never a clamp (spec v0.13.0).
        b.register("FLOOR", Kind.PURE, args -> intResult(Math.floor(Values.toDouble(number("FLOOR", arg("FLOOR", args, 0))))));
        b.register("CEIL",  Kind.PURE, args -> intResult(Math.ceil(Values.toDouble(number("CEIL", arg("CEIL", args, 0))))));
        b.register("ROUND", Kind.PURE, args -> intResult(Math.round(Values.toDouble(number("ROUND", arg("ROUND", args, 0))))));
    }

    /** The 32-bit integer envelope for integer-producing builtins (spec v0.13.0). */
    private static int intResult(double d) {
        if (d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) throw new TeeError("Integer overflow");
        return (int) d;
    }

    private static Object minMax(String fn, List<Object> args, boolean max) {
        if (args.isEmpty()) throw new TeeError(fn + "() requires at least one argument");
        Object best = null;
        double bestD = 0;
        for (Object a : args) {
            number(fn, a);
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
        b.register("TRIM",      Kind.PURE, args -> string("TRIM", arg("TRIM", args, 0)).trim());

        // SUBSTRING(s, start, [length]) — start is 1-based; 3rd arg is LENGTH (13_strings).
        b.register("SUBSTRING", Kind.PURE, args -> {
            String s = string("SUBSTRING", arg("SUBSTRING", args, 0));
            int from = clamp((int) intArg("SUBSTRING", args, 1) - 1, 0, s.length());
            int to = args.size() > 2 ? clamp(from + (int) intArg("SUBSTRING", args, 2), from, s.length()) : s.length();
            return s.substring(from, to);
        });
        // SPLIT preserves trailing empty strings; delimiter is literal.
        b.register("SPLIT", Kind.PURE, args -> {
            String s = string("SPLIT", arg("SPLIT", args, 0));
            String delim = string("SPLIT", arg("SPLIT", args, 1));
            List<Object> out = new ArrayList<>();
            for (String part : s.split(Pattern.quote(delim), -1)) out.add(part);
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
        b.register("MATCHES", Kind.PURE, args -> {
            Pair p = str2("MATCHES", args);
            return compile("MATCHES", p.right).matcher(p.left).find();
        });
        // REGEX_FIND -> [fullMatch, group1, ...] 1-based; no match -> {}; unmatched group -> {} (81_string_matching).
        b.register("REGEX_FIND", Kind.PURE, args -> {
            Pair p = str2("REGEX_FIND", args);
            Matcher m = compile("REGEX_FIND", p.right).matcher(p.left);
            if (!m.find()) return Values.emptyObject();
            List<Object> out = new ArrayList<>(m.groupCount() + 1);
            for (int g = 0; g <= m.groupCount(); g++) {
                String grp = m.group(g);
                out.add(grp == null ? Values.emptyObject() : grp);
            }
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
            int from = clamp((int) intArg("SLICE", args, 1) - 1, 0, arr.size());
            int to = args.size() > 2 ? clamp(from + (int) intArg("SLICE", args, 2), from, arr.size()) : arr.size();
            List<Object> out = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) out.add(Values.deepCopy(arr.get(i)));
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
        b.register("JSON_FORMAT", Kind.PURE, args -> TeeFormat.json(arg("JSON_FORMAT", args, 0)));
        b.register("JSON_PARSE", Kind.PURE, args -> {
            String s = string("JSON_PARSE", arg("JSON_PARSE", args, 0));
            try {
                return Result.ok(JsonParser.parse(s));
            } catch (TeeError e) {
                return Result.error(e.getMessage());
            }
        });
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
            return Result.error(e.getMessage());
        } catch (UncheckedIOException e) {
            // Files.lines()/Files.list() defer I/O — a read failure during lazy consumption
            // (e.g. malformed UTF-8, a directory vanishing mid-iteration) surfaces here, not as a
            // checked IOException. Surface it as the v1 Result.error rather than a raw runtime error.
            IOException cause = e.getCause();
            return Result.error(cause != null ? cause.getMessage() : e.getMessage());
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
            platform.writeFile(string("WRITE_FILE", arg("WRITE_FILE", args, 0)),
                    string("WRITE_FILE", arg("WRITE_FILE", args, 1)));
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
            return Result.ok(Values.emptyObject());
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
            info.put("size", numberFromLong(s.size()));
            info.put("modified", (double) s.modified());
            return Result.ok(info);
        }));

        b.register("LIST_DIR", Kind.HOST_GATED, args -> ioResult(() -> {
            List<PlatformProvider.DirEntry> entries = platform.listDir(string("LIST_DIR", arg("LIST_DIR", args, 0)));
            List<Object> out = new ArrayList<>();
            for (PlatformProvider.DirEntry e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.name());
                m.put("type", e.type());
                m.put("size", numberFromLong(e.size()));
                out.add(m);
            }
            return Result.ok(out);
        }));

        b.register("DELETE_FILE", Kind.HOST_GATED, args -> ioResult(() -> {
            platform.deleteFile(string("DELETE_FILE", arg("DELETE_FILE", args, 0)));
            return Result.ok(Values.emptyObject());
        }));
    }

    private static boolean isWhole(Object v) {
        return Values.isNumber(v) && Values.toDouble(v) == Math.floor(Values.toDouble(v))
                && !Double.isInfinite(Values.toDouble(v));
    }

    private static Object numberFromLong(long v) {
        return (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) ? (Object) (int) v : (Object) (double) v;
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
                    optionsHeaders(options), optionsBody(options), optionsTimeout(options));
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
            String body = coerceBody(args.get(1));
            return doHttp(platform, "POST", (String) args.get(0), optionsHeaders(options),
                    body != null ? body : "", optionsTimeout(options));
        });
    }

    private static Object doHttp(PlatformProvider platform, String method, String url,
                                 Map<String, String> headers, String body, int timeoutMs) {
        Map<String, Object> value = new LinkedHashMap<>();
        try {
            PlatformProvider.HttpResponse resp = platform.httpRequest(method, url, headers, body, timeoutMs);
            value.put("status", numberFromLong(resp.status()));
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

    private static String optionsBody(Map<String, Object> options) {
        if (options == null) return null;
        return coerceBody(options.get("body"));
    }

    private static int optionsTimeout(Map<String, Object> options) {
        if (options == null) return 0;
        Object t = options.get("timeout");
        return Values.isNumber(t) ? (int) Values.toDouble(t) : 0;
    }

    private static String coerceBody(Object body) {
        if (body == null) return null;
        if (body instanceof String s) return s;
        return TeeFormat.json(body);
    }

    // ---- Shell (executes via a host TaskRunner; default UnsupportedTaskRunner — 72/78/80) ----

    private static void registerShell(Builtins b, PlatformProvider platform,
                                      com.flatide.task.TaskRunner taskRunner, String runId) {
        // SHELL runs a process through the host TaskRunner (the v1 com.flatide.task contract). With the
        // default UnsupportedTaskRunner this surfaces as Result.error("...requires a host-provided TaskRunner...").
        Shell shell = new Shell(taskRunner, runId);
        b.register("SHELL", Kind.BLOCKING, shell::run);

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
            int lo = (int) intArg("RANDOM", args, 0);
            int hi = (int) intArg("RANDOM", args, 1);
            return lo + (int) (Math.random() * (hi - lo + 1));        // lo .. hi inclusive
        });
        b.register("MILTIME", Kind.PURE, args -> (double) System.currentTimeMillis()); // epoch millis exceed int range
        b.register("DATE", Kind.PURE, args -> LocalDate.now().toString());              // yyyy-MM-dd
        b.register("TIME", Kind.PURE, args -> LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
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

    private static int clamp(int x, int lo, int hi) { return Math.max(lo, Math.min(hi, x)); }

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

    private static Object toNumber(String s) {
        String t = s.trim();
        try {
            return Integer.valueOf(t);
        } catch (NumberFormatException ignored) {
            try {
                return Double.valueOf(t);
            } catch (NumberFormatException e) {
                throw new TeeError("Cannot convert '" + s + "' to number");
            }
        }
    }
}
