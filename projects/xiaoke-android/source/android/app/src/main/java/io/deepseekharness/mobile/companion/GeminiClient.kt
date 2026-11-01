package io.deepseekharness.mobile.companion

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val SLOW_TIMER = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xiaoke-model-slow").apply { isDaemon = true } }

class GeminiClient internal constructor(private val readKey: () -> String, transport: OkHttpClient? = null) {
    data class AttemptResult(val outcome: String, val usage: JSONObject, val transmitted: Boolean, val httpStatus: Int?, val timing: JSONObject = JSONObject())
    constructor(secrets: CompanionSecrets): this(secrets::read)
    // A profile changes timeouts, not the socket pool or TLS sessions between turns.
    private val transport = transport ?: OkHttpClient()
    @Volatile private var activeCall: Call? = null
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true); activeCall?.cancel() }
    /** Relay text shown to the owner; the key never appears in it even if a relay echoes the request. */
    private fun redact(body: String) = runCatching { readKey() }.getOrDefault("").let { key -> if (key.length >= 8) body.replace(key, "***") else body }.take(800)
    fun reset() { cancelled.set(false) }
    fun checkCancelled() { check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "任务已取消" } }

    fun generate(settings: JSONObject, profile: JSONObject, contents: JSONArray, system: String, tools: JSONArray,
                 progress: (String) -> Unit, beforeRequest: () -> Unit = {}, onText: (String) -> Unit = {},
                 onAttemptFinished: (AttemptResult) -> Unit = {}, builtins: Boolean = false,
                 onTiming: (String, JSONObject) -> Unit = { _, _ -> }): JSONObject {
        check(settings.optBoolean("networkEnabled")) { "模型联网尚未启用，请在设置中明确开启" }
        checkCancelled()
        val base = CompanionPolicy.endpoint(settings.getString("endpoint"))
        val model = CompanionPolicy.modelId(profile.getString("model"))
        val original = GeminiWire.request(profile, contents, system, tools, builtins)
        val aggregate = GeminiWire.Accumulator()
        var token: String? = null
        val client = transport.newBuilder().connectTimeout(20, TimeUnit.SECONDS)
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
                val startedAt = System.nanoTime()
                fun elapsedMs() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                val timing = JSONObject().put("hop", hop).put("attempt", attempt)
                fun stage(name: String, field: String) {
                    if (!timing.has(field)) {
                        val elapsed = elapsedMs()
                        timing.put(field, elapsed)
                        onTiming(name, JSONObject().put("hop", hop).put("attempt", attempt).put("elapsedMs", elapsed))
                    }
                }
                val firstData = java.util.concurrent.atomic.AtomicLong(0L)
                // Slow models (argon through this relay takes 20-30 s) look dead; after 30 s without a word, say so.
                val slow = SLOW_TIMER.schedule({ if (firstData.get() == 0L) progress("还在想…（模型 30 秒还没出第一个字）") }, 30, TimeUnit.SECONDS)
                try {
                    checkCancelled() // Close cancel-before-activeCall assignment race.
                    transmitted = true
                    call.execute().use { response ->
                        httpStatus = response.code
                        timing.put("headersMs", elapsedMs())
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
                        var events = 0
                        var done = false
                        var eof = false
                        var drainUntil = 0L
                        val preview = StringBuilder(GeminiWire.text(aggregate.parts).take(64000))
                        var previewLength = preview.length
                        val data = StringBuilder()
                        fun flushEvent() {
                            if (data.isNotEmpty()) {
                                val payload = data.toString().trim()
                                if (payload == "[DONE]") done = true
                                else {
                                    val event = runCatching { JSONObject(payload) }.getOrElse { throw ModelRejected(200, "中转返回了看不懂的内容", payload.take(800)) }
                                    events++
                                    timing.put("eventCount", events)
                                    stage("first_event", "firstEventMs")
                                    one.accept(event)
                                    val parts = event.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                                    if ((0 until parts.length()).any { parts.optJSONObject(it)?.let { p -> p.optBoolean("thought") && p.optString("text").isNotEmpty() } == true }) stage("first_thought", "firstThoughtMs")
                                    if (event.has("usageMetadata")) stage("first_usage", "firstUsageMs")
                                    val text = GeminiWire.text(parts)
                                    if (text.isNotBlank()) stage("first_text", "firstTextMs")
                                    if (preview.length < 64000) preview.append(text.take(64000 - preview.length))
                                    val now = System.nanoTime()
                                    if (preview.length != previewLength && preview.isNotBlank() && (lastPreviewAt == 0L || now - lastPreviewAt >= TimeUnit.MILLISECONDS.toNanos(400))) {
                                        onText(preview.toString())
                                        previewLength = preview.length
                                        lastPreviewAt = now
                                    }
                                    if (one.finish.isNotEmpty()) {
                                        stage("terminal", "terminalMs")
                                        if (event.has("usageMetadata")) done = true
                                        // Some relays put billing in the next event and leave the SSE socket open.
                                        else if (drainUntil == 0L) drainUntil = now + TimeUnit.MILLISECONDS.toNanos(200)
                                    }
                                }
                                data.setLength(0)
                            }
                        }
                        val timeout = source.timeout()
                        val previousDeadline = if (timeout.hasDeadline()) timeout.deadlineNanoTime() else null
                        try {
                            while (!done) {
                                checkCancelled()
                                if (drainUntil != 0L) {
                                    if (System.nanoTime() >= drainUntil) break
                                    timeout.deadlineNanoTime(previousDeadline?.let { minOf(it, drainUntil) } ?: drainUntil)
                                }
                                val line = source.readUtf8Line()
                                if (line == null) { eof = true; break }
                                bytes += line.toByteArray(Charsets.UTF_8).size + 1L
                                check(bytes < 24 * 1024 * 1024) { "模型响应超过本机限制" }
                                if (line.startsWith("data:")) firstData.compareAndSet(0L, System.nanoTime())
                                if (line.isEmpty()) flushEvent()
                                else if (line.startsWith("data:")) {
                                    if (data.isNotEmpty()) data.append('\n')
                                    data.append(line.removePrefix("data:").trimStart())
                                }
                            }
                            // Accept a complete JSON event at EOF even if its final blank separator is absent.
                            if (!done && eof) flushEvent()
                        } catch (e: InterruptedIOException) {
                            checkCancelled()
                            if (drainUntil == 0L) throw e
                        } finally {
                            if (previousDeadline == null) timeout.clearDeadline() else timeout.deadlineNanoTime(previousDeadline)
                        }
                        if (one.finish.isEmpty()) throw ModelFailure(response.code, "回答没有正常结束（流被截断）")
                        completed = true
                    }
                    if (completed) break
                } catch (e: IOException) {
                    checkCancelled()
                    // Not replayed here; tools only run after a complete answer, so the caller may retry the whole round.
                    val waited = elapsedMs() / 1000
                    val heard = if (firstData.get() == 0L) "一个字都没收到" else "第 ${TimeUnit.NANOSECONDS.toSeconds(firstData.get() - startedAt)} 秒开始有数据"
                    val detail = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}；等了 $waited 秒，$heard"
                    io.deepseekharness.mobile.companion.XLog.w("model stream broke ($model): $detail")
                    throw ModelFailure(null, "模型连接中断（${e.javaClass.simpleName}，$waited 秒）", detail)
                } finally {
                    slow.cancel(false)
                    activeCall = null
                    timing.put("elapsedMs", elapsedMs()).put("textChars", GeminiWire.text(one.parts).length)
                    onAttemptFinished(AttemptResult(
                        when { !transmitted -> "not_sent"; completed -> "completed"; httpStatus != null && httpStatus !in 200..299 -> "rejected"; else -> "unknown" },
                        JSONObject(one.usage.toString()), transmitted, httpStatus, timing))
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
