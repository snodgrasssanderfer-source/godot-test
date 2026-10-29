package io.deepseekharness.mobile.companion.life

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject

/** Uses the original store as the sole content source. No second memory database or remote embedding. */
class LifeMemoryRepository(private val store: CompanionStore) {
    fun upsertMemory(scope: String, input: JSONObject, fromModel: Boolean = false, sourceScope: String = scope): JSONObject = store.lifeTransaction {
        CompanionPolicy.scope(scope)
        val content = CompanionPolicy.text(input.getString("text"), 8000)
        val id = input.optString("id").ifBlank { CompanionPolicy.id() }
        val old = store.get("memory", id)
        check(old == null || old.getString("scope") == scope) { "不能跨会话修改记忆" }
        LifePolicy.checkRevision(old, input)
        if (fromModel) {
            check(old == null) { "模型不能覆盖主人已编辑的记忆" }
            val all = store.list("memory", scope, 10000)
            val duplicate = (0 until all.length()).map(all::getJSONObject).firstOrNull {
                !it.optBoolean("forgotten") && it.optString("text").replace(Regex("\\s+"), "") == content.replace(Regex("\\s+"), "") &&
                    it.optString("sourceId") == input.optString("sourceId")
            }
            if (duplicate != null) return@lifeTransaction duplicate
        }
        val kind = input.optString("kind", "fact")
        require(kind in setOf("fact", "inference", "preference", "relationship", "event", "task")) { "记忆类别无效" }
        val now = System.currentTimeMillis()
        val supplied = JSONObject(input.toString()).put("scope", scope).put("sourceScope", sourceScope)
        val refs = LifePolicy.sources(supplied)
        validateSources(refs, sourceScope)
        check(!fromModel || refs.isNotEmpty()) { "自动记忆必须有已保存的经历来源" }
        val occurredAt = if (input.has("occurredAt")) input.getLong("occurredAt") else refs.firstOrNull()?.let {
            store.get(it.kind, it.id)?.optLong("createdAt", now)
        } ?: now
        require(occurredAt in 0..now + 300000) { "事实发生时间无效" }
        val primarySource = refs.firstOrNull()?.let { store.get(it.kind, it.id) }
        val actorId = primarySource?.optString("actorId", primarySource.optString("senderId")).orEmpty().ifBlank {
            if (sourceScope == CompanionPolicy.OWNER || sourceScope.startsWith("qq:private:")) sourceScope else ""
        }
        var factKey = input.optString("factKey", old?.optString("factKey").orEmpty()).trim()
        require(factKey.length <= 160 && !factKey.contains('\u0000'))
        if (fromModel && sourceScope.startsWith("qq:group:") && factKey.isNotBlank()) {
            // Display names cannot collapse two participants into a single evolving fact.
            factKey = if (actorId.isBlank()) "" else CompanionPolicy.digest(actorId).take(24) + ":" + factKey.take(135)
        }
        val value = JSONObject().put("id", id).put("scope", scope).put("text", content).put("kind", kind)
            .put("factKey", factKey).put("occurredAt", occurredAt).put("observedAt", now)
            .put("createdAt", old?.optLong("createdAt", now) ?: now).put("revision", (old?.optInt("revision", 1) ?: 0) + 1)
            .put("source", input.optString("source", if (fromModel) "有来源的自动记录" else "主人纠正").take(1000))
            .put("sourceId", input.optString("sourceId")).put("sourceScope", sourceScope)
            .put("actorId", actorId)
            .put("sourceRefs", JSONArray().also { a -> (refs + (old?.let(LifePolicy::sources) ?: emptyList())).distinctBy { it.key }.forEach { a.put(it.json()) } })
            .put("status", if (fromModel) "unverified" else "confirmed").put("pinned", !fromModel && input.optBoolean("pinned", old?.optBoolean("pinned") ?: false))
            .put("forgotten", old?.optBoolean("forgotten") ?: false).put("suppressed", old?.optBoolean("suppressed") ?: false)
            .put("suppressedBy", old?.optJSONArray("suppressedBy") ?: JSONArray()).put("superseded", false)
            .put("evidenceType", if (fromModel) "source_linked_candidate" else "owner_corrected")
        if (kind == "preference") value.put("stability", input.optString("stability", "developing").also { require(it in setOf("developing", "stable", "situational")) })
        if (!fromModel) {
            value.put("shareSummary", input.optString("shareSummary", old?.optString("shareSummary").orEmpty()).take(1000))
            val audiences = input.optJSONArray("shareWithScopes") ?: old?.optJSONArray("shareWithScopes") ?: JSONArray()
            require(audiences.length() <= 100)
            for (i in 0 until audiences.length()) if (audiences.getString(i) != "*") CompanionPolicy.scope(audiences.getString(i))
            value.put("shareWithScopes", audiences)
        }
        if (old != null) store.recordRevision("memory", old)
        val saved = store.put("memory", value)
        store.appendMemoryHistory(scope, id, old?.optString("text"), content)
        val changedContext = reconcileFact(scope, factKey, id)
        if (old != null || changedContext) store.bumpContentEpoch()
        store.get("memory", saved.getString("id"))!!
    }

