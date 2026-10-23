package com.flatide.propertee2.value;

/**
 * A finite number as the decimal digits {@code PRINT} shows for it: the shortest round-trip digits
 * of ECMA-262 {@code Number::toString} (spec v0.14.0), read as {@code ±0.digits × 10^exponent}.
 * {@code digits} has no leading or trailing zeros; zero has no digits and no sign.
 *
 * <p>{@code ROUND}/{@code FLOOR}/{@code CEIL}/{@code TRUNC} with a digits argument (spec v0.30.0)
 * and the number patterns of {@code TEXT} and {@code FORMAT} (spec v0.31.0) round these digits,
 * not the binary value. So {@code FLOOR(0.29, 2)} is 0.29 although the double nearest 0.29 is
 * 0.28999…, and the two runtimes agree because they show the same digits.
 */
public record DecimalDigits(boolean negative, String digits, int exponent) {

    /** Where the dropped digits send the kept ones. */
    public enum Mode {
        /** {@code ROUND} and number patterns: to the nearest, a tie away from zero. */
        HALF_UP,
        /** {@code FLOOR}: toward −∞. */
        FLOOR,
        /** {@code CEIL}: toward +∞. */
        CEILING,
        /** {@code TRUNC}: toward zero. */
        DOWN
    }

    private static final DecimalDigits ZERO = new DecimalDigits(false, "", 0);

    /** The displayed digits of a finite {@code d}; −0 is zero. */
    public static DecimalDigits of(double d) {
        if (d == 0.0) return ZERO;
        // Double.toString gives the shortest round-tripping digits (on the reference's JDK 25) as
        // a mantissa with a point and an optional exponent: "6.0E9", "0.001", "1.50000005E7".
        String js = Double.toString(Math.abs(d));
        int exp = 0;
        int ei = js.indexOf('E');
        if (ei >= 0) { exp = Integer.parseInt(js.substring(ei + 1)); js = js.substring(0, ei); }
        int dot = js.indexOf('.');
        String all = js.substring(0, dot) + js.substring(dot + 1);
        int pointExp = exp - (js.length() - dot - 1);   // value = all × 10^pointExp
        int start = 0;
        while (start < all.length() - 1 && all.charAt(start) == '0') start++;   // leading zeros: value-neutral
        int end = all.length();
        while (end - start > 1 && all.charAt(end - 1) == '0') { end--; pointExp++; } // trailing zeros: bump exponent
        String s = all.substring(start, end);
        return new DecimalDigits(d < 0, s, pointExp + s.length());
    }

    public boolean isZero() { return digits.isEmpty(); }

    /** This number times 10^{@code power}, exactly: only the point moves ({@code TEXT}'s {@code %}). */
    public DecimalDigits scaled(int power) {
        return digits.isEmpty() ? this : new DecimalDigits(negative, digits, exponent + power);
    }

    /**
     * Rounded to {@code places} decimal places in the given direction; a negative {@code places}
     * rounds to tens, hundreds, …. Places the digits do not reach change nothing; a unit above the
     * number leaves zero or one unit.
     */
    public DecimalDigits round(double places, Mode mode) {
        int k = digits.length();
        if (k == 0 || places >= k - exponent) return this;   // nothing to drop; covers any huge places
        // Below this, places is a whole number under 400. A unit above 1e400 exceeds every double,
        // so clamping changes no result: zero stays zero and one unit still overflows to Infinity.
        int p = (int) Math.max(places, -400);
        int keep = exponent + p;                             // leading digits at or above the unit 10^-p
        boolean up = switch (mode) {                         // a nonzero digit is always dropped
            case HALF_UP -> keep >= 0 && digits.charAt(keep) >= '5';
            case DOWN -> false;
            case FLOOR -> negative;
            case CEILING -> !negative;
        };
        if (keep <= 0) return up ? new DecimalDigits(negative, "1", 1 - p) : ZERO;
        char[] kept = digits.substring(0, keep).toCharArray();
        int exp = exponent;
        String carried = null;
        if (up) {
            int i = kept.length - 1;
            while (i >= 0 && kept[i] == '9') kept[i--] = '0';
            if (i >= 0) kept[i]++;
            else { carried = "1" + new String(kept); exp++; }   // 0.99… → 1.0: one more digit
        }
        String s = carried != null ? carried : new String(kept);
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') end--;
        return new DecimalDigits(negative, s.substring(0, end), exp);
    }

    /** The nearest double; a value beyond the double range is ±Infinity, and zero is +0. */
    public double toDouble() {
        if (digits.isEmpty()) return 0.0;
        double v = Double.parseDouble("0." + digits + "E" + exponent);
        return v == 0.0 ? 0.0 : negative ? -v : v;
    }

    /**
     * The unsigned plain text with exactly {@code places} decimals and no exponent —
     * {@code 1234.50}, {@code 0.07}, {@code 1000000000000000000000.00}. Round to {@code places}
     * first; digits below them are not written.
     */
    public String fixed(int places) {
        int k = digits.length();
        StringBuilder sb = new StringBuilder();
        if (exponent <= 0) sb.append('0');
        else for (int i = 0; i < exponent; i++) sb.append(i < k ? digits.charAt(i) : '0');
        if (places > 0) {
            sb.append('.');
            for (int i = 0; i < places; i++) {
                int at = exponent + i;
                sb.append(at >= 0 && at < k ? digits.charAt(at) : '0');
            }
        }
        return sb.toString();
    }
}
