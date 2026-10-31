from concurrent.futures import ThreadPoolExecutor
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import urllib.error
import urllib.request

from runtime import digest
from transport import Client, ModelError, NoRedirect, endpoint, gemini_reply, parse_sse, read_response


class Response:
    def __init__(self, body, status=200):
        self.body = body.encode() if isinstance(body, str) else body
        self.status = status
        self.headers = {"x-request-id": "fiction-request"}
        self.offset, self.closed = 0, False

    def __enter__(self):
        self.offset, self.closed = 0, False
        return self

    def __exit__(self, *_):
        self.closed = True
        return False

    def read(self, limit):
        chunk = self.body[self.offset:self.offset + limit]
        self.offset += len(chunk)
        return chunk

    def read1(self, limit):
        return self.read(limit)


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
        broken.read1 = None
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
        self.assertEqual(client.opener.open.call_count, 6)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [1, 2, 1, 2])
        files = list(self.cache.glob("*.json"))
        self.assertEqual(len(files), 1)
        self.assertTrue(files[0].name.endswith(".failure.json"))
        failure = json.loads(files[0].read_text())
        self.assertEqual(len(failure["attempts"]), 6)
        self.assertEqual([a["native_operation"] for a in failure["attempts"]],
                         ["streamGenerateContent"] * 3 + ["generateContent"] * 3)
        self.assertEqual({attempt["error"] for attempt in failure["attempts"]}, {"provider_error_in_http_200"})
        self.assertNotIn("fiction-private-error", files[0].read_text())

    def test_native_nonstream_fallback_keeps_model_body_cache_key_and_audit(self):
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache)
        client.opener = Mock()
        failed = Response(json.dumps({"error": {"message": "fiction-private-upstream-error"}}))
        success = generated("同模型非流式完成")
        client.opener.open.side_effect = [failed, failed, failed, Response(json.dumps(success))]
        body = {"systemInstruction": {"parts": [{"text": "虚构提示"}]}, "contents": [], "generationConfig": {"seed": 7}}
        with patch("transport.time.sleep"):
            response, key = client.post("gemini-3.8-flash", body)
        self.assertEqual(response, [success])
        self.assertEqual(key, digest({"endpoint": client.base, "protocol": "gemini", "model": "gemini-3.8-flash", "body": body}))
        requests = [call.args[0] for call in client.opener.open.call_args_list]
        self.assertEqual(len(requests), 4)
        for request in requests[:3]:
            self.assertTrue(request.full_url.endswith("/models/gemini-3.8-flash:streamGenerateContent?alt=sse"))
            self.assertEqual(request.get_header("Accept"), "text/event-stream")
        self.assertTrue(requests[3].full_url.endswith("/models/gemini-3.8-flash:generateContent"))
        self.assertEqual(requests[3].get_header("Accept"), "application/json")
        self.assertEqual({request.data for request in requests}, {requests[0].data})
        self.assertEqual({request.get_header("X-goog-api-key") for request in requests}, {"fiction-secret"})
        saved = json.loads((self.cache / (key + ".json")).read_text())
        self.assertEqual(saved["native_operation"], "generateContent")
        self.assertEqual([a["native_operation"] for a in saved["attempts"]],
                         ["streamGenerateContent"] * 3 + ["generateContent"])
        self.assertEqual(saved["response"], [success])
        self.assertNotIn("fiction-private-upstream-error", json.dumps(saved))
        self.assertEqual(client.post("gemini-3.8-flash", body), (response, key))
        self.assertEqual(client.opener.open.call_count, 4)
        # Old successful stream caches have no native_operation and remain reusable.
        saved.pop("native_operation")
        for attempt in saved["attempts"]:
            attempt.pop("native_operation")
        (self.cache / (key + ".json")).write_text(json.dumps(saved))
        self.assertEqual(client.post("gemini-3.8-flash", body), (response, key))
        self.assertEqual(client.opener.open.call_count, 4)

    def test_openai_has_only_three_attempts_and_no_native_route_fallback(self):
        client = Client("https://relay.invalid", "fiction-secret", "openai", self.cache)
        client.opener = Mock()
        client.opener.open.return_value = Response(json.dumps({"error": {"message": "fiction-error"}}))
        with patch("transport.time.sleep"), self.assertRaisesRegex(ModelError, "provider_error_in_http_200"):
            client.post("fiction-judge", {"model": "fiction-judge"})
        requests = [call.args[0] for call in client.opener.open.call_args_list]
        self.assertEqual(len(requests), 3)
        self.assertTrue(all(request.full_url.endswith("/v1/chat/completions") for request in requests))
        self.assertTrue(all(request.get_header("Accept") == "application/json" for request in requests))

    def test_deterministic_http_errors_and_exhausted_rate_limit_do_not_switch_routes(self):
        for status, count in [(400, 1), (401, 1), (403, 1), (404, 1), (422, 1), (429, 3)]:
            with self.subTest(status=status):
                client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache / str(status))
                client.opener = Mock()
                def reject(*_args, code=status, **_kwargs):
                    raise urllib.error.HTTPError("https://relay.invalid", code, "fiction-private-error", {}, io.BytesIO())
                client.opener.open.side_effect = reject
                with patch("transport.time.sleep"), self.assertRaisesRegex(ModelError, "^http_" + str(status) + "$"):
                    client.post("gemini-3.8-flash", {})
                self.assertEqual(client.opener.open.call_count, count)
                self.assertTrue(all(call.args[0].full_url.endswith(":streamGenerateContent?alt=sse")
                                    for call in client.opener.open.call_args_list))

    def test_native_network_and_5xx_exhaustion_can_switch_route(self):
        for name in ("network", "http503"):
            with self.subTest(name=name):
                client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache / name)
                client.opener = Mock()
                errors = [urllib.error.URLError("fiction-private-network") if name == "network" else
                          urllib.error.HTTPError("https://relay.invalid", 503, "unavailable", {}, io.BytesIO()) for _ in range(3)]
                client.opener.open.side_effect = errors + [Response(json.dumps(generated("原模型完成")))]
                with patch("transport.time.sleep"):
                    response, _ = client.post("gemini-4-argon", {})
                self.assertEqual(response, [generated("原模型完成")])
                self.assertEqual(client.opener.open.call_count, 4)
                self.assertTrue(client.opener.open.call_args.args[0].full_url.endswith("/models/gemini-4-argon:generateContent"))

    def test_malformed_native_response_retries_before_cache_then_falls_back(self):
        for failures in (1, 3):
            with self.subTest(failures=failures):
                cache = self.cache / str(failures)
                client = Client("https://relay.invalid", "fiction-secret", "gemini", cache)
                client.opener = Mock()
                bad = generated("fiction-private-malformed-part", "MALFORMED_RESPONSE")
                good = generated("完整的虚构回复")
                client.opener.open.side_effect = [Response(json.dumps(bad)) for _ in range(failures)] + [Response(json.dumps(good))]
                body = {"contents": [{"role": "user", "parts": [{"text": "虚构长对话探针"}]}]}
                with patch("transport.time.sleep"):
                    response, key = client.post("gemini-4-argon", body)
                self.assertEqual(response, [good])
                requests = [call.args[0] for call in client.opener.open.call_args_list]
                self.assertEqual(len(requests), failures + 1)
                self.assertEqual({request.data for request in requests}, {requests[0].data})
                self.assertTrue(all("/models/gemini-4-argon:" in request.full_url for request in requests))
                expected = "generateContent" if failures == 3 else "streamGenerateContent"
                saved = json.loads((cache / (key + ".json")).read_text())
                self.assertEqual(saved["native_operation"], expected)
                self.assertEqual([a["error"] for a in saved["attempts"][:-1]], ["malformed_response"] * failures)
                self.assertEqual(saved["response"], [good])
                self.assertFalse(any("fiction-private-malformed-part" in p.read_text() for p in cache.glob("*.json")))
                self.assertEqual(client.post("gemini-4-argon", body), (response, key))
                self.assertEqual(client.opener.open.call_count, failures + 1)

    def test_cached_malformed_response_is_preserved_then_replaced_with_valid_result(self):
        client = self.client()
        body = {"contents": []}
        key = digest({"endpoint": client.base, "protocol": "gemini", "model": "gemini-4-argon", "body": body})
        response = [generated("fiction-private-old-malformed", "MALFORMED_RESPONSE")]
        old = {"request_hash": key, "request": body, "model": "gemini-4-argon", "protocol": "gemini",
               "response": response, "response_hash": digest(response), "attempts": [{"status": 200, "seconds": 1.5}]}
        path = self.cache / (key + ".json")
        raw = json.dumps(old, ensure_ascii=False, indent=2).encode()
        path.write_bytes(raw)
        client.opener = Mock()
        good = generated("完整结果")
        client.opener.open.return_value = Response(json.dumps(good))
        result, actual_key = client.post("gemini-4-argon", body)
        self.assertEqual((result, actual_key), ([good], key))
        destination = self.cache / "rejected-responses" / (key + "-" + old["response_hash"] + ".json")
        self.assertEqual(destination.read_bytes(), raw)
        saved = json.loads(path.read_text())
        self.assertEqual(saved["response"], [good])
        self.assertEqual(saved["attempts"][0]["error"], "malformed_response")
        self.assertTrue(saved["attempts"][0]["cached"])
        self.assertEqual(saved["attempts"][0]["quarantine"], destination.relative_to(self.cache).as_posix())
        self.assertNotIn("fiction-private-old-malformed", json.dumps(saved))
        self.assertEqual(client.opener.open.call_count, 1)

    def test_quarantine_collision_preserves_both_distinct_original_audits(self):
        client = self.client()
        key = digest({"endpoint": client.base, "protocol": "gemini", "model": "gemini-4-argon", "body": {}})
        response = [generated("fiction-private-malformed", "MALFORMED_RESPONSE")]
        old = {"request_hash": key, "response": response, "response_hash": digest(response), "attempts": [{"status": 200}]}
        path = self.cache / (key + ".json")
        old_bytes = json.dumps(old).encode()
        path.write_bytes(old_bytes)
        rejected = self.cache / "rejected-responses"
        rejected.mkdir()
        previous = rejected / (key + "-" + old["response_hash"] + ".json")
        previous_bytes = json.dumps(old | {"attempts": [{"status": 200, "seconds": 5}]}).encode()
        previous.write_bytes(previous_bytes)
        client.opener = Mock()
        client.opener.open.return_value = Response(json.dumps(generated("完整结果")))
        client.post("gemini-4-argon", {})
        self.assertEqual(previous.read_bytes(), previous_bytes)
        self.assertEqual({p.read_bytes() for p in rejected.iterdir()}, {old_bytes, previous_bytes})
        self.assertEqual(len(list(rejected.iterdir())), 2)

    def test_unknown_finish_is_not_retried_or_treated_as_stop(self):
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache)
        client.opener = Mock()
        client.opener.open.return_value = Response(json.dumps(generated("fiction-private-body", "UNRECOGNIZED_FINISH")))
        bridge = Mock()
        bridge.call.return_value = bridge_result("fiction-private-body", "UNRECOGNIZED_FINISH")
        with patch("transport.time.sleep") as sleep, self.assertRaisesRegex(ModelError, "^incomplete_generation_unknown$"):
            gemini_reply(client, bridge, "gemini-4-argon", {})
        self.assertEqual(client.opener.open.call_count, 1)
        sleep.assert_not_called()

    def test_read_deadline_stops_dripping_progress_and_reduces_socket_timeout(self):
        clock = [0.0]
        response = Response(b"")
        sock = Mock()
        response.fp = SimpleNamespace(raw=SimpleNamespace(_sock=sock))
        def drip(_limit):
            clock[0] += 0.4
            return b": keepalive\n\n"
        response.read1 = Mock(side_effect=drip)
        with patch("transport.time.monotonic", side_effect=lambda: clock[0]), self.assertRaises(TimeoutError):
            with response:
                read_response(response, deadline=1.0, native_sse=True)
        self.assertTrue(response.closed)
        self.assertEqual(response.read1.call_count, 3)
        timeouts = [call.args[0] for call in sock.settimeout.call_args_list]
        self.assertEqual(len(timeouts), 3)
        self.assertAlmostEqual(timeouts[0], 1.0)
        self.assertAlmostEqual(timeouts[1], 0.6)
        self.assertAlmostEqual(timeouts[2], 0.2)

    def test_native_terminal_event_closes_before_keepalive_and_preserves_usage_utf8(self):
        event = generated("完整中文回复 finishReason 不会在正文里终止")
        event["usageMetadata"] = {"promptTokenCount": 17, "candidatesTokenCount": 9}
        first = {"candidates": [{"content": {"parts": [{"text": "正文中的 finishReason 和 [DONE] 都不是结束信号"}]}}]}
        prefix = ("data: " + json.dumps(first, ensure_ascii=False) + "\r\n\r\n").encode()
        terminal = ("data: " + json.dumps(event, ensure_ascii=False) + "\r\n\r\n").encode()
        raw = prefix + terminal
        chinese = raw.index("完整".encode())
        # Split within UTF-8, within JSON, and between CR/LF event delimiters.
        cuts = [13, len(prefix) - 1, len(prefix), chinese + 1, chinese + 2, len(raw) - 3, len(raw) - 1]
        chunks, start = [], 0
        for end in cuts + [len(raw)]:
            chunks.append(raw[start:end]); start = end
        response = Response(b"")
        response.read1 = Mock(side_effect=chunks + [AssertionError("must not wait for keepalive EOF")])
        with patch("transport.time.monotonic", return_value=0):
            with response:
                collected = read_response(response, deadline=10, native_sse=True)
        self.assertTrue(response.closed)
        events = parse_sse(collected.decode("utf-8"))
        self.assertEqual(events, [first, event])
        self.assertEqual(events[-1]["usageMetadata"], event["usageMetadata"])
        self.assertLessEqual(response.read1.call_count, len(chunks))

    def test_done_event_is_terminal_only_after_complete_event_delimiter(self):
        first = generated("虚构正文")
        first["candidates"][0].pop("finishReason")
        stream = ("data: " + json.dumps(first) + "\n\ndata: [DONE]\n\n").encode()
        response = Response(b"")
        response.read1 = Mock(side_effect=[stream[:-1], stream[-1:], AssertionError("EOF must not be read")])
        with patch("transport.time.monotonic", return_value=0):
            with response:
                collected = read_response(response, deadline=10, native_sse=True)
        self.assertEqual(collected, stream)
        self.assertEqual(response.read1.call_count, 2)
        self.assertTrue(response.closed)

    def test_read_compatibility_and_size_limit_do_not_split_utf8(self):
        response = Response("虚构中文".encode())
        response.read1 = None
        with patch("transport.time.monotonic", return_value=0):
            self.assertEqual(read_response(response, deadline=10).decode(), "虚构中文")
            with self.assertRaisesRegex(ModelError, "response_too_large"):
                read_response(Response(b"x" * 33), deadline=10, limit=32)

    def test_total_deadline_retries_then_falls_back_without_changing_body(self):
        clock = [0.0]
        responses = []
        def opening(request, **_kwargs):
            response = Response(json.dumps(generated("非流式完成")))
            if ":streamGenerateContent" in request.full_url:
                def drip(_limit):
                    clock[0] += 0.6
                    return b": ongoing\n\n"
                response.read1 = Mock(side_effect=drip)
            responses.append(response)
            return response
        client = Client("https://relay.invalid", "fiction-secret", "gemini", self.cache, timeout=1, retries=1)
        client.opener = Mock()
        client.opener.open.side_effect = opening
        body = {"contents": [{"role": "user", "parts": [{"text": "虚构问题"}]}]}
        with patch("transport.time.monotonic", side_effect=lambda: clock[0]), patch("transport.time.sleep"):
            result, key = client.post("gemini-3.8-flash", body)
        self.assertEqual(result, [generated("非流式完成")])
        requests = [call.args[0] for call in client.opener.open.call_args_list]
        self.assertEqual(len(requests), 3)
        self.assertTrue(requests[-1].full_url.endswith(":generateContent"))
        self.assertEqual({request.data for request in requests}, {requests[0].data})
        self.assertTrue(all(response.closed for response in responses))
        saved = json.loads((self.cache / (key + ".json")).read_text())
        self.assertEqual([a.get("error") for a in saved["attempts"][:-1]], ["network_or_timeout"] * 2)

    def test_openai_dripping_body_obeys_same_total_deadline(self):
        clock = [0.0]
        responses = []
        def opening(*_args, **_kwargs):
            response = Response(b"")
            def drip(_limit):
                clock[0] += 0.6
                return b" "
            response.read1 = Mock(side_effect=drip)
            responses.append(response)
            return response
        client = Client("https://relay.invalid", "fiction-secret", "openai", self.cache, timeout=1, retries=0)
        client.opener = Mock()
        client.opener.open.side_effect = opening
        with patch("transport.time.monotonic", side_effect=lambda: clock[0]), self.assertRaisesRegex(ModelError, "network_or_timeout"):
            client.post("fiction-judge", {})
        self.assertEqual(client.opener.open.call_count, 1)
        self.assertTrue(responses[0].closed)

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
        client.opener.open.side_effect = [Response(json.dumps(incomplete)), Response(json.dumps(incomplete)),
                                          Response(json.dumps(generated("重试完成")))]
        bridge = Mock()

        def parse(events):
            candidate = events["responses"][-1]["candidates"][0]
            return bridge_result(candidate["content"]["parts"][0]["text"], candidate.get("finishReason", ""))

        bridge.call.side_effect = parse
        with self.assertRaisesRegex(ModelError, "incomplete_stream"):
            gemini_reply(client, bridge, "gemini-4-argon", {"contents": []})
        self.assertEqual(gemini_reply(client, bridge, "gemini-4-argon", {"contents": []})["text"], "重试完成")
        self.assertEqual(client.opener.open.call_count, 3)

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
