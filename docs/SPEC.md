# CBTRN02C – Business rules specification (as-is)

Source: `app/cbl/CBTRN02C.cbl` of aws-samples/aws-mainframe-modernization-carddemo, job `app/jcl/POSTTRAN.jcl` (step `STEP15`).
This document describes what the program **actually does**, including defects. Items marked **[DEFECT]** / **[RISK]** are analysed in `MIGRATION_REPORT.md` §4; the Java port keeps them by default.

## 1. Purpose

Nightly posting of the day's card transactions. Each record of the daily transaction file is validated against the card cross-reference and the account master. Valid transactions are posted: the transaction-category balance and the account balances are updated and the transaction is written to the transaction master. Invalid transactions are written to a rejects file with a reason code.

## 2. Files

| DD | Copybook | Org / access | Open mode | LRECL | Key | Role |
|---|---|---|---|---|---|---|
| DALYTRAN | CVTRA06Y `DALYTRAN-RECORD` | sequential | INPUT | 350 | – | daily transactions |
| XREFFILE | CVACT03Y `CARD-XREF-RECORD` | KSDS, random | INPUT | 50 | card number (16) | card → customer / account |
| ACCTFILE | CVACT01Y `ACCOUNT-RECORD` | KSDS, random | I-O | 300 | account id 9(11) | account master |
| TCATBALF | CVTRA01Y `TRAN-CAT-BAL-RECORD` | KSDS, random | I-O | 50 | acct id 9(11) + type X(2) + cat 9(4) = 17 | balance per account/type/category |
| TRANFILE | CVTRA05Y `TRAN-RECORD` | KSDS, random | **OUTPUT** | 350 | transaction id X(16) | posted transactions |
| DALYREJS | (inline `REJECT-RECORD`) | sequential | OUTPUT | 430 | – | rejected transactions |

The card master (CVACT02Y, `CARDFILE`) is **not** read by CBTRN02C. It is modelled in Java only for the optional card checks (fix `STATUS_CHECKS`).

Numeric fields are zoned decimal (`PIC S9(n)V99` DISPLAY, sign overpunched in the last byte: `{ A–I` = +0..+9, `} J–R` = −0..−9). Field offsets are in `src/main/java/.../model/Layouts.java`.

Reject record (430 bytes) = the 350-byte daily transaction unchanged + `WS-VALIDATION-FAIL-REASON PIC 9(4)` + `WS-VALIDATION-FAIL-REASON-DESC PIC X(76)`.

## 3. Processing flow

1. Display `START OF EXECUTION OF PROGRAM CBTRN02C`.
2. Open, in order: DALYTRAN, TRANFILE (OUTPUT – empties it), XREFFILE, DALYREJS, ACCTFILE, TCATBALF. Any status ≠ `00` → display `ERROR OPENING …`, file status, abend (§7).
3. For each daily record (status `00`; `10` = end of file; anything else → `ERROR READING DALYTRAN FILE` + abend):
   1. Add 1 to transactions processed; reset reason to 0 and description to spaces.
   2. Validate (§4).
   3. Reason = 0 → post (§5). Otherwise add 1 to rejects and write the reject record (§6).
4. Close all six files (close error → abend).
5. Display `TRANSACTIONS PROCESSED :nnnnnnnnn` and `TRANSACTIONS REJECTED  :nnnnnnnnn` (9 digits).
6. Return code **4** if at least one reject, else **0**. Display `END OF EXECUTION OF PROGRAM CBTRN02C`.

## 4. Validation rules and reason codes

Rules are evaluated in this order; a later failing rule **overwrites** the code of an earlier one within the same step.

| Code | Description written | Rule |
|---|---|---|
| **100** | `INVALID CARD NUMBER FOUND` | `DALYTRAN-CARD-NUM` not found as a key of XREFFILE. No further check is made. |
| **101** | `ACCOUNT RECORD NOT FOUND` | `XREF-ACCT-ID` of the cross-reference not found in ACCTFILE. No further check is made. |
| **102** | `OVERLIMIT TRANSACTION` | `WS-TEMP-BAL = ACCT-CURR-CYC-CREDIT − ACCT-CURR-CYC-DEBIT + DALYTRAN-AMT`, stored in `PIC S9(9)V99` (high-order digits silently truncated **[DEFECT]**). Reject if `ACCT-CREDIT-LIMIT < WS-TEMP-BAL`. Equal to the limit is accepted. `ACCT-CURR-BAL` is **not** used **[DEFECT]**; because cycle debits are stored as negative amounts, the subtraction *adds* past payments to the exposure **[DEFECT]**; the test also applies to payments/credits **[DEFECT]**. |
| **103** | `TRANSACTION RECEIVED AFTER ACCT EXPIRATION` | Reject if `ACCT-EXPIRAION-DATE` (X(10), `YYYY-MM-DD`) < first 10 characters of `DALYTRAN-ORIG-TS` — an alphanumeric comparison. A transaction on the expiry date is accepted. A blank/invalid expiry date compares low and rejects every transaction **[RISK]**. Evaluated even when 102 already failed, so **103 overrides 102**. |
| 109 | `ACCOUNT RECORD NOT FOUND` | Set only if the account REWRITE during posting returns INVALID KEY. It is **never written** to the rejects file, the transaction is still written to TRANFILE and not counted as rejected **[DEFECT]**. |

