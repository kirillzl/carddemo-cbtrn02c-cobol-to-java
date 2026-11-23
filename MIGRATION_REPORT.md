# CBTRN02C migration report – COBOL → Java 17 / Maven

| | |
|---|---|
| Source | aws-samples/aws-mainframe-modernization-carddemo, `app/cbl/CBTRN02C.cbl` (CardDemo v2.0-25), copybooks CVTRA06Y, CVTRA05Y, CVACT03Y, CVACT01Y, CVTRA01Y (+ CVACT02Y for optional card checks), job `POSTTRAN.jcl` |
| Target | Java 17, Maven 3.9, no runtime dependencies (JUnit 5 for tests) |
| Reference run | Unmodified COBOL source compiled with GnuCOBOL 3.1.2 (`-std=ibm -fsign=EBCDIC`, BDB indexed files) on Ubuntu 22.04 |
| Verdict | **Byte-for-byte identical** outputs on all three data sets (repo sample + 2 edge-case sets): posted transactions, rejects with reason codes, updated account master, updated category balances, SYSOUT and return code. No functional differences found. |

Business rules: see `docs/SPEC.md` (written first, from the source).

## 1. What was built

| Area | Java | Notes |
|---|---|---|
| Copybooks / file layouts | `model/Layouts.java`, `model/Field.java` | Offsets/PICs of the 6 copybooks plus the 430-byte reject record. |
| Numeric semantics | `io/ZonedDecimal.java`, `io/CobolMath.java` | Zoned decimal with overpunched sign, `BigDecimal` arithmetic, COBOL truncation on store (no ON SIZE ERROR). |
| VSAM KSDS / PS files | `io/FlatIndexedFile.java`, `io/FlatSequentialFiles.java` | In-memory KSDS with COBOL file statuses (`00/10/22/23/35`), saved in key order (= IDCAMS REPRO unload). The `IndexedDataset` interface is the seam to plug a database or real VSAM access later. |
| Program logic | `Cbtrn02c.java` | One method per COBOL paragraph, same order of operations, same DISPLAY texts, same return codes, abend U0999 as `AbendException`. |
| Fix switches | `Fix.java`, `PostingOptions.java`, CLI `--fix=` | Default `NONE` = original behaviour. |
| CLI | `Main.java` | Flat-file equivalent of the POSTTRAN step. |

## 2. Tests

`mvn package` → **44 tests, 0 failures**.

* `Cbtrn02cRulesTest` (31): one test per rule of the spec – 100, 101, 102 (incl. equal-to-limit, current balance ignored, negative cycle debit, payment rejected, WS-TEMP-BAL truncation), 103 (incl. same day, blank date, 103 overrides 102), no card/account status checks, reject layout, cycle credit/debit split incl. zero amount, TCATBAL existing/new/new-then-updated, FILLER inheritance, TCATBAL key from XREF account, PROC-TS format, silent overflow, counters and RC, duplicate-id abend after balances updated, 109 swallowed, I/O status display, and each fix switch.
* `ZonedDecimalTest` (10): sign overpunch decode/encode, truncation.
* `GoldenParityTest` (3): runs the Java CLI on each `testdata` case and asserts equality with the COBOL outputs stored in `testdata/*/expected` – parity is re-checked on every build without needing GnuCOBOL.

## 3. Parity: COBOL (GnuCOBOL) vs Java

Harness (`scripts/run-all.sh` → `build-cobol.sh`, `parity.sh`): text sample files are converted to fixed-length records (`to_fixed.py`), loaded into GnuCOBOL indexed files by `UTLIDX` (IDCAMS REPRO equivalent), CBTRN02C runs with DD names mapped by environment variables and a fixed clock, then the indexed files are unloaded in key order and compared with `cmp` against the Java outputs.

| Case | Input | Posted | Rejected (codes) | TCATBAL created | TRANFILE | DALYREJS | ACCT | TCATBAL | SYSOUT | RC |
|---|---|---|---|---|---|---|---|---|---|---|
| `sample` (repo `app/data/ASCII`) | 300 txns, 50 accts, 50 xref, 51 tcatbal | 262 | 38 (all 102) | 49 | identical (91,700 B) | identical (16,340 B) | identical (15,000 B) | identical (5,000 B) | identical | 4 = 4 |
| `edge` | 24 txns, 13 accts | 15 | 9 (100 ×1, 101 ×1, 102 ×4, 103 ×3) | 9 | identical | identical | identical | identical | identical | 4 = 4 |
| `edge-dup` | 5 txns, duplicate id | 2 then abend | 0 | 1 | identical | identical (empty) | identical | identical | identical | 231 = 231 (U0999 mod 256) |

