package io.deepseekharness.mobile.companion.life

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import org.json.JSONArray
import org.json.JSONObject

data class RecordRef(val kind: String, val id: String, val scope: String) {
    fun json() = JSONObject().put("kind", kind).put("id", id).put("scope", scope)
    val key get() = "$kind\u0000$id"
    companion object {
        val kinds = setOf("memory", "message", "observation", "diary", "interest", "task", "lifeEvent")
        fun parse(value: JSONObject): RecordRef {
            val ref = RecordRef(value.getString("kind"), value.getString("id"), CompanionPolicy.scope(value.getString("scope")))
            require(ref.kind in kinds && ref.id.length in 1..160 && !ref.id.contains('\u0000')) { "来源引用无效" }
            return ref
        }
    }
}

/** Structured decisions only. Model output never acquires owner authority. */
object LifePolicy {
    fun owner(principal: CommandPrincipal): Boolean = principal.owner && (
        (principal.channel in setOf("owner_ui", "background") && principal.scope == CompanionPolicy.OWNER) ||
            (principal.channel == "owner_qq" && principal.scope.startsWith("qq:private:")))
    fun requireOwner(principal: CommandPrincipal) { check(owner(principal)) { "只有主人可以管理此项" } }
    fun scope(input: JSONObject, principal: CommandPrincipal): String {
        val scope = CompanionPolicy.scope(input.optString("scope", principal.scope))
        check(owner(principal) || scope == principal.scope) { "不能跨会话读取或修改私有内容" }
        return scope
    }
    fun sources(value: JSONObject): List<RecordRef> {
        val refs = value.optJSONArray("sourceRefs") ?: JSONArray()
        require(refs.length() <= 20000) { "来源数量过多" }
        val explicit = (0 until refs.length()).map { RecordRef.parse(refs.getJSONObject(it)) }.toMutableList()
        val scope = value.optString("sourceScope", value.optString("scope", CompanionPolicy.OWNER))
        for ((field, kind) in listOf("sourceId" to "message", "replyTo" to "message", "messageId" to "message", "observationId" to "observation")) {
            val id = value.optString(field)
            if (id.isNotBlank()) explicit.add(RecordRef(kind, id, scope))
        }
        return explicit.distinctBy { it.key }
    }
    fun reference(kind: String, value: JSONObject) = RecordRef(kind, value.getString("id"), value.getString("scope"))
    fun checkRevision(old: JSONObject?, input: JSONObject) {
        if (input.has("expectedRevision")) check(old?.optInt("revision", 1) == input.getInt("expectedRevision")) { "记录已被修改，请重新加载后纠正" }
    }
    fun attention(input: JSONObject, now: Long): JSONObject {
        val mode = input.getString("mode")
        require(mode in setOf("available", "busy", "quiet")) { "注意力状态无效" }
        val until = input.optLong("until")
        require(mode != "busy" || until > now) { "忙碌约定须有将来的结束时间" }
        require(until == 0L || until > now) { "结束时间已经过去" }
        val maximum = input.optInt("maxBackgroundTasks", 2)
        require(maximum in 0..8)
        return JSONObject().put("mode", mode).put("until", until).put("maxBackgroundTasks", maximum).put("updatedAt", now)
    }
    fun attentionStatus(saved: JSONObject, now: Long): JSONObject {
        val value = JSONObject(saved.toString())
        val expired = value.optLong("until") > 0 && value.optLong("until") <= now
        if (expired) value.put("mode", "available").put("until", 0)
        if (!value.has("mode")) value.put("mode", "available").put("until", 0).put("maxBackgroundTasks", 2)
        return value.put("mayInterruptOrdinary", value.getString("mode") == "available").put("expired", expired)
    }

    /** Dependency closure works across scopes so a shared derivative cannot outlive its private source. */
    fun descendants(roots: Set<RecordRef>, records: List<Pair<String, JSONObject>>): Set<RecordRef> {
        val result = roots.associateBy { it.key }.toMutableMap()
        var changed: Boolean
        do {
            changed = false
            for ((kind, value) in records) {
                val ref = reference(kind, value)
                if (ref.key !in result && sources(value).any { it.key in result }) {
                    result[ref.key] = ref
                    changed = true
                }
            }
        } while (changed)
        return result.values.toSet()
    }

    /** Older builds did not record prompt lineage. Conservatively clear subsequent generated outputs. */
    fun legacyOutputs(roots: Set<RecordRef>, records: List<Pair<String, JSONObject>>): Set<RecordRef> {
        val cutoffs = mutableMapOf<String, Long>()
        for (root in roots) {
            val value = records.firstOrNull { it.first == root.kind && it.second.optString("id") == root.id }?.second
            val at = value?.optLong("createdAt", value.optLong("updatedAt")) ?: 0L
            cutoffs[root.scope] = minOf(cutoffs[root.scope] ?: Long.MAX_VALUE, at)
        }
        return records.filter { (kind, value) ->
            val cutoff = cutoffs[value.getString("scope")]
            cutoff != null && !value.has("contextEpoch") && value.optLong("createdAt", value.optLong("updatedAt")) >= cutoff &&
                ((kind == "message" && value.optString("role") == "assistant") ||
                    (kind == "task" && (value.has("partialText") || value.has("body"))))
        }.map { reference(it.first, it.second) }.toSet()
    }

    /** Fails closed: unreadable lineage counts as private. */
    fun touchesPrivate(record: JSONObject, isPrivate: (String) -> Boolean): Boolean = isPrivate(record.optString("scope")) ||
        isPrivate(record.optString("sourceScope")) || runCatching { sources(record).any { isPrivate(it.scope) } }.getOrDefault(true)

    /** Share flags never override the owner private domain; such records yield nothing to any requester. */
    fun shareSummaries(records: List<JSONObject>, requesterScope: String, query: String, isPrivate: (String) -> Boolean): JSONArray = JSONArray().also { out ->
        records.filter { value ->
            val audiences = value.optJSONArray("shareWithScopes") ?: JSONArray()
            (0 until audiences.length()).map(audiences::getString).any { it == "*" || it == requesterScope } && visible(value) &&
                value.optString("shareSummary").isNotBlank() && value.getString("shareSummary").contains(query, ignoreCase = true) &&
                !touchesPrivate(value, isPrivate)
        }.forEach { out.put(JSONObject().put("id", it.getString("id")).put("summary", it.getString("shareSummary"))) }
    }

    fun visible(record: JSONObject, proactive: Boolean = true): Boolean = !record.optBoolean("deleted") &&
        (!proactive || (!record.optBoolean("forgotten") && !record.optBoolean("suppressed") && !record.optBoolean("superseded")))
}
