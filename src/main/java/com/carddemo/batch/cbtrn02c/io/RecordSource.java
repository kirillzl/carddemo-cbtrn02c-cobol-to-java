package com.carddemo.batch.cbtrn02c.io;

/** A sequential input file (DALYTRAN). */
public interface RecordSource {

    /** Result of one READ: COBOL status plus the record when status is "00". */
    record ReadResult(String status, byte[] record) {
    }

    ReadResult read();
}
