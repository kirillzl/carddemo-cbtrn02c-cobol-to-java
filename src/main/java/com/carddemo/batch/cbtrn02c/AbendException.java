package com.carddemo.batch.cbtrn02c;

/** Equivalent of CALL 'CEE3ABD' (user abend); the run stops, files keep what was written so far. */
public final class AbendException extends RuntimeException {
    private final int abendCode;

    public AbendException(int abendCode) {
        super("U" + String.format("%04d", abendCode));
        this.abendCode = abendCode;
    }

    public int abendCode() {
        return abendCode;
    }
}
