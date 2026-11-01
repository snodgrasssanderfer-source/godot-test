"""Model calls with retries and a request-hash cache, so a rerun of the same run never pays twice."""
import copy
import http.client
import json
import time
import urllib.error
import urllib.parse
import urllib.request

from runtime import canonical, digest, read, save


class ModelError(RuntimeError):
    pass


class ModelRefused(ModelError):
    """Retrying the same request cannot change the answer."""


def endpoint(base, protocol):
    base = base.strip().rstrip("/")
    if not base:
        raise ValueError("缺少模型地址")
    if base.endswith(("/v1beta", "/v1")):
        return base
    return base + ("/v1beta" if protocol == "gemini" else "/v1")


def finished(event):
    candidates = event.get("candidates") or [{}]
    return bool(candidates[0].get("finishReason"))


def read_sse(response, deadline):
    """Stop at the terminal event: some relays leave the stream open after it."""
    events, data = [], []
    for raw in response:
        if time.monotonic() > deadline:
            raise TimeoutError("超过整次请求时限")
        line = raw.decode("utf-8").rstrip("\r\n")
        if line.startswith("data:"):
            data.append(line[5:].lstrip())
            continue
        if line or not data:
            continue
        payload, data = "\n".join(data).strip(), []
        if payload == "[DONE]":
            break
        events.append(json.loads(payload))
        if finished(events[-1]) and "usageMetadata" in events[-1]:
            break
    if data and "\n".join(data).strip() != "[DONE]":
        events.append(json.loads("\n".join(data)))
    return events


class Client:
    def __init__(self, base, key, protocol, cache, timeout=210, retries=2):
        if not key:
            raise ValueError("缺少模型密钥")
        self.base, self.key, self.protocol = endpoint(base, protocol), key, protocol
        self.cache, self.timeout, self.retries = cache, timeout, retries

    def _once(self, model, body, operation):
        if self.protocol == "gemini":
            url = f"{self.base}/models/{urllib.parse.quote(model, safe='')}:{operation}" + ("?alt=sse" if operation == "streamGenerateContent" else "")
            headers = {"x-goog-api-key": self.key, "Accept": "text/event-stream" if operation == "streamGenerateContent" else "application/json"}
        else:
            url, headers = self.base + "/chat/completions", {"Authorization": "Bearer " + self.key}
        request = urllib.request.Request(url, data=canonical(body).encode(), method="POST",
                                         headers={"Content-Type": "application/json", **headers})
        started = time.monotonic()
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            if operation == "streamGenerateContent":
                result = read_sse(response, started + self.timeout)
            else:
                value = json.loads(response.read().decode("utf-8"))
                result = [value] if self.protocol == "gemini" else value
        if self.protocol == "gemini":
            errors = [e["error"] for e in result if "error" in e]
            if errors:
                raise ModelError(f"中转在 HTTP 200 里报错：{json.dumps(errors[0], ensure_ascii=False)[:300]}")
            blocked = [e["promptFeedback"]["blockReason"] for e in result if e.get("promptFeedback", {}).get("blockReason")]
            if blocked:
                raise ModelRefused(f"提示词被拦截：{blocked[0]}")
            if not any(finished(e) for e in result):
                raise ModelError("流在结束事件前断开")
        return result, round(time.monotonic() - started, 3)

    def post(self, model, body):
        key = digest({"base": self.base, "model": model, "body": body})
        path = self.cache / (key + ".json")
        if path.exists():
            return read(path)["response"], key
        operations = ("streamGenerateContent", "generateContent") if self.protocol == "gemini" else ("chat",)
        attempts = []
        for operation in operations:
            for attempt in range(self.retries + 1):
                try:
                    result, seconds = self._once(model, body, operation)
                    attempts.append({"operation": operation, "seconds": seconds})
                    save(path, {"model": model, "request": body, "response": result, "attempts": attempts})
                    return result, key
                except urllib.error.HTTPError as error:
                    detail = error.read()[:500].decode("utf-8", "replace")
                    failure = ModelError(f"HTTP {error.code}（{operation}）：{detail}")
                    retry = error.code == 429 or error.code >= 500
                except (urllib.error.URLError, TimeoutError, ConnectionError, http.client.HTTPException) as error:
                    failure, retry = ModelError(f"网络或超时（{operation}）：{error}"), True
                except ModelError as error:
                    failure, retry = error, not isinstance(error, ModelRefused)
                attempts.append({"operation": operation, "error": str(failure)})
                if not retry:
                    raise failure
                if attempt < self.retries:
                    time.sleep(2 ** attempt)
            # The relay often drops long streams; the same model without streaming is the next route.
        raise failure


def gemini_reply(client, bridge, model, request):
    """Follows continuation tokens, then returns the visible text exactly as the app would show it."""
    events, keys, body = [], [], copy.deepcopy(request)
    for _ in range(4):
        response, key = client.post(model, body)
        keys.append(key)
        events.extend(response)
        one = bridge.call({"op": "response", "responses": response})
        if one["finish"] != "CONTINUATION":
            break
        body = copy.deepcopy(request) | {"continuationToken": one["continuation"]}
    else:
        raise ModelError("续传超过 4 次")
    reply = bridge.call({"op": "response", "responses": events})
    if one["finish"] != "STOP":
        raise ModelError(f"生成未正常结束：finishReason={one['finish']}")
    if reply["toolCalls"]:
        raise ModelError("评测请求不带工具，模型却返回了工具调用")
    if not reply["text"].strip():
        raise ModelError("模型返回了空的可见回复")
    versions = sorted({e["modelVersion"] for e in events if e.get("modelVersion")})
    return reply | {"request_hashes": keys, "model_versions": versions}


def judge_reply(client, bridge, model, messages, seed):
    if client.protocol == "openai":
        body = {"model": model, "messages": messages, "temperature": 0, "seed": seed,
                "max_tokens": 16384, "response_format": {"type": "json_object"}}
        response, key = client.post(model, body)
        choice = (response.get("choices") or [{}])[0]
        if choice.get("finish_reason") != "stop":
            raise ModelError(f"评委未正常结束：finish_reason={choice.get('finish_reason')}")
        return choice["message"]["content"], [key]
    body = {"systemInstruction": {"parts": [{"text": messages[0]["content"]}]},
            "contents": [{"role": "user", "parts": [{"text": messages[1]["content"]}]}],
            "generationConfig": {"temperature": 0, "seed": seed, "maxOutputTokens": 16384,
                                 "responseMimeType": "application/json"}}
    reply = gemini_reply(client, bridge, model, body)
    return reply["text"], reply["request_hashes"]
