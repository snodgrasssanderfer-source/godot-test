#!/usr/bin/env python3
"""Create this private app's update identity. Never print or put secrets in Git/APK."""
from pathlib import Path
import os
import secrets
import shutil
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
android = root / "android"
properties = android / "keystore.properties"
folder = android / "keystore"
output = folder / "xiaoke-private.p12"
if properties.exists() or output.exists():
    raise SystemExit("Signing material already exists; retained unchanged. Use it for updates.")
keytool = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "keytool"
if not keytool.is_file():
    resolved = shutil.which("keytool")
    if not resolved:
        raise SystemExit("Set JAVA_HOME to the pinned JDK before generating signing material.")
    keytool = Path(resolved)
os.umask(0o077)
folder.mkdir(mode=0o700, exist_ok=True)
password = secrets.token_urlsafe(40)
env = dict(os.environ, XIAOKE_SIGNING_PASSWORD=password)
with tempfile.TemporaryDirectory(prefix="signing-", dir=folder) as temp:
    staged = Path(temp) / "xiaoke-private.p12"
    subprocess.run([str(keytool), "-genkeypair", "-keystore", str(staged), "-storetype", "PKCS12",
                    "-storepass:env", "XIAOKE_SIGNING_PASSWORD", "-keypass:env", "XIAOKE_SIGNING_PASSWORD",
                    "-alias", "xiaoke", "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                    "-dname", "CN=Xiaoke Private Companion,O=Personal Use,C=CN"], env=env, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    staged.chmod(0o600)
    staged.replace(output)
    properties.write_text("storeFile=keystore/xiaoke-private.p12\nstorePassword=" + password + "\nkeyAlias=xiaoke\nkeyPassword=" + password + "\n")
    properties.chmod(0o600)
print("Created private signing material in ignored android/keystore and keystore.properties. No secret printed.")
