package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.UUID

/** Decisions use host-created scope and grants, never a model's claimed identity. */
object CompanionPolicy {
    const val OWNER = "owner:local"
    val packages = setOf("com.tencent.mobileqq", "tv.danmaku.bili")
    val memoryKinds = setOf("fact", "relationship", "event", "task")
    val taskStates = setOf("queued", "running", "waiting", "draft", "approved", "sending", "unknown", "done", "failed", "cancelled")

    fun endpoint(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "模型地址必须是无密钥、无查询参数的 HTTPS 地址"
        }
        val path = uri.path.orEmpty().trimEnd('/')
        require(path.isEmpty() || path.endsWith("/v1beta") || path.endsWith("/v1")) { "使用 Google API 根地址或 /v1beta 路径" }
        return value.trim().trimEnd('/') + if (path.isEmpty()) "/v1beta" else ""
    }

    fun modelId(value: String): String {
        require(Regex("[A-Za-z0-9._-]{1,128}").matches(value)) { "模型 ID 格式无效" }
        return value
    }

    fun scope(value: String): String {
        require(value == OWNER || Regex("(qq|bili):(private|group|public|unresolved):[A-Za-z0-9_-]{1,128}").matches(value)) { "会话范围无效" }
        return value
    }

    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun id() = UUID.randomUUID().toString()
    fun text(value: String, max: Int = 12000): String {
        require(value.isNotBlank() && value.length <= max && !value.contains('\u0000')) { "内容为空或超过长度限制" }
        return value.trim()
    }

    fun profile(id: String): JSONObject = JSONObject().put("id", id).put("model", modelId(id))
        .put("thinking", "low").put("maxOutputTokens", 32768).put("contextBudget", 65536)
        .put("images", true).put("video", true).put("audio", true).put("pdf", true)
        .put("tools", true).put("thinkingMode", "level").put("maxHops", 4).put("timeoutSeconds", 180)

    fun defaultSettings() = JSONObject().put("endpoint", "https://api.apikey.fan/v1beta")
        .put("activeModel", "gemini-3.8-flash")
        .put("profiles", JSONArray().put(profile("gemini-3.8-flash")).put(profile("gemini-4-argon")))
        .put("networkEnabled", false).put("backgroundEnabled", false).put("paused", false)
        .put("dailyCalls", 100).put("ownerQqId", "").put("displayName", "小克")

    fun validateSettings(input: JSONObject): JSONObject {
        val result = JSONObject(input.toString()).put("endpoint", endpoint(input.getString("endpoint")))
        val profiles = result.getJSONArray("profiles")
        require(profiles.length() in 1..12) { "模型配置数量无效" }
        val ids = mutableSetOf<String>()
        for (i in 0 until profiles.length()) {
            val p = profiles.getJSONObject(i)
            require(ids.add(modelId(p.getString("model")))) { "模型 ID 重复" }
            require(p.getString("thinking") in setOf("low", "medium", "high")) { "思考等级无效" }
            require(p.optString("thinkingMode", "level") in setOf("level", "none")) { "思考协议无效" }
            require(p.getInt("maxOutputTokens") in 1024..262144) { "输出预算超出允许范围" }
            require(p.getInt("contextBudget") in 4096..262144) { "上下文预算超出允许范围" }
            require(p.optInt("maxHops", 4) in 1..8 && p.optInt("timeoutSeconds", 180) in 30..1200) { "请求限制无效" }
        }
        require(result.getString("activeModel") in ids) { "请选择已有模型" }
        require(result.optInt("dailyCalls", 100) in 1..10000) { "每日请求上限无效" }
        result.remove("apiKey")
        return result
    }

    /** A grant binds the exact draft and target; an edited draft invalidates it. */
    fun canSend(task: JSONObject, now: Long): Boolean =
        task.optString("state") == "approved" && task.optLong("grantExpiresAt") > now &&
            task.optString("grantDigest") == draftDigest(task) && task.optString("scope") != OWNER &&
            task.optString("target").isNotBlank() && task.optString("body").isNotBlank()

    fun draftDigest(task: JSONObject) = digest(listOf("scope", "target", "body", "package", "account", "observationId").joinToString("\u0000") { task.optString(it) })

    fun recoverState(state: String) = when (state) {
        "sending" -> "unknown"
        "running", "approved" -> "waiting"
        else -> state
    }

    fun mayReadMemory(requestScope: String, recordScope: String) = scope(requestScope) == scope(recordScope)
}
