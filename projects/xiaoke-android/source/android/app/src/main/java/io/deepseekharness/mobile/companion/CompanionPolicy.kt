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
        require(value == OWNER || Regex("(qq|bili|web):(private|group|public|unresolved):[A-Za-z0-9_-]{1,128}").matches(value)) { "会话范围无效" }
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

    /** Screens she does not read even when asked: banking, payment, and the system's permission and install prompts. */
    private val unreadablePackages = Regex("bank|alipay|unionpay|wallet|icbc|chinamworld|cmb\\.|\\.ccb|bankabc|bankcomm|spdb|cebbank|cmbc|webank|" +
        "permissioncontroller|packageinstaller|com\\.android\\.settings\\.password", RegexOption.IGNORE_CASE)
    fun unreadable(packageName: String) = packageName.isBlank() || unreadablePackages.containsMatchIn(packageName)

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
            require(p.getInt("contextBudget") - p.getInt("maxOutputTokens") >= 4096) { "上下文预算至少比输出预算多 4096" }
            require(p.optInt("maxHops", 4) in 1..8 && p.optInt("timeoutSeconds", 180) in 30..1200) { "请求限制无效" }
        }
        require(result.getString("activeModel") in ids) { "请选择已有模型" }
        require(result.optInt("dailyCalls", 100) in 1..10000) { "每日请求上限无效" }
        result.remove("apiKey")
        result.remove("oneBotToken")
        if (result.optBoolean("oneBotEnabled")) {
            val u = URI(result.optString("oneBotUrl"))
            require(u.scheme == "wss" && !u.host.isNullOrBlank() && u.userInfo == null && u.query == null && u.fragment == null) { "消息桥需要不含凭据参数的 wss 地址" }
            result.optString("oneBotAccount").takeIf { it.isNotBlank() }?.let(OneBotProtocol::numeric) // Learned on first connection.
        }
        return result
    }

    /** A grant binds the exact draft and target; an edited draft invalidates it. */
    fun canSend(task: JSONObject, now: Long): Boolean =
        task.optString("state") == "approved" && task.optLong("grantExpiresAt") > now &&
            task.optString("grantDigest") == draftDigest(task) && task.optString("scope") != OWNER &&
            task.optString("target").isNotBlank() && task.optString("body").isNotBlank()

    fun draftDigest(task: JSONObject) = digest(listOf("scope", "target", "body", "package", "account", "observationId", "route").joinToString("\u0000") { task.optString(it) } +
        "\u0000" + task.optJSONObject("uiBinding")?.optString("title").orEmpty() + "\u0000" + task.optJSONObject("uiBinding")?.optString("viewId").orEmpty())

    fun recoverState(state: String) = when (state) {
        "sending" -> "unknown"
        "queued", "running", "approved" -> "waiting"
        else -> state
    }

    /** Everyday talk stays on the active (light) model; code, logs, long texts and requests for deep analysis go to the deep model. */
    fun chooseModel(settings: JSONObject, text: String): String {
        val active = settings.getString("activeModel")
        val deep = settings.optString("deepModel", "gemini-4-argon")
        val profiles = settings.getJSONArray("profiles")
        if (deep == active || (0 until profiles.length()).none { profiles.getJSONObject(it).optString("model") == deep }) return active
        val heavy = text.length > 800 || text.contains("```") || Regex("代码|报错|日志|堆栈|推理|证明|算法|长文|仔细分析|深入分析|帮我分析").containsMatchIn(text)
        return if (heavy) deep else active
    }

    fun mayReadMemory(requestScope: String, recordScope: String) = scope(requestScope) == scope(recordScope)

    /** Owner private domain: the local owner session and the owner's own QQ private chat. Its content never reaches another scope. */
    fun ownerPrivate(scope: String, conversation: JSONObject?, settings: JSONObject): Boolean = scope == OWNER ||
        (scope.startsWith("qq:private:") && conversation != null && conversation.optString("targetType") == "private" &&
            settings.optString("ownerQqId").isNotBlank() && conversation.optString("targetId") == settings.optString("ownerQqId"))

    /** Host opt-in applies only to an account-verified OneBot private chat with the owner ID. */
    /** The owner's own QQ private chat, when QQ is set up: one private domain with [OWNER] for memory, profiles, diary and idle checks. */
    fun ownerQqScope(settings: JSONObject): String? {
        val account = settings.optString("oneBotAccount"); val owner = settings.optString("ownerQqId")
        if (account.isBlank() || owner.isBlank() || !settings.optBoolean("shareOwnerMemoryWithQq", true)) return null
        return "qq:private:" + digest("onebot\u0000$account\u0000$owner").take(40)
    }
    fun ownerScopes(settings: JSONObject): List<String> = listOfNotNull(OWNER, ownerQqScope(settings))

    fun sharesOwnerMemory(conversation: JSONObject?, settings: JSONObject): Boolean = conversation != null &&
        settings.optBoolean("shareOwnerMemoryWithQq") && conversation.optString("route") == "onebot" &&
        conversation.optString("targetType") == "private" && conversation.optBoolean("reliable") &&
        settings.optString("ownerQqId").isNotBlank() && conversation.optString("targetId") == settings.optString("ownerQqId") &&
        conversation.optString("account") == settings.optString("oneBotAccount")

    fun cancelledState(state: String) = when (state) {
        "sending", "unknown" -> "unknown"
        "done", "cancelled" -> state
        else -> "cancelled"
    }

    fun sessionFingerprint(settings: JSONObject, profile: JSONObject): String = digest(
        endpoint(settings.getString("endpoint")) + "\u0000" + profile.getString("model") + "\u0000" + settings.optLong("credentialRevision"))

    fun validateMedia(profile: JSONObject, parts: JSONArray) {
        for (i in 0 until parts.length()) {
            val data = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mime = data.optString("mimeType")
            val capability = when {
                mime.startsWith("image/") -> "images"
                mime.startsWith("video/") -> "video"
                mime.startsWith("audio/") -> "audio"
                mime == "application/pdf" -> "pdf"
                else -> error("不支持的附件格式")
            }
            check(profile.optBoolean(capability, false)) { "当前模型配置未启用 $capability，请选择支持该资料的模型或修改配置" }
        }
    }
}
