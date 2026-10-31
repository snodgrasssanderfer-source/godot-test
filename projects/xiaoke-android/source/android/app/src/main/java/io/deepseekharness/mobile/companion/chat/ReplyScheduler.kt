package io.deepseekharness.mobile.companion.chat

import android.content.Context
import android.os.SystemClock
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.NightQuiet
import io.deepseekharness.mobile.companion.ParticipationPolicy
import io.deepseekharness.mobile.companion.ReplyLikelihood
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** QQ reply timing. Every message is kept as it arrives; she waits until a run of messages is finished
 * (debounce), then decides once for the whole batch. In groups the dynamic gate keeps how much she talks near
 * the group's target frequency; an @ or her name forces a reply; the owner's own chat always gets one. */
object ReplyScheduler {
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "xiaoke-reply-timing").apply { isDaemon = true } }
    private class Pending(val messageId: String, val text: String, val sender: String, val mentioned: Boolean, val observationId: String, val platformId: String,
                          val replyToHer: Boolean = false, val aimedElsewhere: Boolean = false)
    private class State {
        val debouncer = MessageDebouncer()
        val gate = DynamicReplyGate()
        val batch = mutableListOf<Pending>()
        var due: ScheduledFuture<*>? = null
    }
    private val states = ConcurrentHashMap<String, State>()

    fun receive(context: Context, incoming: JSONObject, text: String, source: String, mentioned: Boolean, senderId: String = "", observationId: String = "", platformId: String = "",
                replyToHer: Boolean = false, aimedElsewhere: Boolean = false) {
        val engine = CompanionEngine.get(context)
        val store = engine.store
        val conversation = store.get("conversation", incoming.getString("scope")) ?: return
        if (!ParticipationPolicy.mayReceive(conversation)) return
        val scope = conversation.getString("scope")
        val message = store.message(scope, "user", text, source).let { store.put("message", it.put("actorId", senderId).put("observationId", observationId).put("platformMessageId", platformId)) }
        runCatching { engine.modules.learner.observe(scope) }
        // Quoting one of her messages is talking to her, like an @; so is any of her names (persona aliases included).
        val said = text.substringAfter('：')
        val called = mentioned || replyToHer || store.personaNames().any { said.contains(it) }
        val words = text.substringAfter('：').substringBefore(" [已读取").trim()
        val state = states.getOrPut(scope) { State() }
        synchronized(state) {
            state.batch.add(Pending(message.getString("id"), text, senderId.ifBlank { "owner" }, called, observationId, platformId, replyToHer, aimedElsewhere))
            val now = SystemClock.elapsedRealtime()
            val mode = when (conversation.optString("debounce", "natural")) { "off" -> null; "fast" -> MessageDebouncer.Mode.FAST; "steady" -> MessageDebouncer.Mode.STEADY; else -> MessageDebouncer.Mode.NATURAL }
            val deadline = mode?.let { state.debouncer.observe(senderId.ifBlank { "owner" }, observationId.isNotBlank() && words.isBlank(), false, called, now, it) } ?: now
            state.due?.cancel(false)
            state.due = timer.schedule({ runCatching { fire(context, scope) } }, (deadline - now).coerceAtLeast(0), TimeUnit.MILLISECONDS)
        }
    }

    private fun fire(context: Context, scope: String) {
        val state = states[scope] ?: return
        val batch = synchronized(state) { state.batch.toList().also { state.batch.clear(); state.debouncer.consume(); state.due = null } }
        if (batch.isEmpty()) return
        val engine = CompanionEngine.get(context)
        val store = engine.store
        val conversation = store.get("conversation", scope) ?: return
        val settings = store.settings()
        if (settings.optBoolean("paused") || !settings.optBoolean("networkEnabled") || !conversation.optBoolean("reliable")) return
        val onebot = conversation.optString("route") == "onebot"
        // She has looked at this batch, whatever she does next.
        if (onebot) QqExtras.markRead(context, conversation, batch.last().platformId)
        var react = false
        val now = System.currentTimeMillis()
        val clock = SystemClock.elapsedRealtime()
        val called = batch.any { it.mentioned }
        val last = batch.last()
        // Where she stands in the conversation: when she last spoke here, and how many people talked since.
        val recent = store.contextMessages(scope, 60).let { a -> (0 until a.length()).map(a::getJSONObject) }
        val lastMine = recent.lastOrNull { it.optString("role") == "assistant" }?.optLong("createdAt") ?: 0L
        val sinceHer = if (lastMine > 0) now - lastMine else -1L
        val humansSinceHer = recent.count { it.optString("role") == "user" && it.optLong("createdAt") > lastMine }
        val ownerQq = settings.optString("ownerQqId")
        val ownerToHer = ownerQq.isNotBlank() && batch.any { it.sender == ownerQq &&
            Addressing.ownerToHer(it.text.substringAfter('：').substringBefore(" [已读取"), it.mentioned, it.aimedElsewhere, sinceHer, humansSinceHer) }
        val trigger = if (engine.modules.principal(scope).owner) true else {
            val group = scope.startsWith("qq:group:")
            val hour = now / 3_600_000
            val used = if (conversation.optLong("hour") == hour) conversation.optInt("hourCount") else 0
            val mode = conversation.optString("mode", "quiet")
            when {
                mode == "quiet" -> false
                now - conversation.optLong("humanActivityAt") <= 120_000 -> false
                // The owner talking to her in a group is answered like in private: no frequency gate, no hourly cap.
                ownerToHer -> { state.gate.recordForcedTurn(clock); true }
                group && (NightQuiet.active(store) || engine.modules.activityStopped()) -> false
                !ParticipationPolicy.nightAllows(conversation, settings, called, last.sender, java.time.LocalTime.now().hour) -> false
                now - conversation.optLong("humanActivityAt") <= 120_000 -> false
                used >= conversation.optInt("hourlyLimit", if (group) 12 else 60) -> false
                called -> { state.gate.recordForcedTurn(clock); true }
                mode == "mention" -> false
                !group -> true
                else -> {
                    val p = probability(store, scope, batch, now).let { if (Addressing.followsHer(sinceHer, humansSinceHer)) Addressing.boosted(it) else it }
                    state.gate.recordDemand(p, clock)
                    val decision = state.gate.evaluate(p, conversation.optDouble("replyFrequency", settings.optDouble("groupReplyFrequency", 0.5)), clock)
                    when (QqExtras.tier(p, decision.threshold, decision.trigger)) { "reply" -> true; "react" -> { react = true; false }; else -> false }
                }
            }
        }
        if (react && onebot) {
            val target = batch.lastOrNull { it.platformId.isNotBlank() }
            if (target != null) QqExtras.react(context, conversation, target.platformId, QqExtras.reaction(target.text.substringAfter('：').substringBefore(" [已读取"), target.observationId.isNotBlank()))
        }
        if (!trigger) return
        val hour = now / 3_600_000
        val used = if (conversation.optLong("hour") == hour) conversation.optInt("hourCount") else 0
        store.put("conversation", conversation.put("hour", hour).put("hourCount", used + 1).put("lastReplyAt", now).put("messagesSinceReply", 0))
        state.gate.recordReply(clock)
        engine.respond(scope, last.messageId, batch.lastOrNull { it.observationId.isNotBlank() }?.observationId.orEmpty(), ownerToHer && scope.startsWith("qq:group:"))
        if (onebot) QqExtras.typingWhile(context, conversation)
    }

    /** MaiBot's fitted reply likelihood, fed with what actually happened in this chat over the last five minutes. */
    private fun probability(store: io.deepseekharness.mobile.companion.CompanionStore, scope: String, batch: List<Pending>, now: Long): Double {
        val all = store.contextMessages(scope, 200)
        val recent = (0 until all.length()).map(all::getJSONObject).filter { now - it.optLong("createdAt") <= 300_000 }
        val mine = recent.count { it.optString("role") == "assistant" }
        val lastMine = (0 until all.length()).map(all::getJSONObject).lastOrNull { it.optString("role") == "assistant" }?.optLong("createdAt")
        return ReplyLikelihood.estimate(batch.map { it.text.substringAfter('：') }, batch.any { it.mentioned },
            if (recent.isEmpty()) 0.0 else mine.toDouble() / recent.size, recent.size,
            if (lastMine == null) 3600.0 else (now - lastMine) / 1000.0)
    }
}
