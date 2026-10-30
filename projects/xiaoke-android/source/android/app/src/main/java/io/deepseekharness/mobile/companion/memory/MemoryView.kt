package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONObject
import java.time.ZoneId

/** What a memory looks like in her prompt: the words, when it held, whether it still does, and whose view it is. */
object MemoryView {
    fun project(m: JSONObject, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): JSONObject {
        val out = JSONObject().put("id", m.optString("id")).put("scope", m.optString("scope")).put("text", m.optString("text")).put("kind", m.optString("kind"))
        val from = m.optLong("validFrom", m.optLong("occurredAt"))
        if (from > 0) out.put("since", TimeAnchor.format(TimeAnchor.date(from, zone)))
        m.optLong("validUntil").takeIf { it > 0 }?.let { out.put("until", TimeAnchor.format(TimeAnchor.date(it, zone))) }
        if (MemoryRanking.past(m, now)) out.put("past", true)
        if (m.optString("holder") == "her" || m.optString("kind") == "opinion") out.put("who", "你自己")
        if (m.optBoolean("pinned")) out.put("pinned", true)
        if (m.optString("status") == "unverified") out.put("unverified", true)
        if (m.optBoolean("evidenceMissing")) out.put("evidenceMissing", true)
        return out
    }
}
