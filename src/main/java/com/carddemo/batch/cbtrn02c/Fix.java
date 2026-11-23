package com.carddemo.batch.cbtrn02c;

/**
 * Opt-in corrections of defects found in the original CBTRN02C. With no switch enabled the Java
 * program reproduces the COBOL behaviour byte for byte. See MIGRATION_REPORT.md for each finding.
 */
public enum Fix {
    /**
     * Over-limit test uses ACCT-CURR-BAL + amount (full precision) and only for charges
     * (amount &gt; 0). Original: CYC-CREDIT - CYC-DEBIT + amount truncated to S9(9)V99, which
     * double-counts payments (CYC-DEBIT is stored negative), ignores the carried balance and
     * rejects payments on over-limit accounts.
     */
    OVERLIMIT_FORMULA,
    /**
     * Reject with 104 instead of silently truncating when an amount would not fit ACCT-CURR-BAL,
     * the cycle totals or TRAN-CAT-BAL; WS-TEMP-BAL is also evaluated without truncation.
     */
    SIZE_ERROR,
    /** Reject inactive accounts (105) and, when a card file is supplied, inactive/unknown (106) or expired (107) cards. */
    STATUS_CHECKS,
    /** Reject a transaction whose id is already on TRANSACT (108) before any balance is touched. */
    DUPLICATE_ID,
    /** Keep the existing transaction master (original OPEN OUTPUT empties it every run). */
    TRANSACT_APPEND,
    /** Abend when the account REWRITE fails instead of posting the transaction anyway (109 is lost). */
    REWRITE_FAILURE_ABEND,
    /** New TCATBAL records get a deterministic FILLER instead of bytes left over in working storage. */
    TCATBAL_FILLER
}
