package io.deepseekharness.mobile.companion.eval

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.MemoryRanking
import io.deepseekharness.mobile.companion.life.LifePolicy
import io.deepseekharness.mobile.companion.memory.MemoryView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import kotlin.math.ceil

private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
private fun JSONArray.strings() = (0 until length()).map(::getString)
private fun JSONObject.strings(key: String) = optJSONArray(key)?.strings().orEmpty()
private fun rate(numerator: Int, denominator: Int): Any = if (denominator == 0) JSONObject.NULL else numerator.toDouble() / denominator
private val zone = ZoneId.systemDefault()

private data class Retrieval(val query: JSONObject, val ids: Set<String>, val elapsedNs: Long, val scores: Map<String, Double> = emptyMap())

private fun attribution(records: List<JSONObject>, gold: JSONObject, now: Long): JSONObject {
    val opinions = records.filter { it.optString("kind") == "opinion" && gold.has(it.getString("id")) }
    var wrong = 0
    var missing = 0
    val failures = JSONArray()
    val labels = mapOf("owner" to "主人", "her" to "你自己", "member" to "群友", "other" to "他人", "unknown" to "归属不明")
    for (record in opinions) {
        val id = record.getString("id")
        val expected = labels.getValue(gold.getJSONObject(id).getString("holder"))
        val actual = MemoryView.project(record, now, zone).optString("who")
        if (actual.isBlank()) missing++ else if (actual != expected) wrong++
        if (actual != expected) failures.put(JSONObject().put("id", id).put("expected", expected).put("actual", actual))
    }
    return JSONObject().put("evaluated", opinions.size).put("wrong", wrong).put("missing", missing)
        .put("errorRate", rate(wrong + missing, opinions.size)).put("failures", failures)
}

private fun metrics(rows: List<Retrieval>, allRows: Map<String, Retrieval>, records: Map<String, JSONObject>, details: Boolean): JSONObject {
    var hits = 0
    var expectedCount = 0
    var returned = 0
    var expired = 0
    var currentReturned = 0
    var forbidden = 0
    var paraphrases = 0
    var paraphraseHits = 0
    var pairedHits = 0
    var completeParaphraseHits = 0
    var exact = 0
    var negativeCount = 0
    var negativeCorrect = 0
    val failures = JSONArray()
    for (row in rows) {
        val query = row.query
        val ids = row.ids
        val expected = query.strings("expectedIds").toSet()
        val matched = ids.intersect(expected)
        hits += matched.size
        expectedCount += expected.size
        returned += ids.size
        if (ids == expected) exact++
        val currentQuestion = query.optString("temporalMode", "current") == "current"
        val expiredHits = ids.filter { id ->
            val until = records.getValue(id).optLong("validUntil")
            currentQuestion && id !in expected && until > 0L && until <= query.getLong("asOf")
        }.toSet()
        expired += expiredHits.size
        if (currentQuestion) currentReturned += ids.size
        val forbiddenHits = ids.intersect(query.strings("forbiddenIds").toSet())
        forbidden += forbiddenHits.size
        if (expected.isEmpty()) {
            negativeCount++
            if (ids.isEmpty()) negativeCorrect++
        }
        val originalId = query.optString("paraphraseOf")
        if (originalId.isNotBlank()) {
            paraphrases++
            if (matched.isNotEmpty()) paraphraseHits++
            if (expected.isNotEmpty() && matched == expected) completeParaphraseHits++
            val original = allRows.getValue(originalId)
            if (matched.isNotEmpty() && original.ids.intersect(expected).isNotEmpty()) pairedHits++
        }
        if (details || ids != expected || expiredHits.isNotEmpty() || forbiddenHits.isNotEmpty()) {
            val item = JSONObject().put("id", query.getString("id")).put("text", query.getString("text"))
                .put("retrieved", JSONArray(ids.toList())).put("expected", JSONArray(expected.toList()))
                .put("missed", JSONArray((expected - ids).toList())).put("extra", JSONArray((ids - expected).toList()))
                .put("expired", JSONArray(expiredHits.toList())).put("forbidden", JSONArray(forbiddenHits.toList()))
            if (details) {
                item.put("retrievedScores", JSONObject(row.scores.filterKeys { it in ids }))
                    .put("missedScores", JSONObject(row.scores.filterKeys { it in expected - ids }))
            }
            failures.put(item)
        }
    }
    return JSONObject().put("queries", rows.size).put("hits", hits).put("expected", expectedCount).put("returned", returned)
        .put("recall", rate(hits, expectedCount)).put("precision", rate(hits, returned))
        .put("expiredReturned", expired).put("currentReturned", currentReturned).put("expiredFalseRecallRate", rate(expired, currentReturned))
        .put("forbiddenReturned", forbidden).put("paraphrases", paraphrases).put("paraphraseHits", paraphraseHits)
        .put("paraphraseSuccessRate", rate(paraphraseHits, paraphrases)).put("pairedParaphraseSuccessRate", rate(pairedHits, paraphrases))
        .put("completeParaphraseSuccessRate", rate(completeParaphraseHits, paraphrases))
        .put("exactMatchQueries", exact).put("negativeQueries", negativeCount).put("negativeCorrect", negativeCorrect)
        .put("latencyMs", percentiles(rows.map { it.elapsedNs })).put("queriesWithErrors", failures)
}

