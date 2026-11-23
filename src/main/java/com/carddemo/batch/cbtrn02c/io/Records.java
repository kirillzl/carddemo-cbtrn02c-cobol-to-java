package com.carddemo.batch.cbtrn02c.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Byte-level helpers for fixed-length records. ISO-8859-1 keeps a 1:1 byte/char mapping. */
public final class Records {

    public static final Charset CHARSET = StandardCharsets.ISO_8859_1;
    public static final byte SPACE = ' ';

    private Records() {
    }

    public static byte[] spaces(int len) {
        byte[] b = new byte[len];
        Arrays.fill(b, SPACE);
        return b;
    }

    public static String text(byte[] buf, int off, int len) {
        return new String(buf, off, len, CHARSET);
    }

    /** MOVE alphanumeric: left-justify, pad with spaces, truncate on the right. */
    public static void putText(byte[] buf, int off, int len, String value) {
        byte[] src = value.getBytes(CHARSET);
        for (int i = 0; i < len; i++) {
            buf[off + i] = i < src.length ? src[i] : SPACE;
        }
    }

    /** Alphanumeric comparison using the native (ASCII) collating sequence. */
    public static int compare(byte[] a, int aOff, byte[] b, int bOff, int len) {
        for (int i = 0; i < len; i++) {
            int d = (a[aOff + i] & 0xFF) - (b[bOff + i] & 0xFF);
            if (d != 0) {
                return d;
            }
        }
        return 0;
    }
}
