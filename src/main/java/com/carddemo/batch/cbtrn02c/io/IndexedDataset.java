package com.carddemo.batch.cbtrn02c.io;

import java.util.Optional;

/** A VSAM KSDS opened for RANDOM access, reduced to the operations CBTRN02C uses. */
public interface IndexedDataset {

    int recordLength();

    /** READ ... INVALID KEY: empty when the key does not exist (status 23). */
    Optional<byte[]> read(String key);

    /** WRITE: returns the COBOL file status ("00", or "22" for a duplicate key). */
    String write(byte[] record);

    /** REWRITE: returns the COBOL file status ("00", or "23" when the key does not exist). */
    String rewrite(byte[] record);
}
