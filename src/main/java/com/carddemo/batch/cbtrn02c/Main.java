package com.carddemo.batch.cbtrn02c;

import com.carddemo.batch.cbtrn02c.io.FlatIndexedFile;
import com.carddemo.batch.cbtrn02c.io.FlatSequentialFiles;
import com.carddemo.batch.cbtrn02c.io.RecordSource;
import com.carddemo.batch.cbtrn02c.model.Layouts.Account;
import com.carddemo.batch.cbtrn02c.model.Layouts.Card;
import com.carddemo.batch.cbtrn02c.model.Layouts.Reject;
import com.carddemo.batch.cbtrn02c.model.Layouts.Tran;
import com.carddemo.batch.cbtrn02c.model.Layouts.TranCatBal;
import com.carddemo.batch.cbtrn02c.model.Layouts.Xref;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Command-line job step equivalent of POSTTRAN.jcl STEP15. Indexed files are exchanged as flat
 * fixed-length unloads (key order); outputs are written even when the run abends, mirroring VSAM
 * where every WRITE/REWRITE is already on disk at abend time.
 *
 * <pre>
 * java -jar cbtrn02c.jar --dalytran=F --xref=F --acct-in=F --acct-out=F --tcatbal-in=F --tcatbal-out=F
 *      --tranfile-out=F --dalyrejs=F [--tranfile-in=F] [--card=F]
 *      [--run-timestamp=2022-06-11T01:00:00] [--fix=NONE|ALL|FIX1,FIX2]
 * </pre>
 * Exit code: 0 OK, 4 rejects present, 999 (231 as seen by the shell) abend, 16 usage error.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out));
    }

    public static int run(String[] args, PrintStream out) {
        Map<String, String> a = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (!arg.startsWith("--") || eq < 0) {
                System.err.println("bad argument: " + arg);
                return 16;
            }
            a.put(arg.substring(2, eq), arg.substring(eq + 1));
        }
        for (String req : new String[] {"dalytran", "xref", "acct-in", "tcatbal-in", "tranfile-out", "dalyrejs"}) {
            if (!a.containsKey(req)) {
                System.err.println("missing --" + req + "=");
                return 16;
            }
        }
        PostingOptions options = PostingOptions.parse(a.get("fix"));
        Supplier<LocalDateTime> clock = a.containsKey("run-timestamp")
                ? fixed(LocalDateTime.parse(a.get("run-timestamp")))
                : LocalDateTime::now;
        Path acctOut = Path.of(a.getOrDefault("acct-out", a.get("acct-in")));
        Path tcatOut = Path.of(a.getOrDefault("tcatbal-out", a.get("tcatbal-in")));
        Path tranOut = Path.of(a.get("tranfile-out"));
        Path rejOut = Path.of(a.get("dalyrejs"));

        FlatIndexedFile tranfile = new FlatIndexedFile(Tran.LENGTH, Tran.ID.offset(), Tran.ID.length());
        FlatSequentialFiles.Buffer rejects = new FlatSequentialFiles.Buffer(Reject.LENGTH);
        FlatIndexedFile account = null;
        FlatIndexedFile tcatbal = null;
        try {
            // Open order and messages follow 0000..0500 of the COBOL program.
            Path dalytranPath = Path.of(a.get("dalytran"));
            if (!Files.exists(dalytranPath)) {
                return openFailure(out, "ERROR OPENING DALYTRAN", tranOut, rejOut, null, null);
            }
            RecordSource dalytran = FlatSequentialFiles.reader(dalytranPath, Tran.LENGTH);
            if (options.has(Fix.TRANSACT_APPEND) && a.containsKey("tranfile-in")) {
                tranfile = FlatIndexedFile.load(Path.of(a.get("tranfile-in")), Tran.LENGTH, Tran.ID.offset(), Tran.ID.length());
            }
            Path xrefPath = Path.of(a.get("xref"));
            if (!Files.exists(xrefPath)) {
                return openFailure(out, "ERROR OPENING CROSS REF FILE", tranOut, rejOut, tranfile, null);
            }
            FlatIndexedFile xref = FlatIndexedFile.load(xrefPath, Xref.LENGTH, 0, Xref.CARD_NUM.length());
            Path acctPath = Path.of(a.get("acct-in"));
            if (!Files.exists(acctPath)) {
                return openFailure(out, "ERROR OPENING ACCOUNT MASTER FILE", tranOut, rejOut, tranfile, rejects);
            }
            account = FlatIndexedFile.load(acctPath, Account.LENGTH, 0, Account.ID.length());
            Path tcatPath = Path.of(a.get("tcatbal-in"));
            if (!Files.exists(tcatPath)) {
                account.save(acctOut);
                return openFailure(out, "ERROR OPENING TRANSACTION BALANCE FILE", tranOut, rejOut, tranfile, rejects);
            }
            tcatbal = FlatIndexedFile.load(tcatPath, TranCatBal.LENGTH, 0, TranCatBal.KEY_LENGTH);
            FlatIndexedFile card = a.containsKey("card")
                    ? FlatIndexedFile.load(Path.of(a.get("card")), Card.LENGTH, 0, Card.NUM.length())
                    : null;

            Cbtrn02c program = new Cbtrn02c(
                    new Cbtrn02c.Datasets(dalytran, tranfile, xref, rejects, account, tcatbal, card),
                    options, clock, out);
            int rc;
            try {
                rc = program.run();
            } catch (AbendException e) {
                out.println(abendMessage(e.abendCode()));
                rc = e.abendCode();
            }
            tranfile.save(tranOut);
            rejects.save(rejOut);
            account.save(acctOut);
            tcatbal.save(tcatOut);
            out.flush();
            return rc;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return 16;
        }
    }

    /** Text displayed by the CEE3ABD stub used in the GnuCOBOL build. */
    static String abendMessage(int code) {
        return String.format("CEE3ABD ABEND U%04d", code);
    }

    private static int openFailure(PrintStream out, String message, Path tranOut, Path rejOut,
                                   FlatIndexedFile tranfile, FlatSequentialFiles.Buffer rejects) throws IOException {
        if (tranfile != null) {
            tranfile.save(tranOut);
        }
        if (rejects != null) {
            rejects.save(rejOut);
        }
        out.println("START OF EXECUTION OF PROGRAM CBTRN02C".equals(message) ? "" : "START OF EXECUTION OF PROGRAM CBTRN02C");
        out.println(message);
        out.println("FILE STATUS IS: NNNN0035");
        out.println("ABENDING PROGRAM");
        out.println(abendMessage(Cbtrn02c.ABEND_CODE));
        return Cbtrn02c.ABEND_CODE;
    }

    private static Supplier<LocalDateTime> fixed(LocalDateTime t) {
        return () -> t;
    }
}
