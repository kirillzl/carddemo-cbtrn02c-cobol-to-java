package com.carddemo.batch.cbtrn02c;

import com.carddemo.batch.cbtrn02c.io.CobolMath;
import com.carddemo.batch.cbtrn02c.io.FileStatus;
import com.carddemo.batch.cbtrn02c.io.IndexedDataset;
import com.carddemo.batch.cbtrn02c.io.RecordSink;
import com.carddemo.batch.cbtrn02c.io.RecordSource;
import com.carddemo.batch.cbtrn02c.io.Records;
import com.carddemo.batch.cbtrn02c.model.Field;
import com.carddemo.batch.cbtrn02c.model.Layouts.Account;
import com.carddemo.batch.cbtrn02c.model.Layouts.Card;
import com.carddemo.batch.cbtrn02c.model.Layouts.Reject;
import com.carddemo.batch.cbtrn02c.model.Layouts.Tran;
import com.carddemo.batch.cbtrn02c.model.Layouts.TranCatBal;
import com.carddemo.batch.cbtrn02c.model.Layouts.Xref;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Java port of CBTRN02C (post the records from the daily transaction file). Paragraph names of
 * the COBOL source are kept as method names so the two can be read side by side.
 *
 * <p>Working-storage records are kept as raw byte arrays so FILLER bytes and stale contents behave
 * exactly as in the COBOL program.
 */
public final class Cbtrn02c {

    /** The DD names of the job step. {@code card} is optional and only used by {@link Fix#STATUS_CHECKS}. */
    public record Datasets(RecordSource dalytran, IndexedDataset tranfile, IndexedDataset xref,
                           RecordSink dalyrejs, IndexedDataset account, IndexedDataset tcatbal,
                           IndexedDataset card) {
        public Datasets {
            Objects.requireNonNull(dalytran);
            Objects.requireNonNull(tranfile);
            Objects.requireNonNull(xref);
            Objects.requireNonNull(dalyrejs);
            Objects.requireNonNull(account);
            Objects.requireNonNull(tcatbal);
        }
    }

    public static final int ABEND_CODE = 999;

    private final Datasets files;
    private final PostingOptions options;
    private final Supplier<LocalDateTime> clock;
    private final PrintStream sysout;

    private final byte[] dalytranRecord = Records.spaces(Tran.LENGTH);
    private final byte[] tranRecord = Records.spaces(Tran.LENGTH);
    private final byte[] cardXrefRecord = Records.spaces(Xref.LENGTH);
    private final byte[] accountRecord = Records.spaces(Account.LENGTH);
    private final byte[] tranCatBalRecord = Records.spaces(TranCatBal.LENGTH);

    private int validationFailReason;
    private String validationFailReasonDesc = "";
    private long transactionCount;
    private long rejectCount;
    private long postedCount;

    public Cbtrn02c(Datasets files, PostingOptions options, Supplier<LocalDateTime> clock, PrintStream sysout) {
        this.files = files;
        this.options = options;
        this.clock = clock;
        this.sysout = sysout;
    }

    /**
     * Runs the main loop. Returns RETURN-CODE (4 if any reject, else 0). Throws
     * {@link AbendException} where the COBOL program calls CEE3ABD; datasets keep every update
     * made up to that point, as VSAM does.
     */
    public int run() {
        sysout.println("START OF EXECUTION OF PROGRAM CBTRN02C");
        while (dalytranGetNext()) {
            transactionCount++;
            validationFailReason = 0;
            validationFailReasonDesc = "";
            validateTran();
            if (validationFailReason == 0) {
                postTransaction();
            } else {
                rejectCount++;
                writeRejectRec();
            }
        }
        sysout.println("TRANSACTIONS PROCESSED :" + pic9(transactionCount, 9));
        sysout.println("TRANSACTIONS REJECTED  :" + pic9(rejectCount, 9));
        int returnCode = rejectCount > 0 ? 4 : 0;
        sysout.println("END OF EXECUTION OF PROGRAM CBTRN02C");
        return returnCode;
    }

    public long transactionCount() {
        return transactionCount;
    }

