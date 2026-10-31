package io.deepseekharness.mobile.companion.eval

import io.deepseekharness.mobile.companion.*
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest

private fun copy(value: JSONObject) = JSONObject(value.toString())
private fun elapsed(start: Long) = (System.nanoTime() - start) / 1_000_000.0
private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

/** Observe bytes already delivered by OkHttp; never consume or change the production parser's stream. */
private class StreamObservation(val started: Long) {
    val timing = JSONObject()
    val pending = Buffer()
    val event = StringBuilder()
    val modelVersions = linkedSetOf<String>()
    var count = 0
    fun mark(name: String) { if (!timing.has(name)) timing.put(name, elapsed(started)) }
    fun receive(bytes: Buffer) {
        pending.writeAll(bytes)
        while (true) {
            val end = pending.indexOf('\n'.code.toByte())
            if (end < 0) break
            val line = pending.readUtf8LineStrict()
            if (line.isEmpty()) flush()
            else if (line.startsWith("data:")) {
                if (event.isNotEmpty()) event.append('\n')
                event.append(line.substring(5).trimStart())
            }
        }
        // Production applies the same response limit; bound the observer independently.
        check(pending.size + event.length < 24L * 1024 * 1024)
    }
    fun flush() {
        if (event.isEmpty()) return
        val raw = event.toString().trim()
        event.setLength(0)
        if (raw == "[DONE]") return
        val value = runCatching { JSONObject(raw) }.getOrNull() ?: return
        (value.opt("modelVersion") as? String)?.takeIf { it.isNotBlank() }?.let(modelVersions::add)
        count++
        mark("firstEventMs")
        if (value.has("usageMetadata")) mark("firstUsageMs")
        val candidate = value.optJSONArray("candidates")?.optJSONObject(0) ?: return
        if (candidate.has("finishReason")) mark("terminalMs")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optString("text").isNotEmpty()) {
                if (part.optBoolean("thought")) mark("firstThoughtMs") else mark("firstVisibleEventMs")
            }
        }
    }
}

