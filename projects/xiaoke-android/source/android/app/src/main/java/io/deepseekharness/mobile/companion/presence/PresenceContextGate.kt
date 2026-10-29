package io.deepseekharness.mobile.companion.presence

import org.json.JSONArray
import org.json.JSONObject

/** The host binds these operations to its transactional memory store, never to model JSON. */
interface PresenceContextAccess {
    fun snapshot(query: String): JSONObject
    fun current(epoch: Long): Boolean
    /** Validate epoch + all sourceRefs and run the short durable mutation in the same transaction. */
    fun commit(ticket: JSONObject, mutation: () -> Unit)
}

/** Process binding; installed once by the host before opening a presence session. */
object PresenceContexts {
    @Volatile var access: PresenceContextAccess? = null
}

/** Local invalidation fences callbacks immediately, even before Android's main thread can clear UI. */
class PresenceContextGate(private val access: () -> PresenceContextAccess?) {
    class Ticket internal constructor(internal val generation: Long, internal val owner: PresenceContextAccess?, val value: JSONObject) {
        val epoch: Long get() = value.optLong("epoch", -1)
        val memories: JSONArray get() = JSONArray(value.optJSONArray("memories")?.toString() ?: "[]")
    }
    @Volatile private var generation = 0L
    @Synchronized fun invalidate() { generation++ }
    @Synchronized fun snapshot(query: String): Ticket {
        val owner = access()
        // No adapter: conversation remains available without retrieved memory; saving is disabled.
        val data = owner?.snapshot(query) ?: JSONObject().put("epoch", -1).put("memories", JSONArray()).put("sourceRefs", JSONArray())
        return Ticket(generation, owner, JSONObject(data.toString()))
    }
    fun current(ticket: Ticket): Boolean = ticket.generation == generation && ticket.owner === access() &&
        (ticket.owner?.current(ticket.epoch) ?: true)
    fun sameWindow(a: Ticket, b: Ticket) = a.generation == b.generation && a.epoch == b.epoch && a.owner === b.owner
    /** Inherited ephemeral turns keep their persisted ancestors for later delete propagation. */
    fun inherit(ticket: Ticket, previous: Ticket?): Ticket {
        if (previous == null) return ticket
        check(sameWindow(ticket, previous)) { "陪伴上下文已更新" }
        val refs = linkedMapOf<String, JSONObject>()
        for (value in listOf(previous.value, ticket.value)) {
            val rows = value.optJSONArray("sourceRefs") ?: continue
            for (i in 0 until rows.length()) {
                val ref = rows.getJSONObject(i)
                refs["${ref.getString("kind")}\u0000${ref.getString("scope")}\u0000${ref.getString("id")}"] = ref
            }
        }
        check(refs.size <= 4096) { "本次上下文过长，请重新开启陪伴" }
        return Ticket(ticket.generation, ticket.owner, JSONObject(ticket.value.toString()).put("sourceRefs", JSONArray(refs.values.toList())))
    }
    @Synchronized fun commit(ticket: Ticket, mutation: () -> Unit) {
        check(current(ticket)) { "上下文已经被纠正、忘掉或删除，请重新交流后再保存" }
        val owner = ticket.owner ?: error("记忆事务接线尚未完成，当前事件不能保存")
        owner.commit(JSONObject(ticket.value.toString())) {
            check(current(ticket)) { "陪伴上下文已失效" }
            mutation()
        }
    }
}
