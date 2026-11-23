package com.carddemo.batch.cbtrn02c.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** RECFM=F sequential files as flat byte streams (no record delimiters). */
public final class FlatSequentialFiles {

    private FlatSequentialFiles() {
    }

    public static RecordSource reader(byte[] content, int recordLength) {
        return new RecordSource() {
            private int pos;

            @Override
            public ReadResult read() {
                if (pos >= content.length) {
                    return new ReadResult(FileStatus.EOF, null);
                }
                if (pos + recordLength > content.length) {
                    // Short final record: GnuCOBOL reports status 04 (record length mismatch).
                    pos = content.length;
                    return new ReadResult("04", null);
                }
                byte[] rec = new byte[recordLength];
                System.arraycopy(content, pos, rec, 0, recordLength);
                pos += recordLength;
                return new ReadResult(FileStatus.OK, rec);
            }
        };
    }

    public static RecordSource reader(Path path, int recordLength) throws IOException {
        return reader(Files.readAllBytes(path), recordLength);
    }

    /** Buffers records in memory; {@link #save(Path)} writes them out. */
    public static final class Buffer implements RecordSink {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final int recordLength;
        private int count;

        public Buffer(int recordLength) {
            this.recordLength = recordLength;
        }

        @Override
        public String write(byte[] record) {
            if (record.length != recordLength) {
                throw new IllegalArgumentException("record length " + record.length + " != " + recordLength);
            }
            out.writeBytes(record);
            count++;
            return FileStatus.OK;
        }

        public int count() {
            return count;
        }

        public byte[] bytes() {
            return out.toByteArray();
        }

        public void save(Path path) throws IOException {
            Files.write(path, out.toByteArray());
        }
    }
}
