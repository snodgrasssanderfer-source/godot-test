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
    fun request(profile: JSONObject, contents: JSONArray, system: String, tools: JSONArray): JSONObject {
        val generation = JSONObject().put("maxOutputTokens", profile.getInt("maxOutputTokens"))
        if (profile.optString("thinkingMode", "level") == "level") generation.put("thinkingConfig",
            JSONObject().put("thinkingLevel", profile.getString("thinking").uppercase()).put("includeThoughts", false))
        return JSONObject().put("contents", contents).put("generationConfig", generation)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .also { if (profile.optBoolean("tools", true) && tools.length() > 0) it.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools))) }
    }
    fun text(parts: JSONArray) = (0 until parts.length()).map { parts.getJSONObject(it) }
        .filter { !it.optBoolean("thought") && it.has("text") }.joinToString("") { it.optString("text") }

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
        fun accept(response: JSONObject) {
            val prompt = response.optJSONObject("promptFeedback")
            if (prompt?.has("blockReason") == true) error("模型未生成内容：${prompt.optString("blockReason")}")
            if (response.has("usageMetadata")) usage = response.getJSONObject("usageMetadata")
            val candidate = response.optJSONArray("candidates")?.optJSONObject(0) ?: return
            val incoming = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
            for (i in 0 until incoming.length()) parts.put(JSONObject(incoming.getJSONObject(i).toString()))
            if (candidate.has("finishReason")) finish = candidate.getString("finishReason")
            if (candidate.has("continuationToken")) continuation = candidate.getString("continuationToken")
        }
        fun result(model: String) = JSONObject().put("role", "model").put("parts", parts)
            .put("modelId", model).put("finishReason", finish).put("usageMetadata", usage)
    }
}

class GeminiClient(private val secrets: CompanionSecrets) {
    @Volatile private var activeCall: Call? = null
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true); activeCall?.cancel() }
    fun reset() { cancelled.set(false) }
    fun checkCancelled() { check(!cancelled.get() && !Thread.currentThread().isInterrupted) { "任务已取消" } }

    fun generate(settings: JSONObject, profile: JSONObject, contents: JSONArray, system: String, tools: JSONArray,
                 progress: (String) -> Unit): JSONObject {
        check(settings.optBoolean("networkEnabled")) { "模型联网尚未启用，请在设置中明确开启" }
        checkCancelled()
        val base = CompanionPolicy.endpoint(settings.getString("endpoint"))
        val model = CompanionPolicy.modelId(profile.getString("model"))
        val original = GeminiWire.request(profile, contents, system, tools)
        val aggregate = GeminiWire.Accumulator()
        var token: String? = null
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(profile.optLong("timeoutSeconds", 180), TimeUnit.SECONDS)
            .callTimeout(profile.optLong("timeoutSeconds", 180) + 30, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        for (hop in 0 until profile.optInt("maxHops", 4)) {
            checkCancelled()
            val body = JSONObject(original.toString())
            if (token != null) body.put("continuationToken", token)
            val one = GeminiWire.Accumulator()
            var completed = false
            for (attempt in 0..2) {
                checkCancelled()
                val request = Request.Builder().url("$base/models/$model:streamGenerateContent?alt=sse")
                    .header("x-goog-api-key", secrets.read()).header("Accept", "text/event-stream")
                    .post(body.toString().toRequestBody("application/json".toMediaType())).build()
                val call = client.newCall(request)
                activeCall = call
                try {
                    call.execute().use { response ->
                        if (response.code == 429 || response.code in 500..599) {
                            if (attempt == 2) error("模型服务暂不可用（HTTP ${response.code}），请稍后重试")
                            val wait = response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 30) ?: (attempt + 1).toLong()
                            progress("服务繁忙，${wait} 秒后重试（${attempt + 1}/2）")
                            repeat((wait * 10).toInt()) { checkCancelled(); Thread.sleep(100) }
                            return@use
                        }
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
                                if (payload != "[DONE]") one.accept(JSONObject(payload))
                                data.setLength(0)
                            }
                        }
                        while (!source.exhausted()) {
                            checkCancelled()
                            val line = source.readUtf8Line() ?: break
                            bytes += line.length
                            check(bytes < 24 * 1024 * 1024) { "模型响应超过本机限制" }
                            if (line.isEmpty()) flushEvent()
                            else if (line.startsWith("data:")) {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.removePrefix("data:").trimStart())
                            }
                        }
                        flushEvent()
                        check(one.finish.isNotEmpty()) { "模型流中断，未提交任何工具动作；可手动重试" }
                        completed = true
                    }
                    if (completed) break
                } catch (_: IOException) {
                    checkCancelled()
                    // Do not replay a partially consumed stream or tool calls after uncertain network loss.
                    error("模型连接中断，已停止本轮；未执行尚未完成的工具结果，请手动重试")
                } finally { activeCall = null }
            }
            check(completed) { "模型请求未完成" }
            for (i in 0 until one.parts.length()) aggregate.parts.put(one.parts.getJSONObject(i))
            aggregate.finish = one.finish
            aggregate.usage = one.usage
            if (one.finish != "CONTINUATION") {
                check(one.finish !in setOf("SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST", "MALFORMED_FUNCTION_CALL")) { "模型停止生成：${one.finish}" }
                return aggregate.result(model)
            }
            token = one.continuation ?: error("模型要求续写却没有 continuation token")
            progress("继续生成（第 ${hop + 2} 段，仍受本轮总预算限制）")
        }
        error("已达到续写次数上限，本轮停止；请调整预算后重新发起")
    }
}
