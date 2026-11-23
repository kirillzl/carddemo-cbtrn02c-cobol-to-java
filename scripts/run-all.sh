#!/usr/bin/env bash
# Full verification: unit tests, COBOL build, parity on every case under testdata/.
set -uo pipefail
cd "$(dirname "$0")/.."
mvn -q -B package || exit 1
scripts/build-cobol.sh || exit 1
python3 scripts/make_edge_cases.py
rc=0
for c in testdata/*/; do
  echo "== $(basename "$c")"; scripts/parity.sh "$c" || rc=1
done
mkdir -p parity/results && for c in testdata/*/; do n=$(basename "$c"); cp "parity/work/$n/RESULT.txt" "parity/results/$n.txt"; done
[ $rc = 0 ] && echo "ALL CASES BYTE-IDENTICAL" || echo "DIFFERENCES FOUND"
exit $rc
