package io.deepseekharness.mobile.companion.social

import org.json.JSONArray
import org.json.JSONObject

/** Bounded traversal of received segments. Embedded text never becomes an executable CQ segment. */
object OneBotSegments {
    fun parse(input: JSONArray, account: String): JSONObject {
        val text = StringBuilder(); val attachments = JSONArray(); val images = JSONArray()
        var nodes = 0; var truncated = false; var mentioned = false; var mentionsOther = false; var replyTo = ""
        fun walk(list: JSONArray, depth: Int, source: String) {
            if (depth > 4) { truncated = true; return }
            for (index in 0 until list.length()) {
                if (++nodes > 256 || text.length > 32000) { truncated = true; return }
                val segment = list.optJSONObject(index) ?: continue
                val kind = segment.optString("type"); val data = segment.optJSONObject("data") ?: JSONObject()
                val ref = "$source/$index"
                when (kind) {
                    "text" -> text.append(data.optString("text").take(8000))
                    "at" -> {
                        val who = data.optString("qq")
                        if (depth == 0) { if (who == account) mentioned = true else if (who != "all") mentionsOther = true }
                        text.append(" @").append(who.take(24))
                    }
                    "reply" -> { if (depth == 0) replyTo = data.optString("id").take(80); text.append(" [引用消息]") }
                    "face" -> {
                        // Newer "super" faces are missing from the qface table; NapCat carries their name in raw.faceText.
                        val name = io.deepseekharness.mobile.companion.chat.QqFaces.name(data.optString("id"))
                            ?: data.optJSONObject("raw")?.optString("faceText")?.trim()?.removePrefix("/")?.takeIf { it.isNotBlank() && it.length <= 12 }
                        text.append(if (name != null) " [表情:$name]" else " [QQ表情]")
                        attachments.put(JSONObject().put("kind", "face").put("platformId", data.optString("id").take(80)).put("source", ref).put("status", "typed"))
                    }
                    // Market stickers, and pictures QQ marks as stickers (sub_type 1): looked at once, then remembered by hash.
                    "mface", "image" -> if (kind == "mface" || data.optString("sub_type") == "1") {
                        val summary = data.optString("summary").trim().removePrefix("[").removeSuffix("]").take(30)
                        val placeholder = " [表情包${if (summary.isNotBlank() && summary != "动画表情") "：$summary" else ""}#${attachments.length()}]"
                        text.append(placeholder)
                        if (attachments.length() < 64) attachments.put(JSONObject().put("kind", "sticker").put("source", ref).put("status", "unread")
                            .put("url", data.optString("url").take(4096)).put("summary", summary).put("placeholder", placeholder))
                        else truncated = true
                    } else {
                        text.append(" [图片附件]")
                        val item = JSONObject().put("kind", kind).put("source", ref).put("status", "unread")
                            .put("url", data.optString("url").take(4096)).put("platformId", data.optString("file_id", data.optString("file")).take(512))
                            .put("name", data.optString("name").take(160)).put("retention", "transient")
                        if (attachments.length() < 64) attachments.put(item) else truncated = true
                        if (images.length() < 3) images.put(JSONObject().put("url", item.optString("url")))
                    }
                    "record", "video", "file" -> {
                        text.append(when(kind) { "record" -> " [语音待理解]"; "video" -> " [视频待理解]"; else -> " [文件未下载]" })
                        val item = JSONObject().put("kind", kind).put("source", ref).put("status", "unread")
                            .put("url", data.optString("url").take(4096)).put("platformId", data.optString("file_id", data.optString("file")).take(512))
                            .put("name", data.optString("name").take(160)).put("retention", "transient")
                        if (attachments.length() < 64) attachments.put(item) else truncated = true
                    }
                    "forward", "node" -> {
                        val nested = data.optJSONArray("content") ?: data.optJSONArray("message")
                        attachments.put(JSONObject().put("kind", "forward").put("platformId", data.optString("id").take(256)).put("source", ref)
                            .put("status", if (nested == null) "requires_fetch" else "expanded"))
                        text.append(" [转发资料，非当前发言人授权：")
                        if (nested != null) {
                            for (j in 0 until nested.length().coerceAtMost(64)) {
                                val child = nested.optJSONObject(j) ?: continue
                                val message = child.optJSONArray("message") ?: child.optJSONArray("content")
                                if (message != null) walk(message, depth + 1, "$ref/$j") else walk(JSONArray().put(child), depth + 1, "$ref/$j")
                            }
                        } else text.append("内容尚未读取")
                        text.append(']')
                    }
                    else -> { text.append(" [不支持的附件]"); if (attachments.length() < 64) attachments.put(JSONObject().put("kind", kind.take(40)).put("source", ref).put("status", "unsupported")) }
                }
            }
        }
        walk(input, 0, "segment")
        return JSONObject().put("text", text.toString().take(32000)).put("attachments", attachments).put("images", images)
            .put("mentioned", mentioned).put("mentionsOther", mentionsOther).put("replyTo", replyTo).put("truncated", truncated)
    }
}
