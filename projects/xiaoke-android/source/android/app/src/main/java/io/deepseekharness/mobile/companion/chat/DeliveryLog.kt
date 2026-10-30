package io.deepseekharness.mobile.companion.chat

import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Replies of hers that never went out, and why, as the host saw it. She reads this so that when asked she says what
 * actually happened instead of guessing ("风控", "丢包"). Only causes the host observed are written here.
 */
object DeliveryLog {
    private const val KEY = "deliveryFailures"
    private const val KEEP_MS = 7 * 86_400_000L
    private val clock = DateTimeFormatter.ofPattern("M/d HH:mm")

    fun record(store: CompanionStore, scope: String, kind: String, reason: String, text: String = "", now: Long = System.currentTimeMillis()) {
        val title = store.get("conversation", scope)?.optString("title")?.ifBlank { null } ?: if (scope == "owner:local") "App 里和主人的私聊" else scope
        synchronized(this) {
            val kept = items(store, now)
            kept.add(JSONObject().put("scope", scope).put("title", title.take(60)).put("at", now).put("kind", kind)
                .put("reason", reason.take(200)).put("text", text.take(120)))
            store.saveLifeState(KEY, JSONObject().put("items", JSONArray(kept.takeLast(30))))
        }
    }

    fun items(store: CompanionStore, now: Long = System.currentTimeMillis()): MutableList<JSONObject> {
        val all = store.lifeState(KEY).optJSONArray("items") ?: JSONArray()
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { now - it.optLong("at") < KEEP_MS }.toMutableList()
    }

    /** The owner sees every conversation's failures; anyone else only those of the chat they are in. */
    fun describe(store: CompanionStore, scope: String, ownerView: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String {
        val list = items(store, now).filter { ownerView || it.optString("scope") == scope }.takeLast(8)
        if (list.isEmpty()) return ""
        return list.joinToString("\n") { item ->
            val what = if (item.optString("kind") == "send") "写好了但发送失败" else "没能生成回复"
            val said = item.optString("text").takeIf { it.isNotBlank() }?.let { "（本来想说：$it）" }.orEmpty()
            "- ${clock.format(Instant.ofEpochMilli(item.optLong("at")).atZone(zone))} 在「${item.optString("title")}」$what：${item.optString("reason")}$said"
        }
    }
}
