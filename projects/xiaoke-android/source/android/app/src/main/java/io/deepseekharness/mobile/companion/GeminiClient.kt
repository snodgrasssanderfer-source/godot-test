package io.deepseekharness.mobile.companion

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Google generateContent wire protocol, not the DeepSeek Messages adapter.
 * Continuation follows googleapis/js-genai src/models.ts and _models_converters.ts:
 * same original request, token at request root; concatenate parts only after hops.
 */
object GeminiWire {
    /**
     * Built-in Google tools (google_search, url_context) ride along when [builtins] is set. With function declarations
     * present, server-side tool calls are echoed back in the content so later turns can replay them (js-genai ToolConfig).
     */
    fun request(profile: JSONObject, contents: JSONArray, system: String, tools: JSONArray, builtins: Boolean = false): JSONObject {
        val generation = JSONObject().put("maxOutputTokens", profile.getInt("maxOutputTokens"))
        if (profile.optString("thinkingMode", "level") == "level") generation.put("thinkingConfig",
            // includeThoughts:true so reasoning comes back marked thought:true and is filtered out; with false, one relay let
            // argon write its reasoning into the visible text.
            JSONObject().put("thinkingLevel", profile.getString("thinking").uppercase()).put("includeThoughts", true))
        return JSONObject().put("contents", contents).put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .also {
                val declared = profile.optBoolean("tools", true) && tools.length() > 0
                val list = JSONArray()
                if (declared) list.put(JSONObject().put("functionDeclarations", tools))
                if (builtins) list.put(JSONObject().put("googleSearch", JSONObject())).put(JSONObject().put("urlContext", JSONObject()))
                if (list.length() > 0) it.put("tools", list)
                if (builtins && declared) it.put("toolConfig", JSONObject().put("includeServerSideToolInvocations", true))
            }
    }
    fun text(parts: JSONArray) = (0 until parts.length()).map { parts.getJSONObject(it) }
        .filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.optString("text") }

    /** History written by another model, with its thought signatures replaced by the placeholder the API accepts. */
    fun foreignSignatures(contents: JSONArray): JSONArray = JSONArray(contents.toString()).also { copy ->
        for (i in 0 until copy.length()) copy.optJSONObject(i)?.optJSONArray("parts")?.let { parts ->
            for (j in 0 until parts.length()) parts.optJSONObject(j)?.let { if (it.has("thoughtSignature")) it.put("thoughtSignature", "skip_thought_signature_validator") }
        }
    }

    private val selfCheck = Regex("检查一下|自检|直接输出|非常稳妥|有没有反问|符合人设|按要求输出|现在输出|最终回复[:：]")

    /**
     * Last line of defence when reasoning arrives unmarked: a <think> block, a reply opening with "💭", or self-check
     * paragraphs ("检查一下……直接输出") keep only what follows them. Returns the text and whether anything was cut.
     */
    fun visible(text: String): Pair<String, Boolean> {
        var out = text.replace(Regex("(?s)<think(?:ing)?>.*?</think(?:ing)?>"), "").trim()
        val paragraphs = out.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
        val last = paragraphs.indexOfLast { it.startsWith("💭") || selfCheck.containsMatchIn(it) }
        if (last >= 0 && paragraphs.size > 1) out = paragraphs.drop(last + 1).ifEmpty { listOf(paragraphs.last()) }.joinToString("\n\n")
        else if (out.startsWith("💭") && paragraphs.size > 1) out = paragraphs.last()
        return out to (out != text.trim())
    }

    fun toolResponse(call: JSONObject, response: JSONObject): JSONObject {
        val result = JSONObject().put("name", call.getString("name")).put("response", response)
        if (call.has("id")) result.put("id", call.getString("id"))
        return JSONObject().put("functionResponse", result)
    }

    class Accumulator {
        val parts = JSONArray()
        var finish = ""
        var continuation: String? = null
        var usage = JSONObject()
        /** Where search answers came from and which pages were actually read. */
        val sources = JSONArray()
        val pages = JSONArray()
        fun accept(response: JSONObject) {
            // Relays (new-api and the like) report upstream failures as an error event inside an HTTP 200 stream.
            if (response.has("error")) throw ModelRejected(200, "中转在返回流里报错：" +
                (response.optJSONObject("error")?.optString("message") ?: response.optString("error")).take(160), response.toString().take(800))
            val prompt = response.optJSONObject("promptFeedback")
            if (prompt?.has("blockReason") == true) error("模型未生成内容：${prompt.optString("blockReason")}")
            if (response.has("usageMetadata")) usage = response.getJSONObject("usageMetadata")
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: return
            candidate.optJSONObject("groundingMetadata")?.optJSONArray("groundingChunks")?.let { chunks ->
                for (i in 0 until chunks.length()) chunks.optJSONObject(i)?.optJSONObject("web")?.let { web ->
                    if (sources.length() < 20) sources.put(JSONObject().put("title", web.optString("title").take(200)).put("uri", web.optString("uri").take(500)))
                }
            }
            candidate.optJSONObject("urlContextMetadata")?.optJSONArray("urlMetadata")?.let { list ->
                for (i in 0 until list.length()) list.optJSONObject(i)?.let { m ->
                    if (pages.length() < 20) pages.put(JSONObject().put("url", m.optString("retrievedUrl").take(500)).put("status", m.optString("urlRetrievalStatus")))
                }
            }
            val incoming = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until incoming.length()) {
                check(parts.length() < 8192) { "模型返回过多内容片段" }
                val part = JSONObject(incoming.getJSONObject(i).toString())
                val call = part.optJSONObject("functionCall")
                if (call != null) {
                    check(!call.has("partialArgs") && !call.has("partial_args") && !call.optBoolean("willContinue")) {
                        "站点返回了此 Google 路由不支持的工具参数增量；未执行工具，请关闭参数分片后重试"
                    }
                    check(!call.has("args") || call.optJSONObject("args") != null) { "工具参数必须为完整 JSON 对象，未执行工具" }
                }
                parts.put(part)
            }
            if (candidate.has("finishReason")) finish = candidate.getString("finishReason")
            if (candidate.has("continuationToken")) continuation = candidate.getString("continuationToken")
        }
        fun result(model: String) = JSONObject().put("role", "model").put("parts", parts)
            .put("modelId", model).put("finishReason", finish).put("usageMetadata", usage).put("sources", sources).put("pages", pages)
    }

    fun calls(parts: JSONArray): List<JSONObject> {
        val calls = (0 until parts.length()).map(parts::getJSONObject).mapNotNull { it.optJSONObject("functionCall") }
        check(calls.size <= 8) { "单轮工具数量超过限制" }
        val ids = mutableSetOf<String>()
        calls.forEach { call ->
            check(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}").matches(call.optString("name"))) { "工具名称无效" }
            if (call.has("id")) check(ids.add(call.getString("id"))) { "模型返回重复工具 ID，未重复执行" }
        }
        return calls
    }
}

