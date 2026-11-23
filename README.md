# CardDemo CBTRN02C: COBOL to Java

CBTRN02C is the CardDemo batch job that posts daily credit-card transactions. It validates each transaction, writes accepts and rejects (with reason codes), and updates the account master and category balances.

## What was done

The program was ported to Java 17 (plain Maven, no runtime dependencies). A test suite of 44 tests checks the business rules and compares Java output with the original COBOL, compiled with GnuCOBOL 3.1.2.

Output is byte-identical to GnuCOBOL on all three data sets (329 transactions):

- sample: 300 transactions
- edge cases: 24 transactions
- duplicate-ID: 5 transactions

The original COBOL has 11 latent bugs and risky behaviours (B1 to B11 in the migration report). The Java port keeps them by default, so results match. Optional fix switches (`--fix=NAME` or `--fix=ALL`) apply the documented fixes. With all fixes on, 10 of the 24 edge-case transactions change outcome.

See [docs/SPEC.md](docs/SPEC.md) for the business rules and [MIGRATION_REPORT.md](MIGRATION_REPORT.md) for parity results, the bug list, and what a bank would still need to sign off.

## How to build and run tests

Requires JDK 17 and Maven 3.9+.

```bash
mvn package
```

This runs all 44 tests, including golden parity against stored COBOL outputs. A COBOL compiler is not required for `mvn package`.

To rebuild the COBOL reference and re-check byte-for-byte parity (needs GnuCOBOL 3.1+ with BDB indexed files, gcc, and python3):

```bash
scripts/run-all.sh
```

To run the Java job:

```bash
java -jar target/cbtrn02c.jar --dalytran=F --xref=F --acct-in=F --acct-out=F \
     --tcatbal-in=F --tcatbal-out=F --tranfile-out=F --dalyrejs=F \
     [--card=F] [--tranfile-in=F] [--run-timestamp=2022-06-11T01:02:03] [--fix=NONE|ALL|FIX1,FIX2]
```

`--fix` defaults to `NONE` (original behaviour). Input and output files are fixed-length, undelimited, ISO-8859-1 images of the VSAM/PS datasets.

Built by Kyrill Zlobenko with Devin (Cognition).

## Credit

COBOL source and copybooks come from AWS CardDemo, [aws-samples/aws-mainframe-modernization-carddemo](https://github.com/aws-samples/aws-mainframe-modernization-carddemo), licensed under the Apache License 2.0. See LICENSE and NOTICE.
