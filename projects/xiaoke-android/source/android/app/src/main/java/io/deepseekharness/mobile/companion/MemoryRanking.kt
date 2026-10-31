package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Hybrid memory retrieval, after mem0ai/mem0 26cdefb46600c0fa5953c4b5ee5003c125539fc4 mem0/utils/scoring.py (Apache-2.0):
 * BM25 and semantic evidence can each retrieve a memory; agreement and named entities break ties.
 * Chinese bigrams retain unknown names. Past facts remain available for historical recall.
 */
object MemoryRanking {
    const val SEMANTIC_THRESHOLD = 0.1
    const val ENTITY_BOOST = 0.5
    private const val PAST_WEIGHT = 0.85
    private val latin = Regex("[a-z0-9_]{2,}")
    private val han = Regex("[\\p{IsHan}]+")
    private val history = Regex("以前|之前|当时|那时|那天|那阵|曾经|过去|上周|上个|前几天|原先|起初|一开始|最初|后来|变化|改变|改口|变了|怎么.*不|还记得|聊过|各自|我们.*争论|\\d{1,2}月\\d{1,2}日")
    private val joint = Regex("我们|咱们|各自|双方|你和我|我和你")
    private val herView = Regex("你(?:自己)?(?:现在|当时|以前|后来|原先|最初|还|更|是|对[^，。？！?]{0,24})*(?:觉得|认为|怎么看|喜欢|讨厌|建议|推荐|想|嫌|支持|反对)")

    fun terms(text: String): Set<String> = termList(text).toSet().take(512).toSet()

    private fun normalized(text: String) = Normalizer.normalize(text.take(16000), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun termList(text: String): List<String> {
        val normalized = normalized(text)
        val out = latin.findAll(normalized).map { it.value }.toMutableList()
        for (span in han.findAll(normalized)) if (span.value.length == 1) out.add(span.value) else out.addAll(span.value.windowed(2))
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

    fun past(record: JSONObject, now: Long): Boolean = record.optLong("validUntil").let {
        if (it > 0) it <= now else record.optBoolean("superseded")
    }

    /** Usable for recall: not deleted, forgotten or suppressed. Past facts are kept. */
    fun recallable(record: JSONObject) = !record.optBoolean("deleted") && !record.optBoolean("forgotten") && !record.optBoolean("suppressed")

    fun score(records: List<JSONObject>, query: String, semantic: Map<String, Double>?, now: Long): Map<String, Double> {
        val q = termList(query).toSet()
        if (q.isEmpty() && semantic.isNullOrEmpty()) return records.associate { it.optString("id") to 0.0 }
        val docs = records.map { termList(it.optString("text")).groupingBy { it }.eachCount() }
        val lengths = docs.map { it.values.sum() }
        val avg = lengths.average().takeIf { it > 0 } ?: 1.0
        val df = HashMap<String, Int>()
        docs.forEach { words -> words.keys.forEach { if (it in q) df[it] = (df[it] ?: 0) + 1 } }
        val idf = df.mapValues { (_, count) -> ln(1.0 + (records.size - count + 0.5) / (count + 0.5)) }
        val queryWeight = idf.values.sum().coerceAtLeast(1.0)
        val lowered = normalized(query)
        val similarities = records.mapNotNull { semantic?.get(it.optString("id")) }.sorted()
        // Remove the query's background similarity; a high cosine shared by most memories is weak evidence.
        val background = if (similarities.size >= 8) similarities[similarities.size / 2].coerceIn(0.0, 0.35) else 0.2
        return records.mapIndexed { index, record ->
            val tf = docs[index]
            val raw = q.sumOf { t ->
                val f = tf[t] ?: return@sumOf 0.0
                idf.getValue(t) * f * 2.2 / (f + 1.2 * (0.25 + 0.75 * lengths[index] / avg))
            }
            val coverage = q.sumOf { if (it in tf) idf[it] ?: 0.0 else 0.0 } / queryWeight
            val bm25 = raw / (raw + 4.0) * sqrt(coverage)
            val entities = record.optJSONArray("entities")
            val entity = if (entities != null && (0 until entities.length()).any { entities.optString(it).let { e -> e.length >= 2 && lowered.contains(normalized(e)) } }) ENTITY_BOOST else 0.0
            val id = record.optString("id")
            val sem = semantic?.get(id)?.let { (1.5 * (it - background) / (1.0 - background)).coerceIn(0.0, 1.0) } ?: 0.0
            val combined = (maxOf(sem, bm25) + 0.15 * minOf(sem, bm25) + 0.3 * entity * sqrt(coverage)).coerceAtMost(1.0)
            id to combined * if (past(record, now)) PAST_WEIGHT else 1.0
        }.toMap()
    }

    /**
     * At most [limit], above [minScore] and near the strongest evidence. Pins bypass relevance, not validity.
     * Historical questions keep ended facts; an empty query falls back to the newest few.
     */
    fun relevant(records: List<JSONObject>, query: String, limit: Int = 8, semantic: Map<String, Double>? = null, minScore: Double = 0.35,
                 now: Long = System.currentTimeMillis()): List<JSONObject> {
        val values = records.filter(::recallable)
        if (query.isBlank()) return values.sortedByDescending { it.optLong("updatedAt", it.optLong("createdAt")) }.take(limit)
        val historical = history.containsMatchIn(query)
        val hers = !joint.containsMatchIn(query) && herView.containsMatchIn(query)
        val scores = score(values, query, semantic, now).toMutableMap()
        val byId = values.associateBy { it.optString("id") }
        // Follow only explicit evolution edges inside the already scoped candidates.
        for (value in values) {
            val id = value.optString("id")
            for (key in listOf("endedBy", "currentFactId")) {
                val next = byId[value.optString(key)] ?: continue
                if (next.optString("scope") != value.optString("scope")) continue
                val strength = scores[id] ?: 0.0
                scores[next.optString("id")] = maxOf(scores[next.optString("id")] ?: 0.0, strength)
                if (historical) scores[id] = maxOf(strength, scores[next.optString("id")] ?: 0.0)
            }
        }
        // Asking her opinion can still need the owner's facts ("你觉得我适合什么"). Prefer, do not exclude.
        if (hers) values.filter { it.optString("holder") == "her" }.forEach {
            val id = it.optString("id")
            scores[id] = (scores[id] ?: 0.0) * 1.35
        }
        val eligible = values.filter { historical || !past(it, now) }
        val best = eligible.maxOfOrNull { scores[it.optString("id")] ?: 0.0 } ?: 0.0
        val threshold = maxOf(minScore, best * 0.75)
        return eligible.filter { it.optBoolean("pinned") || (scores[it.optString("id")] ?: 0.0) >= threshold }
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