Edge-case coverage (`scripts/make_edge_cases.py`, each record's description names the rule): over-limit (above / equal / after earlier posting), expired account (after / on expiry day / blank date), expired **card** and inactive account/card (posted – not checked by the original), unknown card (100), unknown account (101), 103-overrides-102, new TCATBAL record vs existing, record created then updated in the same run, FILLER leak into new TCATBAL records, payments, zero amount, prior negative cycle debit, WS-TEMP-BAL truncation, ACCT-CURR-BAL and TRAN-CAT-BAL overflow, max/min amount, duplicate transaction id.

### Differences found

**Program outputs: none.** Every byte of the 4 datasets, the SYSOUT text and the return code matched in all cases.

The harness has these limits. None of them is a difference in the port:

1. **Clock.** GnuCOBOL's `COB_CURRENT_DATE` fixes date and time but still takes the hundredths of a second from the real clock. The first run differed only in `TRAN-PROC-TS` positions 21–22 (`.45`/`.46` vs `.00`). An `LD_PRELOAD` shim (`parity/tools/zero_subsec.c`) now zeroes the sub-seconds. The COBOL source is unchanged.
2. **Abend side output.** On U0999, libcob writes `implicit CLOSE` warnings to stderr. They come from the GnuCOBOL runtime and the `CEE3ABD` stub, not from the program, so they are excluded from the SYSOUT comparison. On z/OS you get an LE dump instead. The Java CLI prints `CEE3ABD ABEND U0999`, the same line as the stub, and exits with 999 (231 to a Linux shell).
3. **Reason 109 (account REWRITE INVALID KEY).** The account is always read just before the rewrite, so this cannot happen in a single-job run. It is covered only by a Java unit test with a failing dataset.
4. **Platform.** The reference ran on ASCII GnuCOBOL, not on z/OS Enterprise COBOL with EBCDIC data:
   * Keys and dates are digits, `-` and space, so the order and comparisons are the same in both code pages.
   * Mixed letter and digit transaction IDs (e.g. `EDGE…` vs `0000…`) unload in a different key order on EBCDIC. The record contents are unaffected.
   * The z/OS parallel run (§6) is the decisive test.
5. **TRANFILE open.** `OPEN OUTPUT` on the KSDS empties it in both GnuCOBOL and Java. On z/OS the result depends on the cluster definition (REUSE). See B5.

## 4. Defects and risky behaviour in the original

By default every item below is reproduced exactly, and parity proves this. Each one has an opt-in switch: `--fix=NAME[,NAME]` or `--fix=ALL` (`PostingOptions` in code). With fixes on, reason-code precedence stays 100 → 101 → 102 → 103. The new checks run only after these four pass, so existing codes keep their meaning.

| # | Severity | Finding (original) | Evidence | Switch → fixed behaviour |
|---|---|---|---|---|
| B1 | **High** | The over-limit test uses `CYC-CREDIT − CYC-DEBIT + amount`. **Problems:** `ACCT-CURR-BAL` is ignored, so balances carried from earlier cycles do not count. Cycle debits are stored negative, so subtracting them *adds* payments to the exposure. Payments and credits are also limit-tested, so a customer over the limit cannot pay. | edge 14 posted on a 5,000 balance against a 100 limit. edge 15 rejected a payment. edge 20 rejected 10.00 after a 300 payment. | `OVERLIMIT_FORMULA`: reject 102 only if amount > 0 and `CURR-BAL + amount > CREDIT-LIMIT`, at full precision. **The business must confirm the intended rule.** |
| B2 | **High** | `WS-TEMP-BAL` is `S9(9)V99` but holds a value built from `S9(10)V99` fields, with no `ON SIZE ERROR`. High-order digits are dropped, so very large exposures look small and pass. | edge 23: 999,999,999.99 posted against a 1,000 limit (1,000,000,028.74 → 28.74). edge 17. | `SIZE_ERROR` (no truncation in the test) and/or `OVERLIMIT_FORMULA`. |
| B3 | High | `ADD` into `ACCT-CURR-BAL`, cycle totals and `TRAN-CAT-BAL` has no `ON SIZE ERROR`, so overflow wraps silently and corrupts balances. | edge 18: balance 9,999,999,999.00 + 5 → 4.00. edge 22: TCATBAL. | `SIZE_ERROR`: reject **104** `AMOUNT EXCEEDS BALANCE FIELD CAPACITY` before any update. |
| B4 | High | A duplicate transaction ID abends with U0999 *after* TCATBAL and ACCOUNT were already updated for that record. There is no commit scope or restart, so a rerun double-posts. | edge-dup: the account includes the duplicate's 9.00 and the TRANFILE does not. | `DUPLICATE_ID`: reject **108** before any update. |
| B5 | Medium | TRANFILE is opened `OUTPUT`, which deletes or replaces the transaction master every run (or fails, depending on VSAM REUSE). Downstream jobs therefore see only today's postings. This is likely intended for a demo but risky in production. | spec §2 | `TRANSACT_APPEND`: load the existing master (`--tranfile-in`) and add to it. |
| B6 | Medium | A REWRITE INVALID KEY on the account sets 109, but nothing writes or checks it. The transaction is still posted and TCATBAL already moved, so the account is out of balance with no reject. | unit test `accountRewriteInvalidKeySets109ButStillPosts` | `REWRITE_FAILURE_ABEND`: abend U0999 before writing the transaction. |
| B7 | Medium | Account active status, card status and card expiry are never checked. Expired or blocked cards post. The requirement "expired card" is not enforced; only *account* expiry is. | edge 19 and 21 posted | `STATUS_CHECKS`: **105** account not active; with `--card`, **106** card not found or not active, **107** card expired. |
| B8 | Low | `INITIALIZE` leaves FILLER untouched, so a new TCATBAL record inherits FILLER bytes from the last TCATBAL record read. This is not deterministic and can leak data. | edge: new records carry `STALE-FILLER-BYTES-XYZ` | `TCATBAL_FILLER`: FILLER = spaces. |
| B9 | Low | The expiry test compares X(10) text. A blank or malformed date (e.g. `MM/DD/YYYY`) rejects or accepts incorrectly, and the description says "account" for any date fault. The transaction date is the merchant-supplied `ORIG-TS`, not the processing date. | edge 16 | Not switched (the behaviour is defined). Recommend data-quality validation upstream. |
| B10 | Low | Type and category codes are not validated against TRANTYPE/TRANCATG, so unknown categories create new TCATBAL rows. Non-numeric amounts cause unpredictable arithmetic, and on z/OS possibly an S0C7 abend. | spec §4 | Not switched. Recommend input-validation step. |
| B11 | Info | The description for 109 duplicates 101 (`ACCOUNT RECORD NOT FOUND`). Zero amounts count as cycle *credit* (charges). The return code is 4 whenever there is at least one reject, so the scheduler cannot tell rejects from a clean run plus warnings. | spec | – |

With `--fix=ALL` on `edge`, 10 of the 24 transactions change outcome:

* **Now posted:** 08, 10, 15, 20 (B1).
* **Now rejected:** 14 → 102, 18 → 102, 19 → 105, 21 → 107, 22 → 104, 23 → 102.

## 5. Effort estimate (experienced engineer, COBOL + Java)

| Work item | Days |
|---|---|
| Analysis of program, copybooks, JCL; business-rule spec; walkthrough with SMEs | 3–4 |
| Java implementation: layouts, zoned decimal and COBOL arithmetic, file emulation, logic, CLI | 4–6 |
| Unit tests per rule, golden tests | 3 |
| Parity harness: GnuCOBOL build, REPRO utility, data conversion, edge-case data, diffing | 4–5 |
| Defect analysis, fix switches with tests | 2–3 |
| Documentation, review rework | 2 |
| **Subtotal: this deliverable (proof of concept to bank-review quality)** | **18–23 days (about 4–5 weeks)** |
| Production hardening: real data store (DB2, VSAM via an access layer, or RDBMS) with transactions and restart; EBCDIC/packed I/O against real extracts; logging and metrics; scheduler integration; performance at production volume | +15–25 days |
| z/OS parallel runs, reconciliation and defect triage over several cycles (calendar time dominated by environment and data access) | +10–15 days of effort over 4–8 weeks elapsed |

Realistic total for one production program: **about 2–3 engineer-months**. Elapsed time is usually set by environment access, test-data provisioning and governance, not by coding.

## 6. What a bank needs to sign off

1. **Business-rule approval.** The product or credit-risk owner signs `docs/SPEC.md` and decides each finding B1–B11: keep (bug-for-bug) or fix. Every fix changes customer outcomes (limits, rejects) and may need customer-communication and regulatory review.
2. **Parallel run on the real platform.** Run COBOL on z/OS and Java side by side on production-like EBCDIC data, for several daily cycles including month-end and cycle-close.
   * Byte or field-level comparison of TRANSACT, DALYREJS, ACCTDATA and TCATBALF.
   * Control totals: count and amount per reason code, sum of balance deltas = sum of posted amounts, TCATBAL delta = ACCT delta.
   * A formal acceptance threshold of zero unexplained differences.
3. **Independent testing and UAT.** A test team separate from the developer re-runs the edge cases and adds its own. Requirements are traced to tests: spec section → unit test → parity case.
4. **Non-functional evidence.**
   * Throughput at peak volume within the batch window.
   * Restart and rerun procedure, which is weak today (B4).
   * Failure and abend handling.
   * Scheduler return-code contract (0, 4, abend).
   * Observability.
5. **Security and compliance.**
   * PCI DSS: card numbers (PAN) appear in clear in the daily, reject and transaction files, so encryption at rest, access control and log masking are needed.
   * SAST and dependency scan.
   * Segregation of duties.
   * Audit trail of postings.
6. **Model, change and operational risk.**
   * Change Advisory Board approval.
   * Rollback plan to the COBOL job.
   * Run book and support handover.
   * Data-reconciliation sign-off by finance or controllers.
   * Internal audit review of the parity evidence.
   * Records retention for the parallel-run results.
7. **Data migration and integration.** Before the Java job takes over, confirm the VSAM-to-target-store conversion (EBCDIC, signed zoned fields, key order) and the downstream consumers of TRANSACT and DALYREJS (e.g. CBTRN03C reports, interest calculation CBACT04C).

## 7. Reproduce

```bash
mvn package            # 44 tests incl. golden parity (no COBOL needed)
scripts/run-all.sh     # needs GnuCOBOL 3.1+ (BDB), gcc, python3; prints ALL CASES BYTE-IDENTICAL
```

Results of the last run are in `parity/results/*.txt`. No changes were pushed to any remote.
