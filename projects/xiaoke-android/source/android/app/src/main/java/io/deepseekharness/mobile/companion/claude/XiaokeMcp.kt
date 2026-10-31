package io.deepseekharness.mobile.companion.claude

import org.json.JSONArray
import org.json.JSONObject

/**
 * The MCP side of Xiaoke's abilities (JSON-RPC 2.0, MCP tools). Each Claude turn gets a [Grant] bound to one
 * conversation: the tools it lists are exactly what that conversation may use with Gemini, and every call runs through
 * the same executor, so scope rules (owner memory, QQ targets, approvals) are enforced in one place for both engines.
 */
object XiaokeMcp {
    class Grant(val scope: String, val declarations: JSONArray, val call: (String, JSONObject) -> JSONObject)

    const val PROTOCOL = "2025-06-18"
    private val KNOWN = setOf("2024-11-05", "2025-03-26", "2025-06-18")

    /** Returns the JSON-RPC response, or null for a notification. */
    fun handle(request: JSONObject, grant: Grant): JSONObject? {
        val id = request.opt("id")
        val method = request.optString("method")
        if (id == null || id == JSONObject.NULL) return null
        return try {
            val result = when (method) {
                "initialize" -> JSONObject()
                    .put("protocolVersion", request.optJSONObject("params")?.optString("protocolVersion")?.takeIf { it in KNOWN } ?: PROTOCOL)
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "xiaoke").put("version", "1"))
                    .put("instructions", "小克自己的能力：记忆、日记、QQ、手机画面、B站和待审清单。只在当前这个会话的范围内有效。")
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", tools(grant.declarations))
                "tools/call" -> {
                    val params = request.optJSONObject("params") ?: JSONObject()
                    val name = params.optString("name")
                    val known = (0 until grant.declarations.length()).any { grant.declarations.getJSONObject(it).optString("name") == name }
                    if (!known) return error(id, -32602, "这个会话没有工具 $name")
                    callResult(runCatching { grant.call(name, params.optJSONObject("arguments") ?: JSONObject()) }
                        .getOrElse { JSONObject().put("error", (it.message ?: "操作不可用").take(300)) })
                }
                else -> return error(id, -32601, "不支持 $method")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) { error(id, -32603, e.message ?: "内部错误") }
    }

    private fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
        .put("error", JSONObject().put("code", code).put("message", message.take(300)))

    fun tools(declarations: JSONArray) = JSONArray().also { out ->
        for (i in 0 until declarations.length()) declarations.getJSONObject(i).let { d ->
            out.put(JSONObject().put("name", d.getString("name")).put("description", d.optString("description"))
                .put("inputSchema", d.optJSONObject("parameters")?.let(::schema) ?: JSONObject().put("type", "object").put("properties", JSONObject())))
        }
    }

    /** Gemini's OpenAPI subset (upper-case types) → JSON Schema. */
    fun schema(gemini: JSONObject): JSONObject {
        val out = JSONObject()
        gemini.keys().forEach { key ->
            val value = gemini.get(key)
            when {
                key == "type" && value is String -> out.put("type", value.lowercase())
                key == "properties" && value is JSONObject -> out.put("properties", JSONObject().also { p -> value.keys().forEach { k -> p.put(k, schema(value.getJSONObject(k))) } })
                key == "items" && value is JSONObject -> out.put("items", schema(value))
                else -> out.put(key, value)
            }
        }
        return out
    }

    /** A tool's JSON result as MCP content; a screenshot the tool returned goes as an image block Claude can see. */
    fun callResult(result: JSONObject): JSONObject {
        val content = JSONArray()
        val frame = if (result.has("preview")) result else result.optJSONObject("after")
        val preview = frame?.optString("preview").orEmpty()
        if (preview.isNotEmpty()) frame?.remove("preview")
        content.put(JSONObject().put("type", "text").put("text", result.toString()))
        if (preview.startsWith("data:image/jpeg;base64,") && preview.length < 6 * 1024 * 1024)
            content.put(JSONObject().put("type", "image").put("data", preview.substringAfter(',')).put("mimeType", "image/jpeg"))
        return JSONObject().put("content", content).put("isError", result.has("error"))
    }
}
