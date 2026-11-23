package com.carddemo.batch.cbtrn02c;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.carddemo.batch.cbtrn02c.io.CobolMath;
import com.carddemo.batch.cbtrn02c.io.Records;
import com.carddemo.batch.cbtrn02c.io.ZonedDecimal;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ZonedDecimalTest {

    @ParameterizedTest
    @CsvSource({
        "0000005047G, 504.77", "0000009190}, -919.00", "0000000567P, -56.77",
        "0000000000{, 0.00", "00000000123, 1.23"
    })
    void decodesOverpunchedSignedFields(String raw, String expected) {
        byte[] b = raw.getBytes(Records.CHARSET);
        assertEquals(new BigDecimal(expected), ZonedDecimal.decode(b, 0, b.length, 2, true));
    }

    @ParameterizedTest
    @CsvSource({"504.77, 0000005047G", "-919.00, 0000009190}", "0, 0000000000{", "-0.01, 0000000000J"})
    void encodesOverpunchedSignedFields(String value, String expected) {
        byte[] b = new byte[11];
        ZonedDecimal.encode(new BigDecimal(value), b, 0, 11, 2, true);
        assertEquals(expected, new String(b, Records.CHARSET));
    }

    @Test
    void truncatesLikeCobolWithoutSizeError() {
        assertEquals(new BigDecimal("10.00"), CobolMath.truncate(new BigDecimal("1000000010.00"), 9, 2));
        assertEquals(new BigDecimal("-10.00"), CobolMath.truncate(new BigDecimal("-1000000010.00"), 9, 2));
        assertEquals(new BigDecimal("1.23"), CobolMath.truncate(new BigDecimal("1.239"), 9, 2));
    }
}
