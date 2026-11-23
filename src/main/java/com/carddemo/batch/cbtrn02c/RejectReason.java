package com.carddemo.batch.cbtrn02c;

/**
 * Validation trailer codes written to DALYREJS. 100-103 and 109 exist in the original program;
 * 104-108 are only produced when the corresponding {@link Fix} switch is enabled.
 */
public enum RejectReason {
    INVALID_CARD(100, "INVALID CARD NUMBER FOUND"),
    ACCOUNT_NOT_FOUND(101, "ACCOUNT RECORD NOT FOUND"),
    OVERLIMIT(102, "OVERLIMIT TRANSACTION"),
    ACCOUNT_EXPIRED(103, "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
    AMOUNT_OVERFLOW(104, "AMOUNT EXCEEDS BALANCE FIELD CAPACITY"),
    ACCOUNT_NOT_ACTIVE(105, "ACCOUNT NOT ACTIVE"),
    CARD_NOT_ACTIVE(106, "CARD NOT FOUND OR NOT ACTIVE"),
    CARD_EXPIRED(107, "TRANSACTION RECEIVED AFTER CARD EXPIRATION"),
    DUPLICATE_TRANSACTION(108, "DUPLICATE TRANSACTION ID"),
    /** Set by 2800-UPDATE-ACCOUNT-REC on REWRITE INVALID KEY; never written to DALYREJS. */
    ACCOUNT_REWRITE_FAILED(109, "ACCOUNT RECORD NOT FOUND");

    private final int code;
    private final String description;

    RejectReason(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public int code() {
        return code;
    }

    public String description() {
        return description;
    }
}
