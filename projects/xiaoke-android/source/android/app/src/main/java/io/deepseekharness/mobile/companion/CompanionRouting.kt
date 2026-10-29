package io.deepseekharness.mobile.companion

import android.content.Context
import org.json.JSONObject

object CompanionRouting {
    /** QQ messages go through debounce, follow-up merge and the dynamic frequency gate (see chat/ReplyScheduler). */
    fun receive(context: Context, incoming: JSONObject, text: String, source: String, mentioned: Boolean, senderId: String = "", observationId: String = "") =
        io.deepseekharness.mobile.companion.chat.ReplyScheduler.receive(context, incoming, text, source, mentioned, senderId, observationId)

    // Segmented sends pause between pieces; never on the model worker.
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "xiaoke-qq-send").apply { isDaemon = true } }

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
            sender.execute { runCatching { OneBotBridge.get(context).send(current) } }
        }
    }
}
