import hashlib
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

from server import Relay, create_server


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = str(Path(self.directory.name) / "private.db")
        self.relay = Relay(self.path)
        self.token = "synthetic-test-auth-token"
        self.server = create_server(self.relay, self.token)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/v1/sync"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.relay.close()
        self.directory.cleanup()

    def request(self, events=None, deletions=None, generation=0, cursor=0, token=None):
        body = {"version": 1, "vault": "0" * 32, "device": "fixture", "events": events or [],
                "deletions": deletions or [], "generation": generation, "cursor": cursor}
        req = urllib.request.Request(self.url, data=json.dumps(body).encode(), headers={
            "Authorization": "Bearer " + (token or self.token), "Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=3) as response:
            return json.load(response)

    def event(self, n=1, sources=None):
        ciphertext = "opaque-encrypted-fixture-" + str(n) * 50
        return {"id": hashlib.sha256(str(n).encode()).hexdigest(), "sources": sources or [],
                "ciphertext": ciphertext, "digest": hashlib.sha256(ciphertext.encode()).hexdigest()}

    def test_authenticated_http_durable_ack_and_restart(self):
        event = self.event()
        result = self.request([event])
        self.assertEqual([event["id"]], result["ackIds"])
        duplicate = self.request([event])
        self.assertEqual(result["cursor"], duplicate["cursor"])
        second = Relay(self.path)
        try:
            rows = second.db.execute("SELECT envelope FROM events").fetchall()
            self.assertEqual(1, len(rows))
            self.assertEqual(event, json.loads(rows[0][0]))
        finally:
            second.close()

    def test_wrong_token_and_plaintext_envelope_rejected_without_write(self):
        with self.assertRaises(urllib.error.HTTPError) as auth:
            self.request([self.event()], token="wrong-credential")
        self.assertEqual(401, auth.exception.code)
        event = self.event(); event["plaintext"] = "must not persist"
        with self.assertRaises(urllib.error.HTTPError) as invalid:
            self.request([event])
        self.assertEqual(400, invalid.exception.code)
        self.assertEqual([], self.request()["events"])

    def test_delete_cascade_and_stale_offline_upload_cannot_resurrect(self):
        source = hashlib.sha256(b"source").hexdigest()
        first = self.event(1, [source]); derived = self.event(2, [first["id"]])
        self.request([first, derived])
        deleted = self.request(deletions=[source])
        self.assertEqual([], deleted["events"])
        self.assertTrue({source, first["id"], derived["id"]}.issubset(set(deleted["deletions"])))
        stale = self.request([first, derived])
        self.assertTrue(stale["needsPull"]); self.assertEqual([], stale["ackIds"])
        current = self.request([first, derived], generation=deleted["generation"])
        self.assertEqual([], current["events"]); self.assertEqual([], current["ackIds"])

    def test_identifier_conflict_and_digest_corruption_do_not_overwrite(self):
        original = self.event(); self.request([original])
        conflict = self.event(2); conflict["id"] = original["id"]
        with self.assertRaises(urllib.error.HTTPError):
            self.request([conflict])
        corrupt = self.event(3); corrupt["digest"] = "0" * 64
        with self.assertRaises(urllib.error.HTTPError):
            self.request([corrupt])
        self.assertEqual([original], self.request()["events"])

    def test_parallel_duplicate_uploads_are_idempotent(self):
        results, errors = [], []
        def send():
            try:
                results.append(self.request([self.event()]))
            except Exception as error:
                errors.append(error)
        threads = [threading.Thread(target=send) for _ in range(4)]
        for thread in threads: thread.start()
        for thread in threads: thread.join()
        self.assertEqual([], errors); self.assertEqual(4, len(results))
        self.assertEqual(1, len(self.request()["events"]))


if __name__ == "__main__":
    unittest.main()
