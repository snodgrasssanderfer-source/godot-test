#!/usr/bin/env python3
"""Portable equivalent of termux-proot loader-info.awk; build host only.

Reads readelf's symbol table, preserving exact symbol matching and integer hex math.
This replaces GNU awk strtonum/word-boundaries on hosts whose awk is mawk.
"""
import sys

symbols = {}
for line in sys.stdin:
    fields = line.split()
    if len(fields) >= 8 and fields[-1] in {"pokedata_workaround", "_start"}:
        symbols[fields[-1]] = int(fields[1], 16)
print("#include <unistd.h>")
print("const ssize_t offset_to_pokedata_workaround=" + str(symbols.get("pokedata_workaround", 0) - symbols.get("_start", 0)) + ";")
