#!/usr/bin/env bash
# Builds the unmodified CBTRN02C with GnuCOBOL 3.x plus the harness programs.
set -euo pipefail
cd "$(dirname "$0")/../cobol"; mkdir -p bin
# -std=ibm: IBM Enterprise COBOL dialect; -fsign=EBCDIC: overpunched signs as on z/OS ({ A-I } J-R)
cobc -x -std=ibm -fsign=EBCDIC -I cpy -o bin/CBTRN02C src/CBTRN02C.cbl harness/CEE3ABD.cbl
cobc -x -std=ibm -fsign=EBCDIC -o bin/UTLIDX harness/UTLIDX.cbl
gcc -shared -fPIC -O2 -o bin/zero_subsec.so ../parity/tools/zero_subsec.c -ldl
echo "built: $(ls bin | tr '\n' ' ')"
