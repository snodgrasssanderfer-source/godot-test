package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.companion.chat.Addressing
import io.deepseekharness.mobile.companion.chat.OutgoingSegments
import io.deepseekharness.mobile.companion.social.OneBotSegments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupAndEmojiTest {
    private val long = 60 * 60_000L

    @Test fun `主人在群里像是在跟她说话就回，明显在跟别人聊就照常判断`() {
        assertTrue(Addressing.ownerToHer("戳戳我", direct = false, elsewhere = false, sinceHerMs = long, humansSinceHer = 20))
        assertTrue(Addressing.ownerToHer("为什么没报错", false, false, long, 20))
        assertTrue(Addressing.ownerToHer("你看这个", false, false, long, 20))
        assertTrue(Addressing.ownerToHer("哈哈哈", false, false, 60_000L, 1)) // right after she spoke
        assertTrue(Addressing.ownerToHer("随便说说", direct = true, elsewhere = false, sinceHerMs = long, humansSinceHer = 20))
        assertFalse(Addressing.ownerToHer("你们吃了没", false, false, long, 20))
        assertFalse(Addressing.ownerToHer("哈哈哈", false, false, long, 20))
        assertFalse(Addressing.ownerToHer("你看这个", direct = false, elsewhere = true, sinceHerMs = 30_000L, humansSinceHer = 1))
    }

    @Test fun `群友刚接她的话时更容易被回`() {
        assertTrue(Addressing.followsHer(30_000L, 1))
        assertFalse(Addressing.followsHer(10 * 60_000L, 1))
        assertFalse(Addressing.followsHer(30_000L, 8))
        assertEquals(0.9, Addressing.boosted(0.5), 1e-9)
        assertEquals(1.0, Addressing.boosted(0.8), 1e-9)
    }

    @Test fun `她写的表情标记变成真正的表情，表情包单独一条，认不出的标记删掉`() {
        val stickers = mapOf("猫猫震惊" to OutgoingSegments.Sticker("AAAA", "猫猫震惊"))
        val messages = OutgoingSegments.build("好热啊[表情:捂脸] [调皮][不存在的表情]，你看[表情包:猫猫震惊][表情包:没有这张]") { stickers[it] }
        assertEquals(2, messages.size)
        val first = messages[0]
        val types = (0 until first.length()).map { first.getJSONObject(it).getString("type") }
        assertEquals(listOf("text", "face", "face", "text"), types)
        assertEquals("264", first.getJSONObject(1).getJSONObject("data").getString("id"))
        assertEquals("12", first.getJSONObject(2).getJSONObject("data").getString("id"))
        val texts = (0 until first.length()).map { first.getJSONObject(it) }.filter { it.getString("type") == "text" }.map { it.getJSONObject("data").getString("text") }
        assertEquals(listOf("好热啊", "，你看"), texts)
        val image = messages[1].getJSONObject(0)
        assertEquals("image", image.getString("type")); assertEquals("base64://AAAA", image.getJSONObject("data").getString("file"))
        assertEquals(listOf(JSONArray().put(JSONObject().put("type", "text").put("data", JSONObject().put("text", "见第[1]条"))).toString()),
            OutgoingSegments.build("见第[1]条") { null }.map { it.toString() })
        assertTrue(OutgoingSegments.build("[表情包:没有这张]") { null }.isEmpty())
        assertEquals("好热啊 你看", OutgoingSegments.plain("好热啊[捂脸] 你看[表情包:猫]"))
    }

    @Test fun `引用了谁、@了别人都会被认出来`() {
        val quoted = OneBotSegments.parse(JSONArray().put(JSONObject().put("type", "reply").put("data", JSONObject().put("id", "777")))
            .put(JSONObject().put("type", "text").put("data", JSONObject().put("text", "真的吗"))), "10001")
        assertEquals("777", quoted.getString("replyTo")); assertFalse(quoted.getBoolean("mentionsOther"))
        val atOther = OneBotSegments.parse(JSONArray().put(JSONObject().put("type", "at").put("data", JSONObject().put("qq", "30003")))
            .put(JSONObject().put("type", "text").put("data", JSONObject().put("text", "你来"))), "10001")
        assertTrue(atOther.getBoolean("mentionsOther")); assertFalse(atOther.getBoolean("mentioned"))
        val atAll = OneBotSegments.parse(JSONArray().put(JSONObject().put("type", "at").put("data", JSONObject().put("qq", "all"))), "10001")
        assertFalse(atAll.getBoolean("mentionsOther"))
    }

    @Test fun `内部占位符不会发出去，单字关键词不再误触表情回应`() {
        assertEquals("你看这个", OutgoingSegments.stripInternal("你看这个 [图片附件] [已读取 0 个媒体片段；1 个没读到：HTTP 403]"))
        assertTrue(OutgoingSegments.build("[图片附件]") { null }.isEmpty())
        val laugh = io.deepseekharness.mobile.companion.chat.QqExtras.reaction("哈哈哈", false)
        assertEquals(laugh, io.deepseekharness.mobile.companion.chat.QqExtras.reaction("乐", false))
        assertFalse(io.deepseekharness.mobile.companion.chat.QqExtras.reaction("这首音乐好听", false) == laugh)
        assertFalse(io.deepseekharness.mobile.companion.chat.QqExtras.reaction("今天好快乐", false) == laugh)
    }

    @Test fun `诊断日志里的 key 和 token 被去掉`() {
        val line = XLog.scrub("x-goog-api-key: sk-0123456789abcdefABCDEF token=abcd1234efgh \"password\":\"hunter22\" Bearer abc.def.ghi1234")
        assertFalse(line.contains("sk-0123") || line.contains("abcd1234efgh") || line.contains("hunter22") || line.contains("abc.def.ghi1234"))
        assertTrue(line.contains("token=***"))
    }
}
