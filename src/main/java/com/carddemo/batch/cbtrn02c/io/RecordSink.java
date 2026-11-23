package com.carddemo.batch.cbtrn02c.io;

/** A sequential output file (DALYREJS). */
public interface RecordSink {
    /** WRITE: returns the COBOL file status. */
    String write(byte[] record);
}
