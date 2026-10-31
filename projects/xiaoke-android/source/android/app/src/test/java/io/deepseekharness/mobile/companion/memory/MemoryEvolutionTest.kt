package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.CompanionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MemoryEvolutionTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val at = ZonedDateTime.of(2026, 10, 29, 20, 15, 0, 0, zone).toInstant().toEpochMilli()
    private fun message(id: String, role: String, time: Long = at, actor: String = "") = JSONObject()
        .put("id", id).put("scope", CompanionPolicy.OWNER).put("role", role).put("createdAt", time).put("actorId", actor).put("text", "虚构测试对话")
    private fun item(holder: String = "her", sources: JSONArray = JSONArray().put("m2")) = JSONObject()
        .put("text", "我觉得完整收尾比华丽开头重要").put("kind", "opinion").put("holder", holder).put("sources", sources)
    private fun plan(item: JSONObject, old: List<JSONObject> = emptyList(), messages: List<JSONObject> = listOf(message("a", "user"), message("b", "assistant", at + 60_000))) =
        MemoryConsolidator.plan(JSONObject().put("memories", JSONArray().put(item)), MemoryConsolidator.Ids(messages, old), false, at + 3_600_000, zone)

    @Test fun `观点归属按持有人而非类别展示`() {
        for ((holder, who) in mapOf("owner" to "主人", "her" to "你自己", "member" to "群友", "other" to "他人"))
            assertEquals(who, MemoryView.project(JSONObject().put("kind", "opinion").put("holder", holder), at, zone).getString("who"))
    }

    @Test fun `旧观点没有归属时不猜成她自己`() {
        assertEquals("归属不明", MemoryView.project(JSONObject().put("kind", "opinion"), at, zone).getString("who"))
    }

    @Test fun `上下文保留来源演化链和一天内的具体时间`() {
        val view = MemoryView.project(JSONObject().put("kind", "opinion").put("holder", "her")
            .put("validFrom", at).put("validUntil", at + 60_000).put("sourceId", "a").put("source", "聊完后整理")
            .put("endedBy", "new").put("links", JSONArray().put("older")), at + 120_000, zone)
        assertEquals("a", view.getString("sourceId"))
        assertEquals("new", view.getString("endedBy"))
        assertEquals("older", view.getJSONArray("links").getString(0))
        assertTrue(view.getString("validFrom").contains("20:15"))
        assertTrue(view.getBoolean("past"))
    }

    @Test fun `来源缺失不能拿最后一条用户消息充数`() {
        val error = assertThrows(IllegalArgumentException::class.java) { plan(item(sources = JSONArray().put("m99"))) }
        assertTrue(error.message!!.contains("来源"))
    }

    @Test fun `无来源档案不能落库`() {
        val answer = JSONObject().put("profiles", JSONArray().put(JSONObject().put("type", "game").put("name", "纸星")
            .put("summary", "听说结局很草率").put("sources", JSONArray())))
        assertThrows(IllegalArgumentException::class.java) {
            MemoryConsolidator.plan(answer, MemoryConsolidator.Ids(listOf(message("a", "user")), emptyList()), false, at, zone)
        }
    }

    @Test fun `无来源兴趣不能凭空产生`() {
        val answer = JSONObject().put("interests", JSONArray().put(JSONObject().put("text", "独立游戏叙事").put("holder", "her")))
        assertThrows(IllegalArgumentException::class.java) {
            MemoryConsolidator.plan(answer, MemoryConsolidator.Ids(listOf(message("a", "user")), emptyList()), false, at, zone)
        }
    }

    @Test fun `不能把主人说的话提取成她的观点`() {
        assertThrows(IllegalArgumentException::class.java) { plan(item(sources = JSONArray().put("m1"))) }
    }

    @Test fun `不能把她说的话提取成主人的观点`() {
        assertThrows(IllegalArgumentException::class.java) { plan(item("owner")) }
    }

    @Test fun `同一天改口用消息时刻而非当天零点`() {
        val result = plan(item().put("validFrom", "2026-10-29"))
        assertEquals(at + 60_000, result.memories.single().getLong("validFrom"))
        assertEquals(at + 60_000, result.memories.single().getLong("occurredAt"))
    }

    @Test fun `她改变观点不会结束主人仍然持有的观点`() {
        val old = JSONObject().put("id", "old").put("kind", "opinion").put("holder", "owner")
        assertThrows(IllegalArgumentException::class.java) { plan(item().put("ends", JSONArray().put("e1")), listOf(old)) }
    }

    @Test fun `群友同名也不能互相改观点`() {
        val old = JSONObject().put("id", "old").put("holder", "member").put("actorId", "qq:alice")
        assertThrows(IllegalArgumentException::class.java) {
            plan(item("member", JSONArray().put("m1")).put("ends", JSONArray().put("e1")), listOf(old), listOf(message("a", "user", actor = "qq:bob")))
        }
    }

    @Test fun `观点改口保留旧观点链接和准确结束时刻`() {
        val old = JSONObject().put("id", "old").put("kind", "opinion").put("holder", "her")
        val result = plan(item().put("ends", JSONArray().put("e1")), listOf(old))
        assertEquals("old", result.memories.single().getJSONArray("links").getString(0))
        assertEquals(at + 60_000, result.ends.single().until)
        assertEquals(0, result.ends.single().byIndex)
    }

    @Test fun `混合来源中用持有人自己的话作为主来源`() {
        val result = plan(item("owner", JSONArray().put("m2").put("m1")))
        assertEquals("a", result.memories.single().getString("sourceId"))
        assertEquals(at, result.memories.single().getLong("validFrom"))
    }
}
