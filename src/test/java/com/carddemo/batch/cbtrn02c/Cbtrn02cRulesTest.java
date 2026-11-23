package com.carddemo.batch.cbtrn02c;

import static com.carddemo.batch.cbtrn02c.Fixtures.account;
import static com.carddemo.batch.cbtrn02c.Fixtures.card;
import static com.carddemo.batch.cbtrn02c.Fixtures.dalytran;
import static com.carddemo.batch.cbtrn02c.Fixtures.tcatbal;
import static com.carddemo.batch.cbtrn02c.Fixtures.xref;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.batch.cbtrn02c.Fixtures.Job;
import com.carddemo.batch.cbtrn02c.io.FileStatus;
import com.carddemo.batch.cbtrn02c.io.FlatIndexedFile;
import com.carddemo.batch.cbtrn02c.io.IndexedDataset;
import com.carddemo.batch.cbtrn02c.io.Records;
import com.carddemo.batch.cbtrn02c.model.Layouts.Account;
import com.carddemo.batch.cbtrn02c.model.Layouts.Reject;
import com.carddemo.batch.cbtrn02c.model.Layouts.Tran;
import com.carddemo.batch.cbtrn02c.model.Layouts.TranCatBal;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** One test (or group) per business rule of docs/SPEC.md, original behaviour first, then the fix. */
class Cbtrn02cRulesTest {

    private static final String CARD = "4111111111111111";
    private static final long ACCT = 1L;

