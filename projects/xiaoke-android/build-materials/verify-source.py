#!/usr/bin/env python3
"""Check the handed-off source tree, without building or running the app."""
import hashlib
import json
from pathlib import Path

here = Path(__file__).resolve().parent
root = here.parent / 'source'
manifest = json.loads((here / 'source-file-manifest.json').read_text())
failures = []
for entry in manifest:
    path = root / entry['path']
    if not path.is_file():
        failures.append(entry['path'] + ': missing')
        continue
    if hashlib.sha256(path.read_bytes()).hexdigest() != entry['sha256']:
        failures.append(entry['path'] + ': changed')
    if entry['executable'] and not path.stat().st_mode & 0o111:
        failures.append(entry['path'] + ': executable bit missing')
if failures:
    raise SystemExit('\n'.join(failures))
print(f'Verified {len(manifest)} source files; no application code executed.')
