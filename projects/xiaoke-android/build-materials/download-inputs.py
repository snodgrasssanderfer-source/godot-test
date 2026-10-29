#!/usr/bin/env python3
"""Download selected public, pinned build inputs. Never extract or execute them.

Prepared for handoff; this handoff turn did not redownload the large inputs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--group', choices=['toolchain', 'release'], required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--names', nargs='+', required=True)
args = parser.parse_args()
here = Path(__file__).resolve().parent
data = json.loads((here / ('toolchain-inputs.json' if args.group == 'toolchain' else 'release-inputs.json')).read_text())
items = data if isinstance(data, list) else data['assets']
selected = {item['name']: item for item in items}
unknown = set(args.names) - selected.keys()
if unknown:
    parser.error('Unknown input names: ' + ', '.join(sorted(unknown)))
args.out.mkdir(parents=True, exist_ok=True)

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

for name in args.names:
    item = selected[name]
    target = args.out / name
    if target.exists():
        if digest(target) != item['sha256']:
            raise SystemExit(f'Existing file hash mismatch; not overwritten: {name}')
        print(f'Verified existing {name}')
        continue
    temporary = target.with_name(target.name + '.part')
    request = urllib.request.Request(item['url'], headers={'User-Agent': 'DSH-Companion-Source-Restore/1'})
    with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as out:
        shutil.copyfileobj(response, out, 1024 * 1024)
    if digest(temporary) != item['sha256']:
        raise SystemExit(f'Download hash mismatch; partial file retained for inspection: {name}')
    temporary.replace(target)
    print(f'Downloaded and verified {name}')