    public long rejectCount() {
        return rejectCount;
    }

    public long postedCount() {
        return postedCount;
    }

    /** Last WS-VALIDATION-FAIL-REASON (visible for tests of the lost 109 code). */
    public int lastValidationFailReason() {
        return validationFailReason;
    }

    // ---------------------------------------------------------------- 1000
    private boolean dalytranGetNext() {
        RecordSource.ReadResult r = files.dalytran().read();
        if (FileStatus.OK.equals(r.status())) {
            System.arraycopy(r.record(), 0, dalytranRecord, 0, Tran.LENGTH);
            return true;
        }
        if (FileStatus.EOF.equals(r.status())) {
            return false;
        }
        sysout.println("ERROR READING DALYTRAN FILE");
        throw abend(r.status());
    }

    // ---------------------------------------------------------------- 1500
    private void validateTran() {
        lookupXref();
        if (validationFailReason == 0) {
            lookupAcct();
        }
        if (validationFailReason == 0) {
            additionalValidations();
        }
    }

    private void lookupXref() {
        Optional<byte[]> xref = files.xref().read(Tran.CARD_NUM.text(dalytranRecord));
        if (xref.isEmpty()) {
            fail(RejectReason.INVALID_CARD);
        } else {
            System.arraycopy(xref.get(), 0, cardXrefRecord, 0, Xref.LENGTH);
        }
    }

    private void lookupAcct() {
        Optional<byte[]> acct = files.account().read(Xref.ACCT_ID.text(cardXrefRecord));
        if (acct.isEmpty()) {
            fail(RejectReason.ACCOUNT_NOT_FOUND);
            return;
        }
        System.arraycopy(acct.get(), 0, accountRecord, 0, Account.LENGTH);
        BigDecimal amount = Tran.AMT.decimal(dalytranRecord);
        if (isOverLimit(amount)) {
            fail(RejectReason.OVERLIMIT);
        }
        // Original: this test runs even when 102 was set, so 103 overwrites 102.
        if (Records.compare(accountRecord, Account.EXPIRATION_DATE.offset(),
                dalytranRecord, Tran.ORIG_TS.offset(), 10) < 0) {
            fail(RejectReason.ACCOUNT_EXPIRED);
        }
    }

    private boolean isOverLimit(BigDecimal amount) {
        BigDecimal limit = Account.CREDIT_LIMIT.decimal(accountRecord);
        if (options.has(Fix.OVERLIMIT_FORMULA)) {
            BigDecimal projected = Account.CURR_BAL.decimal(accountRecord).add(amount);
            return amount.signum() > 0 && projected.compareTo(limit) > 0;
        }
        BigDecimal tempBal = Account.CURR_CYC_CREDIT.decimal(accountRecord)
                .subtract(Account.CURR_CYC_DEBIT.decimal(accountRecord))
                .add(amount);
        if (!options.has(Fix.SIZE_ERROR)) {
            tempBal = CobolMath.truncate(tempBal, 9, 2); // WS-TEMP-BAL PIC S9(09)V99
        }
        return limit.compareTo(tempBal) < 0;
    }

    /** Checks that only exist behind fix switches; evaluated after the original rules pass. */
    private void additionalValidations() {
        if (options.has(Fix.STATUS_CHECKS)) {
            if (!"Y".equals(Account.ACTIVE_STATUS.text(accountRecord))) {
                fail(RejectReason.ACCOUNT_NOT_ACTIVE);
                return;
            }
            if (files.card() != null) {
                Optional<byte[]> card = files.card().read(Tran.CARD_NUM.text(dalytranRecord));
                if (card.isEmpty() || !"Y".equals(Card.ACTIVE_STATUS.text(card.get()))) {
                    fail(RejectReason.CARD_NOT_ACTIVE);
                    return;
                }
                if (Records.compare(card.get(), Card.EXPIRATION_DATE.offset(),
                        dalytranRecord, Tran.ORIG_TS.offset(), 10) < 0) {
                    fail(RejectReason.CARD_EXPIRED);
                    return;
                }
            }
        }
        if (options.has(Fix.SIZE_ERROR) && wouldOverflow()) {
            fail(RejectReason.AMOUNT_OVERFLOW);
            return;
        }
        if (options.has(Fix.DUPLICATE_ID) && files.tranfile().read(Tran.ID.text(dalytranRecord)).isPresent()) {
            fail(RejectReason.DUPLICATE_TRANSACTION);
        }
    }

