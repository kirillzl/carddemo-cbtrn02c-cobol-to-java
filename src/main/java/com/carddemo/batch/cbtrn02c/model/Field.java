package com.carddemo.batch.cbtrn02c.model;

import com.carddemo.batch.cbtrn02c.io.Records;
import com.carddemo.batch.cbtrn02c.io.ZonedDecimal;
import java.math.BigDecimal;

/** One elementary item of a copybook: offset/length within the record plus its PICTURE class. */
public record Field(String name, int offset, int length, Kind kind, int scale) {

    public enum Kind { ALPHANUMERIC, UNSIGNED_DISPLAY, SIGNED_DISPLAY }

    public static Field x(String name, int offset, int length) {
        return new Field(name, offset, length, Kind.ALPHANUMERIC, 0);
    }

    public static Field n(String name, int offset, int length) {
        return new Field(name, offset, length, Kind.UNSIGNED_DISPLAY, 0);
    }

    public static Field s(String name, int offset, int intDigits, int scale) {
        return new Field(name, offset, intDigits + scale, Kind.SIGNED_DISPLAY, scale);
    }

    public int end() {
        return offset + length;
    }

    public int intDigits() {
        return length - scale;
    }

    public String text(byte[] rec) {
        return Records.text(rec, offset, length);
    }

    public void putText(byte[] rec, String value) {
        Records.putText(rec, offset, length, value);
    }

    public BigDecimal decimal(byte[] rec) {
        return ZonedDecimal.decode(rec, offset, length, scale, kind == Kind.SIGNED_DISPLAY);
    }

    public void putDecimal(byte[] rec, BigDecimal value) {
        ZonedDecimal.encode(value, rec, offset, length, scale, kind == Kind.SIGNED_DISPLAY);
    }

    /** MOVE between two items with identical PICTURE: a straight byte copy. */
    public void copy(byte[] from, Field target, byte[] to) {
        if (target.length != length) {
            throw new IllegalArgumentException(name + " -> " + target.name + " length mismatch");
        }
        System.arraycopy(from, offset, to, target.offset, length);
    }
}
