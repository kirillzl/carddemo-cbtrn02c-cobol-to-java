package com.carddemo.batch.cbtrn02c.io;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * COBOL USAGE DISPLAY numeric codec (PIC 9 / PIC S9 with trailing overpunched sign) for the
 * ASCII-converted CardDemo files. The sign convention is the EBCDIC one carried over into ASCII
 * (GnuCOBOL {@code -fsign=EBCDIC}): '{','A'..'I' = +0..+9 and '}','J'..'R' = -0..-9.
 *
 * <p>Digit positions are decoded from the low nibble of each byte, as IBM zoned decimal does,
 * so the codec never throws on dirty data; non-numeric content is the caller's concern.
 */
public final class ZonedDecimal {

    private static final byte[] POS = "{ABCDEFGHI".getBytes(Records.CHARSET);
    private static final byte[] NEG = "}JKLMNOPQR".getBytes(Records.CHARSET);

    private ZonedDecimal() {
    }

    public static BigDecimal decode(byte[] buf, int off, int len, int scale, boolean signed) {
        BigInteger unscaled = BigInteger.ZERO;
        boolean negative = false;
        for (int i = 0; i < len; i++) {
            int c = buf[off + i] & 0xFF;
            int digit;
            if (i == len - 1 && signed) {
                int p = indexOf(POS, c);
                int n = indexOf(NEG, c);
                if (p >= 0) {
                    digit = p;
                } else if (n >= 0) {
                    digit = n;
                    negative = true;
                } else {
                    digit = c & 0x0F;
                }
            } else {
                digit = c & 0x0F;
            }
            if (digit > 9) {
                digit = digit % 10;
            }
            unscaled = unscaled.multiply(BigInteger.TEN).add(BigInteger.valueOf(digit));
        }
        if (negative) {
            unscaled = unscaled.negate();
        }
        return new BigDecimal(unscaled, scale);
    }

    /**
     * Stores {@code value} the way a COBOL MOVE/ADD without ON SIZE ERROR does: digits beyond
     * the picture are silently dropped (high-order truncation, low-order truncation of decimals).
     */
    public static void encode(BigDecimal value, byte[] buf, int off, int len, int scale, boolean signed) {
        BigDecimal stored = CobolMath.truncate(value, len - scale, scale);
        BigInteger unscaled = stored.unscaledValue();
        boolean negative = unscaled.signum() < 0 && signed;
        String digits = unscaled.abs().toString();
        StringBuilder sb = new StringBuilder(len);
        for (int i = digits.length(); i < len; i++) {
            sb.append('0');
        }
        sb.append(digits);
        byte[] out = sb.toString().getBytes(Records.CHARSET);
        if (signed) {
            int last = out[len - 1] - '0';
            out[len - 1] = negative ? NEG[last] : POS[last];
        }
        System.arraycopy(out, 0, buf, off, len);
    }

    private static int indexOf(byte[] table, int c) {
        for (int i = 0; i < table.length; i++) {
            if ((table[i] & 0xFF) == c) {
                return i;
            }
        }
        return -1;
    }
}
