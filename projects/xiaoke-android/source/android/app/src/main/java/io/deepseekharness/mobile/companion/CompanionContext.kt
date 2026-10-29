package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/** Visible facts, persona and opaque provider state have independent retention and budgets. */
object CompanionContext {
    fun cost(text: String) = ceil(text.toByteArray(Charsets.UTF_8).size / 3.0).toInt()

    fun history(messages: JSONArray, fingerprint: String, budget: Int): JSONArray {
        val turns = mutableListOf<List<JSONObject>>()
        var used = 0
        for (i in messages.length() - 1 downTo 0) {
            val message = messages.getJSONObject(i)
            if (message.optString("role") == "notice") continue
            val wire = message.optJSONObject("wire")
            val saved = wire?.optJSONArray("turns")
            val group = if (message.optString("role") == "assistant" && wire?.optString("fingerprint") == fingerprint && saved != null)
                (0 until saved.length()).map { JSONObject(saved.getJSONObject(it).toString()) }
            else listOf(JSONObject().put("role", if (message.optString("role") == "assistant") "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", message.optString("text")))))
            val length = group.sumOf { cost(it.toString()) }
            if (used + length > budget) {
                check(turns.isNotEmpty()) { "最新消息超过本机上下文预算，请缩短内容或提高预算" }
                break
            }
            used += length
            turns.add(group)
        }
        val flattened = turns.asReversed().flatten().toMutableList()
        // Never begin a truncated conversation with an orphan model/tool response.
        while (flattened.isNotEmpty() && flattened.first().optString("role") != "user") flattened.removeAt(0)
        return JSONArray().also { out -> flattened.forEach(out::put) }
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
