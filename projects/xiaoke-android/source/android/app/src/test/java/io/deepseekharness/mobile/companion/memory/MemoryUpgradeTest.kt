package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MemoryUpgradeTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val said = LocalDate.of(2026, 10, 29) // a Thursday

    @Test fun `相对时间换成说话那天算出的日期`() {
        assertEquals("主人2026-10-28去了成都", TimeAnchor.resolve("主人昨天去了成都", said))
        assertEquals("2026-10-26 那周和2026-10-19 那周", TimeAnchor.resolve("这周和上周", said))
        assertEquals("主人2026-10-26说过", TimeAnchor.resolve("主人大前天说过", said))
        assertEquals("2025年开始学吉他，2026年11月要考试", TimeAnchor.resolve("去年开始学吉他，下个月要考试", said))
        // Already dated by the model: leave it.
        assertEquals("2026-10-28（昨天）去了成都", TimeAnchor.resolve("2026-10-28（昨天）去了成都", said))
    }

    @Test fun `分词与 HF tokenizers 一致`() {
        val vocab = javaClass.classLoader!!.getResourceAsStream("embedding/vocab-subset.txt")!!.bufferedReader(Charsets.UTF_8).readLines()
        val wp = WordPiece(vocab)
        fun tokens(text: String) = wp.encode(text).map { vocab[it] }
        assertEquals(listOf("[CLS]", "主", "人", "昨", "天", "说", "想", "去", "成", "都", "吃", "火", "锅", "！", "[SEP]"), tokens("主人昨天说想去成都吃火锅！"))
        assertEquals(listOf("[CLS]", "[UNK]", "love", "[UNK]", "[UNK]", "4", ".", "0", "，", "原", "神", "真", "好", "玩", "～", "[SEP]"), tokens("I love Genshin Impact 4.0，原神真好玩～"))
        assertEquals(listOf("[CLS]", "ｑ", "##ｑ", "群", "里", "的", "[UNK]", "老", "王", "[UNK]", "说", "：", "hello", "world", ".", ".", ".", "[SEP]"), tokens("ｑｑ群里的“老王”说：hello world..."))
        assertEquals(listOf("[CLS]", "打", "了", "３", "把", "排", "位", "赢", "了", "u", "##n", "##bel", "##ie", "##va", "##ble", "[SEP]"), tokens("打了３把排位\t赢了unbelievable"))
    }

    private fun memory(id: String, text: String, extra: JSONObject.() -> Unit = {}) = JSONObject().put("id", id).put("text", text).put("kind", "fact").apply(extra)

    @Test fun `语义、关键词和实体一起打分，过去的事实留着但排在后面`() {
        val now = 1_800_000_000_000L
        val records = JSONArray()
            .put(memory("hotpot", "主人喜欢吃川菜，尤其是火锅"))
            .put(memory("abyss", "主人在玩原神，卡在深渊第十二层") { put("entities", JSONArray().put("原神")) })
            .put(memory("old-job", "主人在杭州上班") { put("validUntil", now - 1000) })
            .put(memory("new-job", "主人搬到成都上班了"))
            .put(memory("unindexed", "周末打算去吃烤肉"))
        // "周末吃什么" shares no word with the hotpot memory; only meaning connects them.
        val semantic = mapOf("hotpot" to 0.62, "abyss" to 0.08, "old-job" to 0.2, "new-job" to 0.21)
        val ranked = MemoryRanking.rank(records, "周末吃什么", semantic = semantic, now = now)
        val order = (0 until ranked.length()).map { ranked.getJSONObject(it).getString("id") }
        assertEquals("hotpot", order.first())
        assertTrue(order.indexOf("unindexed") < order.indexOf("abyss")) // keyword-only still competes before the index catches up
        val jobs = MemoryRanking.rank(records, "主人上班", semantic = mapOf("old-job" to 0.5, "new-job" to 0.5), now = now)
        val jobOrder = (0 until jobs.length()).map { jobs.getJSONObject(it).getString("id") }
        assertTrue(jobOrder.indexOf("new-job") < jobOrder.indexOf("old-job"))
        assertTrue("old-job" in jobOrder)
        val game = MemoryRanking.rank(records, "原神新角色", semantic = null, now = now)
        assertEquals("abyss", game.getJSONObject(0).getString("id"))
    }

    private fun message(id: String, role: String, at: Long, actor: String = "") =
        JSONObject().put("id", id).put("scope", "qq:group:42").put("role", role).put("createdAt", at).put("actorId", actor).put("text", "x")

    @Test fun `整理结果只认这批消息，群里不形成主人的记忆和档案`() {
        val at = TimeAnchor.startMs(said, zone) + 20 * 3_600_000L
        val ids = MemoryConsolidator.Ids(
            listOf(message("a", "user", at, "qq:111"), message("b", "assistant", at + 60_000), message("c", "user", at + 120_000, "qq:222")),
            listOf(JSONObject().put("id", "old-1").put("text", "阿明在杭州上班"), JSONObject().put("id", "old-2").put("text", "小克觉得新版本不好玩")),
        )
        val answer = JSONObject("""{"memories":[
            {"text":"阿明搬到成都工作","kind":"fact","holder":"member","entities":["阿明","成都"],"validFrom":"2026-10-28","sources":["m1","m9"],"links":["e1","e7"],"ends":["e1"]},
            {"text":"我觉得新版本其实挺好玩","kind":"opinion","sources":["m2"],"ends":["e2"]},
            {"text":"主人明天要考试","kind":"plan","holder":"owner","sources":["m3"]},
            {"text":"群里在聊十一月的活动","kind":"weird","validUntil":"2026-11-30","sources":[]}
          ],"profiles":[
            {"type":"owner","name":"主人","member":"m3","summary":"在群里很活跃","sources":["m3"]},
            {"type":"member","name":"阿明","member":"m2","summary":"刚搬去成都"},
            {"type":"game","name":"原神","summary":"群里常聊的游戏","sources":["m1"]}
          ]}""")
        val plan = MemoryConsolidator.plan(answer, ids, group = true, now = at + 3_600_000L, zone = zone)
        assertEquals(3, plan.memories.size) // the owner's plan is not gathered from a group
        val moved = plan.memories[0]
        assertEquals("a", moved.getString("sourceId"))
        assertEquals(1, moved.getJSONArray("sourceRefs").length())
        assertEquals(listOf("old-1"), (0 until moved.getJSONArray("links").length()).map { moved.getJSONArray("links").getString(it) })
        assertEquals(TimeAnchor.startMs(LocalDate.of(2026, 10, 28), zone), moved.getLong("validFrom"))
        val opinion = plan.memories[1]
        assertEquals("opinion", opinion.getString("kind")); assertEquals("her", opinion.getString("holder"))
        assertEquals("fact", plan.memories[2].getString("kind"))
        assertEquals("c", plan.memories[2].getString("sourceId")) // no sources given: the last line from a person
        assertTrue(plan.memories[2].getLong("validUntil") > plan.memories[2].getLong("validFrom"))
        assertEquals(listOf("old-1" to 0, "old-2" to 1), plan.ends.map { it.memoryId to it.byIndex })
        // Owner profile in a group becomes that speaker's member profile; a "member" pointing at her own line is dropped.
        assertEquals(listOf("member" to "qq:222", "game" to ""), plan.profiles.map { it.type to it.actorId })
    }
}
