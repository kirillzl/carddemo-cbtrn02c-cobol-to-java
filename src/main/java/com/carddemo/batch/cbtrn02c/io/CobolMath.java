package com.carddemo.batch.cbtrn02c.io;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** COBOL fixed-point storage semantics for PIC S9(i)V9(s) receiving fields. */
public final class CobolMath {

    private CobolMath() {
    }

    /** Value as stored in a PIC S9(intDigits)V9(scale) field without ON SIZE ERROR / ROUNDED. */
    public static BigDecimal truncate(BigDecimal value, int intDigits, int scale) {
        BigDecimal v = value.setScale(scale, RoundingMode.DOWN);
        BigDecimal modulus = BigDecimal.TEN.pow(intDigits);
        BigDecimal r = v.remainder(modulus);
        if (r.signum() == 0) {
            return BigDecimal.ZERO.setScale(scale);
        }
        return r.setScale(scale);
    }

    /** True if {@code value} does not fit a PIC S9(intDigits)V9(scale) field (a COBOL size error). */
    public static boolean overflows(BigDecimal value, int intDigits, int scale) {
        return value.setScale(scale, RoundingMode.DOWN).abs().compareTo(BigDecimal.TEN.pow(intDigits)) >= 0;
    }
}
