package io.deepseekharness.mobile.companion.iteration

import org.json.JSONArray
import org.json.JSONObject
import io.deepseekharness.mobile.companion.CompanionContext

/** Raw observed media remains request-local even when provider replay data is retained. */
object ProviderHistoryPrivacy {
    fun forStorage(fingerprint: String, turns: JSONArray): JSONObject? =
        if (containsMedia(turns)) null else JSONObject().put("fingerprint", fingerprint).put("turns", JSONArray(turns.toString()))

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