    private static Job standard() {
        return new Job().xref(xref(CARD, ACCT)).account(account(ACCT, "100.00", "1000.00"));
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    @Nested
    class Validation {

        @Test
        void r100_unknownCardIsRejected() {
            Job j = standard().daily(dalytran("T1", "9999999999999999", "10.00")).run();
            assertEquals(1, j.rejectRecords().size());
            assertEquals(100, j.rejectCode(0));
            assertEquals("INVALID CARD NUMBER FOUND", Reject.REASON_DESC.text(j.rejectRecords().get(0)).trim());
            assertEquals(4, j.returnCode);
            assertTrue(j.tranfile.view().isEmpty());
        }

        @Test
        void r101_cardWhoseAccountIsMissingIsRejected() {
            Job j = new Job().xref(xref(CARD, 42)).daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(101, j.rejectCode(0));
            assertEquals("ACCOUNT RECORD NOT FOUND", Reject.REASON_DESC.text(j.rejectRecords().get(0)).trim());
        }

        @Test
        void r102_overLimitIsRejected_andEqualToLimitPasses() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "100.00", "2025-01-01", "60.00", "0"))
                    .daily(dalytran("T1", CARD, "40.01"), dalytran("T2", CARD, "40.00")).run();
            assertEquals(1, j.rejectRecords().size());
            assertEquals(102, j.rejectCode(0));
            assertEquals("T1", Tran.ID.text(j.rejectRecords().get(0)).trim());
            assertEquals(1, j.tranfile.view().size());
        }

        @Test
        void r102_limitTestUsesCycleTotalsNotCurrentBalance() {
            // ACCT-CURR-BAL far above the limit is ignored by the original formula.
            Job j = new Job().xref(xref(CARD, ACCT)).account(account(ACCT, "5000.00", "100.00"))
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(0, j.rejectRecords().size());
            assertEquals(bd("5010.00"), j.accountField(ACCT, Account.CURR_BAL));
        }

        @Test
        void r102_negativeCycleDebitIsSubtracted_soPaymentsIncreaseTheTestedBalance() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "-300.00", "200.00", "2025-01-01", "0", "-300.00"))
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(102, j.rejectCode(0)); // 0 - (-300) + 10 = 310 > 200
        }

        @Test
        void r102_paymentOnOverLimitAccountIsRejected() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "500.00", "100.00", "2025-01-01", "500.00", "0"))
                    .daily(dalytran("T1", CARD, "-50.00")).run();
            assertEquals(102, j.rejectCode(0));
        }

        @Test
        void r102_wsTempBalTruncationLetsHugeCycleCreditPass() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "5000.00", "2025-01-01", "1000000000.00", "0"))
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(0, j.rejectRecords().size()); // 1000000010.00 stored in S9(9)V99 = 10.00
        }

        @Test
        void r103_transactionAfterAccountExpiryIsRejected_sameDayPasses() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "1000.00", "2022-06-10", "0", "0"))
                    .daily(dalytran("T1", CARD, "01", "0001", "1.00", "2022-06-11 00:00:00.000000"),
                           dalytran("T2", CARD, "01", "0001", "1.00", "2022-06-10 23:59:59.000000"))
                    .run();
            assertEquals(1, j.rejectRecords().size());
            assertEquals(103, j.rejectCode(0));
            assertEquals("TRANSACTION RECEIVED AFTER ACCT EXPIRATION",
                    Reject.REASON_DESC.text(j.rejectRecords().get(0)).trim());
        }

        @Test
        void r103_blankExpiryDateRejectsEverything() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "1000.00", "", "0", "0"))
                    .daily(dalytran("T1", CARD, "1.00")).run();
            assertEquals(103, j.rejectCode(0));
        }

        @Test
        void r103_overridesR102WhenBothFail() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "10.00", "2020-01-01", "0", "0"))
                    .daily(dalytran("T1", CARD, "50.00")).run();
            assertEquals(103, j.rejectCode(0));
        }

        @Test
        void expiredCardAndInactiveAccountArePostedByTheOriginal() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "N", "0", "1000.00", "2025-01-01", "0", "0"))
                    .card(card(CARD, ACCT, "2020-01-01", "N"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(0, j.rejectRecords().size());
            assertEquals(1, j.tranfile.view().size());
        }

        @Test
        void rejectRecordIsInputPlusTrailer() {
            byte[] in = dalytran("T1", "0000000000000000", "1.00");
            Job j = standard().daily(in).run();
            byte[] rej = j.rejectRecords().get(0);
            assertArrayEquals(in, Arrays.copyOf(rej, Tran.LENGTH));
            assertEquals("0100", Records.text(rej, 350, 4));
            assertEquals(430, rej.length);
        }
    }

    @Nested
    class Posting {

        @Test
        void accountBalancesUpdated_positiveToCycleCredit_negativeToCycleDebit_zeroToCredit() {
            Job j = standard().daily(dalytran("T1", CARD, "25.50"), dalytran("T2", CARD, "-10.25"),
                    dalytran("T3", CARD, "0.00")).run();
            assertEquals(bd("115.25"), j.accountField(ACCT, Account.CURR_BAL));
            assertEquals(bd("25.50"), j.accountField(ACCT, Account.CURR_CYC_CREDIT));
            assertEquals(bd("-10.25"), j.accountField(ACCT, Account.CURR_CYC_DEBIT));
            assertEquals(0, j.returnCode);
        }

        @Test
        void tcatbalExistingRecordIsIncremented() {
            Job j = standard().tcatbal(tcatbal(ACCT, "01", "0001", "7.00"))
                    .daily(dalytran("T1", CARD, "3.00")).run();
            assertEquals(bd("10.00"), TranCatBal.BAL.decimal(j.tcatbalRec(ACCT, "01", "0001")));
            assertTrue(!j.sysout.contains("Creating"));
        }

        @Test
        void tcatbalMissingRecordIsCreatedThenUpdatedWithinTheRun() {
            Job j = standard().daily(dalytran("T1", CARD, "03", "0002", "3.00", Fixtures.ORIG_TS),
                    dalytran("T2", CARD, "03", "0002", "-1.00", Fixtures.ORIG_TS)).run();
            assertEquals(bd("2.00"), TranCatBal.BAL.decimal(j.tcatbalRec(ACCT, "03", "0002")));
            assertTrue(j.sysout.contains("TCATBAL record not found for key : 00000000001030002.. Creating."));
        }

        @Test
        void newTcatbalFillerIsInheritedFromWorkingStorage() {
            Job first = standard().daily(dalytran("T1", CARD, "03", "0002", "3.00", Fixtures.ORIG_TS)).run();
            assertEquals(" ".repeat(22), TranCatBal.FILLER.text(first.tcatbalRec(ACCT, "03", "0002")));

            byte[] seed = tcatbal(ACCT, "01", "0001", "0");
            TranCatBal.FILLER.putText(seed, "STALE-FILLER-BYTES-XYZ");
            Job second = standard().tcatbal(seed).daily(dalytran("T1", CARD, "1.00"),
                    dalytran("T2", CARD, "03", "0002", "3.00", Fixtures.ORIG_TS)).run();
            assertEquals("STALE-FILLER-BYTES-XYZ", TranCatBal.FILLER.text(second.tcatbalRec(ACCT, "03", "0002")));

            Job fixed = standard().tcatbal(seed).options(PostingOptions.of(Fix.TCATBAL_FILLER))
                    .daily(dalytran("T1", CARD, "1.00"), dalytran("T2", CARD, "03", "0002", "3.00", Fixtures.ORIG_TS))
                    .run();
            assertEquals(" ".repeat(22), TranCatBal.FILLER.text(fixed.tcatbalRec(ACCT, "03", "0002")));
        }

        @Test
        void tcatbalKeyUsesXrefAccountNotCardAccount() {
            Job j = new Job().xref(xref(CARD, 7)).account(account(7, "0", "1000.00"))
                    .daily(dalytran("T1", CARD, "2.00")).run();
            assertNotNull(j.tcatbalRec(7, "01", "0001"));
        }

        @Test
        void postedTransactionCopiesInputAndStampsProcessingTimestamp() {
            byte[] in = dalytran("T1", CARD, "2.00");
            Tran.PROC_TS.putText(in, "IGNORED-INPUT-PROC-TS");
            Tran.FILLER.putText(in, "IGNORED-FILLER");
            Job j = standard().daily(in).run();
            byte[] out = j.tranfile.read("T1              ").orElseThrow();
            assertArrayEquals(Arrays.copyOf(in, Tran.PROC_TS.offset()), Arrays.copyOf(out, Tran.PROC_TS.offset()));
            assertEquals("2022-06-11-01.02.03.000000", Tran.PROC_TS.text(out));
            assertEquals(" ".repeat(20), Tran.FILLER.text(out));
        }

        @Test
        void sizeOverflowIsSilentlyTruncated() {
            Job j = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "9999999999.00", "1000.00", "2025-01-01", "0", "0"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(bd("4.00"), j.accountField(ACCT, Account.CURR_BAL));
        }

        @Test
        void returnCodeAndCounters() {
            Job j = standard().daily(dalytran("T1", CARD, "1.00"), dalytran("T2", "X", "1.00")).run();
            assertEquals(4, j.returnCode);
            assertTrue(j.sysout.contains("TRANSACTIONS PROCESSED :000000002"));
            assertTrue(j.sysout.contains("TRANSACTIONS REJECTED  :000000001"));
        }
    }

    @Nested
    class Abends {

        @Test
        void duplicateTransactionIdAbendsAfterBalancesWereUpdated() {
            Job j = standard().daily(dalytran("T1", CARD, "1.00"), dalytran("T1", CARD, "2.00")).run();
            assertNotNull(j.abend);
            assertEquals(999, j.returnCode);
            assertTrue(j.sysout.contains("ERROR WRITING TO TRANSACTION FILE"));
            assertTrue(j.sysout.contains("FILE STATUS IS: NNNN0022"));
            assertEquals(bd("103.00"), j.accountField(ACCT, Account.CURR_BAL)); // second amount applied
        }

        @Test
        void accountRewriteInvalidKeySets109ButStillPosts() {
            Job j = standard();
            j.account = rewriteFails(j.account);
            j.daily(dalytran("T1", CARD, "1.00")).run();
            assertNull(j.abend);
            assertEquals(1, j.tranfile.view().size());
            assertEquals(0, j.rejectRecords().size());
            assertEquals(109, j.program.lastValidationFailReason());
        }

        @Test
        void fix_rewriteFailureAbends() {
            Job j = standard().options(PostingOptions.of(Fix.REWRITE_FAILURE_ABEND));
            j.account = rewriteFails(j.account);
            j.daily(dalytran("T1", CARD, "1.00")).run();
            assertNotNull(j.abend);
            assertTrue(j.tranfile.view().isEmpty());
        }

        private IndexedDataset rewriteFails(IndexedDataset delegate) {
            return new IndexedDataset() {
                public int recordLength() {
                    return delegate.recordLength();
                }

                public Optional<byte[]> read(String key) {
                    return delegate.read(key);
                }

                public String write(byte[] record) {
                    return delegate.write(record);
                }

                public String rewrite(byte[] record) {
                    return FileStatus.NOT_FOUND;
                }
            };
        }

        @Test
        void displayIoStatus() {
            assertEquals("0022", Cbtrn02c.displayIoStatus("22"));
            assertEquals("9035", Cbtrn02c.displayIoStatus("9#"));
        }
    }

    @Nested
    class Fixes {

        @Test
        void overlimitFormula_usesCurrentBalanceAndSkipsPayments() {
            PostingOptions o = PostingOptions.of(Fix.OVERLIMIT_FORMULA);
            Job a = new Job().xref(xref(CARD, ACCT)).account(account(ACCT, "95.00", "100.00")).options(o)
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(102, a.rejectCode(0));
            Job b = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "500.00", "100.00", "2025-01-01", "500.00", "0")).options(o)
                    .daily(dalytran("T1", CARD, "-50.00")).run();
            assertEquals(0, b.rejectRecords().size());
            Job c = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "5000.00", "2025-01-01", "1000000000.00", "0")).options(o)
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(0, c.rejectRecords().size()); // balance 0 + 10 is within limit
        }

        @Test
        void sizeError_rejects104AndDisablesTempBalTruncation() {
            PostingOptions o = PostingOptions.of(Fix.SIZE_ERROR);
            Job a = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "9999999999.00", "1000.00", "2025-01-01", "0", "0")).options(o)
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(104, a.rejectCode(0));
            assertEquals(bd("9999999999.00"), a.accountField(ACCT, Account.CURR_BAL));
            Job b = new Job().xref(xref(CARD, ACCT))
                    .account(account(ACCT, "Y", "0", "5000.00", "2025-01-01", "1000000000.00", "0")).options(o)
                    .daily(dalytran("T1", CARD, "10.00")).run();
            assertEquals(102, b.rejectCode(0));
        }

        @Test
        void statusChecks_inactiveAccountCardAndExpiredCard() {
            PostingOptions o = PostingOptions.of(Fix.STATUS_CHECKS);
            Job inactive = new Job().xref(xref(CARD, ACCT)).options(o)
                    .account(account(ACCT, "N", "0", "1000.00", "2025-01-01", "0", "0"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(105, inactive.rejectCode(0));
            Job cardOff = standard().options(o).card(card(CARD, ACCT, "2025-01-01", "N"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(106, cardOff.rejectCode(0));
            Job noCard = standard().options(o).card()
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(106, noCard.rejectCode(0));
            Job expired = standard().options(o).card(card(CARD, ACCT, "2022-06-09", "Y"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(107, expired.rejectCode(0));
            Job ok = standard().options(o).card(card(CARD, ACCT, "2022-06-10", "Y"))
                    .daily(dalytran("T1", CARD, "5.00")).run();
            assertEquals(0, ok.rejectRecords().size());
        }

        @Test
        void duplicateId_rejects108WithoutTouchingBalances() {
            Job j = standard().options(PostingOptions.of(Fix.DUPLICATE_ID))
                    .daily(dalytran("T1", CARD, "1.00"), dalytran("T1", CARD, "2.00")).run();
            assertNull(j.abend);
            assertEquals(108, j.rejectCode(0));
            assertEquals(bd("101.00"), j.accountField(ACCT, Account.CURR_BAL));
        }

        @Test
        void originalPrecedenceIsKeptWhenFixesAreOn() {
            Job j = new Job().xref(xref(CARD, ACCT)).options(PostingOptions.allFixes())
                    .account(account(ACCT, "N", "0", "10.00", "2020-01-01", "0", "0"))
                    .daily(dalytran("T1", CARD, "50.00")).run();
            assertEquals(103, j.rejectCode(0));
        }

        @Test
        void parseOptions() {
            assertTrue(PostingOptions.parse("NONE").fixes().isEmpty());
            assertEquals(Fix.values().length, PostingOptions.parse("all").fixes().size());
            assertTrue(PostingOptions.parse("size_error,DUPLICATE_ID").has(Fix.DUPLICATE_ID));
        }
    }

    @Test
    void flatIndexedFileStatuses() {
        FlatIndexedFile f = new FlatIndexedFile(50, 0, 17);
        byte[] r = tcatbal(1, "01", "0001", "0");
        assertEquals(FileStatus.OK, f.write(r));
        assertEquals(FileStatus.DUPLICATE_KEY, f.write(r));
        assertEquals(FileStatus.NOT_FOUND, f.rewrite(tcatbal(2, "01", "0001", "0")));
    }
}
