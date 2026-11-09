#!/usr/bin/env python3
"""
Prints the decode tables of JxrVlc.kt, the adaptive variable length codes of JPEG XR, from
jxrlib's own source, so no table is typed by hand.

T.832 lists the code tables of its adaptive VLCs (clause 9.13), and jxrlib keeps them as root
lookup tables of 5 bits, with a tree of 1-bit nodes after the root for the longest codes, and the
discriminant deltas that choose among them. This script reads those arrays from
image/sys/adapthuff.c of a jxrlib checkout and prints the companion object block of JxrVlc.kt.

    python3 tools/jxr_tables.py /path/to/jxrlib
"""
import re
import sys

SRC = open(sys.argv[1] + "/image/sys/adapthuff.c", encoding="latin-1").read()


def rows(name):
    """The rows of C array [name], each a list of integers."""
    m = re.search(r"%s\s*(\[[^\]]*\])+\s*=\s*\{(.*?)\};" % re.escape(name), SRC, re.S)
    if m is None:
        sys.exit("no array " + name)
    body = re.sub(r"//[^\n]*", "", m.group(2))
    groups = re.findall(r"\{([^{}]*)\}", body) or [body]
    return [[int(x) for x in re.findall(r"-?\d+", g)] for g in groups]


LOOKUPS = [("g4HuffLookupTable", "LOOKUP4", 1, 40), ("g5HuffLookupTable", "LOOKUP5", 2, 42),
           ("g6HuffLookupTable", "LOOKUP6", 4, 44), ("g7HuffLookupTable", "LOOKUP7", 2, 46),
           ("g8HuffLookupTable", "LOOKUP8", 2, 48), ("g9HuffLookupTable", "LOOKUP9", 2, 50),
           ("g12HuffLookupTable", "LOOKUP12", 5, 56)]
DELTAS = [("g5DeltaTable", "DELTA5", 5), ("g6DeltaTable", "DELTA6", 18), ("g7DeltaTable", "DELTA7", 7),
          ("g9DeltaTable", "DELTA9", 9), ("g12DeltaTable", "DELTA12", 48)]

for c, k, count, size in LOOKUPS:
    table = rows(c)
    if len(table) != count or any(len(r) != size for r in table):
        sys.exit(c + " has another shape")
    print("        val %s: Array<IntArray> = arrayOf(" % k)
    for r in table:
        print("            intArrayOf(")
        for i in range(0, len(r), 16):
            print("                " + ", ".join(map(str, r[i:i + 16])) + ",")
        print("            ),")
    print("        )\n")
for c, k, size in DELTAS:
    r = rows(c)[0]
    if len(r) != size:
        sys.exit(c + " has another shape")
    print("        val %s: IntArray = intArrayOf(%s)\n" % (k, ", ".join(map(str, r))))