private fun percentiles(samples: List<Long>): JSONObject {
    val ordered = samples.sorted()
    fun percentile(fraction: Double): Any = if (ordered.isEmpty()) JSONObject.NULL else ordered[(ceil(ordered.size * fraction).toInt() - 1).coerceAtLeast(0)] / 1e6
    return JSONObject().put("p50", percentile(0.5)).put("p95", percentile(0.95))
}

private fun benchmark(records: List<JSONObject>, queries: List<JSONObject>): JSONArray {
    val result = JSONArray()
    val indices = records.mapIndexed { i, m -> m.getString("id") to i }.toMap()
    for (size in listOf(100, 1000, 5000)) {
        val copies = List(size) { i ->
            fun target(id: String) = indices[id]?.let { "bench-${i / records.size * records.size + it}" }.orEmpty()
            val source = records[i % records.size]
            JSONObject(source.toString()).put("id", "bench-$i").put("text", source.getString("text") + " 编号$i")
                .put("endedBy", target(source.optString("endedBy"))).put("currentFactId", target(source.optString("currentFactId")))
                .put("links", JSONArray(source.strings("links").map(::target)))
        }
        val query = queries.first().getString("text")
        val now = queries.maxOf { it.getLong("asOf") }
        // Fixed input and warmup keep compiler startup and JSON decoding outside the ranking measurement.
        repeat(5) { MemoryRanking.relevant(copies, query, 8, null, now = now) }
        val samples = List(20) {
            val start = System.nanoTime()
            MemoryRanking.relevant(copies, query, 8, null, now = now)
            System.nanoTime() - start
        }
        result.put(percentiles(samples).put("memories", size).put("samples", samples.size).put("mode", "lexical").put("selection", "relevant").put("limit", 8))
    }
    return result
}

fun main(args: Array<String>) {
    val corpus = JSONObject(File(args[0]).readText())
    val mode = args[2]
    val semantic = if (mode == "lexical") null else JSONObject(File(args[1]).readText())
    val split = args[3]
    val details = args[4].toBoolean()
    val records = corpus.getJSONArray("memories").objects()
    val recordsById = records.associateBy { it.getString("id") }
    val selection = args[7]
    val limit = args[8].toInt()
    val relative = args.getOrNull(10)?.toDouble() ?: 0.75
    val queries = corpus.getJSONArray("queries").objects()
    val evaluatedQueries = queries.filter { split == "all" || it.getString("split") == split }
    val ownerScopes = corpus.strings("ownerPrivateScopes").ifEmpty { listOf(CompanionPolicy.OWNER) }.toSet()
    val groups = corpus.strings("allowedGroupScopes").toSet()
    val modes = if (mode == "both") listOf("lexical", "semantic") else listOf(mode)
    val result = JSONObject().put("revision", args[6]).put("limit", limit).put("minScore", if (selection != "rank") 0.35 else JSONObject.NULL).put("relative", relative).put("retrieval", args[9]).put("selection", selection)
        .put("messages", corpus.getJSONArray("messages").length()).put("memories", records.size).put("queries", queries.size)
        .put("opinionAttribution", attribution(records, corpus.getJSONObject("memoryGold"), queries.maxOf { it.getLong("asOf") }))
    for (currentMode in modes) {
        val rows = evaluatedQueries.map { query ->
            val scope = query.getString("scope")
            val now = query.getLong("asOf")
            val owner = scope in ownerScopes
            val candidates = records.filter { memory ->
                memory.optLong("createdAt") <= now && (if (owner) memory.getString("scope") in ownerScopes + groups else memory.getString("scope") == scope) &&
                    (owner || !LifePolicy.touchesPrivate(memory) { it in ownerScopes })
            }
            val similarities = if (currentMode == "semantic") semantic!!.getJSONObject("scores").getJSONObject(query.getString("id")).let { values ->
                candidates.associate { it.getString("id") to values.getDouble(it.getString("id")) }
            } else null
            val started = System.nanoTime()
            val hits = when (selection) {
                "relevant" -> MemoryRanking.relevant(candidates, query.getString("text"), limit, similarities, now = now)
                // By reflection: older revisions have no scored(), and this file is compiled against each of them.
                "scored" -> (MemoryRanking::class.java.methods.first { it.name == "scored" && it.parameterCount == 7 }
                    .invoke(MemoryRanking, candidates, query.getString("text"), limit, similarities, 0.35, now, relative) as List<*>)
                    .map { (it as Pair<*, *>).first as JSONObject }
                "rank" -> MemoryRanking.rank(JSONArray(candidates), query.getString("text"), limit, similarities, now).objects()
                else -> error("Unknown selection: $selection")
            }
            val elapsed = System.nanoTime() - started
            Retrieval(query, hits.map { it.getString("id") }.toSet(), elapsed,
                if (details) MemoryRanking.score(candidates, query.getString("text"), similarities, now) else emptyMap())
        }
        val byId = rows.associateBy { it.query.getString("id") }
        val output = JSONObject()
        for (selected in if (split == "all") listOf("dev", "test", "all") else listOf(split)) {
            output.put(selected, metrics(rows.filter { selected == "all" || it.query.getString("split") == selected }, byId, recordsById, details))
        }
        result.put(currentMode, output)
    }
    if (semantic != null) result.put("semanticModel", semantic.getString("model")).put("semanticCorpusSha256", semantic.getString("corpusSha256"))
    if (args[5].toBoolean()) result.put("benchmarkMs", benchmark(records, queries))
    println(result.toString(2))
}
