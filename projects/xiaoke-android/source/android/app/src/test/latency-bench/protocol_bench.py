"""受控本地 TLS 对照；不读取私人设定、不连接云中转、不执行工具。"""
import argparse
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import ssl
import statistics
import subprocess
import threading
import time

import runtime


MODEL = "gemini-3.8-flash"
KEY = "local-protocol-fixture-only"
SCENARIOS = ("first_text", "terminal_wait", "connection_pool")

# Separate Java entrypoint avoids injecting a transport into the pool experiment.
POOL_DRIVER = r'''
import io.deepseekharness.mobile.companion.GeminiClient;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Local-fixture driver: no injected transport, one production client for the whole process. */
public final class ProtocolPoolDriver {
    private static double elapsed(long since) { return (System.nanoTime() - since) / 1_000_000.0; }
    private static Object getter(Object value, String name) {
        try { return value.getClass().getMethod(name).invoke(value); }
        catch (ReflectiveOperationException ignored) { return null; }
    }
    private static JSONObject numbers(Object value) {
        JSONObject out = new JSONObject();
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            for (String name : source.keySet()) if (source.opt(name) instanceof Number) out.put(name, source.get(name));
        }
        return out;
    }
    private static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException && error.getCause() != null ? error.getCause() : error;
    }
    private static JSONObject run(GeminiClient client, Method generate, JSONObject input) {
        JSONObject settings = new JSONObject().put("networkEnabled", true).put("endpoint", System.getenv("XIAOKE_EVAL_BASE_URL"));
        JSONObject profile = input.getJSONObject("profile");
        JSONArray contents = input.getJSONArray("contents");
        String system = input.getString("system");
        int count = input.optInt("requestCount", 3);
        if (count < 1 || count > 3) throw new IllegalArgumentException("fixture request count");
        JSONArray rows = new JSONArray();
        for (int index = 0; index < count; index++) {
            long logicalStart = System.nanoTime();
            long[] firstRequestStart = {0};
            long[] firstText = {0};
            JSONArray attempts = new JSONArray();
            Function1<String, Unit> progress = ignored -> Unit.INSTANCE;
            Function0<Unit> before = () -> {
                if (firstRequestStart[0] == 0) firstRequestStart[0] = System.nanoTime();
                return Unit.INSTANCE;
            };
            Function1<String, Unit> onText = text -> {
                if (firstText[0] == 0 && text != null && !text.trim().isEmpty()) firstText[0] = System.nanoTime();
                return Unit.INSTANCE;
            };
            Function1<Object, Unit> after = attempt -> {
                JSONObject row = new JSONObject();
                Object outcome = getter(attempt, "getOutcome");
                Object transmitted = getter(attempt, "getTransmitted");
                Object status = getter(attempt, "getHttpStatus");
                if (outcome instanceof String) row.put("outcome", outcome);
                if (transmitted instanceof Boolean) row.put("transmitted", transmitted);
                row.put("httpStatus", status instanceof Number ? status : JSONObject.NULL);
                row.put("timing", numbers(getter(attempt, "getTiming")));
                row.put("usage", numbers(getter(attempt, "getUsage")));
                attempts.put(row);
                return Unit.INSTANCE;
            };
            Function2<String, JSONObject, Unit> onTiming = (ignoredName, ignoredValues) -> Unit.INSTANCE;
            Object[] args = new Object[generate.getParameterCount()];
            args[0] = settings; args[1] = profile; args[2] = contents; args[3] = system; args[4] = new JSONArray();
            args[5] = progress; args[6] = before; args[7] = onText; args[8] = after; args[9] = false;
            if (args.length == 11) args[10] = onTiming;
            JSONObject row = new JSONObject().put("request", index + 1).put("attempts", attempts);
            try {
                generate.invoke(client, args);
                row.put("status", "completed");
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                row.put("status", "failed").put("errorType", cause.getClass().getSimpleName());
                Object status = getter(cause, "getStatus");
                if (status instanceof Number) row.put("errorHttpStatus", status);
            }
            long requestStart = firstRequestStart[0] == 0 ? logicalStart : firstRequestStart[0];
            row.put("requestMs", elapsed(requestStart)).put("generateMs", elapsed(logicalStart));
            if (firstText[0] != 0) row.put("firstTextMs", (firstText[0] - requestStart) / 1_000_000.0);
            if (attempts.length() > 0) {
                JSONObject last = attempts.getJSONObject(attempts.length() - 1);
                for (String field : new String[]{"outcome", "transmitted", "httpStatus", "usage", "timing"})
                    if (last.has(field)) row.put(field, last.get(field));
            }
            rows.put(row);
            if (!"completed".equals(row.optString("status"))) break;
        }
        return new JSONObject().put("implementation", "GeminiClient_default_transport").put("clientInstances", 1)
            .put("generateParameterCount", generate.getParameterCount()).put("requests", rows);
    }
    public static void main(String[] ignored) throws Exception {
        // Kotlin internal constructors are public on the JVM. Null preserves the actual production default path.
        GeminiClient client = new GeminiClient((Function0<String>) () -> System.getenv("XIAOKE_EVAL_API_KEY"), null);
        Method generate = null;
        for (Method method : GeminiClient.class.getMethods()) {
            if (method.getName().equals("generate") && (method.getParameterCount() == 10 || method.getParameterCount() == 11)) {
                generate = method;
                break;
            }
        }
        if (generate == null) throw new IllegalStateException("unsupported production API");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            JSONObject result;
            try { result = run(client, generate, new JSONObject(line)); }
            catch (Throwable failure) {
                result = new JSONObject().put("status", "failed").put("errorType", unwrap(failure).getClass().getSimpleName());
            }
            System.out.println(result.toString());
            System.out.flush();
        }
    }
}
'''


