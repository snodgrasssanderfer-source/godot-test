package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Android port of AstrBot aiocqhttp adapter's typed segment parsing and private/group dispatch.
 * AstrBot abf683071952375ec36baa4cad6b6f3d9c9bb03f, AGPL-3.0; see COMMUNITY_REUSE.md.
 * Uses a user-configured OneBot v11 forward WebSocket, never a bundled QQ login client.
 */
object OneBotProtocol {
    fun address(value: JSONObject, expectedAccount: String): JSONObject? {
        if (value.optString("self_id") != numeric(expectedAccount)) return null
        val type = value.optString("message_type")
        if (type !in setOf("private", "group")) return null
        val target = if (type == "group") numeric(value.optString("group_id")) else
            numeric(if (value.optString("post_type") == "message_sent") value.optString("target_id") else value.optString("user_id"))
        return JSONObject().put("scope", "qq:$type:${CompanionPolicy.digest("onebot\u0000$expectedAccount\u0000$target").take(40)}")
            .put("account", expectedAccount).put("targetId", target).put("targetType", type)
    }
    fun numeric(value: String): String {
        require(Regex("[1-9][0-9]{0,18}").matches(value) && (value.toLongOrNull() ?: 0L) > 0L) { "平台账号或会话 ID 必须为数字" }
        return value
    }
    fun event(value: JSONObject, expectedAccount: String): JSONObject? {
        if (value.optString("post_type") != "message" || value.optString("self_id") != numeric(expectedAccount)) return null
        val type = value.optString("message_type")
        if (type !in setOf("private", "group")) return null
        val sender = numeric(value.optString("user_id"))
        if (sender == expectedAccount) return null
        val target = if (type == "group") numeric(value.optString("group_id")) else sender
        val messageId = value.optString("message_id")
        require(messageId.length in 1..80 && messageId.none { it.isISOControl() })
        // Array segments avoid interpreting somebody's text as a CQ send instruction.
        val segments = value.optJSONArray("message") ?: JSONArray().put(JSONObject().put("type", "text")
            .put("data", JSONObject().put("text", value.optString("raw_message", value.optString("message")))))
        val parsed = io.deepseekharness.mobile.companion.social.OneBotSegments.parse(segments, expectedAccount)
        if (parsed.getString("text").isBlank()) return null
        val scope = "qq:$type:${CompanionPolicy.digest("onebot\u0000$expectedAccount\u0000$target").take(40)}"
        return JSONObject().put("id", "qqmsg-" + CompanionPolicy.digest("$expectedAccount\u0000$scope\u0000$messageId"))
            .put("platformMessageId", messageId).put("scope", scope).put("account", expectedAccount).put("actorId", "qq:$sender")
            .put("occurredAt", value.optLong("time") * 1000).put("receivedAt", System.currentTimeMillis())
            .put("targetId", target).put("targetType", type).put("senderId", sender)
            .put("sender", value.optJSONObject("sender")?.optString("card")?.ifBlank { null }
                ?: value.optJSONObject("sender")?.optString("nickname") ?: sender)
            .put("text", parsed.getString("text")).put("mentioned", parsed.getBoolean("mentioned")).put("images", parsed.getJSONArray("images"))
            .put("mentionsOther", parsed.optBoolean("mentionsOther")).put("replyTo", parsed.optString("replyTo"))
            .put("attachments", parsed.getJSONArray("attachments")).put("truncated", parsed.getBoolean("truncated"))
    }
    /** NapCat poke notice (notice_type notify, sub_type poke): who poked whom, in a group or a private chat. Her own pokes are skipped. */
    fun poke(value: JSONObject, expectedAccount: String): JSONObject? {
        if (value.optString("post_type") != "notice" || value.optString("notice_type") != "notify" || value.optString("sub_type") != "poke") return null
        if (value.optString("self_id") != numeric(expectedAccount)) return null
        val poker = runCatching { numeric(value.optString("user_id")) }.getOrNull() ?: return null
        val target = runCatching { numeric(value.optString("target_id")) }.getOrNull() ?: return null
        if (poker == expectedAccount) return null
        val group = value.optString("group_id").takeIf { it.isNotBlank() && it != "0" }?.let { runCatching { numeric(it) }.getOrNull() ?: return null }
        val type = if (group != null) "group" else "private"
        val conversationId = group ?: poker
        // "戳了戳" / "的小脑袋" from raw_info, when NapCat gives it.
        val raw = value.optJSONArray("raw_info")
        val words = if (raw == null) emptyList() else (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            .filter { it.optString("type") == "nor" }.map { it.optString("txt").trim() }.filter { it.isNotBlank() && it.length <= 20 }
        return JSONObject().put("scope", "qq:$type:${CompanionPolicy.digest("onebot\u0000$expectedAccount\u0000$conversationId").take(40)}")
            .put("id", "qqpoke-" + CompanionPolicy.digest("$expectedAccount\u0000$conversationId\u0000$poker\u0000$target\u0000${value.optLong("time")}"))
            .put("targetType", type).put("targetId", conversationId).put("poker", poker).put("target", target).put("atSelf", target == expectedAccount)
            .put("action", words.firstOrNull() ?: "戳了戳").put("suffix", words.drop(1).firstOrNull().orEmpty())
    }

    /** No arbitrary sender URLs, local bridge paths, cookies, redirects or query logging. */
    fun imageUrl(raw: String): String? = runCatching {
        val u = java.net.URI(raw)
        val host = u.host?.lowercase().orEmpty()
        val allowed = host == "qpic.cn" || host.endsWith(".qpic.cn") || host == "qlogo.cn" || host.endsWith(".qlogo.cn") || host == "multimedia.nt.qq.com" || host == "multimedia.nt.qq.com.cn"
        if (!allowed || u.scheme !in setOf("http", "https") || u.userInfo != null || u.port !in setOf(-1, 443) || u.fragment != null) null
        else if (u.scheme == "http") "https:" + raw.substringAfter(':') else raw
    }.getOrNull()
    fun send(conversation: JSONObject, body: String, echo: String): JSONObject =
        send(conversation, JSONArray().put(JSONObject().put("type", "text").put("data", JSONObject().put("text", CompanionPolicy.text(body, 4000)))), echo)

    /** Only text, face and base64 image segments built by OutgoingSegments; never CQ strings or URLs from elsewhere. */
    fun send(conversation: JSONObject, message: JSONArray, echo: String): JSONObject {
        val type = conversation.getString("targetType")
        require(type in setOf("private", "group"))
        for (i in 0 until message.length()) message.getJSONObject(i).let { segment ->
            require(segment.optString("type") in setOf("text", "face", "image"))
            if (segment.optString("type") == "image") require(segment.getJSONObject("data").optString("file").startsWith("base64://"))
            if (segment.optString("type") == "text") segment.getJSONObject("data").optString("text").let { require(it.length <= 4000 && '\u0000' !in it) { "内容超过长度限制" } }
        }
        // NapCat SendMsgPayloadSchema uses string QQ IDs; avoid number coercion across runtimes.
        val params = JSONObject().put(if (type == "group") "group_id" else "user_id", numeric(conversation.getString("targetId"))).put("message", message)
        return JSONObject().put("action", if (type == "group") "send_group_msg" else "send_private_msg").put("params", params).put("echo", echo)
    }
}
