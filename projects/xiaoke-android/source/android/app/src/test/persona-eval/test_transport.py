from concurrent.futures import ThreadPoolExecutor
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request

from runtime import digest
from transport import Client, ModelError, NoRedirect, endpoint, gemini_reply, parse_sse


class Response:
    def __init__(self, body, status=200):
        self.body = body.encode() if isinstance(body, str) else body
        self.status = status
        self.headers = {"x-request-id": "fiction-request"}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def read(self, limit):
        return self.body[:limit]


def generated(text, finish="STOP", continuation=None):
    candidate = {"content": {"role": "model", "parts": [{"text": text}]}, "finishReason": finish}
    if continuation is not None:
        candidate["continuationToken"] = continuation
    return {"candidates": [candidate]}


def bridge_result(text, finish="STOP", continuation=None, calls=None):
    return {"text": text, "parts": [{"text": text}], "finish": finish,
            "continuation": continuation, "toolCalls": calls or [], "usage": {}, "trimmed": False}


class TransportTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.cache = Path(self.temp.name)

    def client(self, protocol="gemini"):
        return Client("https://relay.invalid", "fiction-secret", protocol, self.cache, retries=0)

    def test_https_endpoint_and_redirect_rejection(self):
        self.assertEqual(endpoint("https://relay.invalid/", "gemini"), "https://relay.invalid/v1beta")
        self.assertEqual(endpoint("https://relay.invalid/v1/", "gemini"), "https://relay.invalid/v1")
        for value in ["http://relay.invalid", "https://user:secret@relay.invalid", "https://relay.invalid?key=secret",
                      "https://relay.invalid/#secret", "https://relay.invalid/arbitrary"]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                endpoint(value, "gemini")
        request = urllib.request.Request("https://relay.invalid/v1beta", headers={"x-goog-api-key": "fiction-secret"})
        self.assertIsNone(NoRedirect().redirect_request(request, None, 302, "moved", {}, "https://other.invalid"))

    def test_sse_multiline_keepalive_done_and_plain_json(self):
        expected = generated("虚构回复")
        encoded = json.dumps(expected, ensure_ascii=False)
        stream = ": keepalive\nevent: message\ndata: " + encoded + "\n\ndata: [DONE]\n\n"
        self.assertEqual(parse_sse(stream), [expected])
        self.assertEqual(parse_sse(encoded), [expected])
        self.assertEqual(parse_sse('data: {\ndata: "fiction": 1\ndata: }\n'), [{"fiction": 1}])
        with self.assertRaises(ModelError):
            parse_sse(": heartbeat\n\n")

    def test_sse_preserves_unicode_line_separators_inside_json_strings(self):
        event = generated("虚构第一行\u2028第二行\u2029第三行\u0085末行")
        stream = "data: " + json.dumps(event, ensure_ascii=False) + "\r\n\r\n"
        self.assertEqual(parse_sse(stream), [event])

    def test_parallel_identical_requests_share_one_paid_response(self):
        client = self.client()
        client.opener = Mock()
        start = threading.Barrier(2)
        client.opener.open.return_value = Response(json.dumps(generated("共享虚构回复")))

        def request():
            start.wait(timeout=5)
            return client.post("gemini-4-argon", {"contents": []})

        with ThreadPoolExecutor(max_workers=2) as executor:
            futures = [executor.submit(request) for _ in range(2)]
            results = [f.result(timeout=10) for f in futures]
        self.assertEqual(results[0], results[1])
        self.assertEqual(client.opener.open.call_count, 1)

    def test_native_request_replay_and_corrupt_cache_rejection(self):
        client = self.client()
        expected = generated("虚构回复")
        client.opener = Mock()
        client.opener.open.return_value = Response("data: " + json.dumps(expected) + "\n\n")
        body = {"contents": [{"role": "user", "parts": [{"text": "虚构提示"}]}], "generationConfig": {"seed": 7}}
        result, key = client.post("gemini-4-argon", body)
        self.assertEqual(result, [expected])
        request = client.opener.open.call_args.args[0]
        self.assertTrue(request.full_url.endswith("/v1beta/models/gemini-4-argon:streamGenerateContent?alt=sse"))
        self.assertEqual(request.get_header("X-goog-api-key"), "fiction-secret")
        self.assertEqual(json.loads(request.data), body)
        self.assertEqual(client.post("gemini-4-argon", body), (result, key))
        self.assertEqual(client.opener.open.call_count, 1)
        saved = json.loads((self.cache / (key + ".json")).read_text())
        self.assertNotIn("fiction-secret", json.dumps(saved))
        self.assertEqual(saved["request_hash"], key)
        self.assertEqual(saved["response_hash"], digest(result))
        saved["response"] = [generated("被意外修改")]
        (self.cache / (key + ".json")).write_text(json.dumps(saved))
        with self.assertRaisesRegex(ModelError, "cache_integrity_error"):
            client.post("gemini-4-argon", body)
        self.assertEqual(client.opener.open.call_count, 1)

    def test_failures_never_echo_provider_body_credentials_or_url(self):
        client = self.client()
        client.opener = Mock()
        sensitive = "fiction-private-body-and-key"
        client.opener.open.side_effect = urllib.error.HTTPError(
            "https://relay.invalid/private-path", 403, sensitive, {}, io.BytesIO(sensitive.encode()))
        with self.assertRaisesRegex(ModelError, "^http_403$"):
            client.post("gemini-4-argon", {})
        failure = next(self.cache.glob("*.failure.json")).read_text()
        self.assertNotIn(sensitive, failure)
        self.assertNotIn("relay.invalid", failure)
        client.opener.open.side_effect = None
        client.opener.open.return_value = Response(json.dumps({"error": {"message": sensitive}}))
        with self.assertRaisesRegex(ModelError, "^provider_error_in_http_200$"):
            client.post("gemini-4-argon", {"distinct": 1})
        self.assertFalse(list(self.cache.glob("*.json")) and any(sensitive in p.read_text() for p in self.cache.glob("*.json")))

    def test_incomplete_http_body_is_a_safe_retryable_transport_failure(self):
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache, retries=1)
        broken = Mock()
        broken.__enter__ = Mock(return_value=broken)
        broken.__exit__ = Mock(return_value=False)
        broken.read.side_effect = http.client.IncompleteRead(b"fiction-private-partial", 200)
        client.opener = Mock()
        client.opener.open.side_effect = [broken, Response(json.dumps(generated("重试成功")))]
        with patch("transport.time.sleep"):
            result, _ = client.post("gemini-4-argon", {})
        self.assertEqual(result, [generated("重试成功")])
        self.assertEqual(client.opener.open.call_count, 2)
        self.assertFalse(any("fiction-private-partial" in p.read_text() for p in self.cache.glob("*.json")))

    def test_retry_reuses_model_and_body_without_model_fallback(self):
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache, retries=1)
        client.opener = Mock()
        client.opener.open.side_effect = [urllib.error.HTTPError("https://relay.invalid", 429, "busy", {}, io.BytesIO()),
                                          Response(json.dumps(generated("好了")))]
        with patch("transport.time.sleep") as sleep:
            client.post("gemini-4-argon", {"contents": []})
        sleep.assert_called_once()
        requests = [call.args[0] for call in client.opener.open.call_args_list]
        self.assertEqual(requests[0].full_url, requests[1].full_url)
        self.assertEqual(requests[0].data, requests[1].data)

    def test_http_200_transient_errors_retry_same_request_and_preserve_attempts(self):
        cases = [({"error": {"message": "fiction-private-relay-error"}}, "provider_error_in_http_200"),
                 ({"candidates": [{"content": {"parts": [{"text": "fiction-private-partial"}]}}]}, "incomplete_stream")]
        for index, (failure, code) in enumerate(cases):
            with self.subTest(code=code):
                cache = self.cache / str(index)
                client = Client("https://relay.invalid", "fiction-secret", "gemini", cache)
                client.opener = Mock()
                success = generated("虚构重试成功")
                client.opener.open.side_effect = [Response(json.dumps(failure)), Response(json.dumps(success))]
                body = {"contents": [{"role": "user", "parts": [{"text": "虚构问题"}]}]}
                with patch("transport.time.sleep") as sleep:
                    response, key = client.post("gemini-3.8-flash", body)
                self.assertEqual(response, [success])
                self.assertEqual(client.opener.open.call_count, 2)
                sleep.assert_called_once_with(1)
                requests = [call.args[0] for call in client.opener.open.call_args_list]
                self.assertEqual(requests[0].full_url, requests[1].full_url)
                self.assertEqual(requests[0].data, requests[1].data)
                saved = json.loads((cache / (key + ".json")).read_text())
                self.assertEqual(saved["response"], [success])
                self.assertEqual(len(saved["attempts"]), 2)
                self.assertEqual(saved["attempts"][0]["error"], code)
                self.assertEqual(saved["attempts"][1]["status"], 200)
                failure_audit = json.loads((cache / (key + ".failure.json")).read_text())
                self.assertEqual(failure_audit["attempts"][0]["error"], code)
                self.assertFalse(any("fiction-private-" in p.read_text() for p in cache.glob("*.json")))
                self.assertFalse(any("fiction-secret" in p.read_text() for p in cache.glob("*.json")))
                self.assertEqual(client.post("gemini-3.8-flash", body), (response, key))
                self.assertEqual(client.opener.open.call_count, 2)

    def test_http_200_transient_retries_are_bounded_without_success_cache(self):
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache)
        client.opener = Mock()
        client.opener.open.return_value = Response(json.dumps({"error": {"message": "fiction-private-error"}}))
        with patch("transport.time.sleep") as sleep, self.assertRaisesRegex(ModelError, "^provider_error_in_http_200$"):
            client.post("gemini-3.8-flash", {})
        self.assertEqual(client.opener.open.call_count, 3)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2])
        files = list(self.cache.glob("*.json"))
        self.assertEqual(len(files), 1)
        self.assertTrue(files[0].name.endswith(".failure.json"))
        failure = json.loads(files[0].read_text())
        self.assertEqual(len(failure["attempts"]), 3)
        self.assertEqual({attempt["error"] for attempt in failure["attempts"]}, {"provider_error_in_http_200"})
        self.assertNotIn("fiction-private-error", files[0].read_text())

    def test_schema_and_safety_failures_do_not_retry(self):
        for index, (body, code) in enumerate([
            ("not valid JSON", "empty_event_stream"),
            ('{"invalid":', "invalid_response"),
            (json.dumps({"promptFeedback": {"blockReason": "SAFETY"}}), "prompt_blocked"),
        ]):
            with self.subTest(code=code):
                client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache / str(index))
                client.opener = Mock()
                client.opener.open.return_value = Response(body)
                with patch("transport.time.sleep") as sleep, self.assertRaisesRegex(ModelError, "^" + code + "$"):
                    client.post("gemini-3.8-flash", {})
                self.assertEqual(client.opener.open.call_count, 1)
                sleep.assert_not_called()
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache / "safety-finish")
        client.opener = Mock()
        client.opener.open.return_value = Response(json.dumps(generated("", "SAFETY")))
        bridge = Mock()
        bridge.call.return_value = bridge_result("", "SAFETY")
        with patch("transport.time.sleep") as sleep, self.assertRaisesRegex(ModelError, "^incomplete_generation_SAFETY$"):
            gemini_reply(client, bridge, "gemini-3.8-flash", {})
        self.assertEqual(client.opener.open.call_count, 1)
        sleep.assert_not_called()

    def test_continuation_preserves_original_prompt_and_merges_native_events(self):
        first, second = generated("前半", "CONTINUATION", "fixture-token"), generated("后半")
        client, bridge = Mock(), Mock()
        client.post.side_effect = [([first], "first-hash"), ([second], "second-hash")]
        bridge.call.side_effect = [bridge_result("前半", "CONTINUATION", "fixture-token"),
                                   bridge_result("后半"), bridge_result("前半后半")]
        original = {"systemInstruction": {"parts": [{"text": "虚构系统提示"}]}, "contents": [{"role": "user", "parts": [{"text": "问题"}]}]}
        result = gemini_reply(client, bridge, "gemini-4-argon", original)
        self.assertEqual(result["text"], "前半后半")
        self.assertEqual(result["request_hashes"], ["first-hash", "second-hash"])
        self.assertNotIn("continuationToken", original)
        sent = [c.args[1] for c in client.post.call_args_list]
        self.assertEqual(sent[0], original)
        self.assertEqual(sent[1], original | {"continuationToken": "fixture-token"})
        self.assertEqual(bridge.call.call_args_list[-1].args[0]["responses"], [first, second])

    def test_unfinished_repeated_token_and_tool_calls_are_not_scored(self):
        for response, code in [(bridge_result("截断", ""), "incomplete_stream"),
                               (bridge_result("", "CONTINUATION"), "invalid_continuation")]:
            client, bridge = Mock(), Mock()
            client.post.return_value = ([{}], "hash")
            bridge.call.return_value = response
            with self.subTest(code=code), self.assertRaisesRegex(ModelError, code):
                gemini_reply(client, bridge, "gemini-4-argon", {})
        client, bridge = Mock(), Mock()
        client.post.return_value = ([{}], "hash")
        bridge.call.return_value = bridge_result("", "CONTINUATION", "same-token")
        with self.assertRaisesRegex(ModelError, "invalid_continuation"):
            gemini_reply(client, bridge, "gemini-4-argon", {})
        self.assertEqual(client.post.call_count, 2)
        bridge.call.return_value = bridge_result("调用工具", calls=[{"name": "device_action"}])
        with self.assertRaisesRegex(ModelError, "unexpected_tool_call"):
            gemini_reply(client, bridge, "gemini-4-argon", {})

    def test_truncated_stream_does_not_poison_resume_cache(self):
        client = self.client()
        client.opener = Mock()
        incomplete = {"candidates": [{"content": {"parts": [{"text": "只到半句"}]}}]}
        client.opener.open.side_effect = [Response(json.dumps(incomplete)), Response(json.dumps(generated("重试完成")))]
        bridge = Mock()

        def parse(events):
            candidate = events["responses"][-1]["candidates"][0]
            return bridge_result(candidate["content"]["parts"][0]["text"], candidate.get("finishReason", ""))

        bridge.call.side_effect = parse
        with self.assertRaisesRegex(ModelError, "incomplete_stream"):
            gemini_reply(client, bridge, "gemini-4-argon", {"contents": []})
        self.assertEqual(gemini_reply(client, bridge, "gemini-4-argon", {"contents": []})["text"], "重试完成")
        self.assertEqual(client.opener.open.call_count, 2)

    def test_untrusted_finish_reason_cannot_escape_safe_error_code(self):
        client, bridge = Mock(), Mock()
        client.post.return_value = ([{}], "hash")
        bridge.call.return_value = bridge_result("无效", "fiction-private-provider-echo")
        with self.assertRaises(ModelError) as caught:
            gemini_reply(client, bridge, "gemini-4-argon", {})
        self.assertNotIn("fiction-private-provider-echo", str(caught.exception))
        self.assertRegex(str(caught.exception), r"^[a-z0-9_]+$")


if __name__ == "__main__":
    unittest.main()