/** The model call did not produce an answer. [status] is the HTTP status (200 for an error inside the stream, null when
 * the connection broke); [detail] is the start of what the relay sent back, kept so the owner can see the real cause. */
open class ModelFailure(val status: Int?, message: String, val detail: String = "") : IllegalStateException(message)

/** The relay or model refused the request itself (400/404/422/501, or an error event in the stream). */
class ModelRejected(status: Int, message: String, detail: String = "") : ModelFailure(status, message, detail)

private val SLOW_TIMER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xiaoke-model-slow").apply { isDaemon = true } }

class GeminiClient internal constructor(private val readKey: () -> String, private val transport: OkHttpClient? = null) {
    data class AttemptResult(val outcome: String, val usage: JSONObject, val transmitted: Boolean, val httpStatus: Int?)
    constructor(secrets: CompanionSecrets): this(secrets::read)
    @Volatile private var activeCall: Call? = null
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true); activeCall?.cancel() }
    /** Relay text shown to the owner; the key never appears in it even if a relay echoes the request. */
    private fun redact(body: String) = runCatching { readKey() }.getOrDefault("").let { key -> if (key.length >= 8) body.replace(key, "***") else body }.take(800)
    fun reset() { cancelled.set(false) }
    fun checkCancelled() { check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "任务已取消" } }

    fun generate(settings: JSONObject, profile: JSONObject, contents: JSONArray, system: String, tools: JSONArray,
                 progress: (String) -> Unit, beforeRequest: () -> Unit = {}, onText: (String) -> Unit = {},
                 onAttemptFinished: (AttemptResult) -> Unit = {}, builtins: Boolean = false): JSONObject {
        check(settings.optBoolean("networkEnabled")) { "模型联网尚未启用，请在设置中明确开启" }
        checkCancelled()
        val base = CompanionPolicy.endpoint(settings.getString("endpoint"))
        val model = CompanionPolicy.modelId(profile.getString("model"))
        val original = GeminiWire.request(profile, contents, system, tools, builtins)
        val aggregate = GeminiWire.Accumulator()
        var token: String? = null
        val client = (transport?.newBuilder() ?: OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(profile.optLong("timeoutSeconds", 180), TimeUnit.SECONDS)
            .callTimeout(profile.optLong("timeoutSeconds", 180) + 30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        val seenTokens = mutableSetOf<String>()
        var lastPreviewAt = 0L
        for (hop in 0 until profile.optInt("maxHops", 4)) {
            checkCancelled()
            val body = JSONObject(original.toString())
            if (token != null) body.put("continuationToken", token)
            val one = GeminiWire.Accumulator()
            var completed = false
            for (attempt in 0..2) {
                checkCancelled()
                val request = Request.Builder().url("$base/models/$model:streamGenerateContent?alt=sse")
                    .header("x-goog-api-key", readKey()).header("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                beforeRequest()
                val call = client.newCall(request)
                activeCall = call
                var transmitted = false
                var httpStatus: Int? = null
                val startedAt = System.currentTimeMillis()
                val firstData = java.util.concurrent.atomic.AtomicLong(0L)
                // Slow models (argon through this relay takes 20-30 s) look dead; after 30 s without a word, say so.
                val slow = SLOW_TIMER.schedule({ if (firstData.get() == 0L) progress("还在想…（模型 30 秒还没出第一个字）") }, 30, TimeUnit.SECONDS)
                try {
                    checkCancelled() // Close cancel-before-activeCall assignment race.
                    transmitted = true
                    call.execute().use { response ->
                        httpStatus = response.code
                        if (response.code == 429 || response.code in 500..599) {
                            if (attempt == 2) throw ModelFailure(response.code, "模型服务暂不可用（HTTP ${response.code}）", redact(response.peekBody(4096).string()))
                            val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 30) ?: (attempt + 1).toLong()
                            progress("服务繁忙，${wait} 秒后重试（${attempt + 1}/2）")
                            repeat((wait * 10).toInt()) { checkCancelled(); Thread.sleep(100) }
                            return@use
                        }
                        if (response.code in setOf(400, 404, 422, 501)) throw ModelRejected(response.code,
                            "中转拒绝了这次请求（HTTP ${response.code}）", redact(response.peekBody(4096).string()))
                        check(response.isSuccessful) { when (response.code) {
                            401, 403 -> "模型鉴权失败，请在本机核对密钥与站点权限"
                            400, 404 -> "模型或请求能力不被站点支持（HTTP ${response.code}），请核对模型 ID/工具/思考配置"
                            in 300..399 -> "站点返回重定向，已停止携带密钥跳转；请填写最终 HTTPS 地址"
                            else -> "模型请求失败（HTTP ${response.code}）"
                        } }
                        val source = response.body?.source() ?: error("模型返回空响应")
                        var bytes = 0L
                        val data = StringBuilder()
                        fun flushEvent() {
                            if (data.isNotEmpty()) {
                                val payload = data.toString().trim()
                                if (payload != "[DONE]") {
                                    val event = runCatching { JSONObject(payload) }.getOrElse { throw ModelRejected(200, "中转返回了看不懂的内容", payload.take(800)) }
                                    one.accept(event)
                                    if (System.currentTimeMillis() - lastPreviewAt > 400) {
                                        onText((GeminiWire.text(aggregate.parts) + GeminiWire.text(one.parts)).take(64000))
                                        lastPreviewAt = System.currentTimeMillis()
                                    }
                                }
                                data.setLength(0)
                            }
                        }
                        while (!source.exhausted()) {
                            checkCancelled()
                            val line = source.readUtf8Line() ?: break
                            bytes += line.length
                            check(bytes < 24 * 1024 * 1024) { "模型响应超过本机限制" }
                            if (line.startsWith("data:")) firstData.compareAndSet(0L, System.currentTimeMillis())
                            if (line.isEmpty()) flushEvent()
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trimStart())
                            }
                        }
                        flushEvent()
                        if (one.finish.isEmpty()) throw ModelFailure(response.code, "回答没有正常结束（流被截断）")
                        completed = true
                    }
                    if (completed) break
                } catch (e: IOException) {
                    checkCancelled()
                    // Not replayed here; tools only run after a complete answer, so the caller may retry the whole round.
                    val waited = (System.currentTimeMillis() - startedAt) / 1000
                    val heard = if (firstData.get() == 0L) "一个字都没收到" else "第 ${(firstData.get() - startedAt) / 1000} 秒开始有数据"
                    val detail = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}；等了 $waited 秒，$heard"
                    io.deepseekharness.mobile.companion.XLog.w("model stream broke ($model): $detail")
                    throw ModelFailure(null, "模型连接中断（${e.javaClass.simpleName}，$waited 秒）", detail)
                } finally {
                    slow.cancel(false)
                    activeCall = null
                    onAttemptFinished(AttemptResult(
                        when { !transmitted -> "not_sent"; completed -> "completed"; httpStatus != null && httpStatus !in 200..299 -> "rejected"; else -> "unknown" },
                        JSONObject(one.usage.toString()), transmitted, httpStatus))
                }
            }
            check(completed) { "模型请求未完成" }
            for (i in 0 until one.parts.length()) aggregate.parts.put(one.parts.getJSONObject(i))
            for (i in 0 until one.sources.length()) aggregate.sources.put(one.sources.get(i))
            for (i in 0 until one.pages.length()) aggregate.pages.put(one.pages.get(i))
            aggregate.finish = one.finish
            aggregate.usage = one.usage
            if (one.finish != "CONTINUATION") {
                check(one.finish !in setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "MALFORMED_FUNCTION_CALL")) { "模型停止生成：${one.finish}" }
                val calls = GeminiWire.calls(aggregate.parts)
                check(calls.isEmpty() || one.finish == "STOP") { "工具调用未正常结束，未执行任何工具" }
                checkCancelled()
                onText(GeminiWire.text(aggregate.parts).take(64000))
                return aggregate.result(model)
            }
            token = one.continuation ?: error("模型要求续写却没有 continuation token")
            check(token.length <= 262144 && seenTokens.add(token)) { "模型返回无效或重复续写状态，本轮已停止" }
            progress("继续生成（第 ${hop + 2} 段，仍受本轮总预算限制）")
        }
        error("已达到续写次数上限，本轮停止；请调整预算后重新发起")
    }
}
