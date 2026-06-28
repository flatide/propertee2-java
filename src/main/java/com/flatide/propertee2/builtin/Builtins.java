package com.flatide.propertee2.builtin;

import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.TeeFormat;
import com.flatide.propertee2.value.Values;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <p>PA ports the PURE core (below) so the value model is exercised end-to-end. HOST_GATED
 * and BLOCKING builtins are registered in PC/PD where they are wrapped in {@code Coop.blocking}
 * (the "release baton → block → re-acquire" contract, design §3.1); calling one before then
 * fails loudly rather than silently blocking the scheduler.
 */
public final class Builtins {

    public enum Kind { PURE, OUTPUT, HOST_GATED, BLOCKING }

    private record Entry(Kind kind, Builtin fn) {}

    private final Map<String, Entry> registry = new HashMap<>();

    public void register(String name, Kind kind, Builtin fn) {
        registry.put(name, new Entry(kind, fn));
    }

    public boolean has(String name) { return registry.containsKey(name); }

    public Kind kindOf(String name) {
        Entry e = registry.get(name);
        return e == null ? null : e.kind();
    }

    /** Dispatch a builtin by name. HOST_GATED/BLOCKING are not invocable until PC wires Coop.blocking. */
    public Object call(String name, List<Object> args) {
        Entry e = registry.get(name);
        if (e == null) throw new TeeError("Unknown function '" + name + "'");
        if (e.kind() == Kind.HOST_GATED || e.kind() == Kind.BLOCKING) {
            throw new TeeError("'" + name + "' requires the Coop runtime (not wired until PC)");
        }
        return e.fn().invoke(args);
    }

    /** Registry pre-loaded with the PURE built-ins ported in PA. */
    public static Builtins standard() {
        Builtins b = new Builtins();
        registerMath(b);
        registerTypeAndString(b);
        registerObject(b);
        registerJson(b);
        return b;
    }

    // ---- Math (return types verified against 02_arithmetic) ----------------

    private static void registerMath(Builtins b) {
        b.register("SUM", Kind.PURE, args -> {
            boolean allInt = true;
            double d = 0;
            int i = 0;
            for (Object a : args) {
                number("SUM", a);
                if (a instanceof Integer n) { i += n; d += n; } else { allInt = false; d += (Double) a; }
            }
            return allInt ? (Object) i : (Object) d;
        });
        b.register("MAX", Kind.PURE, args -> minMax("MAX", args, true));
        b.register("MIN", Kind.PURE, args -> minMax("MIN", args, false));
        b.register("ABS", Kind.PURE, args -> {
            Object n = arg("ABS", args, 0);
            number("ABS", n);
            return (n instanceof Integer i) ? (Object) Math.abs(i) : (Object) Math.abs((Double) n);
        });
        // FLOOR/CEIL/ROUND always return an Integer (FLOOR(3.9)->3, CEIL(3.1)->4, ROUND(3.5)->4).
        b.register("FLOOR", Kind.PURE, args -> (int) Math.floor(Values.toDouble(number("FLOOR", arg("FLOOR", args, 0)))));
        b.register("CEIL",  Kind.PURE, args -> (int) Math.ceil(Values.toDouble(number("CEIL", arg("CEIL", args, 0)))));
        b.register("ROUND", Kind.PURE, args -> (int) Math.round(Values.toDouble(number("ROUND", arg("ROUND", args, 0)))));
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
            return 0; // LANGUAGE.md §String: 0 for other types
        });
        b.register("UPPERCASE", Kind.PURE, args -> string("UPPERCASE", arg("UPPERCASE", args, 0)).toUpperCase());
        b.register("LOWERCASE", Kind.PURE, args -> string("LOWERCASE", arg("LOWERCASE", args, 0)).toLowerCase());
        b.register("TRIM",      Kind.PURE, args -> string("TRIM", arg("TRIM", args, 0)).trim());
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

    // ---- Object ------------------------------------------------------------

    private static void registerObject(Builtins b) {
        b.register("KEYS", Kind.PURE, args -> new ArrayList<Object>(object("KEYS", arg("KEYS", args, 0)).keySet()));
        b.register("VALUES", Kind.PURE, args -> {
            Map<String, Object> m = object("VALUES", arg("VALUES", args, 0));
            List<Object> out = new ArrayList<>(m.size());
            for (Object v : m.values()) out.add(Values.deepCopy(v));
            return out;
        });
        b.register("HAS_KEY", Kind.PURE, args -> {
            Map<String, Object> m = object("HAS_KEY", arg("HAS_KEY", args, 0));
            String key = string("HAS_KEY", arg("HAS_KEY", args, 1));
            return m.containsKey(key);
        });
    }

    // ---- JSON --------------------------------------------------------------

    private static void registerJson(Builtins b) {
        b.register("JSON_FORMAT", Kind.PURE, args -> TeeFormat.json(arg("JSON_FORMAT", args, 0)));
        // JSON_PARSE returns a Result and has intricate edge semantics (null -> {}); ported with its fixture in PE.
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

    private static String string(String fn, Object v) {
        if (!(v instanceof String s)) throw new TeeError(fn + "() requires a string argument");
        return s;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(String fn, Object v) {
        if (!(v instanceof Map)) throw new TeeError(fn + "() requires an object argument");
        return (Map<String, Object>) v;
    }
}
