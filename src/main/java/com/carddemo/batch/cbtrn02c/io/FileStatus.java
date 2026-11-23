package com.carddemo.batch.cbtrn02c.io;

/** The subset of COBOL file status codes the program reacts to. */
public final class FileStatus {
    public static final String OK = "00";
    public static final String EOF = "10";
    public static final String DUPLICATE_KEY = "22";
    public static final String NOT_FOUND = "23";
    public static final String FILE_NOT_FOUND = "35";
    public static final String IO_ERROR = "30";

    private FileStatus() {
    }
}
