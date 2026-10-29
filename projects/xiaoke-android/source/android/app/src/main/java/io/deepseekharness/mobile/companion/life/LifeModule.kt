package io.deepseekharness.mobile.companion.life

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionBackupCrypto
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.MemoryRanking
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEndpoint
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject

class LifeModule(@Suppress("UNUSED_PARAMETER") context: Context, private val store: CompanionStore, private val ports: HostPorts) : ModuleEndpoint {
    override val domain = "life"
    private val memory = LifeMemoryRepository(store)

    init {
        store.lifeTransaction {
            val saved = store.lifeState("budget")
            if (saved.optBoolean("configured")) {
                val ledger = BudgetLedger(saved)
                ledger.recover(System.currentTimeMillis())
                store.saveLifeState("budget", ledger.encode())
            }
            store.purgeDeletedMedia()
        }
    }

    override fun snapshot(principal: CommandPrincipal): JSONObject = store.lifeTransaction {
        val scope = CompanionPolicy.scope(principal.scope)
        JSONObject().put("memories", search(JSONObject().put("scope", scope).put("includeForgotten", LifePolicy.owner(principal)).put("includeHistory", true), principal))
            .put("diary", entries("diary", scope, "", includeHidden = LifePolicy.owner(principal)))
            .put("interests", entries("interest", scope, "", includeHidden = LifePolicy.owner(principal)))
            .put("attention", if (LifePolicy.owner(principal)) attention() else JSONObject().put("mode", "private"))
            .put("budgets", if (LifePolicy.owner(principal)) BudgetLedger(store.lifeState("budget")).status() else JSONObject().put("private", true))
            .put("contentEpoch", store.contentEpoch())
    }

