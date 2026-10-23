package com.flatide.propertee2.value;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The shown digits of a number and rounding them (spec v0.30.0; 145_round_digits, 148_format). */
class DecimalDigitsTest {

    private static String digits(double d) {
        DecimalDigits dd = DecimalDigits.of(d);
        return (dd.negative() ? "-" : "") + "0." + dd.digits() + "e" + dd.exponent();
    }

    @Test void shownDigits() {
        assertEquals("0.12345e3", digits(123.45));
        assertEquals("-0.29e0", digits(-0.29));                // not 0.28999…
        assertEquals("0.1e-3", digits(0.0001));
        assertEquals("0.1e22", digits(1e21));
        assertEquals("0.30000000000000004e0", digits(0.1 + 0.2));
        assertEquals("0.e0", digits(-0.0));                    // zero: no digits, no sign
    }

    @Test void roundsInEachDirection() {
        DecimalDigits.Mode[] modes = DecimalDigits.Mode.values();   // HALF_UP, FLOOR, CEILING, DOWN
        double[] positive = { 3.5, 3.4, 3.5, 3.4 };
        double[] negative = { -3.5, -3.5, -3.4, -3.4 };
        for (int i = 0; i < modes.length; i++) {
            assertEquals(positive[i], DecimalDigits.of(3.48).round(1, modes[i]).toDouble(), modes[i].name());
            assertEquals(negative[i], DecimalDigits.of(-3.48).round(1, modes[i]).toDouble(), modes[i].name());
        }
    }

    @Test void placesBeyondTheDigitsAndUnitsAboveTheNumber() {
        DecimalDigits d = DecimalDigits.of(3.48);
        assertEquals(d, d.round(2, DecimalDigits.Mode.FLOOR));
        assertEquals(d, d.round(400, DecimalDigits.Mode.FLOOR));
        assertEquals(0.0, d.round(-5, DecimalDigits.Mode.HALF_UP).toDouble());
        assertEquals(100000.0, d.round(-5, DecimalDigits.Mode.CEILING).toDouble());
        assertEquals(Double.POSITIVE_INFINITY, d.round(-1e9, DecimalDigits.Mode.CEILING).toDouble());
        assertEquals(10.0, DecimalDigits.of(9.96).round(1, DecimalDigits.Mode.HALF_UP).toDouble());   // carry
    }

    @Test void fixedText() {
        assertEquals("3.40", DecimalDigits.of(3.4).round(2, DecimalDigits.Mode.HALF_UP).fixed(2));
        assertEquals("0.07", DecimalDigits.of(0.07).fixed(2));
        assertEquals("1000000000000000000000.00", DecimalDigits.of(1e21).fixed(2));
        assertEquals("0.000", DecimalDigits.of(0.0004).round(3, DecimalDigits.Mode.HALF_UP).fixed(3));
    }

    @Test void scaledMovesOnlyThePoint() {                     // TEXT's % (spec v0.31.0)
        assertEquals(DecimalDigits.of(14.5), DecimalDigits.of(0.145).scaled(2));   // 0.145 * 100 is 14.499999999999998
        assertEquals(DecimalDigits.of(-0.0), DecimalDigits.of(0.0).scaled(2));
    }
}
