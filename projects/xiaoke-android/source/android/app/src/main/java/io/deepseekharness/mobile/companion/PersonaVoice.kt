// Card fields after SillyTavern/SillyTavern 06bde939fb1e9c4c8d8641d810f0a916b5bce127 public/scripts/char-data.js (AGPL-3.0):
// post_history_instructions, extensions.depth_prompt {prompt, depth, role}, character_book {entries: keys, secondary_keys,
// content, constant, selective, enabled, insertion_order}. Mechanism only; no code copied.
package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps her voice steady over long conversations. The full persona sits at the top of the request; this adds, close
 * to the newest messages, a few lines on how she talks (the card's depth prompt, or points she distilled from her own
 * persona once per persona version), the card's post-history instructions, and lorebook entries the chat just touched.
 */
object PersonaVoice {
    private val distilling = AtomicBoolean(false)
    const val MAX_DEPTH = 4

    /** Keeps what a card carries beyond the main text, bounded. Unknown fields are dropped, nothing is executed. */
    fun sanitize(extras: JSONObject?): JSONObject {
        val out = JSONObject()
        if (extras == null) return out
        extras.optString("postHistory").trim().take(4000).takeIf { it.isNotBlank() }?.let { out.put("postHistory", it) }
        extras.optJSONObject("depthPrompt")?.let { d ->
            val prompt = d.optString("prompt").trim().take(4000)
            if (prompt.isNotBlank()) out.put("depthPrompt", JSONObject().put("prompt", prompt).put("depth", d.optInt("depth", 4).coerceIn(0, 100))
                .put("role", d.optString("role", "system").takeIf { it in setOf("system", "user", "assistant") } ?: "system"))
        }
        extras.optJSONObject("characterBook")?.let { book ->
            val entries = book.optJSONArray("entries") ?: JSONArray()
            val kept = JSONArray()
            for (i in 0 until minOf(entries.length(), 200)) entries.optJSONObject(i)?.let { e ->
                val content = e.optString("content").trim().take(4000)
                if (content.isBlank()) return@let
                fun list(name: String) = JSONArray((e.optJSONArray(name) ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it).trim() } }.filter { it.isNotBlank() }.take(20).map { it.take(80) })
                kept.put(JSONObject().put("keys", list("keys")).put("secondaryKeys", list("secondaryKeys")).put("content", content)
                    .put("constant", e.optBoolean("constant")).put("selective", e.optBoolean("selective")).put("enabled", e.optBoolean("enabled", true))
                    .put("order", e.optInt("order", 100)).put("comment", e.optString("comment").take(120)))
            }
            if (kept.length() > 0) out.put("characterBook", JSONObject().put("name", book.optString("name").take(120)).put("entries", kept))
        }
        return out
    }

    fun extras(persona: JSONObject): JSONObject = persona.optJSONObject("extras") ?: JSONObject()

    /** Where the reminder goes: user turns counted back from the newest (0 = the newest). Shallow keeps the cached prefix long. */
    fun depth(persona: JSONObject): Int = extras(persona).optJSONObject("depthPrompt")?.optInt("depth", 0)?.coerceIn(0, MAX_DEPTH) ?: 0

    fun postHistory(persona: JSONObject): String = extras(persona).optString("postHistory")

    /** Lorebook entries whose keys appear in [recent] (constant ones always), in insertion order, within [budget] characters. */
    fun lore(persona: JSONObject, recent: List<String>, budget: Int = 3000): String {
        val entries = extras(persona).optJSONObject("characterBook")?.optJSONArray("entries") ?: return ""
        val haystack = recent.joinToString("\n").lowercase()
        fun any(keys: JSONArray?) = keys != null && (0 until keys.length()).any { haystack.contains(keys.optString(it).lowercase()) }
        val hits = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.filter { e ->
            e.optBoolean("enabled", true) && (e.optBoolean("constant") || any(e.optJSONArray("keys")) &&
                (!e.optBoolean("selective") || (e.optJSONArray("secondaryKeys")?.length() ?: 0) == 0 || any(e.optJSONArray("secondaryKeys"))))
        }.sortedBy { it.optInt("order", 100) }
        val out = StringBuilder()
        for (e in hits) {
            val content = e.optString("content")
            if (out.length + content.length > budget) break
            if (out.isNotEmpty()) out.append('\n')
            out.append(content)
        }
        return out.toString()
    }

    /** The few lines placed near the newest messages. */
    fun reminder(store: CompanionStore, request: ((JSONObject, (JSONObject) -> Unit) -> String)?): String {
        val persona = store.persona()
        extras(persona).optJSONObject("depthPrompt")?.optString("prompt")?.takeIf { it.isNotBlank() }?.let { return it }
        val cached = store.lifeState("voiceReminder")
        if (cached.optInt("version", -1) == persona.optInt("version") && cached.optString("text").isNotBlank()) return cached.getString("text")
        if (request != null && persona.optString("text").isNotBlank()) distill(store, persona, request)
        return GENERIC
    }

    /** Points the persona itself makes about sounding like a person, distilled once per persona version (owner pool). */
    private fun distill(store: CompanionStore, persona: JSONObject, request: (JSONObject, (JSONObject) -> Unit) -> String) {
        if (!distilling.compareAndSet(false, true)) return
        val version = persona.optInt("version")
        val prompt = "下面是你自己的人设。请从里面提炼 3 到 5 条最关键的说话要点，用来提醒自己在长对话里别变得像 AI：口吻、句式、用词、禁忌。" +
            "每条一句话、用“我”的口吻，只输出这几行，不要解释，不要复述整份人设。\n\n人设：\n" + persona.optString("text").take(20000)
        runCatching {
            request(JSONObject().put("scope", CompanionPolicy.OWNER).put("lane", "owner").put("purpose", "persona_voice").put("maxOutputTokens", 400).put("prompt", prompt)) { event ->
                when (event.optString("type")) {
                    "completed" -> {
                        val text = event.optString("text").lines().map { it.trim() }.filter { it.isNotBlank() }.take(5).joinToString("\n").take(600)
                        if (text.isNotBlank() && store.persona().optInt("version") == version) store.saveLifeState("voiceReminder", JSONObject().put("version", version).put("text", text))
                        distilling.set(false)
                    }
                    "failed", "cancelled" -> distilling.set(false)
                }
            }
        }.onFailure { distilling.set(false) }
    }

    /** Always part of the reminder: how she works inside is not small talk. */
    const val DISCRETION = "不谈论自己的后台设置、触发机制、模型配置，除非主人在私聊里问起。" +
        "没有观看记录的视频和文章，不能说自己看过；不知道某件事为什么没结果，就说不知道，不编原因。"

    /** Until her own points are ready: only mechanics of chat style, nothing about who she is. */
    const val GENERIC = "像真人发消息那样说话：口语、短句，不用列表和小标题，不说“作为 AI”，不在结尾总结或主动列出能帮的事。"

    /** Inserts the reminder [depth] user turns back and the post-history instructions after the newest message. */
    fun inject(contents: JSONArray, reminder: String, depth: Int, postHistory: String) {
        val userTurns = (0 until contents.length()).filter { contents.getJSONObject(it).optString("role") == "user" }
        if (userTurns.isEmpty()) return
        val target = userTurns[maxOf(0, userTurns.size - 1 - depth)]
        if (reminder.isNotBlank()) contents.getJSONObject(target).getJSONArray("parts").put(JSONObject().put("text", "（说话方式提醒，给你自己看的，不是对方说的话）\n$DISCRETION\n$reminder"))
        if (postHistory.isNotBlank()) contents.getJSONObject(userTurns.last()).getJSONArray("parts").put(JSONObject().put("text", "（写回复前再看一眼）\n$postHistory"))
    }
}