def event(text="", *, thought=False, terminal=False):
    candidate = {"content": {"parts": [{"text": text, "thought": thought}] if text else []}}
    value = {"candidates": [candidate], "modelVersion": MODEL}
    if terminal:
        candidate["finishReason"] = "STOP"
        value["usageMetadata"] = {"promptTokenCount": 10, "totalTokenCount": 12}
    return ("data: " + json.dumps(value, ensure_ascii=False) + "\n\n").encode()


class FixtureServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False

    def __init__(self, context, scenario):
        self.context = context
        self.scenario = scenario
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.sockets = set()
        self.connections = []
        self.requests = []
        self.errors = []
        super().__init__(("127.0.0.1", 0), FixtureHandler)
        self.serving = threading.Thread(target=self.serve_forever, kwargs={"poll_interval": 0.05}, daemon=True)
        self.serving.start()

    def get_request(self):
        raw, address = super().get_request()
        raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        raw.settimeout(5)
        started = time.perf_counter()
        try:
            connected = self.context.wrap_socket(raw, server_side=True)
        except Exception:
            raw.close()
            raise
        duration = (time.perf_counter() - started) * 1000
        with self.lock:
            connection_id = len(self.connections) + 1
            self.connections.append({"id": connection_id, "handshakeMs": duration})
            self.sockets.add(connected)
        connected.fixture_connection_id = connection_id
        return connected, address

    def shutdown_request(self, request):
        with self.lock:
            self.sockets.discard(request)
        super().shutdown_request(request)

    def handle_error(self, request, client_address):
        # An early-completing client may close a deliberately unfinished response.
        self.errors.append("fixture_handler_failed")

    def close(self):
        self.stopping.set()
        self.shutdown()
        with self.lock:
            sockets = list(self.sockets)
        for connected in sockets:
            try:
                connected.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            connected.close()
        self.server_close()
        self.serving.join(timeout=1)


class FixtureHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        try:
            self.respond()
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, OSError):
            # Expected when the optimized parser returns before the delayed chunk terminator.
            self.close_connection = True

    def respond(self):
        length = int(self.headers.get("Content-Length", "0"))
        if not (0 < length <= 1024 * 1024):
            raise ValueError("fixture request size")
        body = self.rfile.read(length)
        if (self.path != f"/v1beta/models/{MODEL}:streamGenerateContent?alt=sse"
                or self.headers.get("Accept") != "text/event-stream"
                or self.headers.get("x-goog-api-key") != KEY):
            raise ValueError("fixture protocol mismatch")
        with self.server.lock:
            self.server.requests.append({"connectionId": self.connection.fixture_connection_id,
                                         "requestHash": hashlib.sha256(body).hexdigest()})
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        if self.server.scenario == "connection_pool":
            complete = event("虚构回复。", terminal=True)
            self.send_header("Content-Length", str(len(complete)))
            self.end_headers()
            self.wfile.write(complete)
            self.wfile.flush()
            return
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        self.wfile.flush()
        started = time.perf_counter()
        if self.server.scenario == "first_text":
            schedule = [(0, event("虚构思考", thought=True)), (0.1, event("首段")),
                        (0.7, event("尾段", terminal=True))]
            eof_at = 0.7
        else:
            schedule = [(0.1, event("虚构回复。", terminal=True))]
            eof_at = 1.5
        for at, payload in schedule:
            if self.server.stopping.wait(max(0, started + at - time.perf_counter())):
                return
            self.wfile.write(f"{len(payload):x}\r\n".encode() + payload + b"\r\n")
            self.wfile.flush()
        if not self.server.stopping.wait(max(0, started + eof_at - time.perf_counter())):
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()


@contextmanager
def local_environment(endpoint, truststore):
    changes = {name: None for name in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy",
                                      "ALL_PROXY", "all_proxy", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")}
    changes.update({"XIAOKE_EVAL_BASE_URL": endpoint, "XIAOKE_EVAL_API_KEY": KEY,
                    "JAVA_TOOL_OPTIONS": f"-Djavax.net.ssl.trustStore={truststore} -Djavax.net.ssl.trustStorePassword=fixture-pass"})
    previous = {name: os.environ.get(name) for name in changes}
    try:
        for name, value in changes.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value
        yield
    finally:
        for name, value in previous.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value


def certificate(directory):
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    cert, key, truststore = [directory / name for name in ("cert.pem", "key.pem", "trust.p12")]
    java = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
    keytool = str(java / "keytool") if java else "keytool"
    # Regenerate each run; the fixture key has no application identity or data.
    for path in (cert, key, truststore):
        path.unlink(missing_ok=True)
    with (directory / "certificate.log").open("w") as log:
        subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(key),
                        "-out", str(cert), "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1"],
                       stdout=log, stderr=log, check=True)
        subprocess.run([keytool, "-importcert", "-noprompt", "-alias", "fixture", "-file", str(cert),
                        "-keystore", str(truststore), "-storepass", "fixture-pass"], stdout=log, stderr=log, check=True)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    return context, truststore


def payload(variant):
    return {"persona": {"name": "虚构测试角色", "text": "所有内容均为本地虚构测试。"},
            "model": MODEL, "variant": variant, "webCache": "warm", "warmWebMode": "none",
            "warmWebModeSource": "controlled_local_fixture",
            "profile": {"maxOutputTokens": 32768, "contextBudget": 65536, "thinking": "low", "timeoutSeconds": 5},
            "case": {"id": "controlled-protocol", "scenario": "background", "purpose": "虚构摘要", "user": "回复虚构摘要。",
                     "context": {"scope": "owner:local", "time": "2026-10-31T20:00", "authenticatedOwner": True, "memories": []},
                     "expected": {"answer_anchors": []}, "tool_results": {}}}


def default_pool_calls(bridge, directory):
    """Run the actual default constructor; the observation bridge's injected pool is not measured."""
    command = bridge.process.args
    java = command[0]
    classpath = command[command.index("-cp") + 1]
    build = directory / "pool-driver" / runtime.digest(POOL_DRIVER.encode())
    build.mkdir(parents=True, exist_ok=True, mode=0o700)
    source = build / "ProtocolPoolDriver.java"
    source.write_text(POOL_DRIVER, encoding="utf-8")
    executable = Path(java)
    javac = str(executable.with_name("javac")) if executable.parent != Path(".") else "javac"
    with (build / "compile.log").open("a") as log:
        if not (build / "ProtocolPoolDriver.class").exists():
            subprocess.run([javac, "-encoding", "UTF-8", "-cp", classpath, "-d", str(build), str(source)],
                           stdout=log, stderr=log, check=True)
    profile = dict(payload("baseline")["profile"], model=MODEL, tools=False)
    value = {"profile": profile, "system": "这是固定的本地虚构协议测试。", "requestCount": 3,
             "contents": [{"role": "user", "parts": [{"text": "只回复一句虚构短句。"}]}]}
    bridge.close()
    with (build / "run.log").open("a") as log:
        completed = subprocess.run([java, "-Dfile.encoding=UTF-8", "-cp", str(build) + os.pathsep + classpath,
                                    "ProtocolPoolDriver"], input=runtime.canonical(value) + "\n",
                                   stdout=subprocess.PIPE, stderr=log, text=True, encoding="utf-8", check=True, timeout=30)
    result = json.loads(completed.stdout)
    rows = result.get("requests", [])
    if result.get("clientInstances") != 1 or len(rows) != 3:
        raise ValueError("default transport fixture did not issue three requests on one client")
    if any(row.get("status") != "completed" or len(row.get("attempts", [])) != 1 for row in rows):
        raise ValueError("default transport fixture unexpectedly failed or retried")
    return rows


