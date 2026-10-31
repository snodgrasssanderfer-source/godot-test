package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal fun copy(value: JSONObject) = JSONObject(value.toString())
internal fun objects(array: JSONArray) = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
internal fun strings(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
internal fun rows(root: JSONObject, table: String) = root.optJSONObject(table) ?: JSONObject().also { root.put(table, it) }
internal fun values(table: JSONObject) = table.keys().asSequence().toList().map { table.getJSONObject(it) }
internal fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun id() = UUID.randomUUID().toString()
internal fun canonical(value: Any?): String = when (value) {
    null, JSONObject.NULL -> "null"
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
    is String -> JSONObject.quote(value)
    else -> value.toString()
}

/**
 * One atomic durable snapshot; a failed write never commits the in-memory transaction.
 * Reads see the live snapshot without copying it (readers must not change it; a stray change is dropped at the next
 * write, which starts from the last committed text). Writes work on one parsed copy and are detected by comparing its
 * serialized text with the committed one: idle ticks used to deep-copy a ~320 KB ledger several times every 5 seconds.
 */
class SocialLedger(initial: JSONObject = JSONObject(), private val persist: (JSONObject) -> Unit = {}) {
    private var data = copy(initial)
    private var text: String
    init {
        require(data.optInt("version", 1) == 1) { "社交数据版本不受支持" }
        data.put("version", 1)
        text = data.toString()
    }
    @Synchronized internal fun <T> read(block: (JSONObject) -> T): T = block(data)
    @Synchronized internal fun <T> change(block: (JSONObject) -> T): T {
        val next = JSONObject(text)
        val result = block(next)
        val after = next.toString()
        if (after == text) return result
        persist(next)
        // Rebuilt from the text so objects the block returned stay detached from the committed state.
        data = JSONObject(after); text = after
        return result
    }
    @Synchronized fun exportEncryptedInput(): JSONObject = JSONObject(text)
}

internal object SocialRules {
    val effects = mapOf("reply" to "social_send", "post" to "public_post", "comment" to "public_comment",
        "add_friend" to "add_friend", "join_group" to "join_group", "leave_group" to "leave_group",
        "bili_like" to "like", "bili_follow" to "follow", "bili_comment" to "public_comment", "bili_reply" to "public_comment", "qzone_reply" to "public_comment", "qzone_like" to "like", "bili_download" to "download", "save_media" to "save")
    fun owner(p: CommandPrincipal) {
        require(p.owner && (p.channel == "owner_ui" && p.scope == "owner:local" || p.channel == "owner_qq" && p.scope.startsWith("qq:private:"))) { "此操作需要宿主验证的主人身份" }
    }
    fun bounded(value: String, max: Int): String {
        require(value.isNotBlank() && value.length <= max && value.none { it == '\u0000' }) { "内容为空或超过限制" }
        return value
    }
    fun numeric(value: String): String {
        require(Regex("[1-9][0-9]{0,18}").matches(value) && value.toLongOrNull() != null) { "平台账号和会话必须使用数字 ID" }
        return value
    }
    fun digest(action: JSONObject) = hash(canonical(JSONObject().also { out ->
        listOf("kind", "account", "target", "scope", "content", "payload", "sourceIds", "expiresAt").forEach { out.put(it, action.opt(it) ?: JSONObject.NULL) }
    }))
    fun sourceHashes(event: JSONObject): List<String> = (strings(event.optJSONArray("sourceIds")) + event.optString("sourceId") + event.optString("id"))
        .filter { it.isNotBlank() }.distinct().map(::hash)
    fun blocked(root: JSONObject, record: JSONObject) = sourceHashes(record).any { rows(root, "tombstones").has(it) }
}
