package io.deepseekharness.mobile.companion

import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.InetAddress
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/** Real local TLS + SSE requests, no relay access and only a disposable fixture credential. */
class GeminiLocalProtocolTest {
    private lateinit var server: SSLServerSocket
    private lateinit var serving: Thread
    private lateinit var client: GeminiClient
    private lateinit var settings: JSONObject
    private val requests = CopyOnWriteArrayList<JSONObject>()
    private var replies = listOf<Pair<Int, String>>()
    private var index = 0
    private var streaming: List<Pair<Long, String>>? = null
    private val releaseStream = CountDownLatch(1)
    @Before fun setup() {
        server = tls.serverSocketFactory.createServerSocket(0, 8, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        serving = kotlin.concurrent.thread(isDaemon = true, name = "local-gemini-fixture") {
            while (!server.isClosed) try { server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().buffered()
                fun line(): String { val out = StringBuilder(); while (true) { val byte = input.read(); if (byte < 0 || byte == 10) return out.toString().trimEnd('\r'); out.append(byte.toChar()) } }
                val path = line(); check(path.startsWith("POST /v1beta/models/gemini-3.8-flash:streamGenerateContent"))
                var length = 0
                while (true) { val header = line(); if (header.isEmpty()) break; if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt() }
                val body = ByteArray(length); var read = 0
                while (read < length) { val count = input.read(body, read, length - read); check(count > 0); read += count }
                requests.add(JSONObject(String(body, Charsets.UTF_8)))
                socket.getOutputStream().use { out ->
                    val chunks = streaming
                    if (chunks != null) {
                        out.write("HTTP/1.1 200 Fixture\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        for ((delay, chunk) in chunks) {
                            Thread.sleep(delay)
                            val bytes = chunk.toByteArray(Charsets.UTF_8)
                            out.write("${bytes.size.toString(16)}\r\n".toByteArray(Charsets.US_ASCII)); out.write(bytes); out.write("\r\n".toByteArray(Charsets.US_ASCII)); out.flush()
                        }
                        releaseStream.await(3, TimeUnit.SECONDS)
                    } else {
                        val response = replies.getOrElse(index++) { 500 to "" }; val bytes = response.second.toByteArray(Charsets.UTF_8)
                        out.write("HTTP/1.1 ${response.first} Fixture\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        out.write(bytes); out.flush()
                    }
                }
            } } catch (_: java.net.SocketException) { if (!server.isClosed) throw IllegalStateException("Local TLS fixture failed") }
        }
        val transport = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust).build()
        client = GeminiClient({ "fixture-credential-not-a-real-key" }, transport)
        settings = CompanionPolicy.defaultSettings().put("endpoint", "https://127.0.0.1:${server.localPort}/v1beta").put("networkEnabled", true)
    }
    @After fun close() { releaseStream.countDown(); server.close(); serving.join(1000) }
    private fun event(parts: JSONArray, finish: String, token: String = "") = "data: " + JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("content", JSONObject().put("parts", parts)).put("finishReason", finish).also { if (token.isNotEmpty()) it.put("continuationToken", token) })).toString() + "\n\n"
    private fun generate(before: () -> Unit = {}) = client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"),
        JSONArray("""[{"role":"user","parts":[{"text":"fixture"}]}]"""), "fixture system", JSONArray(), {}, before)
    @Test fun continuationUsesOriginalRequestAndRootToken() {
        replies = listOf(200 to event(JSONArray("""[{"text":"第一段"}]"""), "CONTINUATION", "token-fixture"),
            200 to event(JSONArray("""[{"text":"第二段"}]"""), "STOP"))
        var count = 0; val reply = generate { count++ }
        assertEquals("第一段第二段", GeminiWire.text(reply.getJSONArray("parts"))); assertEquals(2, count)
        assertEquals(requests[0].getJSONArray("contents").toString(), requests[1].getJSONArray("contents").toString())
        assertEquals("token-fixture", requests[1].getString("continuationToken")); assertFalse(requests[0].has("continuationToken"))
    }
    @Test fun hiddenThoughtNeverAppearsButSignatureIsRetained() {
        replies = listOf(200 to event(JSONArray("""[{"text":"hidden","thought":true,"thoughtSignature":"opaque"},{"text":"可见"}]"""), "STOP"))
        val result = generate(); assertEquals("可见", GeminiWire.text(result.getJSONArray("parts")))
        assertEquals("opaque", result.getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
    }
    @Test fun repeatedContinuationTokenFailsWithinBudget() {
        val response = event(JSONArray("""[{"text":"片段"}]"""), "CONTINUATION", "same-token")
        replies = listOf(200 to response, 200 to response)
        assertThrows(IllegalStateException::class.java) { generate() }; assertEquals(2, requests.size)
    }
    @Test fun incompleteStreamDoesNotRunOrReplayTools() {
        replies = listOf(200 to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"recall\",\"args\":{}}}]}}]}\n\n")
        assertThrows(IllegalStateException::class.java) { generate() }; assertEquals(1, requests.size)
    }
    @Test fun partialToolArgumentsAreRejectedBeforeExecution() {
        replies = listOf(200 to event(JSONArray("""[{"functionCall":{"name":"recall","partialArgs":[{"text":"x"}],"willContinue":true}}]"""), "STOP"))
        assertThrows(IllegalStateException::class.java) { generate() }; assertEquals(1, requests.size)
    }
    @Test fun relayErrorInsideTheStreamKeepsWhatTheRelaySaid() {
        replies = listOf(200 to "data: {\"error\":{\"message\":\"upstream service request failed\",\"code\":\"upstream_service_error\"},\"type\":\"error\"}\n\n")
        val error = assertThrows(ModelRejected::class.java) { generate() }
        assertEquals(200, error.status); assertTrue(error.detail.contains("upstream_service_error")); assertEquals(1, requests.size)
    }
    @Test fun rejectedRequestCarriesTheRelayBodyForTheOwner() {
        replies = listOf(400 to "{\"error\":{\"message\":\"Function calling with search is unsupported\"}}")
        val error = assertThrows(ModelRejected::class.java) { generate() }
        assertEquals(400, error.status); assertTrue(error.detail.contains("unsupported")); assertFalse(error.message.orEmpty().contains("unsupported"))
    }
    @Test fun authFailureDoesNotExposeResponseBodyOrRetry() {
        replies = listOf(403 to "private upstream diagnostic")
        val error = assertThrows(IllegalStateException::class.java) { generate() }
        assertFalse(error.message.orEmpty().contains("private upstream")); assertEquals(1, requests.size)
    }
    @Test fun exhaustedBudgetPreventsAnyNetworkRequest() {
        assertThrows(IllegalStateException::class.java) { generate { error("本机额度限制") } }
        assertEquals(0, requests.size)
    }
    @Test fun cancellationAfterReservationReportsNotSentWithoutConsumingTransport() {
        val outcomes = mutableListOf<GeminiClient.AttemptResult>()
        assertThrows(IllegalStateException::class.java) {
            client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"), JSONArray(), "fixture", JSONArray(), {},
                beforeRequest = { client.cancel() }, onAttemptFinished = { outcomes.add(it) })
        }
        assertEquals(0, requests.size)
        assertEquals("not_sent", outcomes.single().outcome)
        assertFalse(outcomes.single().transmitted)
    }
    @Test fun incompleteStreamRetainsUnknownChargeInsteadOfClaimingFreeCancellation() {
        replies = listOf(200 to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}\n\n")
        val outcomes = mutableListOf<GeminiClient.AttemptResult>()
        assertThrows(IllegalStateException::class.java) {
            client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"), JSONArray(), "fixture", JSONArray(), {},
                onAttemptFinished = { outcomes.add(it) })
        }
        assertEquals(1, requests.size)
        assertEquals("unknown", outcomes.single().outcome)
        assertTrue(outcomes.single().transmitted)
    }
    @Test fun firstVisibleTextIsImmediateEvenWhenThoughtArrivesFirst() {
        val thought = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"private thought\",\"thought\":true}]}}]}\n\n"
        val text = "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"可见\"}]}}]}\n\n"
        val terminal = "data: {\"candidates\":[{\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":9}}\n\n"
        streaming = listOf(0L to thought, 40L to text, 500L to terminal)
        val previews = mutableListOf<Pair<Long, String>>()
        val stages = mutableMapOf<String, Long>()
        var result: GeminiClient.AttemptResult? = null
        val start = System.nanoTime()
        client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"), JSONArray(), "fixture", JSONArray(), {},
            onText = { previews.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) to it) },
            onAttemptFinished = { result = it }, onTiming = { name, values -> stages[name] = values.getLong("elapsedMs") })
        assertTrue(previews.isNotEmpty()); assertTrue(previews.all { it.second == "可见" })
        assertTrue("first visible callback waited for terminal", previews.first().first < stages.getValue("terminal") - 250)
        assertTrue(stages.getValue("first_thought") <= stages.getValue("first_text"))
        assertEquals(9, result!!.usage.getInt("totalTokenCount"))
        assertTrue(result!!.timing.keySet().all { result!!.timing.get(it) is Number })
        assertFalse(result!!.timing.toString().contains("private thought"))
    }
    @Test fun terminalEventWithUsageDoesNotWaitForConnectionClose() {
        streaming = listOf(0L to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"完成\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"totalTokenCount\":7}}\n\n")
        val start = System.nanoTime()
        val result = generate()
        assertEquals(7, result.getJSONObject("usageMetadata").getInt("totalTokenCount"))
        assertTrue("waited for open SSE connection", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun usageAfterTerminalIsStillAccountedWithoutWaitingForEof() {
        streaming = listOf(0L to event(JSONArray("""[{"text":"完成"}]"""), "STOP"),
            50L to "data: {\"usageMetadata\":{\"totalTokenCount\":23}}\n\n")
        var outcome: GeminiClient.AttemptResult? = null
        val start = System.nanoTime()
        val result = client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"), JSONArray(), "fixture", JSONArray(), {}, onAttemptFinished = { outcome = it })
        assertEquals(23, result.getJSONObject("usageMetadata").getInt("totalTokenCount"))
        assertEquals(23, outcome!!.usage.getInt("totalTokenCount")); assertEquals("completed", outcome!!.outcome)
        assertTrue(outcome!!.timing.getLong("firstUsageMs") >= outcome!!.timing.getLong("terminalMs"))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun missingTailUsageHasBoundedDrainAndRemainsMissing() {
        streaming = listOf(0L to event(JSONArray("""[{"text":"完成"}]"""), "STOP"))
        val start = System.nanoTime()
        val result = generate()
        assertEquals(0, result.getJSONObject("usageMetadata").length())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }
    @Test fun doneEndsTheStreamWithoutInventingAFinishReason() {
        streaming = listOf(0L to "data: [DONE]\n\n")
        val start = System.nanoTime()
        assertThrows(ModelFailure::class.java) { generate() }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
        assertEquals(1, requests.size)
    }
    @Test fun cancellationWhileStreamingRetainsUnknownAccountingAndDoesNotReplay() {
        streaming = listOf(0L to "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"片段\"}]}}]}\n\n")
        val outcomes = mutableListOf<GeminiClient.AttemptResult>()
        assertThrows(IllegalStateException::class.java) {
            client.generate(settings, CompanionPolicy.profile("gemini-3.8-flash"), JSONArray(), "fixture", JSONArray(), {},
                onText = { client.cancel() }, onAttemptFinished = { outcomes.add(it) })
        }
        assertEquals(1, requests.size); assertEquals("unknown", outcomes.single().outcome)
        assertTrue(outcomes.single().transmitted); assertEquals(0, outcomes.single().usage.length())
        assertTrue(outcomes.single().timing.has("firstTextMs"))
    }
    companion object {
        private lateinit var tls: SSLContext
        private lateinit var trust: X509TrustManager
        private lateinit var folder: File
        @JvmStatic @BeforeClass fun certificate() {
            folder = java.nio.file.Files.createTempDirectory("xiaoke-local-protocol-").toFile()
            val file = File(folder, "fixture.p12"); val pass = "fixture-local-only"
            val process = ProcessBuilder(System.getProperty("java.home") + "/bin/keytool", "-genkeypair", "-alias", "fixture", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=IP:127.0.0.1,DNS:localhost", "-keystore", file.path, "-storetype", "PKCS12", "-storepass", pass, "-noprompt")
                .redirectErrorStream(true).redirectOutput(File(folder, "keytool.log")).start()
            check(process.waitFor() == 0)
            val ks = KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, pass.toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass.toCharArray()) }
            val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            trust = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
            tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trusts.trustManagers, null) }
        }
        @JvmStatic @AfterClass fun cleanup() { folder.deleteRecursively() }
    }
}