def one_run(args, directory, context, truststore, scenario, variant, ref, repeat, frozen):
    server = FixtureServer(context, scenario)
    bridge = None
    try:
        with local_environment(f"https://127.0.0.1:{server.server_port}/v1beta", truststore):
            bridge = runtime.LatencyBridge(args.gradle, directory / "compile" / variant, ref)
        previous = frozen.setdefault(variant, bridge.metadata)
        if previous != bridge.metadata:
            raise ValueError("source changed during controlled comparison")
        rows = []
        request_count = 3 if scenario == "connection_pool" else 1
        if scenario == "connection_pool":
            with local_environment(f"https://127.0.0.1:{server.server_port}/v1beta", truststore):
                calls = default_pool_calls(bridge, directory)
            bridge = None
            for index, call in enumerate(calls):
                rows.append({"request": index + 1, "connectionId": server.requests[index]["connectionId"],
                             "requestHash": server.requests[index]["requestHash"], "requestMs": call["requestMs"],
                             "firstTextMs": call["firstTextMs"]})
        else:
            result = bridge.call(payload(variant))
            if result.get("status") != "completed" or len(result.get("requests", [])) != 1:
                raise ValueError("controlled request did not complete exactly once")
            request = result["requests"][0]
            timing = request["timing"]
            if request["requestHash"] != server.requests[0]["requestHash"]:
                raise ValueError("wire hash mismatch")
            rows.append({"request": 1, "connectionId": server.requests[0]["connectionId"],
                         "requestHash": request["requestHash"], "requestMs": timing["elapsedMs"],
                         "firstTextMs": timing["firstTextMs"], "visibleEventMs": timing["firstVisibleEventMs"],
                         "terminalMs": timing["terminalMs"],
                         "callbackDelayMs": timing["firstTextMs"] - timing["firstVisibleEventMs"],
                         "finishWaitMs": max(0, timing["elapsedMs"] - timing["terminalMs"])})
        if server.errors:
            raise ValueError("local fixture server failed")
        expected_connections = request_count if variant == "baseline" else 1
        if len(server.connections) != expected_connections:
            raise ValueError("connection pool count did not match production lifecycle")
        return {"scenario": scenario, "variant": variant, "repeat": repeat,
                "requests": rows, "tlsConnections": len(server.connections),
                "tlsHandshakeMs": [item["handshakeMs"] for item in server.connections]}
    finally:
        if bridge:
            bridge.close()
        server.close()


def summary(records):
    result = {}
    for scenario in SCENARIOS:
        result[scenario] = {}
        for variant in ("baseline", "streaming"):
            trials = [row for row in records if row["scenario"] == scenario and row["variant"] == variant]
            requests = [request for row in trials for request in row["requests"]]
            values = {"repeats": len(trials), "requests": len(requests),
                      "tlsConnections": sum(row["tlsConnections"] for row in trials)}
            for name in ("requestMs", "firstTextMs", "callbackDelayMs", "finishWaitMs"):
                measured = [row[name] for row in requests if name in row]
                if measured:
                    values["median" + name[0].upper() + name[1:]] = statistics.median(measured)
            if scenario == "connection_pool":
                values["medianFirstRequestMs"] = statistics.median(row["requests"][0]["requestMs"] for row in trials)
                values["medianLaterRequestMs"] = statistics.median(request["requestMs"] for row in trials for request in row["requests"][1:])
            result[scenario][variant] = values
    return result


