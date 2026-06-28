package com.flatide.propertee2.interp;

import com.flatide.propertee2.value.TeeError;
import com.flatide.propertee2.value.Values;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Range-array construction ({@code [start..end, step]}, LANGUAGE.md §Range Arrays).
 *
 * <ul>
 *   <li>Bounds and step must be numbers; step must be positive; direction is inferred.</li>
 *   <li>All-integer ranges iterate in {@code long} (no int overflow — 77_range_int_overflow)
 *       and yield {@code Integer} elements.</li>
 *   <li>Any fractional bound/step iterates in {@link BigDecimal} so float noise does not drop
 *       the endpoint ({@code [0.0..0.3, 0.1]} → {@code [0, 0.1, 0.2, 0.3]} — 62) nor over-include
 *       a tiny one (76_range_tiny_float_bound).</li>
 * </ul>
 */
final class Ranges {
    private Ranges() {}

    static List<Object> build(Object startV, Object endV, Object stepV, int line, int col) {
        if (!Values.isNumber(startV) || !Values.isNumber(endV)) {
            throw new TeeError("Range bounds must be numbers", line, col);
        }
        boolean allInt = startV instanceof Integer && endV instanceof Integer;
        double start = Values.toDouble(startV);
        double end = Values.toDouble(endV);

        double step;
        if (stepV == null) {
            step = 1;
        } else {
            if (!Values.isNumber(stepV)) throw new TeeError("Range step must be a number", line, col);
            step = Values.toDouble(stepV);
            if (step <= 0) throw new TeeError("Range step must be positive", line, col);
            if (!(stepV instanceof Integer)) allInt = false;
        }

        boolean ascending = start <= end;
        List<Object> out = new ArrayList<>();
        if (allInt) {
            long s = (long) start, e = (long) end, st = (long) step;
            if (ascending) for (long v = s; v <= e; v += st) out.add((int) v);
            else           for (long v = s; v >= e; v -= st) out.add((int) v);
        } else {
            BigDecimal s = BigDecimal.valueOf(start), e = BigDecimal.valueOf(end), st = BigDecimal.valueOf(step);
            if (ascending) for (BigDecimal v = s; v.compareTo(e) <= 0; v = v.add(st)) out.add(v.doubleValue());
            else           for (BigDecimal v = s; v.compareTo(e) >= 0; v = v.subtract(st)) out.add(v.doubleValue());
        }
        return out;
    }
}
