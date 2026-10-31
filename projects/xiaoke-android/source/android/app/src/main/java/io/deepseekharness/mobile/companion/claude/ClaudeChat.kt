package io.deepseekharness.mobile.companion.claude

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONObject

/**
 * Which engine answers a conversation, and the state Claude needs between turns. Gemini is the default everywhere
 * except the owner's own chats; a group uses Claude only when the owner switched it on in 连接 → QQ. The choice is
 * made once per reply, when the task is created, so one reply never mixes engines and a new setting applies from the
 * next message.
 */
object ClaudeChat {
    const val ENGINE_CLAUDE = "claude"
    const val ENGINE_GEMINI = "gemini"
    val EFFORTS = setOf("low", "medium", "high", "xhigh", "max")

    fun settings(store: CompanionStore): JSONObject = store.lifeState("claude").let { s ->
        JSONObject().put("enabled", s.optBoolean("enabled", true)).put("model", s.optString("model", "default").ifBlank { "default" })
            .put("effort", s.optString("effort", "medium").takeIf { it in EFFORTS } ?: "medium")
            // Full access: in the owner's own chats every edit and command is allowed without asking. Never in groups.
            .put("fullAccess", s.optBoolean("fullAccess")).put("workspaceBranch", s.optString("workspaceBranch"))
    }

    fun saveSettings(store: CompanionStore, input: JSONObject): JSONObject {
        val model = input.optString("model", "default").trim().ifBlank { "default" }
        require(Regex("[A-Za-z0-9._\\[\\]-]{1,80}").matches(model)) { "模型名只能是字母、数字和 .-_" }
        val effort = input.optString("effort", "medium")
        require(effort in EFFORTS) { "思考强度无效" }
        store.saveLifeState("claude", store.lifeState("claude").put("enabled", input.optBoolean("enabled")).put("model", model).put("effort", effort)
            .put("fullAccess", input.optBoolean("fullAccess"))
            .put("workspaceBranch", input.optString("workspaceBranch").trim().also { require(it.isEmpty() || ClaudeWorkspace.BRANCH.matches(it)) { "分支名只能是字母、数字和 ._/-" } }))
        // Switching it back on is the owner saying "try again": a remembered outage should not keep it on Gemini.
        if (input.optBoolean("enabled")) clearOutage(store)
        return settings(store)
    }

    /**
     * [ownerChat]: the owner's phone chat or their authenticated QQ private chat. [conversation] is the group's record;
     * only its own "engine" flag can turn Claude on for it.
     */
    fun engineFor(store: CompanionStore, scope: String, ownerChat: Boolean, conversation: JSONObject?, installed: Boolean, now: Long = System.currentTimeMillis()): String {
        if (!installed || !settings(store).optBoolean("enabled") || outage(store, now) != null) return ENGINE_GEMINI
        return when {
            ownerChat -> ENGINE_CLAUDE
            scope.startsWith("qq:group:") && conversation?.optString("engine") == ENGINE_CLAUDE -> ENGINE_CLAUDE
            else -> ENGINE_GEMINI
        }
    }

    // ---- Falling back to Gemini

    fun outage(store: CompanionStore, now: Long = System.currentTimeMillis()): JSONObject? =
        store.lifeState("claudeOutage").takeIf { it.has("reason") && it.optLong("until") > now }

    /**
     * Claude could not answer: until [until] every conversation that would use Claude uses Gemini instead, so a group
     * does not flip back and forth reply by reply. Returns true when this starts a new outage (the owner is told once).
     */
    fun recordOutage(store: CompanionStore, failure: ClaudeStream.Failure, detail: String, usage: JSONObject?, now: Long = System.currentTimeMillis()): Boolean {
        val fresh = outage(store, now) == null
        val resets = listOfNotNull(usage?.optLong("resetsAt"), usage?.optJSONObject("fiveHour")?.optLong("resetsAt"))
            .filter { it > now / 1000 }.minOrNull()?.times(1000)
        val until = when (failure) {
            ClaudeStream.Failure.LOGIN -> Long.MAX_VALUE // until she is logged in again (see clearOutage)
            ClaudeStream.Failure.LIMIT -> resets ?: (now + 60 * 60_000L)
            ClaudeStream.Failure.NOT_INSTALLED -> Long.MAX_VALUE
            ClaudeStream.Failure.ERROR -> now + 10 * 60_000L
        }
        store.saveLifeState("claudeOutage", JSONObject().put("reason", failure.name).put("detail", detail.take(300)).put("since", now).put("until", until))
        return fresh
    }

