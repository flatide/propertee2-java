package com.flatide.propertee2.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser producing ProperTee values
 * (Integer / Double / String / Boolean / LinkedHashMap / ArrayList).
 * JSON {@code null} maps to {@code {}} (an empty object) — there is no null (84_json).
 * Integers without a fractional/exponent part become {@code Integer}; otherwise {@code Double}.
 *
 * <p>Used by the {@code JSON_PARSE} builtin, which wraps success/failure in a {@link Result}.
 */
public final class JsonParser {
    private final String s;
    private int i;

    private JsonParser(String s) { this.s = s; }

    /** Parse a complete JSON document; throws {@link TeeError} on malformed input. */
    public static Object parse(String text) {
        JsonParser p = new JsonParser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing characters");
        return v;
    }

    private Object value() {
        if (i >= s.length()) throw err("unexpected end of input");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't', 'f' -> bool();
            case 'n' -> nullValue();
            default -> number();
        };
    }

    private Map<String, Object> object() {
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { i++; return m; }
        while (true) {
            ws();
            if (peek() != '"') throw err("expected object key");
            String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value());
            ws();
            char c = next();
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        expect('[');
        List<Object> list = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') return list;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c == '\\') {
                if (i >= s.length()) throw err("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) throw err("bad unicode escape");
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw err("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        while (i < s.length() && isNum(s.charAt(i))) i++;
        if (i == start || (i == start + 1 && s.charAt(start) == '-')) throw err("invalid number");
        String tok = s.substring(start, i);
        boolean fractional = tok.indexOf('.') >= 0 || tok.indexOf('e') >= 0 || tok.indexOf('E') >= 0;
        try {
            if (!fractional) return Integer.valueOf(tok);
        } catch (NumberFormatException tooBig) {
            return Double.valueOf(tok);
        }
        return Double.valueOf(tok);
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("invalid literal");
    }

    private Object nullValue() {
        if (s.startsWith("null", i)) { i += 4; return Values.emptyObject(); } // null -> {}
        throw err("invalid literal");
    }

    // ---- low-level ----
    private boolean isNum(char c) { return (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E'; }
    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    private char next() { if (i >= s.length()) throw err("unexpected end of input"); return s.charAt(i++); }
    private void expect(char c) { if (next() != c) throw err("expected '" + c + "'"); }
    private TeeError err(String why) { return new TeeError("Invalid JSON: " + why + " at position " + i); }
}
