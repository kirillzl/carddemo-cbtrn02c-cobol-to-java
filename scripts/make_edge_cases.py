#!/usr/bin/env python3
"""Generates the edge-case input sets under testdata/ in the repo's text format.
Each daily transaction description says which rule it exercises."""
import os
from decimal import Decimal

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "testdata")
POS, NEG = "{ABCDEFGHI", "}JKLMNOPQR"


def zoned(value, digits, scale=2):
    v = Decimal(value).quantize(Decimal(1).scaleb(-scale))
    s = str(abs(v)).replace(".", "").rjust(digits, "0")
    assert len(s) == digits, value
    return s[:-1] + (NEG if v < 0 else POS)[int(s[-1])]


def x(v, n):
    v = str(v)
    assert len(v) <= n, (v, n)
    return v.ljust(n)


def n(v, d):
    return str(v).rjust(d, "0")


def acct(aid, bal, limit, exp, cyc_cr="0", cyc_db="0", status="Y"):
    r = (n(aid, 11) + status + zoned(bal, 12) + zoned(limit, 12) + zoned("0", 12) + x("2015-01-01", 10)
         + x(exp, 10) + x("2025-01-01", 10) + zoned(cyc_cr, 12) + zoned(cyc_db, 12) + x("12345", 10)
         + x("DEFAULT", 10) + x("", 178))
    assert len(r) == 300
    return r


def xref(card, aid, cust=1):
    return card + n(cust, 9) + n(aid, 11)


def tcat(aid, typ, cat, bal, filler="0" * 22):
    return n(aid, 11) + typ + n(cat, 4) + zoned(bal, 11) + x(filler, 22)


def card(num, aid, exp, status="Y"):
    return num + n(aid, 11) + "123" + x("EDGE CASE HOLDER", 50) + x(exp, 10) + status + x("", 59)


def tran(tid, card_no, amt, desc, typ="01", cat=1, ts="2022-06-10 19:27:53.000000"):
    r = (x(tid, 16) + typ + n(cat, 4) + x("POS TERM", 10) + x(desc, 100) + zoned(amt, 11) + n(800000001, 9)
         + x("EDGE MERCHANT", 50) + x("SEATTLE", 50) + x("98101", 10) + x(card_no, 16) + x(ts, 26)
         + x("", 26) + x("", 20))
    assert len(r) == 350, len(r)
    return r


C = lambda i: "41000000000000" + n(i, 2)

ACCOUNTS = [
    acct(101, "100.00", "1000.00", "2025-12-31"),                          # baseline
    acct(102, "0", "100.00", "2025-12-31", cyc_cr="60.00"),                # over-limit pair
    acct(103, "0", "1000.00", "2022-06-10"),                               # expires on tran date
    acct(104, "0", "10.00", "2020-01-01"),                                 # expired + would be over limit
    acct(105, "5000.00", "100.00", "2025-12-31"),                          # curr bal > limit, cycle 0
    acct(106, "500.00", "100.00", "2025-12-31", cyc_cr="500.00"),          # payment on over-limit acct
    acct(107, "0", "1000.00", ""),                                         # blank expiry date
    acct(108, "0", "5000.00", "2025-12-31", cyc_cr="1000000000.00"),       # WS-TEMP-BAL truncation
    acct(109, "9999999999.00", "9999999999.99", "2025-12-31"),            # ACCT-CURR-BAL overflow
    acct(110, "0", "1000.00", "2025-12-31", status="N"),                   # inactive account
    acct(111, "-300.00", "200.00", "2025-12-31", cyc_db="-300.00"),        # prior payments
    acct(112, "0", "1000.00", "2025-12-31"),                               # expired card (card file)
    acct(113, "0", "99999.00", "2025-12-31"),                              # TCATBAL overflow
]
XREFS = [xref(C(i), 100 + i) for i in range(1, 14)] + [xref(C(20), 120)]   # 120: no account record
TCATS = [tcat(101, "01", 1, "7.00", filler="STALE-FILLER-BYTES-XYZ"),  # FILLER leaks into new records
         tcat(113, "01", 1, "999999999.00"), tcat(999, "99", 9999, "1.00")]
