package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * Hybrid memory retrieval, after mem0ai/mem0 26cdefb46600c0fa5953c4b5ee5003c125539fc4 mem0/utils/scoring.py (Apache-2.0):
 * semantic similarity + sigmoid-normalized BM25 + entity boost, added and divided by the most the active signals
 * could give. Chinese is tokenized into character bigrams. A memory without a vector yet is scored on the
 * signals it has, so nothing disappears while the index catches up. Memories that are no longer true stay
 * findable (she should be able to say "你上周还……"), slightly behind current ones.
 */
object MemoryRanking {
    const val SEMANTIC_THRESHOLD = 0.1
    const val ENTITY_BOOST = 0.5
    private const val PAST_WEIGHT = 0.85

    fun terms(text: String): Set<String> {
        val normalized = text.lowercase().take(16000)
        val result = Regex("[a-z0-9_]{2,}").findAll(normalized).map { it.value }.toMutableSet()
        for (span in Regex("[\\p{IsHan}]+").findAll(normalized)) {
            if (span.value.length == 1) result.add(span.value)
            else span.value.windowed(2).forEach(result::add)
        }
        return result.take(512).toSet()
    }

    private fun termList(text: String): List<String> {
        val normalized = text.lowercase().take(16000)
        val out = Regex("[a-z0-9_]{2,}").findAll(normalized).map { it.value }.toMutableList()
        for (span in Regex("[\\p{IsHan}]+").findAll(normalized)) if (span.value.length == 1) out.add(span.value) else out.addAll(span.value.windowed(2))
        return out
    }

    /** mem0 get_bm25_params; a Chinese "word" is taken as two bigrams. */
    fun bm25Params(queryTerms: Int): Pair<Double, Double> = when ((queryTerms + 1) / 2) {
        in 0..3 -> 5.0 to 0.7
        in 4..6 -> 7.0 to 0.6
        in 7..9 -> 9.0 to 0.5
        in 10..15 -> 10.0 to 0.5
        else -> 12.0 to 0.5
    }

    fun sigmoid(raw: Double, midpoint: Double, steepness: Double) = 1.0 / (1.0 + exp(-steepness * (raw - midpoint)))

    fun past(record: JSONObject, now: Long): Boolean = record.optBoolean("superseded") ||
        record.optLong("validUntil").let { it in 1..now }

    /** Usable for recall: not deleted, forgotten or suppressed. Past facts are kept. */
    fun recallable(record: JSONObject) = !record.optBoolean("deleted") && !record.optBoolean("forgotten") && !record.optBoolean("suppressed")

    fun score(records: List<JSONObject>, query: String, semantic: Map<String, Double>?, now: Long): Map<String, Double> {
        val q = termList(query).toSet()
        if (q.isEmpty() && semantic.isNullOrEmpty()) return records.associate { it.optString("id") to 0.0 }
        val docs = records.associateWith { termList(it.optString("text")) }
        val avg = docs.values.map { it.size }.average().takeIf { it > 0 } ?: 1.0
        val df = q.associateWith { t -> docs.values.count { t in it } }
        val (mid, steep) = bm25Params(q.size)
        val lowered = query.lowercase()
        return records.associate { record ->
            val words = docs.getValue(record)
            val tf = words.groupingBy { it }.eachCount()
            val raw = q.sumOf { t ->
                val f = tf[t] ?: return@sumOf 0.0
                val idf = ln(1.0 + (records.size - df.getValue(t) + 0.5) / (df.getValue(t) + 0.5))
                idf * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * words.size / avg))
            }
            val bm25 = if (raw > 0) sigmoid(raw, mid, steep) else 0.0
            val entities = record.optJSONArray("entities")
            val entity = if (entities != null && (0 until entities.length()).any { entities.optString(it).let { e -> e.length >= 2 && lowered.contains(e.lowercase()) } }) ENTITY_BOOST else 0.0
            val id = record.optString("id")
            val sem = semantic?.get(id)
            val combined = when {
                sem != null && sem < SEMANTIC_THRESHOLD && bm25 < 0.5 && entity == 0.0 -> 0.0
                sem != null -> (sem.coerceIn(0.0, 1.0) + bm25 + entity) / (2.0 + ENTITY_BOOST)
                else -> (bm25 + entity) / (1.0 + ENTITY_BOOST)
            }
            id to combined * if (past(record, now)) PAST_WEIGHT else 1.0
        }
    }

    /**
     * What she asked for, not everything: at most [limit], and only what scores at least [minScore] (pinned ones
     * always). An empty query has nothing to judge by and falls back to the newest few.
     */
    fun relevant(records: List<JSONObject>, query: String, limit: Int = 8, semantic: Map<String, Double>? = null, minScore: Double = 0.35,
                 now: Long = System.currentTimeMillis()): List<JSONObject> {
        val values = records.filter(::recallable)
        if (query.isBlank()) return values.sortedByDescending { it.optLong("updatedAt", it.optLong("createdAt")) }.take(limit)
        val scores = score(values, query, semantic, now)
        return values.filter { it.optBoolean("pinned") || (scores[it.optString("id")] ?: 0.0) >= minScore }
            .sortedByDescending { scores[it.optString("id")] ?: 0.0 }.take(limit)
    }

    fun rank(records: JSONArray, query: String, limit: Int = 80, semantic: Map<String, Double>? = null, now: Long = System.currentTimeMillis()): JSONArray {
        val values = (0 until records.length()).map(records::getJSONObject).filter(::recallable)
        val scores = score(values, query, semantic, now)
        val ranked = values.sortedWith(compareByDescending<JSONObject> { if (it.optBoolean("pinned")) 1 else 0 }
            .thenByDescending { scores[it.optString("id")] ?: 0.0 }
            .thenByDescending { if (past(it, now)) 0 else 1 }
            .thenByDescending { if (it.optString("status") == "confirmed") 1 else 0 }
            .thenByDescending { it.optLong("updatedAt") })
        return JSONArray().also { a -> ranked.take(limit).forEach(a::put) }
    }
}
