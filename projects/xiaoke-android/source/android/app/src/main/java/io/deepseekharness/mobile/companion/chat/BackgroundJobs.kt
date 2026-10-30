package io.deepseekharness.mobile.companion.chat

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Things she set off in the background (a B站 video, an article, a web lookup, a reminder, a summary), tied to the chat
 * that asked for them. Success or failure, the outcome is shown to her in that chat's next turn and through job_status,
 * so she reports what happened instead of guessing. Her own idle browsing is kept under [SELF] (the owner sees it).
 */
object BackgroundJobs {
    const val SELF = "self"
    private const val KEY = "backgroundJobs"
    private const val KEEP_MS = 3 * 86_400_000L
    private val clock = DateTimeFormatter.ofPattern("M/d HH:mm")

    fun start(store: CompanionStore, scope: String, kind: String, title: String, now: Long = System.currentTimeMillis()): String {
        val id = "bg-" + CompanionPolicy.id()
        change(store, now) { it.add(JSONObject().put("id", id).put("scope", scope).put("kind", kind).put("title", title.take(80)).put("state", "running").put("startedAt", now)) }
        return id
    }

    fun finish(store: CompanionStore, id: String, ok: Boolean, outcome: String, now: Long = System.currentTimeMillis()) = change(store, now) { list ->
        list.firstOrNull { it.optString("id") == id }?.put("state", if (ok) "done" else "failed")?.put("outcome", outcome.take(300))?.put("endedAt", now)
    }

    /** A job tracked elsewhere (social summaries and reminders), recorded here when it ends. */
    fun record(store: CompanionStore, scope: String, kind: String, title: String, ok: Boolean, outcome: String, now: Long = System.currentTimeMillis()) =
        change(store, now) { it.add(JSONObject().put("id", "bg-" + CompanionPolicy.id()).put("scope", scope).put("kind", kind).put("title", title.take(80))
            .put("state", if (ok) "done" else "failed").put("outcome", outcome.take(300)).put("startedAt", now).put("endedAt", now)) }

    fun items(store: CompanionStore, scope: String, ownerView: Boolean, now: Long = System.currentTimeMillis()): List<JSONObject> {
        val all = store.lifeState(KEY).optJSONArray("items") ?: JSONArray()
        return (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filter { now - it.optLong("startedAt") < KEEP_MS && (ownerView || it.optString("scope") == scope) }
    }

    fun describe(store: CompanionStore, scope: String, ownerView: Boolean, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String =
        items(store, scope, ownerView, now).filter { it.optString("state") == "running" || now - it.optLong("endedAt") < 86_400_000L }.takeLast(8).joinToString("\n") { line(it, zone, now) }

    fun line(item: JSONObject, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): String {
        val at = clock.format(Instant.ofEpochMilli(item.optLong("startedAt")).atZone(zone))
        val who = if (item.optString("scope") == SELF) "（你自己逛的）" else ""
        val state = when (item.optString("state")) {
            "running" -> if (now - item.optLong("startedAt") > 20 * 60_000L) "还没结果（已经过了 ${(now - item.optLong("startedAt")) / 60_000} 分钟，原因不知道）" else "进行中"
            "done" -> "完成：" + item.optString("outcome")
            else -> "没成：" + item.optString("outcome")
        }
        return "- $at ${item.optString("title")}$who：$state"
    }

    /** Millisecond timestamps, or local "2026-11-04 09:00" / "2026-11-04T09:00" / "2026-11-04" (9:00) / "11-04 09:00" (this or next year). */
    fun dueAt(value: Any?, now: java.time.LocalDateTime = java.time.LocalDateTime.now(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        if (value is Number) return value.toLong().takeIf { it > 0 }
        val text = value?.toString()?.trim()?.replace('T', ' ')?.replace('/', '-').orEmpty()
        text.toLongOrNull()?.let { return it.takeIf { n -> n > 1_000_000_000_000L } }
        val full = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2}))?").matchEntire(text)
        val short = Regex("(\\d{1,2})-(\\d{1,2})(?:\\s+(\\d{1,2}):(\\d{2}))?").matchEntire(text)
        val time = when {
            full != null -> runCatching { java.time.LocalDateTime.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt(),
                full.groupValues[4].ifBlank { "9" }.toInt(), full.groupValues[5].ifBlank { "0" }.toInt()) }.getOrNull()
            short != null -> runCatching { java.time.LocalDateTime.of(now.year, short.groupValues[1].toInt(), short.groupValues[2].toInt(),
                short.groupValues[3].ifBlank { "9" }.toInt(), short.groupValues[4].ifBlank { "0" }.toInt()) }.getOrNull()?.let { if (it.isBefore(now)) it.plusYears(1) else it }
            else -> null
        } ?: return null
        return time.atZone(zone).toInstant().toEpochMilli()
    }

    private fun change(store: CompanionStore, now: Long, edit: (MutableList<JSONObject>) -> Unit) = synchronized(this) {
        val all = store.lifeState(KEY).optJSONArray("items") ?: JSONArray()
        val list = (0 until all.length()).mapNotNull { all.optJSONObject(it) }.filter { now - it.optLong("startedAt") < KEEP_MS }.toMutableList()
        edit(list)
        store.saveLifeState(KEY, JSONObject().put("items", JSONArray(list.takeLast(40))))
    }
}