    override fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        val result = when (action) {
            "memory.search" -> JSONObject().put("items", search(data, principal))
            "memory.upsert" -> { LifePolicy.requireOwner(principal); memory.upsertMemory(LifePolicy.scope(data, principal), data) }
            "memory.forget" -> { LifePolicy.requireOwner(principal); store.forgetMemory(LifePolicy.scope(data, principal), data.getString("id"), data.optBoolean("forgotten", true)) }
            "memory.delete" -> { LifePolicy.requireOwner(principal); memory.delete("memory", LifePolicy.scope(data, principal), data.getString("id")) }
            "memory.history" -> {
                LifePolicy.requireOwner(principal)
                val scope = LifePolicy.scope(data, principal)
                JSONObject().put("items", store.lifeHistory("memory", scope, data.getString("id")))
                    .put("legacy", store.memoryHistory(scope, data.getString("id")))
            }
            "diary.list", "interest.list" -> {
                val scope = LifePolicy.scope(data, principal)
                JSONObject().put("items", entries(if (action.startsWith("diary")) "diary" else "interest", scope, data.optString("query"), LifePolicy.owner(principal)))
            }
            "diary.correct", "interest.upsert" -> {
                LifePolicy.requireOwner(principal)
                memory.correct(if (action.startsWith("diary")) "diary" else "interest", LifePolicy.scope(data, principal), data)
            }
            "diary.delete", "interest.delete" -> {
                LifePolicy.requireOwner(principal)
                memory.delete(if (action.startsWith("diary")) "diary" else "interest", LifePolicy.scope(data, principal), data.getString("id"))
            }
            "attention.set" -> {
                LifePolicy.requireOwner(principal)
                store.lifeTransaction { store.saveLifeState("attention", LifePolicy.attention(data, System.currentTimeMillis())); attention() }
            }
            "attention.status" -> { LifePolicy.requireOwner(principal); attention() }
            "budget.configure", "budget.reserve", "budget.settle", "budget.release", "budget.status" -> budget(action, data, principal)
            "backup.export" -> {
                LifePolicy.requireOwner(principal)
                JSONObject().put("text", CompanionBackupCrypto.encrypt(store.exportData(), data.getString("password")))
            }
            "backup.restore" -> {
                LifePolicy.requireOwner(principal)
                val decrypted = CompanionBackupCrypto.decrypt(data.getString("text"), data.getString("password"))
                store.lifeTransaction {
                    check(!store.pendingWork()) { "请先取消或完成在途任务再恢复" }
                    val holds = BudgetLedger(store.lifeState("budget")).reservations()
                    check((0 until holds.length()).none { holds.getJSONObject(it).getString("state") in setOf("reserved", "sent") }) { "仍有在途模型请求，请先停止并核对结果" }
                    store.restoreData(decrypted)
                    JSONObject().put("restored", true).put("networkPaused", true).put("contentEpoch", store.contentEpoch())
                }
            }
            else -> error("生活模块操作不存在")
        }
        if (action !in READ_ACTIONS) {
            emit("moduleState", JSONObject().put("domain", domain))
            if (action.startsWith("memory.") || action.startsWith("diary.") || action.startsWith("interest.") || action == "backup.restore") {
                val event = JSONObject().put("contentEpoch", store.contentEpoch()).put("action", action)
                result.optJSONArray("deleted")?.let { event.put("deleted", it) }
                emit("life.contentChanged", event)
            }
        }
        return result
    }

    private fun budget(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        val result = store.lifeTransaction {
            val ledger = BudgetLedger(store.lifeState("budget"))
            when (action) {
                "budget.configure", "budget.status" -> LifePolicy.requireOwner(principal)
                "budget.reserve" -> {
                    val scope = LifePolicy.scope(data, principal)
                    check(scope == data.getString("scope"))
                    check(data.getString("lane") != "owner" || (LifePolicy.owner(principal) && scope == CompanionPolicy.OWNER)) { "社交活动不能借用主人聊天额度" }
                }
                else -> {
                    val reservation = ledger.reservation(data.getString("requestId")) ?: error("预留不存在")
                    check(LifePolicy.owner(principal) || (principal.channel == "background" && reservation.getString("scope") == principal.scope)) { "结算须由可信宿主完成" }
                }
            }
            val value = when (action) {
                "budget.configure" -> ledger.configure(data)
                "budget.reserve" -> ledger.reserve(data, System.currentTimeMillis())
                "budget.settle" -> ledger.settle(data, System.currentTimeMillis())
                "budget.release" -> ledger.release(data.getString("requestId"), System.currentTimeMillis())
                else -> ledger.status()
            }
            if (action != "budget.status") store.saveLifeState("budget", ledger.encode())
            value
        }
        if (action != "budget.status") emit("budget.changed", result.optJSONObject("budgets") ?: result)
        return result
    }

    private fun search(data: JSONObject, principal: CommandPrincipal): JSONArray = store.lifeTransaction {
        val requested = CompanionPolicy.scope(data.optString("scope", principal.scope))
        if (data.optBoolean("shareSummary")) {
            val all = store.list("memory", requested, 10000)
            return@lifeTransaction JSONArray().also { out ->
                for (i in 0 until all.length()) {
                    val value = all.getJSONObject(i)
                    val audiences = value.optJSONArray("shareWithScopes") ?: JSONArray()
                    val allowed = (0 until audiences.length()).map(audiences::getString).any { it == "*" || it == principal.scope }
                    if (allowed && LifePolicy.visible(value) && value.optString("shareSummary").isNotBlank() &&
                        value.getString("shareSummary").contains(data.optString("query"), ignoreCase = true)) {
                        out.put(JSONObject().put("id", value.getString("id")).put("summary", value.getString("shareSummary")))
                    }
                }
            }
        }
        val scope = LifePolicy.scope(data, principal)
        val hidden = data.optBoolean("includeForgotten")
        if (hidden) LifePolicy.requireOwner(principal)
        val all = store.list("memory", scope, 10000)
        val values = (0 until all.length()).map(all::getJSONObject).filter {
            !it.optBoolean("deleted") && (hidden || (!it.optBoolean("forgotten") && !it.optBoolean("suppressed"))) &&
                (data.optBoolean("includeHistory") || !it.optBoolean("superseded"))
        }
        val query = data.optString("query")
        // Explicit review keeps forgotten/history entries; automatic MemoryRanking always filters them.
        store.withEvidenceStatus(JSONArray().also { out -> values.filter { query.isBlank() || it.optString("text").contains(query, true) ||
            MemoryRanking.terms(query).any { term -> term in MemoryRanking.terms(it.optString("text")) } }
            .sortedByDescending { it.optLong("occurredAt", it.optLong("updatedAt")) }.take(1000).forEach(out::put) })
    }

    private fun entries(kind: String, scope: String, query: String, includeHidden: Boolean): JSONArray {
        val all = store.list(kind, scope, 10000)
        return store.withEvidenceStatus(JSONArray().also { out -> for (i in all.length() - 1 downTo 0) all.getJSONObject(i).let {
            if ((includeHidden || LifePolicy.visible(it)) && it.optString("text").contains(query, true)) out.put(it)
        } })
    }

    private fun attention(): JSONObject = LifePolicy.attentionStatus(store.lifeState("attention"), System.currentTimeMillis()).also { state ->
        val events = store.list("lifeEvent", CompanionPolicy.OWNER, 200)
        state.put("pendingEvents", JSONArray().also { out -> for (i in 0 until events.length()) events.getJSONObject(i).let {
            if (it.optBoolean("deferred")) out.put(JSONObject().put("id", it.getString("id")).put("type", it.getString("type")).put("createdAt", it.optLong("createdAt")))
        } })
    }

    override fun onEvent(event: ModuleEvent) {
        if (event.type !in setOf("conversation.message", "observation.summary", "action.completed")) return
        // A raw arrival may precede its grounded summary. Do not consume the idempotency key early.
        if (event.type == "conversation.message" && (!event.data.optBoolean("meaningful") || event.data.optJSONObject("memory") == null)) return
        if (event.type == "observation.summary" && !event.data.optBoolean("meaningful")) return
        val kind = when (event.type) { "conversation.message" -> "message"; "observation.summary" -> "observation"; else -> "task" }
        val key = "life-" + CompanionPolicy.digest(event.type + "\u0000" + event.sourceId)
        val epoch = store.contentEpoch()
        val changed = store.lifeTransaction {
            if (store.get("lifeEvent", key) != null || store.isDeleted("lifeEvent", key)) return@lifeTransaction false
            val source = store.get(kind, event.sourceId) ?: return@lifeTransaction false
            check(source.getString("scope") == CompanionPolicy.scope(event.scope)) { "事件与经历来源不属于同一会话" }
            if (!LifePolicy.visible(source)) return@lifeTransaction false
            val ref = LifePolicy.reference(kind, source)
            when (event.type) {
                "conversation.message" -> {
                    if (event.data.optBoolean("meaningful") && event.data.optJSONObject("memory") != null) {
                        val candidate = JSONObject(event.data.getJSONObject("memory").toString()).put("id", "$key-memory")
                            .put("sourceId", event.sourceId).put("source", "已保存的对话经历")
                        val text = candidate.getString("text")
                        if (!source.optString("text").contains(text)) candidate.put("kind", "inference")
                        memory.upsertMemory(event.scope, candidate, fromModel = true)
                    }
                }
                "observation.summary" -> {
                    val summary = source.optString("analysis", source.optString("summary"))
                    if (summary.isBlank()) return@lifeTransaction false
                    if (event.data.optBoolean("meaningful") && summary.isNotBlank()) {
                        val refs = JSONArray().put(ref.json())
                        memory.upsertMemory(event.scope, JSONObject().put("id", "$key-memory").put("text", summary.take(8000)).put("kind", "inference")
                            .put("sourceRefs", refs).put("source", "已保存观察的摘要"), fromModel = true)
                        memory.diary(event.scope, "$key-diary", "我记录了一段观察摘要：${summary.take(7900)}", listOf(ref), "observation_summary")
                    }
                }
                "action.completed" -> {
                    check(source.optString("state") == "done") { "任务尚未完成，不能写成已完成的日记" }
                    val description = source.optString("progress", source.optString("type", "任务")).take(7900)
                    memory.diary(event.scope, "$key-diary", "我完成了一项任务，已记录的结果是：$description", listOf(ref), "completed_task")
                }
            }
            val attention = attention()
            store.put("lifeEvent", JSONObject().put("id", key).put("scope", event.scope).put("type", event.type)
                .put("createdAt", System.currentTimeMillis()).put("deferred", !attention.getBoolean("mayInterruptOrdinary"))
                .put("sourceRefs", JSONArray().put(ref.json())))
            true
        }
        if (changed) {
            emit("moduleState", JSONObject().put("domain", domain))
            if (store.contentEpoch() != epoch) emit("life.contentChanged", JSONObject().put("contentEpoch", store.contentEpoch()).put("action", event.type))
        }
    }

    private fun emit(topic: String, data: JSONObject) {
        // State is already durable. A detached UI cannot roll it back or change the success of the action.
        try { ports.emit(topic, data) } catch (_: Exception) { /* next snapshot carries the same state */ }
    }

    companion object {
        private val READ_ACTIONS = setOf("memory.search", "memory.history", "diary.list", "interest.list", "attention.status", "budget.status", "backup.export")
    }
}
