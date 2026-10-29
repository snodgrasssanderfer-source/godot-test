#!/usr/bin/env python3
"""Copy the fixed public shell archive from an already pulled official image; never start QQ."""
import hashlib
from pathlib import Path
import subprocess
import uuid

IMAGE = 'mlikiowa/napcat-docker@sha256:b101b8968cc31ae428ec0f8e117386e4ced7773b2d8226ed9f7ddfcec55f1b7e'
EXPECTED = '6801f93e533846cea05f4f531874a0e84371ab2331c5585b57cd7e3b869cf96b'
target = Path(__file__).resolve().parent / 'data' / 'NapCat.Shell.zip'
target.parent.mkdir(parents=True, exist_ok=True)
if not target.exists():
    subprocess.run(['docker', 'image', 'inspect', IMAGE], check=True, stdout=subprocess.DEVNULL)
    name = 'xiaoke-shell-copy-' + uuid.uuid4().hex[:12]
    subprocess.run(['docker', 'create', '--name', name, '--network', 'none', '--entrypoint', '/bin/false', IMAGE], check=True, stdout=subprocess.DEVNULL)
    try:
        subprocess.run(['docker', 'cp', name + ':/app/NapCat.Shell.zip', str(target)], check=True)
        target.chmod(0o400)
    finally:
        subprocess.run(['docker', 'rm', name], check=True, stdout=subprocess.DEVNULL)
if hashlib.sha256(target.read_bytes()).hexdigest() != EXPECTED:
    raise SystemExit('Shell archive differs from the fixed v4.18.41 image; do not start this deployment.')
print('Verified fixed NapCat shell archive. No account or QQ process was started.')
