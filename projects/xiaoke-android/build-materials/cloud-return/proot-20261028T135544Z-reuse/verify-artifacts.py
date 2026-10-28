#!/usr/bin/env python3
"""Verify packaged hashes and ELF structure; never execute either Android binary."""
from pathlib import Path
import hashlib
import json
import struct

root = Path(__file__).resolve().parent
manifest = json.loads((root / 'output-manifest.json').read_text())
results = []
for expected in manifest['outputs']:
    raw = (root / expected['file']).read_bytes()
    assert len(raw) == expected['bytes'], 'Size mismatch'
    assert hashlib.sha256(raw).hexdigest() == expected['sha256'], 'SHA-256 mismatch'
    assert raw[:6] == b'\x7fELF\x02\x01', 'Expected little-endian ELF64'
    header = struct.unpack_from('<HHIQQQIHHHHHH', raw, 16)
    elf_type, machine, phoff, phentsize, phnum = header[0], header[1], header[4], header[8], header[9]
    assert machine == 183, 'Expected AArch64, not host x86_64'
    assert phentsize == 56
    segments = [struct.unpack_from('<IIQQQQQQ', raw, phoff + n * phentsize) for n in range(phnum)]
    loads = [p for p in segments if p[0] == 1]
    assert loads
    for p in loads:
        assert p[7] >= 16384 and p[7] & (p[7] - 1) == 0, 'LOAD alignment below 16 KiB'
        assert (p[3] - p[2]) % p[7] == 0, 'LOAD file/virtual address mismatch'
    interpreter = None
    for p in segments:
        if p[0] == 3:
            interpreter = raw[p[2]:p[2] + p[5]].rstrip(b'\0').decode()
        if p[0] == 0x6474e551:
            assert not p[1] & 1, 'Executable stack unexpectedly requested'
    dynamic = []
    for p in segments:
        if p[0] == 2:
            for pos in range(p[2], p[2] + p[5], 16):
                tag, value = struct.unpack_from('<qQ', raw, pos)
                if tag == 0:
                    break
                dynamic.append((tag, value))
    needed = []
    if dynamic:
        address = next(value for tag, value in dynamic if tag == 5)
        load = next(p for p in loads if p[3] <= address < p[3] + p[5])
        strings = load[2] + address - load[3]
        for tag, value in dynamic:
            if tag == 1:
                begin = strings + value
                needed.append(raw[begin:raw.index(0, begin)].decode())
    if expected['file'] == 'libdsh_proot.so':
        assert elf_type == 3 and interpreter == '/system/bin/linker64'
        assert sorted(needed) == ['libc.so', 'libdl.so']
    else:
        assert elf_type == 2 and interpreter is None and not dynamic
    results.append({
        **expected, 'elf_class': 64, 'machine': 'AArch64',
        'elf_type': 'PIE/DYN' if elf_type == 3 else 'EXEC',
        'interpreter': interpreter, 'needed': needed,
        'load_alignments': [p[7] for p in loads],
        'load_offset_vaddr_congruence': True, 'static_checks': 'passed',
    })
print(json.dumps({'executed_android_binaries': False, 'results': results}, indent=2))
