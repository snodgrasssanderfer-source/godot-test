package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** Local lexical retrieval for Chinese and Latin text. No remote embeddings or private-data upload. */
object MemoryRanking {
    fun terms(text: String): Set<String> {
        val normalized = text.lowercase().take(16000)
        val result = Regex("[a-z0-9_]{2,}").findAll(normalized).map { it.value }.toMutableSet()
        for (span in Regex("[\\p{IsHan}]+").findAll(normalized)) {
            if (span.value.length == 1) result.add(span.value)
            else span.value.windowed(2).forEach(result::add)
        }
        return result.take(512).toSet()
    }

    fun rank(records: JSONArray, query: String, limit: Int = 80): JSONArray {
        val q = terms(query)
        val values = (0 until records.length()).map(records::getJSONObject)
            .filter { io.deepseekharness.mobile.companion.life.LifePolicy.visible(it) }
        val tokens = values.associateWith { terms(it.optString("text")) }
        val frequencies = q.associateWith { term -> tokens.values.count { term in it } }
        val ranked = values.sortedWith(compareByDescending<JSONObject> { if (it.optBoolean("pinned")) 1 else 0 }
            .thenByDescending { record ->
                val words = tokens.getValue(record)
                q.filter(words::contains).sumOf { ln(1.0 + (values.size + 1.0) / (frequencies.getValue(it) + 1.0)) } /
                    (1.0 + words.size / 200.0)
            }.thenByDescending { if (it.optString("status") == "confirmed") 1 else 0 }
            .thenByDescending { it.optLong("updatedAt") })
        return JSONArray().also { a -> ranked.take(limit).forEach(a::put) }
    }
}
