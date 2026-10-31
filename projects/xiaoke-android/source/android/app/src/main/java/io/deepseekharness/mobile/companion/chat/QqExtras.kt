package io.deepseekharness.mobile.companion.chat

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.OneBotBridge
import io.deepseekharness.mobile.companion.devicev2.EmergencyHold
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The small things a person does in QQ besides talking, through NapCat's extended actions
 * (NapNeko/NapCatQQ 49ecfe0f56c1e0649db6988444950216fd5fe792): a reaction on a group message, "正在输入" while she
 * writes a private reply, the occasional poke, and marking what she has read. All best effort: a failure here
 * never blocks a reply and is never retried.
 */
object QqExtras {
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xiaoke-qq-extras").apply { isDaemon = true } }

    /** Unicode code points (NapCat treats ids longer than three characters as emoji, shorter as QQ faces). */
    const val THUMBS_UP = "128077"
    const val LAUGH = "128514"
    const val PARTY = "127881"
    const val CRY = "128557"
    const val THINK = "129300"
    const val EYES = "128064"
    const val HEART = "10084"

    /** Picks a reaction from what was said; no model call. */
    fun reaction(text: String, image: Boolean): String {
        val t = text.lowercase()
        return when {
            // Single characters only when they are the whole reaction: "音乐""快乐""草莓""哭笑不得" must not fire.
            Regex("哈哈|hhh|笑死|xswl|绷不住|233|乐死|太乐了|典中典|^\\s*(草+|乐)[!！。~～]*\\s*$").containsMatchIn(t) -> LAUGH
            Regex("恭喜|好耶|出了|出金|欧皇|过了|成功|上岸|中了|赢了|赢麻").containsMatchIn(t) -> PARTY
            Regex("哭了|想哭|大哭|哭死|难受|寄了|呜呜|^\\s*唉|好累|裂开|emo").containsMatchIn(t) -> CRY
            Regex("[?？]$|为什么|怎么|咋").containsMatchIn(t) -> THINK
            Regex("可爱|喜欢|爱了|贴贴").containsMatchIn(t) -> HEART
            image && t.isBlank() -> EYES
            else -> THUMBS_UP
        }
    }

    /**
     * Group participation in three steps on top of the dynamic reply gate: above the gate's threshold she replies;
     * a little below it (the message caught her eye, but not enough to talk) she may just react; otherwise she lets it pass.
     */
    fun tier(probability: Double, threshold: Double, trigger: Boolean, roll: Double = Random.nextDouble()): String = when {
        trigger -> "reply"
        probability >= threshold * REACT_BAND && roll < REACT_CHANCE -> "react"
        else -> "ignore"
    }
    const val REACT_BAND = 0.6
    const val REACT_CHANCE = 0.5

    private fun allowed(context: Context, outbound: Boolean): Boolean {
        val settings = CompanionEngine.get(context).store.settings()
        return settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") && !settings.optBoolean("paused") &&
            (!outbound || !EmergencyHold.sensitiveHeld)
    }

    private fun call(context: Context, conversation: JSONObject, action: String, params: JSONObject): JSONObject? = runCatching {
        OneBotBridge.get(context).extra(conversation, action, params)
    }.getOrNull()

    fun react(context: Context, conversation: JSONObject, platformMessageId: String, emoji: String) {
        if (platformMessageId.isBlank() || !allowed(context, true)) return
        val store = CompanionEngine.get(context).store
        // At most one reaction every few minutes per group, so it reads as attention rather than a bot tic.
        val key = "reacted-" + CompanionPolicy.digest(conversation.getString("scope")).take(24)
        val last = store.lifeState(key).optLong("at")
        if (System.currentTimeMillis() - last < 3 * 60_000L) return
        store.saveLifeState(key, JSONObject().put("at", System.currentTimeMillis()))
        worker.execute { call(context, conversation, "set_msg_emoji_like", JSONObject().put("message_id", platformMessageId).put("emoji_id", emoji).put("set", true)) }
    }

    /** "对方正在输入…" in a private chat while her reply is being written; QQ shows it for a few seconds, so it is renewed. */
    fun typingWhile(context: Context, conversation: JSONObject) {
        if (conversation.optString("targetType") != "private" || !allowed(context, false)) return
        val engine = CompanionEngine.get(context)
        val scope = conversation.getString("scope")
        val params = JSONObject().put("user_id", conversation.getString("targetId")).put("event_type", 1)
        fun tick(round: Int) {
            if (round >= 15 || !engine.responding(scope)) return
            call(context, conversation, "set_input_status", params)
            worker.schedule({ tick(round + 1) }, 6, java.util.concurrent.TimeUnit.SECONDS)
        }
        worker.schedule({ tick(0) }, 300, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    fun markRead(context: Context, conversation: JSONObject, platformMessageId: String) {
        if (!allowed(context, false)) return
        val params = if (platformMessageId.isNotBlank()) JSONObject().put("message_id", platformMessageId)
            else if (conversation.optString("targetType") == "group") JSONObject().put("group_id", conversation.getString("targetId"))
            else JSONObject().put("user_id", conversation.getString("targetId"))
        worker.execute { call(context, conversation, "mark_msg_as_read", params) }
    }

    /** Daily caps: a few in the owner's chat, one per friend chat, one per group. */
    fun poke(context: Context, conversation: JSONObject, targetQq: String, owner: Boolean): JSONObject {
        check(allowed(context, true)) { "现在不能戳" }
        val store = CompanionEngine.get(context).store
        val day = java.time.LocalDate.now().toString()
        val key = "poked-" + CompanionPolicy.digest(conversation.getString("scope")).take(24)
        val state = store.lifeState(key).let { if (it.optString("day") == day) it else JSONObject().put("day", day).put("count", 0) }
        val cap = if (owner) 10 else 1
        check(state.optInt("count") < cap) { "今天在这里已经戳过了" }
        val params = JSONObject().put("user_id", targetQq)
        if (conversation.optString("targetType") == "group") params.put("group_id", conversation.getString("targetId"))
        val result = call(context, conversation, "send_poke", params)
        check(result != null && result.optInt("retcode", -1) == 0) { "没戳成功" }
        store.saveLifeState(key, state.put("count", state.optInt("count") + 1))
        return JSONObject().put("state", "poked")
    }

    /** NapCat's own speech-to-text for a voice message; null when it has none. */
    fun voiceText(context: Context, conversation: JSONObject, platformMessageId: String): String? {
        if (platformMessageId.isBlank()) return null
        val result = call(context, conversation, "fetch_ptt_text", JSONObject().put("message_id", platformMessageId)) ?: return null
        if (result.optInt("retcode", -1) != 0) return null
        return result.optJSONObject("data")?.optString("text")?.trim()?.takeIf { it.isNotBlank() }?.take(2000)
    }
}