object LatencyBridge {
    private var currentObservation: StreamObservation? = null
    private var currentRecord: JSONObject? = null
    private fun transport(): OkHttpClient {
        val builder = OkHttpClient.Builder().addNetworkInterceptor { chain ->
            val observation = currentObservation
            val requestBytes = Buffer()
            chain.request().body?.writeTo(requestBytes)
            currentRecord?.put("requestHash", sha(requestBytes.readByteArray()))
            val response = try { chain.proceed(chain.request()) } catch (failure: Exception) {
                currentRecord?.put("transportErrorType", failure.javaClass.simpleName)
                    ?.put("transportCauseType", failure.cause?.javaClass?.simpleName ?: JSONObject.NULL)
                throw failure
            }
            observation?.mark("headersMs")
            val body = response.body
            if (body == null || observation == null) response else {
                val source = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        val offset = sink.size
                        val read = super.read(sink, byteCount)
                        if (read > 0) {
                            val duplicate = Buffer()
                            sink.copyTo(duplicate, offset, read)
                            observation.receive(duplicate)
                        } else if (read == -1L) observation.flush()
                        return read
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = source
                }).build()
            }
        }
        val proxy = System.getenv("HTTPS_PROXY") ?: System.getenv("https_proxy")
        if (!proxy.isNullOrBlank()) {
            val uri = URI(proxy)
            require(uri.userInfo == null) { "authenticated proxy is unsupported" }
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(uri.host, if (uri.port >= 0) uri.port else 80)))
        }
        return builder.build()
    }
    private val sharedTransport by lazy { transport() }

    fun handle(input: JSONObject): JSONObject {
        val turnStart = System.nanoTime()
        val case = input.getJSONObject("case")
        val model = input.getString("model")
        val variant = input.optString("variant", "baseline")
        val background = case.getString("scenario") == "background"
        val context = copy(case.getJSONObject("context")).put("model", model)
        val profile = copy(input.optJSONObject("profile") ?: JSONObject()).put("model", model)
        if (!profile.has("maxOutputTokens")) profile.put("maxOutputTokens", 32768)
        if (!profile.has("contextBudget")) profile.put("contextBudget", 65536)
        if (!profile.has("thinking")) profile.put("thinking", "low")
        if (!profile.has("maxHops")) profile.put("maxHops", 4)
        if (background) profile.put("tools", false) else if (!profile.has("tools")) profile.put("tools", true)
        val messages = JSONArray(case.optJSONArray("history")?.toString() ?: "[]")
        val recentUserMessages = (0 until messages.length()).map(messages::getJSONObject)
            .filter { it.optString("role") == "user" }.takeLast(3).map { it.optString("text") }
        messages.put(JSONObject().put("id", "fiction-benchmark-user").put("role", "user").put("text", case.getString("user")))
        val hasObservation = context.optJSONObject("observation") != null || context.optJSONObject("observed") != null ||
            context.optString("observationId").isNotBlank() || context.optBoolean("hasObservation")
        val toolFreeChat = variant in setOf("tool_free", "optimized") && !background &&
            context.optString("scope", "owner:local") == "owner:local" && !context.optBoolean("viaClaude") &&
            LatencyPolicy.toolFreeChat(case.getString("user"), recentUserMessages, hasObservation)
        val omitFirstTools = toolFreeChat && context.optInt("merges", 0) == 0
        val endpoint = System.getenv("XIAOKE_EVAL_BASE_URL") ?: error("missing endpoint")
        val key = System.getenv("XIAOKE_EVAL_API_KEY") ?: error("missing key")
        val settings = JSONObject().put("networkEnabled", true).put("endpoint", endpoint).put("webSearch", "auto")
        val requests = JSONArray()
        val toolsUsed = JSONArray()
        val textEvents = JSONArray()
        val metrics = JSONObject()
        val output = JSONObject().put("case_id", case.getString("id")).put("scenario", case.getString("scenario"))
            .put("model", model).put("variant", variant).put("webCache", input.optString("webCache", "cold"))
            .put("metrics", metrics).put("requests", requests).put("tools", toolsUsed).put("text_events", textEvents)
            .put("connectionPool", if (variant == "baseline") "fresh_per_generate" else "shared")
            .put("maxToolRounds", 12)
            .put("effective_profile", copy(profile)).put("latency_policy", JSONObject()
                .put("configuredThinking", profile.optString("thinking")).put("effectiveThinking", profile.optString("thinking"))
                .put("acknowledgement", false).put("applied", false))
        var visible = ""
        var anchorRecorded = false
        var recordedSnapshots = 0
        var lastVisibleSnapshot = ""
        var lastSnapshotRound = -1
        var round = -1
        val anchors = case.optJSONObject("expected")?.optJSONArray("answer_anchors") ?: JSONArray()
        val pendingTools = mutableListOf<JSONObject>()
        val clients = mutableListOf<OkHttpClient>()
        fun generate(purpose: String, requestProfile: JSONObject, contents: JSONArray, system: String, declarations: JSONArray, builtins: Boolean = false): JSONObject {
            val http = if (variant == "baseline") transport().also(clients::add) else sharedTransport
            val client = GeminiClient({ key }, http)
            return client.generate(settings, requestProfile, contents, system, declarations, progress = {},
                beforeRequest = {
                    val now = System.nanoTime()
                    currentObservation = StreamObservation(now)
                    currentRecord = JSONObject().put("purpose", purpose).put("round", round).put("startedMs", elapsed(turnStart))
                        .put("timing", currentObservation!!.timing)
                        .put("declarationCount", if (requestProfile.optBoolean("tools", true)) declarations.length() else 0)
                    requests.put(currentRecord)
                    if (purpose == "reply") pendingTools.forEach { if (!it.has("nextRequestStartedMs")) it.put("nextRequestStartedMs", elapsed(turnStart)) }
                }, onText = { raw ->
                    val text = GeminiWire.visible(raw).first
                    if (text.isNotBlank()) {
                        currentObservation?.mark("firstTextMs")
                        if (purpose == "reply") {
                            visible = text
                            if (!metrics.has("firstTextMs")) metrics.put("firstTextMs", elapsed(turnStart))
                            pendingTools.forEach { if (!it.has("nextTextMs")) it.put("nextTextMs", elapsed(turnStart)) }
                            val matches = anchors.length() > 0 && (0 until anchors.length()).all { text.contains(anchors.getString(it)) }
                            if ((text != lastVisibleSnapshot || round != lastSnapshotRound) && (recordedSnapshots < 16 || (!anchorRecorded && matches))) {
                                textEvents.put(JSONObject().put("atMs", elapsed(turnStart)).put("text", text).put("round", round).put("kind", "visible"))
                                recordedSnapshots++
                                if (matches) anchorRecorded = true
                            }
                            lastVisibleSnapshot = text
                            lastSnapshotRound = round
                        }
                    }
                }, onAttemptFinished = { result ->
                    val observation = currentObservation
                    if (observation != null) {
                        observation.timing.put("elapsedMs", elapsed(observation.started)).put("eventCount", observation.count)
                        val production = runCatching { result.javaClass.getMethod("getTiming").invoke(result) as? JSONObject }.getOrNull()
                        if (production != null) production.keys().forEach { name ->
                            observation.timing.put(if (name == "firstTextMs") "productionFirstTextMs" else name, production.get(name))
                        }
                        if (purpose == "reply") for (name in listOf("firstThoughtMs", "firstUsageMs")) {
                            if (!metrics.has(name) && observation.timing.has(name)) metrics.put(name,
                                currentRecord!!.getDouble("startedMs") + observation.timing.getDouble(name))
                        }
                    }
                    currentRecord?.put("outcome", result.outcome)?.put("transmitted", result.transmitted)
                        ?.put("httpStatus", result.httpStatus ?: JSONObject.NULL)?.put("usage", copy(result.usage))
                        ?.put("modelVersions", JSONArray(observation?.modelVersions.orEmpty()))
                        ?.put("modelVersionMissing", observation?.modelVersions.isNullOrEmpty())
                }, builtins = builtins)
        }
        try {
            if (variant == "optimized" && !background) {
                val configuredThinking = profile.optString("thinking", "low")
                val decision = LatencyPolicy.plan(case.getString("user"), LatencyPolicy.Input(
                    localOwner = context.optString("scope", "owner:local") == "owner:local" && !context.optBoolean("viaClaude"),
                    online = true, toolsEnabled = profile.optBoolean("tools", true),
                    researchAvailable = CompanionRequestPrompts.webOffered(settings, input.optString("warmWebMode").takeIf { input.optString("webCache") == "warm" }) == "split",
                    screenObservationAuthorized = context.optBoolean("screenObservationAuthorized"),
                    backgroundAvailable = context.optBoolean("socialAvailable", true),
                    emergencyHeld = context.optBoolean("emergencyHeld"),
                    recallAvailable = true, jobStatusAvailable = true,
                ), configuredThinking)
                if (!hasObservation && !context.optBoolean("viaClaude")) profile.put("thinking", decision.thinking)
                if (toolFreeChat && configuredThinking in setOf("medium", "high")) profile.put("thinking", "low")
                output.put("latency_policy", JSONObject().put("configuredThinking", configuredThinking)
                    .put("effectiveThinking", profile.getString("thinking")).put("reason", if (toolFreeChat && profile.getString("thinking") != configuredThinking) "tool_free_chat" else decision.reason)
                    .put("acknowledgement", decision.acknowledgement != null).put("applied", true))
                decision.acknowledgement?.let { ack ->
                    val at = elapsed(turnStart)
                    metrics.put("hostAckMs", at)
                    output.put("host_ack", JSONObject().put("atMs", at).put("kind", ack.kind.name).put("text", ack.text))
                }
                output.put("effective_profile", copy(profile))
            }
            output.getJSONObject("latency_policy").put("toolFreeChat", toolFreeChat).put("firstRoundToolsOmitted", omitFirstTools)
                .put("hasObservation", hasObservation).put("recentUserMessageCount", recentUserMessages.size)
            var webMode = "none"
            var webSource = "background_no_tools"
            if (!background) {
                val probeStart = System.nanoTime()
                if (input.optString("webCache", "cold") == "warm") {
                    webMode = input.getString("warmWebMode")
                    require(webMode in setOf("split", "none", "unknown"))
                    webSource = input.optString("warmWebModeSource", "supplied_previous_probe")
                } else if (variant in setOf("no_probe", "tool_free", "optimized", "defer_probe")) {
                    webMode = CompanionRequestPrompts.webOffered(settings, null)
                    webSource = "production_deferred_probe"
                } else {
                    val probe = CompanionRequestPrompts.webProbe(profile)
                    webMode = try { generate("web_probe", probe.profile, probe.contents, probe.system, JSONArray(), true); "split" }
                    catch (e: ModelRejected) { if (e.status == 200) "unknown" else "none" }
                    catch (_: Exception) { "unknown" }
                    webSource = "real_probe"
                }
                metrics.put("probeMs", elapsed(probeStart))
            } else metrics.put("probeMs", 0)
            output.put("webMode", webMode).put("webModeSource", webSource)
            val buildStart = System.nanoTime()
            val system: String
            val contents: JSONArray
            if (background) {
                system = CompanionRequestPrompts.backgroundSystem(input.getJSONObject("persona"), case.optString("purpose"))
                contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", case.getString("user")))))
            } else {
                val promptInput = JSONObject().put("persona", input.getJSONObject("persona")).put("context", context)
                    .put("profile", copy(profile)).put("messages", messages)
                if (input.has("voiceReminder")) promptInput.put("voiceReminder", input.get("voiceReminder"))
                val prompt = PersonaPromptBridge.handle(promptInput)
                system = prompt.getString("system")
                contents = prompt.getJSONArray("contents")
                output.put("voiceMode", prompt.getString("voiceMode"))
            }
            val scope = context.optString("scope", "owner:local")
            val ownChat = scope == "owner:local" || context.optBoolean("authenticatedOwner")
            val declarationInput = CompanionTools.Input(scope, ownChat || context.optBoolean("ownerAsked"), ownChat,
                context.optBoolean("qqReady", true), context.optBoolean("onebot", scope.startsWith("qq:")),
                context.optBoolean("lifeAvailable", true), context.optBoolean("socialAvailable", true),
                context.optBoolean("deviceAvailable", true), emptyList(), webMode == "split")
            val declarations = if (background || !profile.optBoolean("tools", true)) JSONArray() else CompanionTools.declarations(declarationInput)
            val firstDeclarations = if (omitFirstTools) JSONArray() else declarations
            val initialRequest = GeminiWire.request(profile, contents, system, firstDeclarations)
            metrics.put("promptBuildMs", elapsed(buildStart)).put("promptBytes", initialRequest.toString().toByteArray(Charsets.UTF_8).size)
                .put("estimatedInputTokens", CompanionContext.cost(system) + CompanionContext.cost(contents.toString()) + CompanionContext.cost(firstDeclarations.toString()))
            output.put("systemHash", sha(system.toByteArray(Charsets.UTF_8))).put("declarationsHash", sha(firstDeclarations.toString().toByteArray(Charsets.UTF_8)))
                .put("profile", copy(profile)).put("declarationCount", firstDeclarations.length()).put("availableDeclarationCount", declarations.length())
            for (nextRound in 0 until 12) {
                round = nextRound
                val reply = generate("reply", profile, contents, system, if (round == 0 && omitFirstTools) JSONArray() else declarations)
                pendingTools.forEach { if (!it.has("nextFinishedMs")) it.put("nextFinishedMs", elapsed(turnStart)) }
                pendingTools.clear()
                val parts = reply.getJSONArray("parts")
                visible = GeminiWire.visible(GeminiWire.text(parts)).first
                if (visible.isNotBlank()) textEvents.put(JSONObject().put("atMs", elapsed(turnStart)).put("text", visible).put("round", round).put("kind", "round_finished"))
                output.put("finish_reason", reply.optString("finishReason"))
                val calls = GeminiWire.calls(parts)
                if (calls.isEmpty()) {
                    output.put("status", if (reply.optString("finishReason") == "STOP") "completed" else "incomplete")
                    break
                }
                contents.put(JSONObject().put("role", "model").put("parts", parts))
                val responses = JSONArray()
                val fixtures = case.optJSONObject("tool_results") ?: JSONObject()
                var unsupported = false
                for (call in calls) {
                    val name = call.getString("name")
                    val timing = JSONObject().put("callReceivedMs", elapsed(turnStart))
                    val record = JSONObject().put("name", name).put("args", call.optJSONObject("args") ?: JSONObject()).put("round", round).put("timing", timing)
                    toolsUsed.put(record)
                    val realWeb = name == "web_research" && case.optBoolean("real_web")
                    if (!realWeb && !fixtures.has(name)) { record.put("status", "unsupported_tool"); unsupported = true; continue }
                    timing.put("executionStartedMs", elapsed(turnStart))
                    val result = try {
                        if (realWeb) {
                            val args = call.optJSONObject("args") ?: JSONObject()
                            val urls = args.optString("urls").split(Regex("[\\s,，]+")).filter { it.isNotBlank() }
                            val research = CompanionRequestPrompts.webResearch(profile, args.optString("query"), urls)
                            try {
                                val answer = generate("web_research", research.profile, research.contents, research.system, JSONArray(), true)
                                JSONObject().put("answer", GeminiWire.text(answer.getJSONArray("parts")).take(4000))
                                    .put("sources", answer.optJSONArray("sources") ?: JSONArray()).put("pages", answer.optJSONArray("pages") ?: JSONArray())
                            } catch (failure: ModelRejected) {
                                record.put("errorHttpStatus", failure.status ?: JSONObject.NULL)
                                JSONObject().put("error", if (failure.status == 200) "这次联网查询失败，还没有查到结果" else "中转不支持联网搜索")
                            }
                        } else copy(fixtures.getJSONObject(name))
                    } catch (failure: Exception) {
                        record.put("status", if (realWeb) "real_web_failed" else "fixture_failed").put("errorType", failure.javaClass.simpleName)
                        throw failure
                    } finally {
                        val finished = elapsed(turnStart)
                        timing.put("executionFinishedMs", finished).put("fixtureFinishedMs", finished)
                    }
                    record.put("status", if (realWeb) { if (result.has("error")) "real_web_failed" else "real_web" } else "fixture")
                        .put("response", result)
                    pendingTools.add(timing)
                    responses.put(GeminiWire.toolResponse(call, result))
                }
                if (unsupported) { output.put("status", "unsupported_tool"); break }
                contents.put(JSONObject().put("role", "user").put("parts", responses))
            }
            if (!output.has("status")) output.put("status", "tool_round_limit")
        } catch (failure: Exception) {
            output.put("status", "failed").put("error", "benchmark_request_failed").put("errorType", failure.javaClass.simpleName)
                .put("errorCauseType", failure.cause?.javaClass?.simpleName ?: JSONObject.NULL)
            if (failure is ModelFailure) output.put("errorHttpStatus", failure.status ?: JSONObject.NULL)
        } finally {
            currentObservation = null
            currentRecord = null
            clients.forEach { it.connectionPool.evictAll(); it.dispatcher.executorService.shutdown() }
        }
        metrics.put("totalMs", elapsed(turnStart)).put("requestCount", requests.length()).put("toolRounds", round.coerceAtLeast(0))
        return output.put("text", visible)
    }
}

fun main() {
    System.`in`.bufferedReader(Charsets.UTF_8).forEachLine { line ->
        val output = try { LatencyBridge.handle(JSONObject(line)) }
        catch (failure: Exception) { JSONObject().put("status", "failed").put("error", "benchmark_input_failed").put("errorType", failure.javaClass.simpleName) }
        println(output.toString())
        System.out.flush()
    }
}
