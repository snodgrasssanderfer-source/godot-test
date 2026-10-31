package io.deepseekharness.mobile.companion.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.companion.CompanionService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Claude Code asking to edit a file, run a command and the like. The question goes to the owner: in the chat, on the
 * floating ball's badge and as a notification with 允许 / 拒绝. No answer in [TIMEOUT_MS] is a no.
 */
object ClaudePermissions {
    class Pending(val id: String, val taskId: String, val scope: String, val tool: String, val summary: String, val at: Long) {
        val answer = CompletableFuture<Boolean>()
        fun json() = JSONObject().put("id", id).put("taskId", taskId).put("scope", scope).put("tool", tool).put("summary", summary).put("at", at)
    }

    const val TIMEOUT_MS = 10 * 60_000L
    private val pending = ConcurrentHashMap<String, Pending>()

    fun list(): JSONArray = JSONArray(pending.values.sortedBy { it.at }.map { it.json() })
    fun count() = pending.size

    /** Blocks the turn until the owner answers, the turn is cancelled ([cancelled]), or the request times out. */
    fun ask(context: Context, taskId: String, scope: String, tool: String, summary: String, cancelled: () -> Boolean): Boolean {
        val item = Pending("perm-" + java.util.UUID.randomUUID().toString().take(12), taskId, scope, tool, summary, System.currentTimeMillis())
        pending[item.id] = item
        notify(context, item)
        try {
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (true) {
                if (cancelled()) return false
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                try { return item.answer.get(minOf(left, 1_000L), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) {}
            }
        } finally {
            pending.remove(item.id)
            context.getSystemService(NotificationManager::class.java)?.cancel(item.id.hashCode())
        }
    }

    fun answer(id: String, allow: Boolean): Boolean = pending[id]?.answer?.complete(allow) == true

    /** A cancelled turn takes its open questions with it. */
    fun dropTask(taskId: String) { pending.values.filter { it.taskId == taskId }.forEach { it.answer.complete(false) } }

    private fun notify(context: Context, item: Pending) = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Claude 请求授权", NotificationManager.IMPORTANCE_HIGH))
        fun action(allow: Boolean) = PendingIntent.getService(context, (item.id + allow).hashCode(),
            Intent(context, CompanionService::class.java).setAction(ACTION).putExtra("id", item.id).putExtra("allow", allow),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(context, 820, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(item.id.hashCode(), Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("小克想${item.summary.substringBefore('：')}").setContentText(item.summary.substringAfter('：'))
            .setStyle(Notification.BigTextStyle().bigText(item.summary)).setContentIntent(open).setAutoCancel(true)
            .addAction(Notification.Action.Builder(null, "允许", action(true)).build())
            .addAction(Notification.Action.Builder(null, "拒绝", action(false)).build()).build())
    }

    const val CHANNEL = "claude-permission"
    const val ACTION = "claudePermission"
}
