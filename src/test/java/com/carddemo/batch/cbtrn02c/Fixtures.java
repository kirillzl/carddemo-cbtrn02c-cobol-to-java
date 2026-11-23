package com.carddemo.batch.cbtrn02c;

import com.carddemo.batch.cbtrn02c.io.FlatIndexedFile;
import com.carddemo.batch.cbtrn02c.io.FlatSequentialFiles;
import com.carddemo.batch.cbtrn02c.io.IndexedDataset;
import com.carddemo.batch.cbtrn02c.io.Records;
import com.carddemo.batch.cbtrn02c.model.Layouts.Account;
import com.carddemo.batch.cbtrn02c.model.Layouts.Card;
import com.carddemo.batch.cbtrn02c.model.Layouts.Reject;
import com.carddemo.batch.cbtrn02c.model.Layouts.Tran;
import com.carddemo.batch.cbtrn02c.model.Layouts.TranCatBal;
import com.carddemo.batch.cbtrn02c.model.Layouts.Xref;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Builders for copybook records and a one-call runner for the posting program. */
final class Fixtures {

    static final LocalDateTime RUN_TS = LocalDateTime.of(2022, 6, 11, 1, 2, 3);
    static final String ORIG_TS = "2022-06-10 19:27:53.000000";

    private Fixtures() {
    }

    static byte[] dalytran(String id, String card, String type, String cat, String amount, String origTs) {
        byte[] r = Records.spaces(Tran.LENGTH);
        Tran.ID.putText(r, id);
        Tran.TYPE_CD.putText(r, type);
        Tran.CAT_CD.putText(r, cat);
        Tran.SOURCE.putText(r, "POS TERM");
        Tran.DESC.putText(r, "Test purchase " + id);
        Tran.AMT.putDecimal(r, new BigDecimal(amount));
        Tran.MERCHANT_ID.putText(r, "800000000");
        Tran.MERCHANT_NAME.putText(r, "Merchant");
        Tran.MERCHANT_CITY.putText(r, "City");
        Tran.MERCHANT_ZIP.putText(r, "12345");
        Tran.CARD_NUM.putText(r, card);
        Tran.ORIG_TS.putText(r, origTs);
        return r;
    }

    static byte[] dalytran(String id, String card, String amount) {
        return dalytran(id, card, "01", "0001", amount, ORIG_TS);
    }

    static byte[] xref(String card, long acctId) {
        byte[] r = Records.spaces(Xref.LENGTH);
        Xref.CARD_NUM.putText(r, card);
        Xref.CUST_ID.putDecimal(r, BigDecimal.ONE);
        Xref.ACCT_ID.putDecimal(r, BigDecimal.valueOf(acctId));
        return r;
    }

    static byte[] account(long id, String status, String currBal, String limit, String expiry,
                          String cycCredit, String cycDebit) {
        byte[] r = Records.spaces(Account.LENGTH);
        Account.ID.putDecimal(r, BigDecimal.valueOf(id));
        Account.ACTIVE_STATUS.putText(r, status);
        Account.CURR_BAL.putDecimal(r, new BigDecimal(currBal));
        Account.CREDIT_LIMIT.putDecimal(r, new BigDecimal(limit));
        Account.CASH_CREDIT_LIMIT.putDecimal(r, new BigDecimal(limit));
        Account.OPEN_DATE.putText(r, "2015-01-01");
        Account.EXPIRATION_DATE.putText(r, expiry);
        Account.REISSUE_DATE.putText(r, expiry);
        Account.CURR_CYC_CREDIT.putDecimal(r, new BigDecimal(cycCredit));
        Account.CURR_CYC_DEBIT.putDecimal(r, new BigDecimal(cycDebit));
        Account.ADDR_ZIP.putText(r, "A000000000");
        return r;
    }

    static byte[] account(long id, String currBal, String limit) {
        return account(id, "Y", currBal, limit, "2025-01-01", "0", "0");
    }

