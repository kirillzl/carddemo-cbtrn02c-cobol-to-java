package com.carddemo.batch.cbtrn02c;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Replays each testdata case through the Java CLI and compares every output byte for byte with
 * the datasets produced by the original COBOL program under GnuCOBOL (testdata/CASE/expected,
 * captured by scripts/parity.sh). Runs without a COBOL compiler.
 */
class GoldenParityTest {

    private static final Path TESTDATA = Path.of("testdata");

    @TempDir
    Path tmp;

    @ParameterizedTest
    @ValueSource(strings = {"sample", "edge", "edge-dup"})
    void javaOutputEqualsCobolOutput(String name) throws IOException {
        Path in = TESTDATA.resolve(name);
        Path exp = in.resolve("expected");
        Path dal = fixed(in.resolve("dailytran.txt"), 350);
        Path xref = fixed(in.resolve("cardxref.txt"), 50);
        Path acct = fixed(in.resolve("acctdata.txt"), 300);
        Path tcat = fixed(in.resolve("tcatbal.txt"), 50);
        ByteArrayOutputStream sysout = new ByteArrayOutputStream();
        int rc = Main.run(new String[] {
            "--dalytran=" + dal, "--xref=" + xref, "--acct-in=" + acct, "--acct-out=" + tmp.resolve("ACCT"),
            "--tcatbal-in=" + tcat, "--tcatbal-out=" + tmp.resolve("TCATBAL"),
            "--tranfile-out=" + tmp.resolve("TRANFILE"), "--dalyrejs=" + tmp.resolve("DALYREJS"),
            "--run-timestamp=2022-06-11T01:02:03"
        }, new PrintStream(sysout, true, StandardCharsets.ISO_8859_1));

        for (String f : new String[] {"TRANFILE", "DALYREJS", "ACCT", "TCATBAL"}) {
            assertArrayEquals(Files.readAllBytes(exp.resolve(f)), Files.readAllBytes(tmp.resolve(f)), name + "/" + f);
        }
        assertArrayEquals(Files.readAllBytes(exp.resolve("SYSOUT")), sysout.toByteArray(), name + "/SYSOUT");
        assertEquals(Integer.parseInt(Files.readString(exp.resolve("RC")).trim()), rc & 0xFF, name + "/RC");
    }

    /** Same conversion as scripts/to_fixed.py (text lines to fixed-length records). */
    private Path fixed(Path text, int lrecl) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String line : Files.readString(text, StandardCharsets.ISO_8859_1).split("\n")) {
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!l.isEmpty()) {
                out.writeBytes(String.format("%-" + lrecl + "s", l).getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        Path p = tmp.resolve(text.getFileName() + ".dat");
        Files.write(p, out.toByteArray());
        return p;
    }
}
