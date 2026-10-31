package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemoryRankingTest {
    private fun memory(id: String, text: String) = JSONObject().put("id", id).put("text", text)

    @Test fun semanticMatchDoesNotNeedLexicalOverlap() {
        val records = listOf(memory("story", "独立游戏虎头蛇尾"), memory("drink", "咖啡不加糖"))
        val hits = MemoryRanking.relevant(records, "开篇许下太多诺言，收尾却潦草", semantic = mapOf("story" to 0.62, "drink" to 0.21))
        assertEquals(listOf("story"), hits.map { it.getString("id") })
    }

    @Test fun backgroundSimilarityDoesNotMakeEveryMemoryRelevant() {
        val records = (1..10).map { memory("$it", "不同的日常记录$it") }
        assertTrue(MemoryRanking.relevant(records, "一个未聊过的话题", semantic = records.associate { it.getString("id") to 0.36 }).isEmpty())
    }

    @Test fun addingAnUnrelatedVectorDoesNotEraseAKeywordHit() {
        val record = memory("game", "星穹轨道的战斗与配乐")
        val records = listOf(record) + (1..20).map { memory("$it", "今天吃了早餐") }
        val query = "星穹轨道的战斗与配乐"
        assertEquals("game", MemoryRanking.relevant(records, query, semantic = null).first().getString("id"))
        assertEquals("game", MemoryRanking.relevant(records, query, semantic = mapOf("game" to 0.05)).first().getString("id"))
    }

    @Test fun latinTokensHaveTheSameWidthAndCase() {
        assertEquals(MemoryRanking.terms("ｒｐｇ ＡＢＣ１２"), MemoryRanking.terms("RPG abc12"))
    }

    @Test fun currentRecallFollowsTheEndedFactWhileHistoryKeepsBoth() {
        val old = memory("old", "主人每天乘公交上班").put("scope", "owner:local")
            .put("holder", "owner").put("validUntil", 100).put("endedBy", "new")
        val current = memory("new", "主人改为骑折叠车通勤").put("scope", "owner:local").put("holder", "owner")
        val scores = mapOf("old" to 0.8, "new" to 0.3)
        assertEquals(listOf("new"), MemoryRanking.relevant(listOf(old, current), "我现在怎么上班", semantic = scores, now = 200).map { it.getString("id") })
        assertEquals(setOf("old", "new"), MemoryRanking.relevant(listOf(old, current), "我以前怎么上班，后来变了吗", semantic = scores, now = 200).map { it.getString("id") }.toSet())
    }

    @Test fun anEvolutionLinkCannotCrossCandidateScopes() {
        val group = memory("group", "群里说乘公交上班").put("scope", "qq:group:42").put("endedBy", "private")
        val private = memory("private", "主人的私聊内容").put("scope", "owner:local")
        assertFalse(MemoryRanking.relevant(listOf(group, private), "以前怎么上班", semantic = mapOf("group" to 0.8, "private" to 0.1))
            .any { it.getString("id") == "private" })
    }

    @Test fun aScheduledChangeDoesNotExpireToday() {
        val record = memory("old", "目前的安排").put("superseded", true).put("validUntil", 300)
        assertFalse(MemoryRanking.past(record, 200))
        assertTrue(MemoryRanking.past(record, 300))
    }

    @Test fun askingHerAdviceStillRetrievesTheOwnersPreferences() {
        val preference = memory("food", "主人不喜欢太甜的饮料").put("holder", "owner")
        assertEquals("food", MemoryRanking.relevant(listOf(preference), "你觉得我适合喝什么", semantic = mapOf("food" to 0.7)).single().getString("id"))
    }
}
