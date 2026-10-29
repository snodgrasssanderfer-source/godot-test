package io.deepseekharness.mobile.companion.life

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.removeGrant
import org.json.JSONArray
import org.json.JSONObject

/** Restore planning is pure so tombstone precedence is tested without a mocked database. */
object LifeBackup {
    val kinds = listOf("persona", "personaVersion", "memory", "message", "observation", "task", "diary", "interest", "lifeEvent", "lifeState")
    data class Plan(val rows: List<Pair<String, JSONObject>>, val tombstones: List<JSONObject>, val revisions: List<JSONObject>)

    fun rows(data: JSONObject): List<Pair<String, JSONObject>> = kinds.flatMap { kind ->
        val all = data.optJSONArray(kind) ?: JSONArray()
        require(all.length() <= 100000) { "备份记录数量过多" }
        (0 until all.length()).map { index ->
            val value = JSONObject(all.getJSONObject(index).toString())
            require(value.getString("id").length in 1..160 && !value.getString("id").contains('\u0000'))
            CompanionPolicy.scope(value.getString("scope"))
            kind to value
        }
    }

    fun sanitize(kind: String, value: JSONObject): JSONObject = JSONObject(value.toString()).apply {
        if (kind == "message") remove("wire")
        if (kind == "observation") { remove("parts"); remove("frames"); put("mediaStored", false) }
        if (kind == "task") {
            removeGrant(); remove("uiBinding"); remove("automaticGrant"); remove("approvalId"); remove("controlLease")
            put("state", when (optString("state")) {
                "sending", "unknown" -> "unknown"
                "done", "failed", "cancelled" -> optString("state")
                else -> "waiting"
            })
        }
        remove("apiKey"); remove("credential"); remove("authorization")
    }

    fun plan(current: JSONObject, incoming: JSONObject): Plan {
        require(incoming.getString("format") == "dsh-companion-backup" && incoming.getInt("version") in 1..2) { "备份格式不受支持" }
        val merged = linkedMapOf<String, Pair<String, JSONObject>>()
        for ((kind, value) in rows(current)) merged["$kind\u0000${value.getString("id")}"] = kind to value
        for ((kind, value) in rows(incoming)) {
            val key = "$kind\u0000${value.getString("id")}"
            val local = merged[key]?.second
            check(local == null || local.getString("scope") == value.getString("scope")) { "备份记录身份冲突" }
            if (kind == "lifeState" && value.getString("id") == "budget") {
                merged[key] = kind to BudgetLedger.merge(local ?: JSONObject(), value).put("id", "budget").put("scope", CompanionPolicy.OWNER)
            } else if (kind != "lifeState" || value.getString("id") != "contentEpoch") {
                if (local == null || value.optLong("updatedAt") > local.optLong("updatedAt")) {
                    val restored = sanitize(kind, value)
                    if (local != null && (local.optBoolean("forgotten") || local.optBoolean("suppressed"))) {
                        restored.put("forgotten", local.optBoolean("forgotten")).put("suppressed", local.optBoolean("suppressed"))
                            .put("suppressedBy", local.optJSONArray("suppressedBy") ?: JSONArray())
                    }
                    merged[key] = kind to restored
                }
            }
        }
        val deleted = linkedMapOf<String, JSONObject>()
        for (data in listOf(current, incoming)) {
            val all = data.optJSONArray("tombstones") ?: JSONArray()
            for (i in 0 until all.length()) {
                val row = all.getJSONObject(i)
                val ref = RecordRef.parse(row)
                val old = deleted[ref.key]
                check(old == null || old.getString("scope") == ref.scope) { "删除记录身份冲突" }
                if (old == null || row.optLong("epoch") > old.optLong("epoch")) deleted[ref.key] = JSONObject(row.toString())
            }
        }
        val content = merged.values.filter { it.first in RecordRef.kinds }
        val closure = LifePolicy.descendants(deleted.values.map(RecordRef::parse).toSet(), content)
        for (ref in closure) {
            merged.remove(ref.key)
            if (ref.key !in deleted) deleted[ref.key] = ref.json().put("deletedAt", System.currentTimeMillis()).put("epoch", 1)
        }
        // Revisions are data, never a route for resurrecting removed source material.
        val revisions = linkedMapOf<String, JSONObject>()
        for (data in listOf(current, incoming)) {
            val all = data.optJSONArray("revisions") ?: JSONArray()
            for (i in 0 until all.length()) {
                val row = all.getJSONObject(i)
                val kind = row.getString("recordKind")
                require(kind in setOf("memory", "diary", "interest"))
                val ref = LifePolicy.reference(kind, row)
                if (ref.key !in deleted && LifePolicy.sources(row).none { it.key in deleted }) {
                    val key = ref.key + "\u0000" + row.getInt("revision")
                    revisions.putIfAbsent(key, JSONObject(row.toString()))
                }
            }
        }
        return Plan(merged.values.toList(), deleted.values.toList(), revisions.values.toList())
    }
}
