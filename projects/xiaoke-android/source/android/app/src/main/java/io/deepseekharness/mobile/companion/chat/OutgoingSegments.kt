package io.deepseekharness.mobile.companion.chat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Her reply text → QQ message segments. "[表情:捂脸]" (or a bare "[捂脸]") becomes a real face, "[表情包:名称]" a picture
 * from the owner's sticker shelf sent as its own message. A marker that cannot be resolved is dropped, never sent as text.
 */
object OutgoingSegments {
    /** A sticker ready to send: bytes as base64 and the name it was picked by. */
    data class Sticker(val base64: String, val name: String)

    private val marker = Regex("""\[(表情包|表情)?\s*[:：]?\s*([^\[\]\n]{1,20}?)\s*]""")
    /** Bare "[xxx]" counts as an emoji attempt only when it is short and wordy; "[1]" or "[2024-10-30]" stay text. */
    private val emojiLike = Regex("""[\p{IsHan}A-Za-z]{1,6}""")

    /** Markers the host itself writes into messages she reads ("[图片附件]", "[已读取 1 个媒体片段…]"); they must never go out. */
    private val internal = Regex("\\s*\\[(?:图片附件|语音待理解|视频待理解|文件未下载|引用消息|不支持的附件|QQ表情|转发资料[^\\]]*|已读取[^\\]]*|语音转文字[^\\]]*|内容尚未读取)]")

    fun stripInternal(text: String): String = internal.replace(text, "").replace(Regex("[ \\t]{2,}"), " ").trim()

    fun build(raw: String, sticker: (String) -> Sticker?): List<JSONArray> {
        val text = stripInternal(raw)
        val messages = mutableListOf<JSONArray>()
        var current = JSONArray()
        val pending = StringBuilder()
        fun flushText() { if (pending.isNotBlank()) current.put(JSONObject().put("type", "text").put("data", JSONObject().put("text", pending.toString()))); pending.setLength(0) }
        fun closeMessage() {
            flushText()
            if (current.length() > 0) messages.add(current)
            current = JSONArray()
        }
        var at = 0
        for (match in marker.findAll(text)) {
            pending.append(text, at, match.range.first)
            at = match.range.last + 1
            val kind = match.groupValues[1]; val name = match.groupValues[2].trim()
            when {
                kind == "表情包" -> sticker(name)?.let { s ->
                    closeMessage()
                    messages.add(JSONArray().put(JSONObject().put("type", "image").put("data", JSONObject()
                        .put("file", "base64://${s.base64}").put("sub_type", 1).put("summary", "[${s.name}]"))))
                }
                QqFaces.id(name) != null -> { flushText(); current.put(JSONObject().put("type", "face").put("data", JSONObject().put("id", QqFaces.id(name)))) }
                kind == "表情" || emojiLike.matches(name) -> Unit // unknown emoji marker: dropped
                else -> pending.append(match.value)
            }
        }
        pending.append(text, at, text.length)
        closeMessage()
        // Tidy the gaps left by dropped markers.
        return messages.onEach { message ->
            for (i in 0 until message.length()) message.getJSONObject(i).takeIf { it.optString("type") == "text" }?.getJSONObject("data")?.let { d ->
                d.put("text", d.getString("text").replace(Regex("[ \\t]{2,}"), " "))
            }
        }
    }

    /** What the reply reads like with markers resolved, for places that only take text. */
    fun plain(text: String): String = marker.replace(text) { m ->
        val name = m.groupValues[2].trim()
        if (m.groupValues[1] == "表情包" || m.groupValues[1] == "表情" || QqFaces.id(name) != null || emojiLike.matches(name)) "" else m.value
    }.replace(Regex("[ \\t]{2,}"), " ").trim()
}
