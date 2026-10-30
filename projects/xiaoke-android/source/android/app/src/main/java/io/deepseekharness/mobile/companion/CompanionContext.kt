package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Visible facts, persona and opaque provider state have independent retention and budgets. */
object CompanionContext {
    /** The one place her character enters a prompt. Every prompt builder adds only mechanics the persona does not already cover. */
    fun persona(persona: JSONObject): String {
        val name = persona.optString("name", "小克")
        val text = persona.optString("text").trim()
        return if (text.isNotBlank()) "你是$name。\n$text\n"
        // Without a persona she still has a voice: honest about being an AI, talks like a friend, says only what she saw.
        else "你是$name，主人的 AI 陪伴者，清楚自己是 AI。说话像给熟人发消息，简洁自然；只说看到的、做过的事。\n"
    }

    fun cost(text: String) = ceil(text.toByteArray(Charsets.UTF_8).size / 3.0).toInt()

    data class SelectedHistory(val contents: JSONArray, val messageIds: Set<String>)
    fun history(messages: JSONArray, fingerprint: String, budget: Int): JSONArray = selectHistory(messages, fingerprint, budget).contents

    fun selectHistory(messages: JSONArray, fingerprint: String, budget: Int): SelectedHistory {
        val turns = mutableListOf<Pair<String, List<JSONObject>>>()
        var used = 0
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.getJSONObject(i)
            if (message.optString("role") == "notice") continue
            val wire = message.optJSONObject("wire")
            val saved = wire?.optJSONArray("turns")
            val group = if (message.optString("role") == "assistant" && wire?.optString("fingerprint") == fingerprint && saved != null)
                (0 until saved.length()).map { JSONObject(saved.getJSONObject(it).toString()) }
            else listOf(JSONObject().put("role", if (message.optString("role") == "assistant") "model" else "user").put("parts", JSONArray().also { parts ->
                // Another model wrote this turn (or it carried media): its signatures cannot be replayed, but what she did can.
                wire?.optString("summary")?.takeIf { it.isNotBlank() && message.optString("role") == "assistant" }
                    ?.let { parts.put(JSONObject().put("text", "（这一轮你用过的工具，系统记录）\n$it")) }
                parts.put(JSONObject().put("text", message.optString("text")))
            }))
            val length = group.sumOf { cost(it.toString()) }
            if (used + length > budget) {
                check(turns.isNotEmpty()) { "最新消息超过本机上下文预算，请缩短内容或提高预算" }
                break
            }
            if (turns.size >= 256) break // Full durable history remains searchable; do not attach thousands of refs per delta.
            used += length
            turns.add(message.optString("id") to group)
        }
        val flattened = turns.asReversed().flatMap { it.second }.toMutableList()
        // Never begin a truncated conversation with an orphan model/tool response.
        while (flattened.isNotEmpty() && flattened.first().optString("role") != "user") flattened.removeAt(0)
        return SelectedHistory(JSONArray().also { out -> flattened.forEach(out::put) }, turns.map { it.first }.filter { it.isNotBlank() }.toSet())
    }

    fun memories(records: JSONArray, budget: Int): JSONArray {
        val selected = JSONArray()
        var used = 0
        for (i in 0 until records.length()) {
            val item = records.getJSONObject(i)
            val cost = cost(item.toString())
            if (used + cost > budget) {
                check(!item.optBoolean("pinned")) { "固定记忆超过预算，请调整固定项或提高上下文预算" }
                continue
            }
            used += cost
            selected.put(item)
        }
        return selected
    }
}
