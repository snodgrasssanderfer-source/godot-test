package io.deepseekharness.mobile.companion.iteration

import org.json.JSONArray
import org.json.JSONObject
import io.deepseekharness.mobile.companion.CompanionContext

/** Raw observed media remains request-local even when provider replay data is retained. */
object ProviderHistoryPrivacy {
    /** Raw turns replay only to the same model setup; the plain summary of her tool calls survives model switches and media. */
    fun forStorage(fingerprint: String, turns: JSONArray): JSONObject? {
        val summary = toolSummary(turns)
        if (containsMedia(turns)) return if (summary.isBlank()) null else JSONObject().put("fingerprint", fingerprint).put("summary", summary)
        return JSONObject().put("fingerprint", fingerprint).put("turns", JSONArray(turns.toString())).also { if (summary.isNotBlank()) it.put("summary", summary) }
    }

    /** "watch_bilibili(query=莫比乌斯) → state=started" lines: what she called and what came back, without signatures or media. */
    fun toolSummary(turns: JSONArray): String {
        val lines = mutableListOf<String>()
        for (i in 0 until turns.length()) {
            val parts = turns.optJSONObject(i)?.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                part.optJSONObject("functionCall")?.let { call ->
                    lines.add("调用 ${call.optString("name")}(${flat(call.optJSONObject("args")).take(160)})")
                }
                part.optJSONObject("functionResponse")?.let { response ->
                    lines.add("→ ${response.optString("name")} 返回 ${flat(response.optJSONObject("response")).take(240)}")
                }
            }
        }
        return lines.takeLast(16).joinToString("\n")
    }

    private fun flat(value: JSONObject?): String = value?.keys()?.asSequence()?.filterNot { it == "preview" }
        ?.joinToString(", ") { key -> "$key=${value.opt(key).toString().replace('\n', ' ').take(120)}" }.orEmpty()

    fun estimatedInput(contents: JSONArray): Int {
        var total = 0L
        for (i in 0 until contents.length()) {
            val parts = contents.getJSONObject(i).optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.getJSONObject(j)
                total += if (part.has("inlineData") || part.has("fileData")) 4096 else CompanionContext.cost(part.toString())
            }
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun containsMedia(value: Any?): Boolean = when (value) {
        is JSONObject -> value.has("inlineData") || value.has("fileData") || value.keys().asSequence().any { containsMedia(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { containsMedia(value.opt(it)) }
        is String -> value.startsWith("data:image/") || value.startsWith("data:audio/") || value.startsWith("data:video/")
        else -> false
    }
}