Not validated by the original (all are posted): account active status, card active status, card expiry date (card file is not opened), duplicate transaction id (leads to an abend, §7), transaction type/category validity, numeric validity of the amount, amounts that overflow the balance fields **[RISK]**.

Reason codes 104–108 exist only in the Java port and only when fixes are enabled (see report §4).

## 5. Posting a valid transaction (paragraph 2000)

Order matters for partial updates on abend: **TCATBAL → ACCOUNT → TRANFILE**.

### 5.1 Transaction record (CVTRA05Y)
All fields are copied from the daily record (id, type, category, source, description, amount, merchant id/name/city/zip, card number, original timestamp). `TRAN-PROC-TS` is set to the run's current date-time in DB2 format `YYYY-MM-DD-HH.MM.SS.hh0000` (hundredths of a second, then four zeros). The 20-byte FILLER is spaces. The daily record's own PROC-TS/FILLER are not carried.

### 5.2 Transaction category balance (paragraph 2700)
* Key = `XREF-ACCT-ID` (account from the cross-reference) + `DALYTRAN-TYPE-CD` + `DALYTRAN-CAT-CD`.
* **Existing key**: `TRAN-CAT-BAL = TRAN-CAT-BAL + DALYTRAN-AMT` (signed), REWRITE.
* **Missing key** (status `23`): display `TCATBAL record not found for key : <17-byte key>.. Creating.`; `INITIALIZE` the record (key fields and balance set to zero, **FILLER is not initialised** – it keeps the bytes of the last TCATBAL record read in the run, or spaces if none **[RISK]**); set the key; balance = 0 + amount; WRITE. A second transaction for the same new key in the same run updates the record created earlier.
* Overflow beyond `S9(9)V99` is silently truncated **[DEFECT]**.
* Any other status on read/write/rewrite → `ERROR READING|WRITING|REWRITING TRANSACTION BALANCE FILE` + abend.

### 5.3 Account (paragraph 2800)
* `ACCT-CURR-BAL = ACCT-CURR-BAL + DALYTRAN-AMT`.
* If `DALYTRAN-AMT >= 0` (charges **and zero amounts**): `ACCT-CURR-CYC-CREDIT += amount`.
* If `DALYTRAN-AMT < 0` (payments/credits): `ACCT-CURR-CYC-DEBIT += amount` (so the cycle debit accumulates as a negative number).
* Overflow beyond `S9(10)V99` is silently truncated **[DEFECT]**.
* REWRITE; INVALID KEY → reason 109 (see §4), processing continues.

### 5.4 Transaction master (paragraph 2900)
WRITE the transaction record. Any status ≠ `00` (including `22` duplicate key) → `ERROR WRITING TO TRANSACTION FILE` + abend, **after** TCATBAL and ACCOUNT have already been updated for that record **[DEFECT]**.

## 6. Reject handling (paragraph 2500)
Write the 350-byte input record followed by the 4-digit reason code and the 76-byte description. Write status ≠ `00` → `ERROR WRITING TO REJECTS FILE` + abend. Rejected records do not change any balance.

## 7. Abend
`9910-DISPLAY-IO-STATUS` displays `FILE STATUS IS: NNNN` + the status as 4 digits (`00ss` for numeric statuses; for `9x` statuses the second byte is shown as its binary value). `9999-ABEND-PROGRAM` displays `ABENDING PROGRAM` and calls `CEE3ABD` with code **999** (user abend U0999). Updates already done stay on the VSAM files; there is no checkpoint/restart.

## 8. Worked examples (from `testdata/edge`, verified on GnuCOBOL and Java)

| Txn | Account state | Amount | Outcome |
|---|---|---|---|
| 08 | limit 100, cyc credit 60 | 40.01 | 102 |
| 09 | same | 40.00 | posted (equal to limit); cyc credit 100 |
| 11 / 12 | expiry 2022-06-10 | ts 2022-06-11 / 2022-06-10 23:59 | 103 / posted |
| 13 | expired and over limit | 50.00 | 103 (overrides 102) |
| 14 | curr bal 5000, limit 100, cycle 0 | 10.00 | posted (curr bal ignored) |
| 15 | cyc credit 500, limit 100 | −50.00 (payment) | 102 (payment rejected) |
| 20 | cyc debit −300, limit 200 | 10.00 | 102 (0 − (−300) + 10 = 310) |
| 23 | cycle ≈ 0, limit 1000 | 999,999,999.99 | posted (WS-TEMP-BAL truncates 1,000,000,028.74 → 28.74) |
| 02 / 03 | no TCATBAL 101/03/0002 | 4.50 then −1.25 | record created (4.50) then updated (3.25) |