def report_text(result):
    rows = ["# 受控本地 TLS 协议测量", "", "这是回环地址上的固定流事件实验，不是云中转数据。每项每版本 3 次；没有人为增加 TLS 握手延迟。", "",
            "| 隔离项目 | 旧版中位数 | 新版中位数 |", "|---|---:|---:|"]
    for scenario, metric, label in (("first_text", "medianCallbackDelayMs", "收到首个可见事件→首文本回调（ms）"),
                                    ("terminal_wait", "medianFinishWaitMs", "收到结束事件→请求收尾（ms）")):
        values = result[scenario]
        rows.append(f"| {label} | {values['baseline'][metric]:.2f} | {values['streaming'][metric]:.2f} |")
    pool = result["connection_pool"]
    rows.extend([f"| 9 次请求实际建立的 TLS 连接数 | {pool['baseline']['tlsConnections']} | {pool['streaming']['tlsConnections']} |",
                 f"| 每组后两次请求耗时中位数（ms） | {pool['baseline']['medianLaterRequestMs']:.2f} | {pool['streaming']['medianLaterRequestMs']:.2f} |", "",
                 "首文本实验：thought 0ms、首段 100ms、下一段与结束 700ms。收尾实验：STOP 与 usage 100ms，HTTP chunked 终止 1500ms。", "",
                 "连接池实验：直接使用生产 GeminiClient 默认构造器，不注入 OkHttp；同一实例连续请求三次，完整 HTTP/1.1 响应立即结束。每组独立 JVM，统计成功 TLS 握手的连接数。耗时包含本地 JVM、加密和调度开销，不能换算成云端提速。", "",
                 "每组使用新端口，旧版与新版分开进程。生产源码、桥接源码及依赖摘要保存在 manifest.json；原始数值保存在 records.json。", ""])
    return "\n".join(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", default="protocol-20261031")
    parser.add_argument("--gradle", default=os.environ.get("GRADLE_HOME"))
    parser.add_argument("--baseline", default="8e8ee82")
    parser.add_argument("--optimized", default="worktree")
    args = parser.parse_args()
    if not args.gradle:
        parser.error("请设置 GRADLE_HOME 或传入 --gradle")
    directory = runtime.private_run(args.run)
    context, truststore = certificate(directory / "fixture-tls")
    records, frozen = [], {}
    for scenario in SCENARIOS:
        for repeat in range(1, 4):
            variants = [("baseline", args.baseline), ("streaming", args.optimized)]
            if repeat % 2 == 0:
                variants.reverse()
            for variant, ref in variants:
                records.append(one_run(args, directory, context, truststore, scenario, variant, ref, repeat, frozen))
                runtime.save(directory / "records.json", records)
                print(f"{scenario} {variant} {repeat}/3 completed", flush=True)
    hashes = {scenario: {request["requestHash"] for row in records if row["scenario"] == scenario for request in row["requests"]}
              for scenario in SCENARIOS}
    if any(len(values) != 1 for values in hashes.values()):
        raise ValueError("request bodies differed between variants within a controlled scenario")
    harness = [{name: entry for name, entry in metadata["production_sources"].items() if name.startswith("harness/")}
               for metadata in frozen.values()]
    if harness[0] != harness[1]:
        raise ValueError("benchmark harness differed across variants")
    result = summary(records)
    runtime.save(directory / "manifest.json", {"kind": "controlled_loopback_tls", "repeats": 3,
                 "script_sha256": runtime.digest(Path(__file__).read_bytes()), "sources": frozen,
                 "scheduleMs": {"first_text": [0, 100, 700], "terminal_wait": [100, 1500]},
                 "poolRequestsPerRepeat": 3, "poolDefaultConstructor": True, "poolDriverSha256": runtime.digest(POOL_DRIVER.encode()),
                 "artificialTlsDelayMs": 0, "profile": payload("baseline")["profile"],
                 "requestHashes": {name: values.pop() for name, values in hashes.items()}})
    runtime.save(directory / "summary.json", result)
    (directory / "report.md").write_text(report_text(result), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True), flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        print("受控协议测量失败：" + type(failure).__name__ + "；检查忽略目录内的记录与编译日志。", flush=True)
        raise SystemExit(1) from None
