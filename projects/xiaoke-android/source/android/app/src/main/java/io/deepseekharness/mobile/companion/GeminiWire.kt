package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

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
