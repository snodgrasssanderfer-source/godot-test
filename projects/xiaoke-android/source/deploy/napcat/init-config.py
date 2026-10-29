#!/usr/bin/env python3
"""Prepare a local deployment without downloading, starting or logging in."""
import json
import os
from pathlib import Path
import secrets

base = Path(__file__).resolve().parent
os.umask(0o077)
for name in ("qq", "config", "plugins", "runtime"):
    (base / "data" / name).mkdir(parents=True, exist_ok=True)
config = base / "data" / "config"
if any((config / name).exists() for name in ("webui.json", "onebot11.json")):
    raise SystemExit("Existing configuration retained; edit it explicitly instead of replacing credentials.")
webui_token, onebot_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
(config / "webui.json").write_text(json.dumps({"host": "0.0.0.0", "port": 6099, "token": webui_token, "loginRate": 3}))
(config / "onebot11.json").write_text(json.dumps({
    "network": {"httpServers": [], "httpClients": [], "websocketClients": [], "websocketServers": [{
        "name": "xiaoke-private", "enable": True, "host": "0.0.0.0", "port": 3001,
        "messagePostFormat": "array", "reportSelfMessage": True, "token": onebot_token,
        "debug": False, "heartInterval": 30000}]},
    "musicSignUrl": "", "enableLocalFile2Url": False, "parseMultMsg": False,
}))
(config / "napcat.json").write_text(json.dumps({"fileLog": False, "consoleLog": True, "consoleLogLevel": "error"}))
print("Prepared local data/config files. Tokens were not printed. No service was started.")
