"""Authenticated model calls, bounded retries and content-addressed private replay."""
import copy
import http.client
import json
import re
import socket
import time
import threading
import urllib.error
import urllib.parse
import urllib.request
from runtime import canonical, digest, save


class ModelError(RuntimeError):
    """Only a safe error code; never include a provider body, URL or credentials."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def endpoint(base, protocol):
    parsed = urllib.parse.urlsplit(base.strip())
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("模型地址必须是不带密钥、查询参数的 HTTPS 地址")
    path = parsed.path.rstrip("/")
    if protocol == "gemini" and path not in ("", "/v1", "/v1beta"):
        raise ValueError("被测模型使用生产 Google 协议，请提供根地址或 /v1beta")
    return base.strip().rstrip("/") + (("/v1beta" if protocol == "gemini" else "/v1") if not path else "")


def parse_sse(text):
    if text.lstrip().startswith("{"):
        return [json.loads(text)]
    events, data = [], []
    for line in re.split(r"\r\n|\r|\n", text) + [""]:
        if not line:
            if data:
                payload = "\n".join(data).strip()
                if payload != "[DONE]":
                    events.append(json.loads(payload))
                data = []
        elif line.startswith("data:"):
            data.append(line[5:].lstrip())
    if not events:
        raise ModelError("empty_event_stream")
    return events


class Client:
    def __init__(self, base, key, protocol, cache, timeout=210, retries=2):
        self.base = endpoint(base, protocol)
        if not key or key.strip() != key:
            raise ValueError("模型密钥缺失或有首尾空白")
        self.key, self.protocol, self.cache = key, protocol, cache
        self.timeout, self.retries = timeout, retries
        self.opener = urllib.request.build_opener(NoRedirect())
        self.locks, self.locks_guard = {}, threading.Lock()

    def post(self, model, body):
        identity = digest({"model": model, "body": body})
        with self.locks_guard:
            lock = self.locks.setdefault(identity, threading.Lock())
        with lock:
            return self._post(model, body)

    def _post(self, model, body):
        if not model or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-/" for c in model):
            raise ValueError("模型名称格式无效")
        request_key = digest({"endpoint": self.base, "protocol": self.protocol, "model": model, "body": body})
        path = self.cache / (request_key + ".json")
        if path.exists():
            saved = json.loads(path.read_text(encoding="utf-8"))
            if saved.get("request_hash") != request_key or digest(saved["response"]) != saved.get("response_hash"):
                raise ModelError("cache_integrity_error")
            return saved["response"], request_key
        # Both native routes use the same model, prompt and logical cache identity.
        operations = ("streamGenerateContent", "generateContent") if self.protocol == "gemini" else (None,)
        attempts = []
        for operation in operations:
            suffix = ("/models/" + urllib.parse.quote(model, safe="") + ":" + operation +
                      ("?alt=sse" if operation == "streamGenerateContent" else "")) if operation else "/chat/completions"
            headers = {"Content-Type": "application/json", "Accept": "text/event-stream" if operation == "streamGenerateContent" else "application/json"}
            headers["x-goog-api-key" if operation else "Authorization"] = self.key if operation else "Bearer " + self.key
            for attempt in range(self.retries + 1):
                started = time.monotonic()
                try:
                    req = urllib.request.Request(self.base + suffix, data=canonical(body).encode(), headers=headers, method="POST")
                    with self.opener.open(req, timeout=self.timeout) as response:
                        raw = response.read(24 * 1024 * 1024 + 1)
                        if len(raw) > 24 * 1024 * 1024:
                            raise ModelError("response_too_large")
                        decoded = raw.decode("utf-8")
                        result = parse_sse(decoded) if self.protocol == "gemini" else json.loads(decoded)
                        has_error = any("error" in event for event in result) if isinstance(result, list) else "error" in result
                        if has_error:
                            raise ModelError("provider_error_in_http_200")
                        if self.protocol == "gemini" and any(event.get("promptFeedback", {}).get("blockReason") for event in result):
                            raise ModelError("prompt_blocked")
                        if self.protocol == "gemini" and not any(
                            event.get("candidates", [{}])[0].get("finishReason")
                            for event in result if event.get("candidates")
                        ):
                            raise ModelError("incomplete_stream")
                        attempts.append({"status": response.status, "native_operation": operation,
                                         "seconds": round(time.monotonic() - started, 3)})
                        metadata = {k: response.headers[k] for k in ("x-request-id", "request-id", "date") if response.headers.get(k)}
                        save(path, {"request_hash": request_key, "request": body, "protocol": self.protocol,
                                    "model": model, "response": result, "response_hash": digest(result),
                                    "native_operation": operation, "metadata": metadata, "attempts": attempts})
                        return result, request_key
                except urllib.error.HTTPError as error:
                    code = "http_" + str(error.code)
                    retry = error.code == 429 or 500 <= error.code < 600
                    error.close()
                except (urllib.error.URLError, TimeoutError, socket.timeout, ConnectionError, http.client.HTTPException):
                    code, retry = "network_or_timeout", True
                except (ValueError, UnicodeError):
                    code, retry = "invalid_response", False
                except ModelError as error:
                    code = str(error)
                    retry = code in {"provider_error_in_http_200", "incomplete_stream"}
                attempts.append({"error": code, "native_operation": operation,
                                 "seconds": round(time.monotonic() - started, 3)})
                save(self.cache / (request_key + ".failure.json"), {"request_hash": request_key, "attempts": attempts})
                if not retry:
                    raise ModelError(code)
                if attempt == self.retries:
                    alternate_route = operation == "streamGenerateContent" and (
                        code in {"provider_error_in_http_200", "incomplete_stream", "network_or_timeout"} or code.startswith("http_5")
                    )
                    if alternate_route:
                        break
                    raise ModelError(code)
                time.sleep(min(2 ** attempt, 8))
        raise ModelError("retry_exhausted")


def gemini_reply(client, bridge, model, request):
    original = copy.deepcopy(request)
    request_hashes, events, seen = [], [], set()
    body = copy.deepcopy(original)
    for _ in range(4):
        response, key = client.post(model, body)
        request_hashes.append(key)
        one = bridge.call({"op": "response", "responses": response})
        if not one["finish"]:
            raise ModelError("incomplete_stream")
        events.extend(response)
        if one["finish"] == "CONTINUATION":
            token = one.get("continuation")
            if not token or token in seen or len(token) > 262144:
                raise ModelError("invalid_continuation")
            seen.add(token)
            body = copy.deepcopy(original)
            body["continuationToken"] = token
            continue
        aggregate = bridge.call({"op": "response", "responses": events})
        if one["finish"] != "STOP":
            known = {"MAX_TOKENS", "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "MALFORMED_FUNCTION_CALL", "OTHER"}
            reason = one["finish"] if one["finish"] in known else "unknown"
            raise ModelError("incomplete_generation_" + reason)
        if aggregate["toolCalls"]:
            raise ModelError("unexpected_tool_call_in_reply_only_eval")
        if not aggregate["text"].strip():
            raise ModelError("empty_visible_reply")
        return aggregate | {"request_hashes": request_hashes}
    raise ModelError("continuation_limit")


def judge_reply(client, bridge, model, messages, seed=20261031):
    if client.protocol == "openai":
        body = {"model": model, "messages": messages, "temperature": 0, "top_p": 1,
                "seed": seed, "max_tokens": 16384, "response_format": {"type": "json_object"}}
        response, key = client.post(model, body)
        choices = response.get("choices", [])
        if not choices or choices[0].get("finish_reason") != "stop":
            raise ModelError("judge_incomplete_generation")
        text = choices[0].get("message", {}).get("content")
        if not isinstance(text, str) or not text.strip():
            raise ModelError("judge_empty_reply")
        return text, [key]
    body = {"systemInstruction": {"parts": [{"text": messages[0]["content"]}]},
            "contents": [{"role": "user", "parts": [{"text": messages[1]["content"]}]}],
            "generationConfig": {"temperature": 0, "topP": 1, "seed": seed,
                                 "maxOutputTokens": 16384, "responseMimeType": "application/json"}}
    reply = gemini_reply(client, bridge, model, body)
    return reply["text"], reply["request_hashes"]
