package io.deepseekharness.mobile.companion

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Android QQ notifications and native reply actions, not a server-side bot identity. */
class QqNotificationBridge : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName != QQ) return
        val engine = CompanionEngine.get(this)
        val settings = engine.store.settings()
        if (!settings.optBoolean("qqEnabled")) return
        try {
            val notification = sbn.notification
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            val group = style?.isGroupConversation == true
            val shortcut = notification.shortcutId.orEmpty()
            val reliable = shortcut.isNotBlank()
            val title = (style?.conversationTitle ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().take(160)
            val namespace = settings.optString("qqAccountLabel", "未标记账号")
            val identity = namespace + "\u0000" + shortcut.ifBlank { "${sbn.id}:${sbn.tag}:$title" }
            val scope = "qq:${if (!reliable) "unresolved" else if (group) "group" else "private"}:${CompanionPolicy.digest(identity).take(40)}"
            val store = engine.store
            val conversation = store.get("conversation", scope) ?: JSONObject().put("id", scope).put("scope", scope)
                .put("mode", "quiet").put("autoReply", false).put("cooldownSeconds", 60).put("hourlyLimit", 6).put("threshold", 0.65)
            conversation.put("title", title).put("reliable", reliable).put("identitySource", if (reliable) "Android QQ shortcutId" else "通知聚合标识，尚不能可靠分群")
                .put("route", "notification").put("account", namespace)
            val action = notification.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true && a.actionIntent.creatorPackage == QQ }
            if (action != null) replies[scope] = ReplyTarget(action.actionIntent, action.remoteInputs, System.currentTimeMillis(), title)
            conversation.put("canReply", action != null)
            store.put("conversation", conversation)
            if (!ParticipationPolicy.mayReceive(conversation)) return
            val seen = conversation.optJSONArray("seen") ?: JSONArray()
            val known = (0 until seen.length()).map { seen.getString(it) }.toMutableList()
            val fresh = mutableListOf<String>()
            val messages = style?.messages.orEmpty().takeLast(12)
            if (messages.isNotEmpty()) for (message in messages) {
                val text = message.text?.toString().orEmpty().take(8000)
                val sender = message.person?.name?.toString().orEmpty()
                val key = CompanionPolicy.digest("${message.timestamp}\u0000$sender\u0000$text")
                if (text.isNotBlank() && key !in known) { known.add(key); fresh.add(if (sender.isBlank()) text else "$sender：$text") }
            } else {
                val text = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                val key = CompanionPolicy.digest("${sbn.postTime}\u0000$text")
                if (text.isNotBlank() && key !in known) { known.add(key); fresh.add(text.take(8000)) }
            }
            store.put("conversation", conversation.put("seen", JSONArray(known.takeLast(64))))
            if (fresh.isEmpty()) return
            val text = fresh.joinToString("\n")
            val mentioned = text.contains("@${settings.optString("displayName", "小克")}") || title.contains("[有人@我]")
            CompanionRouting.receive(this, conversation, text, "QQ 通知：$title", mentioned)
        } catch (_: Exception) {
            // Ignore malformed platform events; never log private message bodies or credentials.
        }
    }

    companion object {
        @Volatile private var effectGate: ((JSONObject) -> Boolean)? = null
        fun setEffectGate(gate: ((JSONObject) -> Boolean)?) { effectGate = gate }
        private const val QQ = "com.tencent.mobileqq"
        private data class ReplyTarget(val intent: PendingIntent, val inputs: Array<RemoteInput>, val at: Long, val title: String)
        private val replies = ConcurrentHashMap<String, ReplyTarget>()

        fun send(context: Context, task: JSONObject): JSONObject {
            check(effectGate?.invoke(JSONObject(task.toString())) == true) { "宿主尚未授权此通知回复" }
            check(CompanionPolicy.canSend(task, System.currentTimeMillis())) { "草稿授权已失效" }
            val target = replies[task.getString("scope")] ?: error("QQ 原生回复入口已过期；请等待此会话的新通知或使用界面路线")
            check(target.intent.creatorPackage == QQ && target.title == task.getString("target") && System.currentTimeMillis() - target.at < 600000) { "通知目标已变化，请重新核对" }
            val store = CompanionEngine.get(context).store
            val conversation = store.get("conversation", task.getString("scope")) ?: error("会话未绑定")
            check(conversation.optBoolean("reliable")) { "QQ 通知没有可靠会话标识，不能自动发送" }
            val text = task.getString("body")
            val result = Bundle()
            target.inputs.filter { it.allowFreeFormInput }.forEach { result.putCharSequence(it.resultKey, text) }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(target.inputs, intent, result)
            store.claimSend(task.getString("id"), CompanionPolicy.draftDigest(task))
            try { target.intent.send(context, 0, intent) }
            catch (_: PendingIntent.CanceledException) { return store.transitionTask(task.getString("id"), setOf("sending"), "unknown", "QQ 回复入口已取消，请核对是否发送") ?: store.get("task", task.getString("id"))!! }
            return store.transitionTask(task.getString("id"), setOf("sending"), "unknown", "已交给安卓 QQ 回复入口；送达未确认，不会自动重发") ?: store.get("task", task.getString("id"))!!
        }

        fun maybeAutoSend(context: Context, task: JSONObject) {
            val store = CompanionEngine.get(context).store
            val c = store.get("conversation", task.getString("scope")) ?: return
            if (!store.settings().optBoolean("qqEnabled") || !store.settings().optBoolean("networkEnabled") || !c.optBoolean("captureEnabled") || !c.optBoolean("autoReply") || !c.optBoolean("reliable") || c.optString("mode") == "quiet" || store.settings().optBoolean("paused")) return
            if (!replies.containsKey(task.getString("scope"))) return
            runCatching { send(context, store.approveDraft(task.getString("id"), CompanionPolicy.draftDigest(task), automatic = true)) }
        }
    }
}