    static byte[] tcatbal(long acct, String type, String cat, String bal) {
        byte[] r = Records.spaces(TranCatBal.LENGTH);
        TranCatBal.ACCT_ID.putDecimal(r, BigDecimal.valueOf(acct));
        TranCatBal.TYPE_CD.putText(r, type);
        TranCatBal.CAT_CD.putText(r, cat);
        TranCatBal.BAL.putDecimal(r, new BigDecimal(bal));
        TranCatBal.FILLER.putText(r, "0".repeat(22));
        return r;
    }

    static byte[] card(String num, long acct, String expiry, String status) {
        byte[] r = Records.spaces(Card.LENGTH);
        Card.NUM.putText(r, num);
        Card.ACCT_ID.putDecimal(r, BigDecimal.valueOf(acct));
        Card.EXPIRATION_DATE.putText(r, expiry);
        Card.ACTIVE_STATUS.putText(r, status);
        return r;
    }

    /** All inputs and outputs of one run. */
    static final class Job {
        final List<byte[]> daily = new ArrayList<>();
        final FlatIndexedFile xref = new FlatIndexedFile(Xref.LENGTH, 0, 16);
        IndexedDataset account = new FlatIndexedFile(Account.LENGTH, 0, 11);
        final FlatIndexedFile tcatbal = new FlatIndexedFile(TranCatBal.LENGTH, 0, 17);
        final FlatIndexedFile tranfile = new FlatIndexedFile(Tran.LENGTH, 0, 16);
        FlatIndexedFile card;
        final FlatSequentialFiles.Buffer rejects = new FlatSequentialFiles.Buffer(Reject.LENGTH);
        PostingOptions options = PostingOptions.original();
        Cbtrn02c program;
        String sysout;
        int returnCode;
        AbendException abend;

        Job daily(byte[]... recs) {
            daily.addAll(List.of(recs));
            return this;
        }

        Job xref(byte[]... recs) {
            for (byte[] r : recs) {
                xref.write(r);
            }
            return this;
        }

        Job account(byte[]... recs) {
            for (byte[] r : recs) {
                account.write(r);
            }
            return this;
        }

        Job tcatbal(byte[]... recs) {
            for (byte[] r : recs) {
                tcatbal.write(r);
            }
            return this;
        }

        Job card(byte[]... recs) {
            card = new FlatIndexedFile(Card.LENGTH, 0, 16);
            for (byte[] r : recs) {
                card.write(r);
            }
            return this;
        }

        Job options(PostingOptions o) {
            options = o;
            return this;
        }

        Job run() {
            ByteArrayOutputStream daily = new ByteArrayOutputStream();
            this.daily.forEach(daily::writeBytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream ps = new PrintStream(out, true, Records.CHARSET);
            program = new Cbtrn02c(new Cbtrn02c.Datasets(
                    FlatSequentialFiles.reader(daily.toByteArray(), Tran.LENGTH),
                    tranfile, xref, rejects, account, tcatbal, card), options, () -> RUN_TS, ps);
            try {
                returnCode = program.run();
            } catch (AbendException e) {
                abend = e;
                returnCode = e.abendCode();
            }
            sysout = out.toString(Records.CHARSET);
            return this;
        }

        List<byte[]> rejectRecords() {
            byte[] all = rejects.bytes();
            List<byte[]> list = new ArrayList<>();
            for (int off = 0; off < all.length; off += Reject.LENGTH) {
                byte[] r = new byte[Reject.LENGTH];
                System.arraycopy(all, off, r, 0, Reject.LENGTH);
                list.add(r);
            }
            return list;
        }

        int rejectCode(int index) {
            return Reject.REASON.decimal(rejectRecords().get(index)).intValueExact();
        }

        byte[] accountRec(long id) {
            return account.read(String.format("%011d", id)).orElseThrow();
        }

        BigDecimal accountField(long id, com.carddemo.batch.cbtrn02c.model.Field f) {
            return f.decimal(accountRec(id));
        }

        byte[] tcatbalRec(long acct, String type, String cat) {
            return tcatbal.read(String.format("%011d%s%s", acct, type, cat)).orElseThrow();
        }
    }
}