CARDS = [card(C(i), 100 + i, "2026-01-01") for i in range(1, 14)]
CARDS[11] = card(C(12), 112, "2020-01-01")                                 # expired card
CARDS[9] = card(C(10), 110, "2026-01-01", status="N")

TRANS = [
    tran("EDGE000000000001", C(1), "3.00", "POST: existing TCATBAL 101/01/0001 incremented"),
    tran("EDGE000000000002", C(1), "4.50", "POST: new TCATBAL 101/03/0002 created", typ="03", cat=2),
    tran("EDGE000000000003", C(1), "-1.25", "POST: TCATBAL created this run then updated (credit)", typ="03", cat=2),
    tran("EDGE000000000004", C(1), "0.00", "POST: zero amount goes to cycle credit"),
    tran("EDGE000000000005", C(1), "-20.00", "POST: payment goes to cycle debit", typ="02", cat=1),
    tran("EDGE000000000006", "4999999999999999", "10.00", "REJECT 100: card not in XREF"),
    tran("EDGE000000000007", C(20), "10.00", "REJECT 101: XREF account has no account record"),
    tran("EDGE000000000008", C(2), "40.01", "REJECT 102: 60.00 + 40.01 > limit 100.00"),
    tran("EDGE000000000009", C(2), "40.00", "POST: 60.00 + 40.00 = limit 100.00"),
    tran("EDGE000000000010", C(2), "0.01", "REJECT 102: cycle credit now 100.00"),
    tran("EDGE000000000011", C(3), "1.00", "REJECT 103: tran date after expiry", ts="2022-06-11 00:00:00.000000"),
    tran("EDGE000000000012", C(3), "1.00", "POST: tran on expiry date", ts="2022-06-10 23:59:59.999999"),
    tran("EDGE000000000013", C(4), "50.00", "REJECT 103 overrides 102"),
    tran("EDGE000000000014", C(5), "10.00", "POST (bug): balance 5000 > limit 100 ignored"),
    tran("EDGE000000000015", C(6), "-50.00", "REJECT 102 (bug): payment on over-limit account", typ="02"),
    tran("EDGE000000000016", C(7), "1.00", "REJECT 103 (risk): blank expiry date"),
    tran("EDGE000000000017", C(8), "10.00", "POST (bug): WS-TEMP-BAL 1000000010.00 truncates to 10.00"),
    tran("EDGE000000000018", C(9), "5.00", "POST (bug): ACCT-CURR-BAL overflow truncates"),
    tran("EDGE000000000019", C(10), "5.00", "POST (gap): inactive account / inactive card"),
    tran("EDGE000000000020", C(11), "10.00", "REJECT 102: 0 - (-300) + 10 = 310 > 200"),
    tran("EDGE000000000021", C(12), "5.00", "POST (gap): card expired 2020-01-01, not checked"),
    tran("EDGE000000000022", C(13), "2.00", "POST (bug): TCATBAL overflow truncates"),
    tran("EDGE000000000023", C(1), "999999999.99", "POST (bug): max amount passes, WS-TEMP-BAL truncation"),
    tran("EDGE000000000024", C(1), "-999999999.99", "POST: min amount (credits only raise headroom)"),
]


def write(case, name, lines):
    os.makedirs(os.path.join(ROOT, case), exist_ok=True)
    with open(os.path.join(ROOT, case, name), "w", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


for case, trans in (("edge", TRANS), ("edge-dup", TRANS[:2] + [
        tran("EDGE000000000001", C(1), "9.00", "ABEND 999: duplicate TRAN-ID, balances already updated")] + TRANS[2:4])):
    write(case, "dailytran.txt", trans)
    write(case, "acctdata.txt", ACCOUNTS)
    write(case, "cardxref.txt", XREFS)
    write(case, "tcatbal.txt", TCATS)
    write(case, "carddata.txt", CARDS)
print("edge cases written to", os.path.abspath(ROOT))
