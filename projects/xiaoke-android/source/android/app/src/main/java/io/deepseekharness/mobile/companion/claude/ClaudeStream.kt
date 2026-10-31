package io.deepseekharness.mobile.companion.claude

import org.json.JSONArray
import org.json.JSONObject

/**
 * Claude Code's `-p --input-format stream-json --output-format stream-json` protocol: one JSON object per line each
 * way. Field names follow what Claude Code 2.1.x emits (cross-checked with cccodroid gateway/src/stream.mjs).
 */
object ClaudeStream {
    sealed interface Event
    data class Init(val sessionId: String, val model: String) : Event
    data class TextDelta(val text: String) : Event
    data class ToolUse(val name: String, val input: JSONObject) : Event
    data class RateLimit(val usage: JSONObject) : Event
    data class Permission(val requestId: String, val tool: String, val input: JSONObject, val reason: String) : Event
    data class OtherControl(val requestId: String, val subtype: String) : Event
    data class Result(val sessionId: String, val isError: Boolean, val text: String, val subtype: String) : Event
    data object Ignored : Event

    fun parse(line: String): Event {
        val json = runCatching { JSONObject(line.trim()) }.getOrNull() ?: return Ignored
        return when (json.optString("type")) {
            "system" -> if (json.optString("subtype") == "init") Init(json.optString("session_id"), json.optString("model")) else Ignored
            "stream_event" -> json.optJSONObject("event")?.takeIf { it.optString("type") == "content_block_delta" }
                ?.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let { TextDelta(it.optString("text")) } ?: Ignored
            "assistant" -> json.optJSONObject("message")?.optJSONArray("content")?.let { content ->
                (0 until content.length()).map(content::getJSONObject).lastOrNull { it.optString("type") == "tool_use" }
                    ?.let { ToolUse(it.optString("name"), it.optJSONObject("input") ?: JSONObject()) }
            } ?: Ignored
            "rate_limit_event" -> json.optJSONObject("rate_limit_info")?.let { RateLimit(usage(it)) } ?: Ignored
            "control_request" -> {
                val id = json.optString("request_id")
                val request = json.optJSONObject("request") ?: JSONObject()
                if (request.optString("subtype") == "can_use_tool")
                    Permission(id, request.optString("tool_name"), request.optJSONObject("input") ?: JSONObject(), request.optString("decision_reason"))
                else OtherControl(id, request.optString("subtype"))
            }
            "result" -> Result(json.optString("session_id"), json.optBoolean("is_error") || json.optString("subtype") != "success",
                json.optString("result"), json.optString("subtype"))
            else -> Ignored
        }
    }

    /** The subscription windows Claude Code reports. Only its own numbers; nothing is estimated here. */
    fun usage(info: JSONObject): JSONObject {
        val out = JSONObject().put("status", info.optString("status")).put("type", info.optString("rateLimitType"))
            .put("resetsAt", info.optLong("resetsAt")).put("updatedAt", System.currentTimeMillis())
        if (info.has("utilization")) out.put("utilization", info.optDouble("utilization"))
        info.optJSONObject("unifiedWindows")?.let { windows ->
            for ((key, name) in listOf("five_hour" to "fiveHour", "seven_day" to "sevenDay", "seven_day_opus" to "sevenDayOpus")) {
                windows.optJSONObject(key)?.let { w -> out.put(name, JSONObject().put("utilization", w.optDouble("utilization")).put("resetsAt", w.optLong("resetsAt"))) }
            }
        }
        return out
    }

    /** Folds a new report into the stored one: a report about one window keeps what is known about the others. */
    fun mergeUsage(old: JSONObject, new: JSONObject): JSONObject {
        val merged = JSONObject(old.toString())
        new.keys().forEach { merged.put(it, new.get(it)) }
        val type = new.optString("type")
        val window = when (type) { "five_hour" -> "fiveHour"; "seven_day" -> "sevenDay"; "seven_day_opus" -> "sevenDayOpus"; else -> "" }
        if (window.isNotEmpty() && !new.has(window) && new.has("utilization"))
            merged.put(window, JSONObject().put("utilization", new.optDouble("utilization")).put("resetsAt", new.optLong("resetsAt")))
        return merged
    }

    fun userMessage(content: Any): String = JSONObject().put("type", "user")
        .put("message", JSONObject().put("role", "user").put("content", content)).toString()