    private fun reconcileFact(scope: String, factKey: String, insertedId: String): Boolean {
        if (factKey.isBlank()) return false
        val all = store.list("memory", scope, 10000)
        val facts = (0 until all.length()).map(all::getJSONObject).filter { it.optString("factKey") == factKey && it.optString("kind") != "inference" }
        val latest = facts.maxWithOrNull(compareBy<JSONObject> { it.optLong("occurredAt") }.thenBy { it.optLong("observedAt") }.thenBy { it.getString("id") }) ?: return false
        var changedExisting = false
        facts.forEach { value ->
            val superseded = value.getString("id") != latest.getString("id")
            if (value.optBoolean("superseded") != superseded || value.optString("currentFactId") != latest.getString("id")) {
                if (value.getString("id") != insertedId) changedExisting = true
                store.put("memory", value.put("superseded", superseded).put("currentFactId", latest.getString("id")))
            }
        }
        return changedExisting
    }

    fun validateSources(refs: List<RecordRef>, sourceScope: String) {
        refs.forEach { ref ->
            check(ref.scope == sourceScope) { "来源不属于当前会话" }
            val source = store.get(ref.kind, ref.id) ?: error("来源不存在或已删除")
            check(source.getString("scope") == ref.scope) { "来源身份不匹配" }
            check(!source.optBoolean("suppressed") && !source.optBoolean("forgotten")) { "被要求忘掉的来源不能自动派生新内容" }
        }
    }

    fun correct(kind: String, scope: String, input: JSONObject): JSONObject = store.lifeTransaction {
        require(kind in setOf("diary", "interest"))
        val id = input.optString("id").ifBlank { CompanionPolicy.id() }
        val old = store.get(kind, id)
        check(old == null || old.getString("scope") == scope) { "记录不属于当前会话" }
        if (kind == "diary") check(old != null) { "日记须由真实经历建立，不能补造缺失经历" }
        LifePolicy.checkRevision(old, input)
        val refs = if (old != null) LifePolicy.sources(old) else LifePolicy.sources(JSONObject(input.toString()).put("scope", scope))
        if (old == null) validateSources(refs, scope) else refs.forEach { ref ->
            check(ref.scope == scope && !store.isDeleted(ref.kind, ref.id)) { "来源已删除或不属于当前会话" }
            // Expiry of ordinary media must not prevent the owner correcting the retained record.
            store.get(ref.kind, ref.id)?.let { source ->
                check(source.getString("scope") == scope && !source.optBoolean("forgotten") && !source.optBoolean("suppressed")) {
                    "来源不可用于当前纠正"
                }
            }
        }
        val text = CompanionPolicy.text(input.getString("text"), 8000)
        val value = JSONObject(old?.toString() ?: "{}").put("id", id).put("scope", scope).put("text", text)
            .put("revision", (old?.optInt("revision", 1) ?: 0) + 1).put("createdAt", old?.optLong("createdAt") ?: System.currentTimeMillis())
            .put("sourceRefs", JSONArray().also { a -> refs.forEach { a.put(it.json()) } }).put("correctedByOwner", old != null)
        if (old != null) { store.recordRevision(kind, old); store.bumpContentEpoch() }
        store.put(kind, value)
    }

    fun diary(scope: String, id: String, text: String, refs: List<RecordRef>, evidence: String): JSONObject = store.lifeTransaction {
        store.get("diary", id)?.let { return@lifeTransaction it }
        check(refs.isNotEmpty()) { "缺少经历证据，不能生成日记" }
        validateSources(refs, scope)
        require(text.startsWith("我") && text.length <= 8000)
        store.put("diary", JSONObject().put("id", id).put("scope", scope).put("text", text).put("revision", 1)
            .put("createdAt", System.currentTimeMillis()).put("evidenceType", evidence).put("evidenceMissing", false)
            .put("sourceRefs", JSONArray().also { a -> refs.forEach { a.put(it.json()) } }))
    }

    fun delete(kind: String, scope: String, id: String): JSONObject = store.lifeTransaction {
        val target = store.get(kind, id) ?: return@lifeTransaction JSONObject().put("deleted", JSONArray()).put("contentEpoch", store.contentEpoch())
        check(target.getString("scope") == scope) { "记录不属于当前会话" }
        val targets = mutableListOf(target)
        if (kind == "memory" && target.optString("factKey").isNotBlank()) {
            val all = store.list("memory", scope, 10000)
            for (i in 0 until all.length()) all.getJSONObject(i).let { if (it.optString("factKey") == target.optString("factKey")) targets.add(it) }
        }
        val roots = targets.flatMap { listOf(LifePolicy.reference(kind, it)) + if (kind == "memory") LifePolicy.sources(it) else emptyList() }.toSet()
        store.deleteContent(roots)
    }
}
