package com.carddemo.batch.cbtrn02c.model;

import static com.carddemo.batch.cbtrn02c.model.Field.n;
import static com.carddemo.batch.cbtrn02c.model.Field.s;
import static com.carddemo.batch.cbtrn02c.model.Field.x;

/** Byte layouts of the copybooks used by CBTRN02C (offsets are 0-based). */
public final class Layouts {

    private Layouts() {
    }

    /** CVTRA06Y DALYTRAN-RECORD and CVTRA05Y TRAN-RECORD share this 350-byte layout. */
    public static final class Tran {
        public static final int LENGTH = 350;
        public static final Field ID = x("TRAN-ID", 0, 16);
        public static final Field TYPE_CD = x("TRAN-TYPE-CD", 16, 2);
        public static final Field CAT_CD = n("TRAN-CAT-CD", 18, 4);
        public static final Field SOURCE = x("TRAN-SOURCE", 22, 10);
        public static final Field DESC = x("TRAN-DESC", 32, 100);
        public static final Field AMT = s("TRAN-AMT", 132, 9, 2);
        public static final Field MERCHANT_ID = n("TRAN-MERCHANT-ID", 143, 9);
        public static final Field MERCHANT_NAME = x("TRAN-MERCHANT-NAME", 152, 50);
        public static final Field MERCHANT_CITY = x("TRAN-MERCHANT-CITY", 202, 50);
        public static final Field MERCHANT_ZIP = x("TRAN-MERCHANT-ZIP", 252, 10);
        public static final Field CARD_NUM = x("TRAN-CARD-NUM", 262, 16);
        public static final Field ORIG_TS = x("TRAN-ORIG-TS", 278, 26);
        public static final Field PROC_TS = x("TRAN-PROC-TS", 304, 26);
        public static final Field FILLER = x("FILLER", 330, 20);

        /** The fields 2000-POST-TRANSACTION MOVEs from DALYTRAN-RECORD to TRAN-RECORD, in order. */
        public static final Field[] POSTED_FIELDS = {
            ID, TYPE_CD, CAT_CD, SOURCE, DESC, AMT, MERCHANT_ID, MERCHANT_NAME, MERCHANT_CITY,
            MERCHANT_ZIP, CARD_NUM, ORIG_TS
        };

        private Tran() {
        }
    }

    /** CVACT03Y CARD-XREF-RECORD (KSDS key = card number). */
    public static final class Xref {
        public static final int LENGTH = 50;
        public static final Field CARD_NUM = x("XREF-CARD-NUM", 0, 16);
        public static final Field CUST_ID = n("XREF-CUST-ID", 16, 9);
        public static final Field ACCT_ID = n("XREF-ACCT-ID", 25, 11);

        private Xref() {
        }
    }

    /** CVACT01Y ACCOUNT-RECORD (KSDS key = account id). */
    public static final class Account {
        public static final int LENGTH = 300;
        public static final Field ID = n("ACCT-ID", 0, 11);
        public static final Field ACTIVE_STATUS = x("ACCT-ACTIVE-STATUS", 11, 1);
        public static final Field CURR_BAL = s("ACCT-CURR-BAL", 12, 10, 2);
        public static final Field CREDIT_LIMIT = s("ACCT-CREDIT-LIMIT", 24, 10, 2);
        public static final Field CASH_CREDIT_LIMIT = s("ACCT-CASH-CREDIT-LIMIT", 36, 10, 2);
        public static final Field OPEN_DATE = x("ACCT-OPEN-DATE", 48, 10);
        public static final Field EXPIRATION_DATE = x("ACCT-EXPIRAION-DATE", 58, 10);
        public static final Field REISSUE_DATE = x("ACCT-REISSUE-DATE", 68, 10);
        public static final Field CURR_CYC_CREDIT = s("ACCT-CURR-CYC-CREDIT", 78, 10, 2);
        public static final Field CURR_CYC_DEBIT = s("ACCT-CURR-CYC-DEBIT", 90, 10, 2);
        public static final Field ADDR_ZIP = x("ACCT-ADDR-ZIP", 102, 10);
        public static final Field GROUP_ID = x("ACCT-GROUP-ID", 112, 10);

        private Account() {
        }
    }

    /** CVTRA01Y TRAN-CAT-BAL-RECORD (KSDS key = acct id + type code + category code). */
    public static final class TranCatBal {
        public static final int LENGTH = 50;
        public static final int KEY_LENGTH = 17;
        public static final Field ACCT_ID = n("TRANCAT-ACCT-ID", 0, 11);
        public static final Field TYPE_CD = x("TRANCAT-TYPE-CD", 11, 2);
        public static final Field CAT_CD = n("TRANCAT-CD", 13, 4);
        public static final Field BAL = s("TRAN-CAT-BAL", 17, 9, 2);
        public static final Field FILLER = x("FILLER", 28, 22);

        private TranCatBal() {
        }
    }

    /** CVACT02Y CARD-RECORD (only read when the STATUS_CHECKS fix is enabled). */
    public static final class Card {
        public static final int LENGTH = 150;
        public static final Field NUM = x("CARD-NUM", 0, 16);
        public static final Field ACCT_ID = n("CARD-ACCT-ID", 16, 11);
        public static final Field EXPIRATION_DATE = x("CARD-EXPIRAION-DATE", 80, 10);
        public static final Field ACTIVE_STATUS = x("CARD-ACTIVE-STATUS", 90, 1);

        private Card() {
        }
    }

    /** DALYREJS record: the 350-byte daily transaction followed by the 80-byte validation trailer. */
    public static final class Reject {
        public static final int LENGTH = 430;
        public static final Field TRAN_DATA = x("REJECT-TRAN-DATA", 0, 350);
        public static final Field REASON = n("WS-VALIDATION-FAIL-REASON", 350, 4);
        public static final Field REASON_DESC = x("WS-VALIDATION-FAIL-REASON-DESC", 354, 76);

        private Reject() {
        }
    }
}
