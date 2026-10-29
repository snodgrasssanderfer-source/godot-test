"""Optional single-owner encrypted event relay. Standard library only; loopback by default.

No QQ client, model, scheduler or plaintext memory store runs here. Put an HTTPS
reverse proxy with owner-controlled authentication in front before pairing a phone.
Do not expose this development listener to the Internet.
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import re
import sqlite3
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

HEX = re.compile(r"[a-f0-9]{64}\Z")
VAULT = re.compile(r"[a-f0-9]{32}\Z")
MAX_BODY = 8 * 1024 * 1024


class Relay:
    def __init__(self, path: str):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA secure_delete=ON")
        self.db.executescript("""
        CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,
          vault TEXT NOT NULL, id TEXT NOT NULL, envelope TEXT NOT NULL, UNIQUE(vault,id));
        CREATE TABLE IF NOT EXISTS deleted(vault TEXT NOT NULL, id TEXT NOT NULL,
          generation INTEGER NOT NULL, PRIMARY KEY(vault,id));
        CREATE TABLE IF NOT EXISTS generations(vault TEXT PRIMARY KEY, value INTEGER NOT NULL);
        """)

    def close(self):
        self.db.close()

    def exchange(self, body: dict) -> dict:
        if body.get("version") != 1 or not VAULT.fullmatch(str(body.get("vault", ""))):
            raise ValueError("unsupported request")
        vault = body["vault"]
        cursor, generation = body.get("cursor"), body.get("generation")
        if type(cursor) is not int or type(generation) is not int or min(cursor, generation) < 0:
            raise ValueError("invalid cursor")
        events, deletions = body.get("events"), body.get("deletions")
        if not isinstance(events, list) or len(events) > 16 or not isinstance(deletions, list) or len(deletions) > 1000:
            raise ValueError("batch too large")
        if any(not isinstance(x, str) or not HEX.fullmatch(x) for x in deletions):
            raise ValueError("invalid deletion")
        for event in events:
            if not isinstance(event, dict) or set(event) != {"id", "sources", "ciphertext", "digest"}:
                raise ValueError("invalid envelope")
            if not HEX.fullmatch(str(event["id"])) or not HEX.fullmatch(str(event["digest"])):
                raise ValueError("invalid identifier")
            if not isinstance(event["sources"], list) or len(event["sources"]) > 64 or any(not isinstance(x, str) or not HEX.fullmatch(x) for x in event["sources"]):
                raise ValueError("invalid sources")
            if not isinstance(event["ciphertext"], str) or not 40 <= len(event["ciphertext"]) <= 700000:
                raise ValueError("invalid ciphertext")
            if hashlib.sha256(event["ciphertext"].encode()).hexdigest() != event["digest"]:
                raise ValueError("ciphertext digest mismatch")
        with self.lock, self.db:
            current = self.db.execute("SELECT value FROM generations WHERE vault=?", (vault,)).fetchone()
            current = current[0] if current else 0
            for deletion in deletions:
                if not self.db.execute("SELECT 1 FROM deleted WHERE vault=? AND id=?", (vault, deletion)).fetchone():
                    current += 1
                    self.db.execute("INSERT INTO deleted VALUES(?,?,?)", (vault, deletion, current))
            blocked = {row[0] for row in self.db.execute("SELECT id FROM deleted WHERE vault=?", (vault,))}
            # Cascade to encrypted derivatives through their opaque source references.
            while True:
                added = False
                for seq, identifier, encoded in self.db.execute("SELECT seq,id,envelope FROM events WHERE vault=?", (vault,)).fetchall():
                    event = json.loads(encoded)
                    if identifier in blocked or set(event["sources"]) & blocked:
                        if identifier not in blocked:
                            current += 1
                            self.db.execute("INSERT INTO deleted VALUES(?,?,?)", (vault, identifier, current))
                            blocked.add(identifier)
                        self.db.execute("DELETE FROM events WHERE seq=?", (seq,))
                        added = True
                if not added:
                    break
            self.db.execute("INSERT INTO generations VALUES(?,?) ON CONFLICT(vault) DO UPDATE SET value=excluded.value", (vault, current))
            acks = []
            # A stale device must apply all new deletion fences before any upload is accepted.
            if generation == current:
                for event in events:
                    if event["id"] in blocked or set(event["sources"]) & blocked:
                        continue
                    old = self.db.execute("SELECT envelope FROM events WHERE vault=? AND id=?", (vault, event["id"])).fetchone()
                    encoded = json.dumps(event, sort_keys=True, separators=(",", ":"))
                    if old and old[0] != encoded:
                        raise ValueError("event identifier conflict")
                    if not old:
                        self.db.execute("INSERT INTO events(vault,id,envelope) VALUES(?,?,?)", (vault, event["id"], encoded))
                    acks.append(event["id"])
            page = self.db.execute("SELECT seq,envelope FROM events WHERE vault=? AND seq>? ORDER BY seq LIMIT 16", (vault, cursor)).fetchall()
            return {"version": 1, "generation": current, "cursor": page[-1][0] if page else cursor,
                    "ackIds": acks, "ackDeletions": deletions, "deletions": sorted(blocked),
                    "events": [json.loads(row[1]) for row in page], "needsPull": generation != current}


def create_server(relay: Relay, token: str, address=("127.0.0.1", 0)):
    if len(token) < 16 or any(c.isspace() for c in token):
        raise ValueError("provide a random authentication token, at least 16 characters")

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # URLs, authorization and user content never enter access logs.

        def do_POST(self):
            if self.path != "/v1/sync":
                self.send_error(404)
                return
            if not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
                self.send_error(401)
                return
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= MAX_BODY:
                    raise ValueError("invalid body size")
                self.connection.settimeout(15)
                raw = self.rfile.read(length)
                if len(raw) != length:
                    raise ValueError("incomplete body")
                body = json.loads(raw)
                if not isinstance(body, dict):
                    raise ValueError("invalid body")
                result = relay.exchange(body)
            except (ValueError, KeyError, TypeError, json.JSONDecodeError):
                self.send_error(400, "Invalid sync request")
                return
            output = json.dumps(result, separators=(",", ":")).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(output)))
            self.end_headers()
            self.wfile.write(output)

    return ThreadingHTTPServer(address, Handler)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8767)
    args = parser.parse_args()
    token = os.environ.get("XIAOKE_SYNC_TOKEN", "")
    # Never create the database in the source tree by default. Operator supplies private storage.
    args.database.parent.mkdir(parents=True, exist_ok=True)
    relay = Relay(str(args.database))
    os.chmod(args.database, 0o600)
    server = create_server(relay, token, ("127.0.0.1", args.port))
    try:
        server.serve_forever()
    finally:
        server.server_close()
        relay.close()


if __name__ == "__main__":
    main()