    fun allow(requestId: String, input: JSONObject): String = control(requestId, JSONObject().put("behavior", "allow").put("updatedInput", input))
    fun deny(requestId: String, message: String): String = control(requestId, JSONObject().put("behavior", "deny").put("message", message))
    fun unsupported(requestId: String, subtype: String): String = JSONObject().put("type", "control_response")
        .put("response", JSONObject().put("subtype", "error").put("request_id", requestId).put("error", "unsupported: $subtype")).toString()

    private fun control(requestId: String, response: JSONObject) = JSONObject().put("type", "control_response")
        .put("response", JSONObject().put("subtype", "success").put("request_id", requestId).put("response", response)).toString()

    enum class Failure { NOT_INSTALLED, LOGIN, LIMIT, ERROR }

    /** Why a turn failed, from Claude Code's own result text or exit output. Login and quota are told apart from errors. */
    fun classify(text: String, usage: JSONObject? = null): Failure {
        val t = text.lowercase()
        return when {
            usage?.optString("status") == "rejected" -> Failure.LIMIT
            listOf("not logged in", "please run /login", "invalid api key", "oauth token", "authentication_error", "login required", "api error: 401").any { it in t } -> Failure.LOGIN
            listOf("usage limit", "rate limit", "limit reached", "rate_limit", "api error: 429", "out of extra usage", "session limit").any { it in t } -> Failure.LIMIT
            else -> Failure.ERROR
        }
    }

    /** One-line description of what a tool wants to do, for the approval prompt. */
    fun describe(tool: String, input: JSONObject): String {
        val detail = when (tool) {
            "Bash" -> input.optString("command")
            "Edit", "MultiEdit", "Write", "Read", "NotebookEdit" -> input.optString("file_path").ifBlank { input.optString("notebook_path") }
            "WebFetch" -> input.optString("url")
            "WebSearch" -> input.optString("query")
            else -> input.toString()
        }
        val verb = when (tool) { "Bash" -> "运行命令"; "Edit", "MultiEdit" -> "修改文件"; "Write" -> "写文件"; "NotebookEdit" -> "改笔记本"; "WebFetch" -> "读网页"; else -> "用 $tool" }
        return "$verb：${detail.replace(Regex("\\s+"), " ").take(300)}"
    }

    /** What she is doing right now, in a few words, for the step list under her reply. */
    fun step(tool: String, input: JSONObject): String {
        val name = tool.substringAfterLast("__")
        fun short(v: String, n: Int = 60) = v.replace(Regex("\\s+"), " ").let { if (it.length > n) it.take(n) + "…" else it }
        return when (name) {
            "Bash" -> "在跑 " + short(input.optString("command"))
            "Read" -> "在看 " + input.optString("file_path").substringAfterLast('/')
            "Edit", "MultiEdit", "Write", "NotebookEdit" -> "在改 " + input.optString("file_path").ifBlank { input.optString("notebook_path") }.substringAfterLast('/')
            "Grep", "Glob" -> "在搜 " + short(input.optString("pattern"), 40)
            "WebSearch", "web_research" -> "在网上搜 " + short(input.optString("query"), 40)
            "WebFetch" -> "在读网页 " + short(input.optString("url"), 50)
            "TodoWrite" -> "在列步骤"
            "Task" -> "在分头查"
            "remember" -> "在记下来"
            "recall" -> "在翻记忆" + input.optString("query").takeIf { it.isNotBlank() }?.let { "：" + short(it, 30) }.orEmpty()
            "job_status" -> "在看后台的事"
            "life_action" -> "在看生活记录（${input.optString("action")}）"
            "send_qq" -> "在发 QQ"
            "say_in_group" -> "在去群里说"
            "poke" -> "在戳人"
            "watch_bilibili", "bili_search" -> "在看 B站"
            "device_action" -> "在操作手机（${input.optString("action")}）"
            "observe_screen" -> "在看屏幕"
            "open_app" -> "在打开应用"
            "draft_comment" -> "在写评论草稿"
            "background_task" -> "在安排提醒"
            "social_plan" -> "在安排外发"
            else -> "在用 $name"
        }
    }

    /** Text content for a stream-json user turn, with images as base64 blocks (Claude reads JPEG/PNG/GIF/WebP). */
    fun content(text: String, images: List<Pair<String, String>>): Any {
        if (images.isEmpty()) return text
        return JSONArray().put(JSONObject().put("type", "text").put("text", text)).also { blocks ->
            images.forEach { (mime, data) -> blocks.put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data))) }
        }
    }
}
