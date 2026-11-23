#!/usr/bin/env python3
"""Convert a repo ASCII sample file (one record per line, CR/LF tolerant, trailing
spaces possibly trimmed) into a fixed-length, undelimited dataset image, as an
IDCAMS REPRO from the mainframe would produce. Usage: to_fixed.py IN OUT LRECL"""
import sys

src, dst, lrecl = sys.argv[1], sys.argv[2], int(sys.argv[3])
out = bytearray()
with open(src, "rb") as f:
    for n, line in enumerate(f.read().split(b"\n"), 1):
        line = line.rstrip(b"\r")
        if not line:
            continue
        if len(line) > lrecl:
            sys.exit(f"{src}:{n}: record length {len(line)} > {lrecl}")
        out += line.ljust(lrecl, b" ")
open(dst, "wb").write(out)
print(f"{src} -> {dst}: {len(out) // lrecl} records of {lrecl}")