    private boolean wouldOverflow() {
        BigDecimal amount = Tran.AMT.decimal(dalytranRecord);
        Optional<byte[]> tcat = files.tcatbal().read(tranCatKey());
        BigDecimal catBal = tcat.map(TranCatBal.BAL::decimal).orElse(BigDecimal.ZERO).add(amount);
        Field cyc = amount.signum() >= 0 ? Account.CURR_CYC_CREDIT : Account.CURR_CYC_DEBIT;
        return overflows(Account.CURR_BAL, Account.CURR_BAL.decimal(accountRecord).add(amount))
                || overflows(cyc, cyc.decimal(accountRecord).add(amount))
                || overflows(TranCatBal.BAL, catBal);
    }

    private static boolean overflows(Field f, BigDecimal v) {
        return CobolMath.overflows(v, f.intDigits(), f.scale());
    }

    private void fail(RejectReason reason) {
        validationFailReason = reason.code();
        validationFailReasonDesc = reason.description();
    }

    // ---------------------------------------------------------------- 2000
    private void postTransaction() {
        for (var field : Tran.POSTED_FIELDS) {
            field.copy(dalytranRecord, field, tranRecord);
        }
        Tran.PROC_TS.putText(tranRecord, db2FormatTimestamp(clock.get()));
        updateTcatbal();
        updateAccountRec();
        writeTransactionFile();
        postedCount++;
    }

    // ---------------------------------------------------------------- 2500
    private void writeRejectRec() {
        byte[] reject = new byte[Reject.LENGTH];
        System.arraycopy(dalytranRecord, 0, reject, 0, Tran.LENGTH);
        Reject.REASON.putDecimal(reject, BigDecimal.valueOf(validationFailReason));
        Reject.REASON_DESC.putText(reject, validationFailReasonDesc);
        String status = files.dalyrejs().write(reject);
        if (!FileStatus.OK.equals(status)) {
            sysout.println("ERROR WRITING TO REJECTS FILE");
            throw abend(status);
        }
    }

    // ---------------------------------------------------------------- 2700
    private String tranCatKey() {
        byte[] key = new byte[TranCatBal.KEY_LENGTH];
        Xref.ACCT_ID.copy(cardXrefRecord, TranCatBal.ACCT_ID, key);
        Tran.TYPE_CD.copy(dalytranRecord, TranCatBal.TYPE_CD, key);
        Tran.CAT_CD.copy(dalytranRecord, TranCatBal.CAT_CD, key);
        return Records.text(key, 0, key.length);
    }

    private void updateTcatbal() {
        String key = tranCatKey();
        Optional<byte[]> existing = files.tcatbal().read(key);
        if (existing.isEmpty()) {
            sysout.println("TCATBAL record not found for key : " + key + ".. Creating.");
            createTcatbalRec();
        } else {
            System.arraycopy(existing.get(), 0, tranCatBalRecord, 0, TranCatBal.LENGTH);
            updateTcatbalRec();
        }
    }

    private void createTcatbalRec() {
        // INITIALIZE TRAN-CAT-BAL-RECORD: named items only, FILLER keeps whatever was there.
        TranCatBal.ACCT_ID.putDecimal(tranCatBalRecord, BigDecimal.ZERO);
        TranCatBal.TYPE_CD.putText(tranCatBalRecord, "");
        TranCatBal.CAT_CD.putDecimal(tranCatBalRecord, BigDecimal.ZERO);
        TranCatBal.BAL.putDecimal(tranCatBalRecord, BigDecimal.ZERO);
        if (options.has(Fix.TCATBAL_FILLER)) {
            TranCatBal.FILLER.putText(tranCatBalRecord, "");
        }
        Xref.ACCT_ID.copy(cardXrefRecord, TranCatBal.ACCT_ID, tranCatBalRecord);
        Tran.TYPE_CD.copy(dalytranRecord, TranCatBal.TYPE_CD, tranCatBalRecord);
        Tran.CAT_CD.copy(dalytranRecord, TranCatBal.CAT_CD, tranCatBalRecord);
        addTo(TranCatBal.BAL, tranCatBalRecord, Tran.AMT.decimal(dalytranRecord));
        String status = files.tcatbal().write(tranCatBalRecord.clone());
        if (!FileStatus.OK.equals(status)) {
            sysout.println("ERROR WRITING TRANSACTION BALANCE FILE");
            throw abend(status);
        }
    }

