#!/usr/bin/env bash
# Runs the original COBOL CBTRN02C (GnuCOBOL) and the Java port on the same input
# case directory and compares every output dataset byte for byte.
#   scripts/parity.sh CASE_DIR [FIX_SPEC]
# CASE_DIR holds dailytran.txt cardxref.txt acctdata.txt tcatbal.txt (repo text format).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
CASE=$(cd "$1" && pwd); NAME=$(basename "$CASE")
RUN_TS_COB="2022/06/11 01:02:03"; RUN_TS_JAVA="2022-06-11T01:02:03"
W="$ROOT/parity/work/$NAME"; rm -rf "$W"; mkdir -p "$W"/{in,cobol,java}
BIN="$ROOT/cobol/bin"; JAR="$ROOT/target/cbtrn02c.jar"
fx() { python3 "$ROOT/scripts/to_fixed.py" "$CASE/$1" "$W/in/$2" "$3" >/dev/null; }
fx dailytran.txt DALYTRAN 350; fx cardxref.txt XREF 50; fx acctdata.txt ACCT 300; fx tcatbal.txt TCATBAL 50
: > "$W/in/TRANFILE"

# ---- COBOL: REPRO flat -> indexed, run, REPRO indexed -> flat
cd "$W/cobol"
load() { FLATFILE="$W/in/$2" IDXFILE="$W/cobol/$3" "$BIN/UTLIDX" LOAD "$1" >/dev/null; }
unload() { FLATFILE="$W/cobol/$3" IDXFILE="$W/cobol/$2" "$BIN/UTLIDX" UNLOAD "$1" >/dev/null; }
load XREF XREF XREF.idx; load ACCT ACCT ACCT.idx; load TCAT TCATBAL TCATBAL.idx; load TRAN TRANFILE TRANFILE.idx
set +e
DD_DALYTRAN="$W/in/DALYTRAN" DD_TRANFILE="$W/cobol/TRANFILE.idx" DD_XREFFILE="$W/cobol/XREF.idx" \
DD_DALYREJS="$W/cobol/DALYREJS" DD_ACCTFILE="$W/cobol/ACCT.idx" DD_TCATBALF="$W/cobol/TCATBAL.idx" \
COB_CURRENT_DATE="$RUN_TS_COB" LD_PRELOAD="$BIN/zero_subsec.so" "$BIN/CBTRN02C" > "$W/cobol/SYSOUT" 2> "$W/cobol/STDERR"
echo $? > "$W/cobol/RC"
set -e
unload ACCT ACCT.idx ACCT; unload TCAT TCATBAL.idx TCATBAL; unload TRAN TRANFILE.idx TRANFILE
[ -f "$W/cobol/DALYREJS" ] || : > "$W/cobol/DALYREJS"

# ---- Java
set +e
env -u JDK_JAVA_OPTIONS java -jar "$JAR" --dalytran="$W/in/DALYTRAN" --xref="$W/in/XREF" --acct-in="$W/in/ACCT" \
  --acct-out="$W/java/ACCT" --tcatbal-in="$W/in/TCATBAL" --tcatbal-out="$W/java/TCATBAL" \
  --tranfile-in="$W/in/TRANFILE" --tranfile-out="$W/java/TRANFILE" --dalyrejs="$W/java/DALYREJS" \
  --run-timestamp="$RUN_TS_JAVA" --fix="${2:-NONE}" > "$W/java/SYSOUT" 2> "$W/java/STDERR"
echo $? > "$W/java/RC"
set -e

# ---- Compare
status=0
for f in TRANFILE DALYREJS ACCT TCATBAL SYSOUT RC; do
  if cmp -s "$W/cobol/$f" "$W/java/$f"; then r=IDENTICAL; else r=DIFFERENT; status=1; fi
  printf '%-9s %-9s cobol=%8s bytes sha256=%s\n' "$f" "$r" "$(stat -c%s "$W/cobol/$f")" \
    "$(sha256sum < "$W/cobol/$f" | cut -c1-16)"
done | tee "$W/RESULT.txt"
if [ -s "$W/cobol/STDERR" ]; then echo "note: GnuCOBOL runtime stderr (not program output):"; sed 's/^/  /' "$W/cobol/STDERR"; fi | tee -a "$W/RESULT.txt"
grep -q DIFFERENT "$W/RESULT.txt" && status=1
exit $status
