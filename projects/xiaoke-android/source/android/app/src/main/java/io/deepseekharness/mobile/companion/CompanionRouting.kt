package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject

object CompanionRouting {
    fun receive(context: Context, incoming: JSONObject, text: String, source: String, mentioned: Boolean, senderId: String = "", observationId: String = "") {
        val engine = CompanionEngine.get(context)
        val store = engine.store
        synchronized(store) {
        val conversation = store.get("conversation", incoming.getString("scope")) ?: return
        val settings = store.settings()
        if (!ParticipationPolicy.mayReceive(conversation)) return
        val scope = conversation.getString("scope")
        val now = System.currentTimeMillis()
        val hour = now / 3600000
        val used = if (conversation.optLong("hour") == hour) conversation.optInt("hourCount") else 0
        val since = (now - conversation.optLong("lastReplyAt")) / 1000
        val count = conversation.optInt("messagesSinceReply") + 1
        conversation.put("messagesSinceReply", count)
        val score = ReplyLikelihood.estimate(listOf(text), mentioned, 0.0, 1, since.toDouble())
        // Night quiet and an exhausted activity pool both keep her out of group chats; messages are still recorded.
        val groupPaused = scope.startsWith("qq:group:") && (NightQuiet.active(store) || engine.modules.activityStopped())
        val ownerChat = engine.modules.principal(scope).owner
        val eligible = ownerChat && conversation.optBoolean("reliable") && !settings.optBoolean("paused") && settings.optBoolean("networkEnabled") ||
            !groupPaused && conversation.optBoolean("reliable") && !settings.optBoolean("paused") && settings.optBoolean("networkEnabled") &&
            ParticipationPolicy.nightAllows(conversation, settings, mentioned, senderId, java.time.LocalTime.now().hour) &&
            now - conversation.optLong("humanActivityAt") > 120000 &&
            ReplyLikelihood.shouldReply(conversation.optString("mode", "quiet"), mentioned, score, conversation.optDouble("threshold", 0.65),
                since, conversation.optInt("cooldownSeconds", 60), used, conversation.optInt("hourlyLimit", 6).coerceAtMost(if (scope.startsWith("qq:group:")) 6 else 60))
        if (eligible) {
            store.put("conversation", conversation.put("hour", hour).put("hourCount", used + 1).put("lastReplyAt", now).put("messagesSinceReply", 0))
            engine.submit(scope, text, source, observationId, senderId)
        } else {
            store.put("conversation", conversation)
            val message = store.message(scope, "user", text, source).let { store.put("message", it.put("actorId", senderId)) }
            if (observationId.isNotEmpty()) store.put("message", message.put("observationId", observationId))
        }
        }
    }
    fun maybeAutoSend(context: Context, task: JSONObject) {
        val store = CompanionEngine.get(context).store
        val conversation = store.get("conversation", task.getString("scope")) ?: return
        if (conversation.optString("route") != "onebot") { QqNotificationBridge.maybeAutoSend(context, task); return }
        val settings = store.settings()
        if (!settings.optBoolean("networkEnabled") || settings.optBoolean("paused") || !settings.optBoolean("oneBotEnabled") ||
            !conversation.optBoolean("captureEnabled") || !conversation.optBoolean("reliable")) return
        // The owner's own QQ chat is a conversation with the owner, not something to approve; others follow the conversation's settings.
        val owner = CompanionEngine.get(context).modules.principal(task.getString("scope")).owner
        if (!owner && (!conversation.optBoolean("autoReply") || conversation.optString("mode") == "quiet" ||
            System.currentTimeMillis() - conversation.optLong("humanActivityAt") <= 120000)) return
        runCatching {
            val current = store.approveDraft(task.getString("id"), CompanionPolicy.draftDigest(task), automatic = true)
            OneBotBridge.get(context).send(current)
        }
    }
}
