package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONObject
import java.time.ZoneId
import java.time.Instant
import java.time.format.DateTimeFormatter

/** What a memory looks like in her prompt: the words, when it held, whether it still does, and whose view it is. */
object MemoryView {
    fun project(m: JSONObject, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val out = JSONObject().put("id", m.optString("id")).put("scope", m.optString("scope")).put("text", m.optString("text")).put("kind", m.optString("kind"))
        val from = m.optLong("validFrom", m.optLong("occurredAt"))
        if (from > 0) out.put("since", TimeAnchor.format(TimeAnchor.date(from, zone)))
        m.optLong("validUntil").takeIf { it > 0 }?.let { out.put("until", TimeAnchor.format(TimeAnchor.date(it, zone))) }
        if (MemoryRanking.past(m, now)) out.put("past", true)
        val holder = m.optString("holder")
        out.put("holder", holder).put("who", when (holder) {
            "her" -> "你自己"
            "owner" -> "主人"
            "member" -> "群友"
            "other" -> "他人"
            else -> "归属不明"
        })
        for (key in listOf("actorId", "sourceId", "sourceScope", "source", "endedBy"))
            m.optString(key).takeIf { it.isNotBlank() }?.let { out.put(key, it) }
        m.optJSONArray("links")?.takeIf { it.length() > 0 }?.let { out.put("links", it) }
        for (key in listOf("validFrom", "validUntil")) m.optLong(key).takeIf { it > 0 }?.let {
            out.put(key, Instant.ofEpochMilli(it).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        if (m.optBoolean("pinned")) out.put("pinned", true)
        if (m.optString("status") == "unverified") out.put("unverified", true)
        if (m.optBoolean("evidenceMissing")) out.put("evidenceMissing", true)
        return out
    }
}
