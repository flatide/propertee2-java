package com.flatide.propertee2.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Spec v0.35.0: the parser reads JSON as RFC 8259 writes it, and everything wrong is a TeeError —
 * a malformed number or unicode escape used to escape as a NumberFormatException and deep nesting
 * as a StackOverflowError, ending the whole run.
 */
class JsonParserTest {

    private static String error(String text) {
        return assertThrows(TeeError.class, () -> JsonParser.parse(text), text).getMessage();
    }

    @Test void numbersAreJsonNumbers() {
        assertEquals(List.of(0, 0, 0.5, 100000.0, 100000.0, 0.0025, -12), JsonParser.parse("[0, -0, 0.5, 1E5, 1e+5, 2.5e-3, -12]"));
        // the position is where the text stops being a number
        assertEquals("Invalid JSON: trailing characters at position 1", error("01"));
        assertEquals("Invalid JSON: invalid number at position 2", error("1."));
        assertEquals("Invalid JSON: invalid number at position 0", error(".5"));
        assertEquals("Invalid JSON: invalid number at position 2", error("1.e5"));
        assertEquals("Invalid JSON: invalid number at position 0", error("+1"));
        assertEquals("Invalid JSON: invalid number at position 2", error("1e"));
        assertEquals("Invalid JSON: invalid number at position 3", error("1e+"));
        assertEquals("Invalid JSON: invalid number at position 1", error("--1"));
        assertEquals("Invalid JSON: trailing characters at position 1", error("1-2"));
        assertEquals("Invalid JSON: expected ',' or ']' at position 7", error("[1, 2e5.5]"));   // was 8, past the '.'
        assertEquals("Invalid JSON: expected ':' at position 5", error("{\"a\" 1}"));
        assertEquals("Invalid JSON: invalid number at position 6", error("[1, 2e]"));
        assertEquals("Invalid JSON: invalid number at position 6", error("{\"x\": Infinity}"));   // as before
        // the parser reads a numeral too large to represent as Infinity; JSON_PARSE refuses it
        assertEquals(List.of(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0), JsonParser.parse("[1e999, -1e999, 1e-999]"));
    }

    @Test void whitespaceAndStringsAreJson() {
        assertEquals(List.of(1), JsonParser.parse(" \t\n\r[1] \r\n"));
        for (char c : new char[] {'\u000B', '\u000C', '\u001C', '\u00A0', '\u2002', '\u3000', '\uFEFF'}) {
            assertEquals("Invalid JSON: invalid number at position 0", error(c + "[1]"));
        }
        assertEquals("a\tbA\u00e9", JsonParser.parse("\"a\\tb\\u0041\\u00E9\""));
        assertEquals("Invalid JSON: control character in string at position 2", error("\"a\tb\""));
        assertEquals("Invalid JSON: control character in string at position 1", error("\"\u0000\""));
        assertEquals("Invalid JSON: bad unicode escape at position 3", error("\"\\uZZZZ\""));
        assertEquals("Invalid JSON: bad unicode escape at position 5", error("\"\\u41\""));
        assertEquals("Invalid JSON: bad unicode escape at position 3", error("\"\\u+041\""));
        assertEquals("Invalid JSON: bad unicode escape at position 3", error("\"\\u\uFF21041\""));   // fullwidth A
        assertEquals("Invalid JSON: bad unicode escape at position 4", error("\"\\u0"));
        assertEquals("Invalid JSON: expected object key at position 1", error("{1}"));   // as before
    }

    @Test void atMostAThousandLevels() {
        String deep = "[".repeat(1000) + "]".repeat(1000);
        JsonParser.parse(deep);
        assertEquals("Invalid JSON: nested more than 1000 levels deep at position 1000", error("[" + deep + "]"));
        assertEquals("Invalid JSON: nested more than 1000 levels deep at position 5000",
                error("{\"a\":".repeat(1001) + "1" + "}".repeat(1001)));
        // far too deep fails the same way rather than overflowing the stack
        assertEquals("Invalid JSON: nested more than 1000 levels deep at position 1000", error("[".repeat(100_000)));
    }

    /** Whatever the text, the parser returns a value or throws a TeeError — nothing else. */
    @Test void onlyTeeErrorsEscape() {
        String alphabet = "0123456789-+.eE \t\n\u000B\"\\u/bfnrtAZaz[]{}:,xn\u3000\u0001";
        String[] seeds = {"1e5", "-0.5", "\"\\u00e9\"", "[1,2]", "{\"a\":[true,null]}", "1.5e+3", "\"a\\\"b\""};
        Random random = new Random(35);
        for (int k = 0; k < 50_000; k++) {
            StringBuilder b = new StringBuilder(seeds[random.nextInt(seeds.length)]);
            for (int edits = random.nextInt(4); edits >= 0; edits--) {
                int at = random.nextInt(b.length() + 1);
                switch (random.nextInt(3)) {
                    case 0 -> b.insert(at, alphabet.charAt(random.nextInt(alphabet.length())));
                    case 1 -> { if (at < b.length()) b.deleteCharAt(at); }
                    default -> { if (at < b.length()) b.setCharAt(at, alphabet.charAt(random.nextInt(alphabet.length()))); }
                }
            }
            String text = b.toString();
            try {
                JsonParser.parse(text);
            } catch (TeeError expected) {
                // invalid JSON
            } catch (RuntimeException | StackOverflowError e) {
                throw new AssertionError("'" + text + "' threw " + e, e);
            }
        }
    }
}