    fun clearOutage(store: CompanionStore) { store.saveLifeState("claudeOutage", JSONObject()) }

    fun describe(failure: ClaudeStream.Failure): String = when (failure) {
        ClaudeStream.Failure.NOT_INSTALLED -> "Claude Code 还没装好"
        ClaudeStream.Failure.LOGIN -> "Claude 没登录或登录过期了"
        ClaudeStream.Failure.LIMIT -> "Claude 订阅额度用完了"
        ClaudeStream.Failure.ERROR -> "Claude 出错了"
    }

    fun notifyOwner(context: Context, text: String) = runCatching {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return@runCatching
        manager.createNotificationChannel(NotificationChannel("claude-status", "Claude 状态", NotificationManager.IMPORTANCE_DEFAULT))
        manager.notify(831, Notification.Builder(context, "claude-status").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("先换成 Gemini 了").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true).build())
    }

    // ---- Sessions

    /**
     * Each conversation keeps its own Claude Code session. A session made before something in this conversation was
     * forgotten or deleted ([epoch] is the scope's removal epoch) is dropped: its transcript could still hold what was removed.
     */
    fun session(store: CompanionStore, scope: String, epoch: Long): JSONObject? =
        store.lifeState("claudeSessions").optJSONObject(CompanionPolicy.digest(scope))?.takeIf { it.optLong("epoch") == epoch && it.optString("id").isNotBlank() }

    fun saveSession(store: CompanionStore, scope: String, id: String, epoch: Long, lastMessageId: String, cwd: String) {
        val all = store.lifeState("claudeSessions")
        all.put(CompanionPolicy.digest(scope), JSONObject().put("id", id).put("epoch", epoch).put("lastMessageId", lastMessageId).put("cwd", cwd).put("at", System.currentTimeMillis()))
        store.saveLifeState("claudeSessions", all)
    }

    /** Session ids whose transcripts should be deleted from the rootfs (the epoch moved on). */
    fun staleSessions(store: CompanionStore): List<String> {
        val all = store.lifeState("claudeSessions")
        return all.keys().asSequence().filter { it != "id" && it != "scope" }.mapNotNull { k -> all.optJSONObject(k)?.takeIf { it.optLong("epoch") != store.removalEpochFor(k) } }
            .map { it.optString("id") }.filter { it.isNotBlank() }.toList()
    }
    fun forgetSessions(store: CompanionStore) {
        val all = store.lifeState("claudeSessions")
        all.keys().asSequence().toList().filter { k -> all.optJSONObject(k)?.let { it.optLong("epoch") != store.removalEpochFor(k) } == true }.forEach(all::remove)
        store.saveLifeState("claudeSessions", all)
    }

    /**
     * What Claude has not seen yet, as a transcript: everything after its last turn (including replies Gemini wrote
     * meanwhile), or the recent history when the session is new. Bounded by [budget] characters, newest kept.
     */
    fun transcript(messages: List<JSONObject>, lastSeen: String?, budget: Int, name: (JSONObject) -> String): String {
        val start = lastSeen?.let { id -> messages.indexOfFirst { it.optString("id") == id }.takeIf { it >= 0 }?.plus(1) } ?: 0
        val lines = messages.drop(start).map { m ->
            val at = java.time.Instant.ofEpochMilli(m.optLong("createdAt")).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withSecond(0).withNano(0)
            "[$at] ${name(m)}：${m.optString("text")}"
        }
        val kept = ArrayDeque<String>(); var used = 0
        for (line in lines.asReversed()) { if (used + line.length > budget && kept.isNotEmpty()) break; kept.addFirst(line.take(budget)); used += line.length }
        return kept.joinToString("\n")
    }

    /**
     * Where Claude works: the owner's chats in the project (so its CLAUDE.md applies), or the workspace before it is
     * cloned; every other conversation in an empty directory of its own.
     */
    fun cwd(scope: String, ownerChat: Boolean, projectReady: Boolean) = when {
        ownerChat && projectReady -> ClaudeWorkspace.PROJECT
        ownerChat -> ClaudeRuntime.WORKSPACE_DIR
        else -> "/root/.xiaoke/chats/" + CompanionPolicy.digest(scope).take(24)
    }
}
