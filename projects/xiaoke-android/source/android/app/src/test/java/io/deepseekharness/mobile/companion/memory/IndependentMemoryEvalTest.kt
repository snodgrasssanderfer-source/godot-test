package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * An independent check of memory recall, written without looking at the ranking internals or the other eval set:
 * 60 fictional memories over four weeks and 112 queries (paraphrase, changed opinions, expired facts, indirect
 * reference, two people's views, never discussed). Semantic scores come from the phone's own bge-small-zh ONNX model.
 * Only the public entry points are called, so the same file scores any version. The report goes to stdout and
 * build/indie-report.txt. To rebuild: src/test/memory-indie/build_set.py writes the set, IndieTokenDumpTest the token
 * ids, embed.py the scores.
 */
class IndependentMemoryEvalTest {
    private val set = JSONObject(File("src/test/resources/memory-indie/indie_memory.json").readText())
    private val cosine = JSONObject(File("src/test/resources/memory-indie/indie_semantic.json").readText())
    private val now = set.getLong("now")
    private val memories = set.getJSONArray("memories").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private val queries = set.getJSONArray("queries").let { a -> (0 until a.length()).map(a::getJSONObject) }

    private fun ids(a: JSONArray) = (0 until a.length()).map(a::getString).toSet()
    private fun semantic(q: JSONObject) = cosine.getJSONObject(q.getString("id")).let { s -> s.keys().asSequence().associateWith { s.getDouble(it) } }

    data class Score(val full: Double, val recall: Double, val precision: Double, val stale: Double, val noAnswer: Double, val byCategory: Map<String, Double>)

    private fun score(select: (JSONObject) -> List<String>): Score {
        var fullHits = 0; var answerable = 0; var expected = 0; var found = 0; var returned = 0; var useful = 0
        var currentQs = 0; var staleQs = 0; var noneQs = 0; var noneOk = 0
        val cat = mutableMapOf<String, Pair<Int, Int>>()
        for (q in queries) {
            val got = select(q).toSet()
            val expect = ids(q.getJSONArray("expect")); val accept = ids(q.getJSONArray("accept")); val stale = ids(q.getJSONArray("stale"))
            val c = q.getString("category")
            if (expect.isEmpty()) {
                noneQs++; val ok = got.isEmpty(); if (ok) noneOk++
                cat[c] = (cat[c]?.first ?: 0) + (if (ok) 1 else 0) to (cat[c]?.second ?: 0) + 1
                continue
            }
            answerable++; expected += expect.size; found += (got intersect expect).size
            returned += got.size; useful += (got intersect (expect + accept)).size
            val full = got.containsAll(expect); if (full) fullHits++
            cat[c] = (cat[c]?.first ?: 0) + (if (full) 1 else 0) to (cat[c]?.second ?: 0) + 1
            if (q.getString("intent") == "current" && stale.isNotEmpty()) { currentQs++; if ((got intersect stale).isNotEmpty()) staleQs++ }
        }
        return Score(fullHits.toDouble() / answerable, found.toDouble() / expected, if (returned == 0) 0.0 else useful.toDouble() / returned,
            if (currentQs == 0) 0.0 else staleQs.toDouble() / currentQs, if (noneQs == 0) 0.0 else noneOk.toDouble() / noneQs,
            cat.mapValues { (_, v) -> v.first.toDouble() / v.second })
    }

    private fun relevant(limit: Int, withVectors: Boolean): (JSONObject) -> List<String> = { q ->
        MemoryRanking.relevant(memories.map { JSONObject(it.toString()) }, q.getString("query"), limit, if (withVectors) semantic(q) else null, now = now).map { it.getString("id") }
    }
    private fun recallTool(q: JSONObject) =
        MemoryRanking.scored(memories.map { JSONObject(it.toString()) }, q.getString("query"), 8, semantic(q), now = now, relative = 0.5)
    private val WEAK = 0.5

    private fun ranked(limit: Int): (JSONObject) -> List<String> = { q ->
        val a = MemoryRanking.rank(JSONArray(memories.map { JSONObject(it.toString()) }), q.getString("query"), limit, semantic(q), now)
        (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    }

    @Test fun report() {
        assertEquals(112, queries.size)
        fun pct(x: Double) = "%5.1f%%".format(x * 100)
        val rows = listOf(
            "recall 工具（线上配置：最高分一半以上，最多 8 条）" to score { q -> recallTool(q).map { it.first.getString("id") } },
            "recall 工具（relevant，最多 8 条）" to score(relevant(8, true)),
            "recall 工具（relevant，最多 8 条，无向量）" to score(relevant(8, false)),
            "自动上下文 relevant（最多 40 条）" to score(relevant(40, true)),
            "自动上下文 rank（前 40 条）" to score(ranked(40)),
        )
        // The low-relevance hint: never-discussed questions that come back empty or weak, and answerable ones wrongly marked weak.
        var flagged = 0; var none = 0; var falseFlag = 0; var answerable = 0
        for (q in queries) {
            val best = recallTool(q).maxOfOrNull { it.second } ?: 0.0
            if (q.getJSONArray("expect").length() == 0) { none++; if (best < WEAK) flagged++ } else { answerable++; if (best < WEAK) falseFlag++ }
        }
        val text = buildString {
            append("指标：全中=应召回的全部返回；召回率=命中/应召回；准确率=返回中属于应召回或可接受的比例；过期误召=问现在却返回已过期记录的题比例；没聊过=什么都不返回的比例\n")
            append("相关度提示（< $WEAK）：没聊过的问题被提示或无结果 ${pct(flagged.toDouble() / none)}，能答的问题被误提示 ${pct(falseFlag.toDouble() / answerable)}\n")
            for ((name, s) in rows) {
                append("$name\n  全中 ${pct(s.full)}  召回率 ${pct(s.recall)}  准确率 ${pct(s.precision)}  过期误召 ${pct(s.stale)}  没聊过 ${pct(s.noAnswer)}\n  ")
                append(s.byCategory.entries.sortedBy { it.key }.joinToString("  ") { "${it.key} ${pct(it.value)}" }).append('\n')
            }
        }
        println(text)
        File("build/indie-report.txt").writeText(text)
    }
}
