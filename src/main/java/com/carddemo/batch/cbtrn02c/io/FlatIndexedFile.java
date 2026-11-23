package com.carddemo.batch.cbtrn02c.io;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * In-memory KSDS backed by a flat file of fixed-length records (the same layout an IDCAMS REPRO
 * unload produces). Records are kept in key order; ISO-8859-1 strings sort in unsigned byte order,
 * which matches VSAM key sequence for these ASCII keys.
 */
public final class FlatIndexedFile implements IndexedDataset {

    private final int recordLength;
    private final int keyOffset;
    private final int keyLength;
    private final TreeMap<String, byte[]> records = new TreeMap<>();

    public FlatIndexedFile(int recordLength, int keyOffset, int keyLength) {
        this.recordLength = recordLength;
        this.keyOffset = keyOffset;
        this.keyLength = keyLength;
    }

    public static FlatIndexedFile load(Path path, int recordLength, int keyOffset, int keyLength)
            throws IOException {
        FlatIndexedFile f = new FlatIndexedFile(recordLength, keyOffset, keyLength);
        if (path == null || !Files.exists(path)) {
            return f;
        }
        byte[] all = Files.readAllBytes(path);
        if (all.length % recordLength != 0) {
            throw new IOException(path + ": length " + all.length + " is not a multiple of " + recordLength);
        }
        for (int off = 0; off < all.length; off += recordLength) {
            byte[] rec = new byte[recordLength];
            System.arraycopy(all, off, rec, 0, recordLength);
            if (f.write(rec).equals(FileStatus.DUPLICATE_KEY)) {
                throw new IOException(path + ": duplicate key " + f.keyOf(rec));
            }
        }
        return f;
    }

    public void save(Path path) throws IOException {
        try (OutputStream out = Files.newOutputStream(path)) {
            for (byte[] rec : records.values()) {
                out.write(rec);
            }
        }
    }

    public Map<String, byte[]> view() {
        return java.util.Collections.unmodifiableMap(records);
    }

    public String keyOf(byte[] record) {
        return Records.text(record, keyOffset, keyLength);
    }

    @Override
    public int recordLength() {
        return recordLength;
    }

    @Override
    public Optional<byte[]> read(String key) {
        byte[] rec = records.get(key);
        return rec == null ? Optional.empty() : Optional.of(rec.clone());
    }

    @Override
    public String write(byte[] record) {
        checkLength(record);
        String key = keyOf(record);
        if (records.containsKey(key)) {
            return FileStatus.DUPLICATE_KEY;
        }
        records.put(key, record.clone());
        return FileStatus.OK;
    }

    @Override
    public String rewrite(byte[] record) {
        checkLength(record);
        String key = keyOf(record);
        if (!records.containsKey(key)) {
            return FileStatus.NOT_FOUND;
        }
        records.put(key, record.clone());
        return FileStatus.OK;
    }

    private void checkLength(byte[] record) {
        if (record.length != recordLength) {
            throw new IllegalArgumentException("record length " + record.length + " != " + recordLength);
        }
    }
}
