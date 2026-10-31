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
    const val MAX_DEPTH = PersonaVoicePrompt.MAX_DEPTH

    fun sanitize(extras: JSONObject?): JSONObject = PersonaVoicePrompt.sanitize(extras)
    fun extras(persona: JSONObject): JSONObject = PersonaVoicePrompt.extras(persona)
    fun depth(persona: JSONObject): Int = PersonaVoicePrompt.depth(persona)
    fun postHistory(persona: JSONObject): String = PersonaVoicePrompt.postHistory(persona)
    fun lore(persona: JSONObject, recent: List<String>, budget: Int = 3000): String = PersonaVoicePrompt.lore(persona, recent, budget)

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

    const val DISCRETION = PersonaVoicePrompt.DISCRETION
    const val GENERIC = PersonaVoicePrompt.GENERIC
    fun inject(contents: JSONArray, reminder: String, depth: Int, postHistory: String) =
        PersonaVoicePrompt.inject(contents, reminder, depth, postHistory)
}
