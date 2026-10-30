package io.deepseekharness.mobile.companion

import android.content.Context
import io.deepseekharness.mobile.companion.chat.DeliveryLog
import org.json.JSONObject

object CompanionRouting {
    /** QQ messages go through debounce, follow-up merge and the dynamic frequency gate (see chat/ReplyScheduler). */
    fun receive(context: Context, incoming: JSONObject, text: String, source: String, mentioned: Boolean, senderId: String = "", observationId: String = "", platformMessageId: String = "") =
        io.deepseekharness.mobile.companion.chat.ReplyScheduler.receive(context, incoming, text, source, mentioned, senderId, observationId, platformMessageId)

    // Segmented sends pause between pieces; never on the model worker.
    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "xiaoke-qq-send").apply { isDaemon = true } }

    fun maybeAutoSend(context: Context, task: JSONObject) {
        val engine = CompanionEngine.get(context)
        val store = engine.store
        val scope = task.getString("scope")
        val conversation = store.get("conversation", scope) ?: return
        if (conversation.optString("route") != "onebot") { QqNotificationBridge.maybeAutoSend(context, task); return }
        // The owner's own QQ chat is a conversation with the owner, not something to approve; others follow the conversation's settings.
        val owner = engine.modules.principal(scope).owner
        if (!owner && (!conversation.optBoolean("autoReply") || conversation.optString("mode") == "quiet")) return // stays for review
        val settings = store.settings()
        val blocked = when {
            settings.optBoolean("paused") -> "她被暂停了"
            !settings.optBoolean("networkEnabled") -> "联网关着"
            !settings.optBoolean("oneBotEnabled") -> "QQ 消息桥关着"
            !conversation.optBoolean("captureEnabled") || !conversation.optBoolean("reliable") -> "这个会话没开接收"
            !owner && System.currentTimeMillis() - conversation.optLong("humanActivityAt") <= 120000 -> "主人刚用她的号在这里说过话，她让开了"
            else -> null
        }
        if (blocked != null) { fail(store, task, blocked); return }
        val current = runCatching { store.approveDraft(task.getString("id"), CompanionPolicy.draftDigest(task), automatic = true) }
            .getOrElse { fail(store, task, it.message ?: "放行失败"); return }
        sender.execute { deliver(context, current) }
    }

    /** Sends and makes sure the task never stays in "sending"; anything that did not go out is written where she can see it. */
    fun deliver(context: Context, task: JSONObject): JSONObject? {
        val store = CompanionEngine.get(context).store
        val id = task.getString("id")
        val result = runCatching { OneBotBridge.get(context).send(task) }
        val after = store.get("task", id)
        val reason = when {
            result.isFailure -> result.exceptionOrNull()?.message ?: "发送出错"
            after?.optString("state") == "failed" -> after.optString("progress").ifBlank { "QQ 拒绝了发送" }
            after?.optString("state") == "unknown" -> "发出去没收到 QQ 的确认：" + after.optString("progress")
            after?.optString("state") in setOf("sending", "approved", "draft") -> "没有走到发送这一步"
            else -> null
        } ?: return after
        if (after?.optString("state") in setOf("approved", "draft", "sending"))
            store.transitionTask(id, setOf("approved", "draft", "sending"), if (after?.optString("state") == "sending") "unknown" else "failed", reason.take(200))
        DeliveryLog.record(store, task.getString("scope"), "send", reason, task.optString("body"))
        return store.get("task", id)
    }

    private fun fail(store: CompanionStore, task: JSONObject, reason: String) {
        store.transitionTask(task.getString("id"), setOf("draft", "approved"), "failed", "没发出去：$reason")
        DeliveryLog.record(store, task.getString("scope"), "send", reason, task.optString("body"))
    }
}