    private void updateTcatbalRec() {
        addTo(TranCatBal.BAL, tranCatBalRecord, Tran.AMT.decimal(dalytranRecord));
        String status = files.tcatbal().rewrite(tranCatBalRecord.clone());
        if (!FileStatus.OK.equals(status)) {
            sysout.println("ERROR REWRITING TRANSACTION BALANCE FILE");
            throw abend(status);
        }
    }

    // ---------------------------------------------------------------- 2800
    private void updateAccountRec() {
        BigDecimal amount = Tran.AMT.decimal(dalytranRecord);
        addTo(Account.CURR_BAL, accountRecord, amount);
        if (amount.signum() >= 0) {
            addTo(Account.CURR_CYC_CREDIT, accountRecord, amount);
        } else {
            addTo(Account.CURR_CYC_DEBIT, accountRecord, amount);
        }
        String status = files.account().rewrite(accountRecord.clone());
        if (FileStatus.OK.equals(status)) {
            return;
        }
        if (options.has(Fix.REWRITE_FAILURE_ABEND)) {
            sysout.println("ERROR REWRITING ACCOUNT FILE");
            throw abend(status);
        }
        // Original: INVALID KEY sets 109 which nobody reads; any other status is not checked at all.
        if (FileStatus.NOT_FOUND.equals(status)) {
            fail(RejectReason.ACCOUNT_REWRITE_FAILED);
        }
    }

    // ---------------------------------------------------------------- 2900
    private void writeTransactionFile() {
        String status = files.tranfile().write(tranRecord.clone());
        if (!FileStatus.OK.equals(status)) {
            sysout.println("ERROR WRITING TO TRANSACTION FILE");
            throw abend(status);
        }
    }

    // ---------------------------------------------------------------- helpers
    /** ADD amount TO field (no ON SIZE ERROR: high-order digits are lost). */
    private static void addTo(Field field, byte[] rec, BigDecimal amount) {
        field.putDecimal(rec, field.decimal(rec).add(amount));
    }

    /** Z-GET-DB2-FORMAT-TIMESTAMP: YYYY-MM-DD-HH.MM.SS.hh0000 (hh = hundredths). */
    public static String db2FormatTimestamp(LocalDateTime t) {
        return String.format("%04d-%02d-%02d-%02d.%02d.%02d.%02d0000",
                t.getYear(), t.getMonthValue(), t.getDayOfMonth(),
                t.getHour(), t.getMinute(), t.getSecond(), t.getNano() / 10_000_000);
    }

    private AbendException abend(String fileStatus) {
        sysout.println("FILE STATUS IS: NNNN" + displayIoStatus(fileStatus));
        sysout.println("ABENDING PROGRAM");
        return new AbendException(ABEND_CODE);
    }

    /** 9910-DISPLAY-IO-STATUS: 4-character rendering of a 2-byte file status. */
    public static String displayIoStatus(String status) {
        char s1 = status.charAt(0);
        char s2 = status.charAt(1);
        boolean numeric = Character.isDigit(s1) && Character.isDigit(s2);
        if (!numeric || s1 == '9') {
            return s1 + String.format("%03d", (int) s2 & 0xFF);
        }
        return "00" + status;
    }

    private static String pic9(long value, int digits) {
        String s = Long.toString(value % (long) Math.pow(10, digits));
        return "0".repeat(digits - s.length()) + s;
    }
}
