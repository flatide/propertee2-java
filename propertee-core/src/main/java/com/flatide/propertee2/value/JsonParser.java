package com.flatide.propertee2.value;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser producing ProperTee values
 * (Integer / Double / String / Boolean / LinkedHashMap / ArrayList / JsonNull).
 * JSON {@code null} maps to {@link JsonNull#NULL} — preserved losslessly (spec v0.8.0 #4;
 * until spec v0.7.0 it was normalized to {@code {}}).
 * Integers without a fractional/exponent part become {@code Integer}; otherwise {@code Double}.
 *
 * <p>It reads JSON as RFC 8259 writes it (spec v0.35.0): it used to take {@code 01}, {@code .5},
 * {@code 1.}, any {@link Character#isWhitespace} space and raw control characters in a string, and a
 * malformed number or unicode escape escaped as a NumberFormatException that ended the whole run.
 * Everything wrong is a {@link TeeError} now. A numeral too large to represent is read as Infinity,
 * as {@link Double#valueOf} reads it; JSON_PARSE and READ_JSON_FILE refuse it.
 *
 * <p>Used by the {@code JSON_PARSE} builtin, which wraps success/failure in a {@link Result}.
 */
public final class JsonParser {
    /** Nesting deeper than this is invalid (spec v0.35.0): each level is a call, so 10,000 overflowed the stack. */
    public static final int MAX_DEPTH = 1000;

    private final String s;
    private int i;
    private int depth;

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
        enter();
        expect('{');
        Map<String, Object> m = new LinkedHashMap<>();
        ws();
        if (peek() == '}') { i++; depth--; return m; }
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
            if (c == '}') { depth--; return m; }
            if (c != ',') { i--; throw err("expected ',' or '}'"); }   // the position is that character's
        }
    }

    private List<Object> array() {
        enter();
        expect('[');
        List<Object> list = new ArrayList<>();
        ws();
        if (peek() == ']') { i++; depth--; return list; }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = next();
            if (c == ']') { depth--; return list; }
            if (c != ',') { i--; throw err("expected ',' or ']'"); }
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c < 0x20) { i--; throw err("control character in string"); }   // JSON escapes them
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
                        int code = 0;
                        for (int k = 0; k < 4; k++, i++) {
                            int h = hex(peek());
                            if (h < 0) throw err("bad unicode escape");
                            code = code * 16 + h;
                        }
                        sb.append((char) code);
                    }
                    default -> throw err("bad escape \\" + e);
                }
            } else {
                sb.append(c);
            }
        }
    }

    /** -?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)? — the position is where it stops being one. */
    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        if (peek() == '0') i++;
        else digits();
        boolean fractional = false;
        if (peek() == '.') {
            i++;
            digits();
            fractional = true;
        }
        if (peek() == 'e' || peek() == 'E') {
            i++;
            if (peek() == '+' || peek() == '-') i++;
            digits();
            fractional = true;
        }
        String tok = s.substring(start, i);
        if (!fractional) {
            try {
                long v = Long.parseLong(tok);
                if (Values.fitsInteger(v)) return Values.integer(v);
            } catch (NumberFormatException outsideLong) {
                // beyond a long: a decimal, like any integer outside the range (spec v0.33.0)
            }
        }
        return Double.valueOf(tok);
    }

    private Boolean bool() {
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        throw err("invalid literal");
    }

    private Object nullValue() {
        if (s.startsWith("null", i)) { i += 4; return JsonNull.NULL; } // spec v0.8.0 (#4): preserved
        throw err("invalid literal");
    }

    // ---- low-level ----
    /** One or more ASCII digits. */
    private void digits() {
        if (!isDigit(peek())) throw err("invalid number");
        while (isDigit(peek())) i++;
    }
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    /** An ASCII hex digit's value, or -1 (Character.digit would also take a fullwidth 'Ａ'). */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
    private void enter() { if (++depth > MAX_DEPTH) throw err("nested more than " + MAX_DEPTH + " levels deep"); }
    /** JSON's four whitespace characters only. */
    private void ws() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
    }
    private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }
    private char next() { if (i >= s.length()) throw err("unexpected end of input"); return s.charAt(i++); }
    private void expect(char c) { if (next() != c) { i--; throw err("expected '" + c + "'"); } }
    private TeeError err(String why) { return new TeeError("Invalid JSON: " + why + " at position " + i); }
}
