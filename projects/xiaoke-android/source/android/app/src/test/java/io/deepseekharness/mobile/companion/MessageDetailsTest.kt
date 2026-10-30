package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.companion.chat.StickerReader
import io.deepseekharness.mobile.companion.social.OneBotSegments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDetailsTest {
    private fun poke(group: String?, from: String, to: String) = JSONObject().put("post_type", "notice").put("notice_type", "notify").put("sub_type", "poke")
        .put("self_id", "10001").put("user_id", from).put("target_id", to).put("time", 1_700_000_000).also { if (group != null) it.put("group_id", group) }
        .put("raw_info", JSONArray().put(JSONObject().put("type", "qq").put("uid", "u")).put(JSONObject().put("type", "nor").put("txt", "戳了戳"))
            .put(JSONObject().put("type", "qq").put("uid", "v")).put(JSONObject().put("type", "nor").put("txt", "的小脑袋")))

    @Test fun `戳一戳：群里戳她、私聊戳她都能认出来，她自己戳人的不算`() {
        val inGroup = OneBotProtocol.poke(poke("20002", "30003", "10001"), "10001")!!
        assertTrue(inGroup.getString("scope").startsWith("qq:group:")); assertTrue(inGroup.getBoolean("atSelf"))
        assertEquals("30003", inGroup.getString("poker")); assertEquals("的小脑袋", inGroup.getString("suffix"))
        val others = OneBotProtocol.poke(poke("20002", "30003", "40004"), "10001")!!
        assertFalse(others.getBoolean("atSelf"))
        val private = OneBotProtocol.poke(poke(null, "30003", "10001"), "10001")!!
        assertEquals("private", private.getString("targetType")); assertEquals("30003", private.getString("targetId"))
        assertNull(OneBotProtocol.poke(poke("20002", "10001", "30003"), "10001"))
        assertNull(OneBotProtocol.poke(poke("20002", "30003", "10001").put("sub_type", "lucky_king"), "10001"))
    }

    @Test fun `QQ 表情换成名字，表情包和商城表情单独标出来`() {
        val parsed = OneBotSegments.parse(JSONArray()
            .put(JSONObject().put("type", "face").put("data", JSONObject().put("id", "264")))
            .put(JSONObject().put("type", "mface").put("data", JSONObject().put("summary", "[哈哈哈]").put("url", "https://gxh.vip.qq.com/club/item/parcel/item/ab/abcd/raw300.gif")))
            .put(JSONObject().put("type", "image").put("data", JSONObject().put("sub_type", "1").put("summary", "[动画表情]").put("url", "https://multimedia.nt.qq.com/download?x=1")))
            .put(JSONObject().put("type", "image").put("data", JSONObject().put("url", "https://multimedia.nt.qq.com/download?x=2"))), "10001")
        val text = parsed.getString("text")
        assertTrue(text, text.contains("[表情:捂脸]"))
        val kinds = (0 until parsed.getJSONArray("attachments").length()).map { parsed.getJSONArray("attachments").getJSONObject(it).getString("kind") }
        assertEquals(listOf("face", "sticker", "sticker", "image"), kinds)
        assertTrue(text.contains("[表情包：哈哈哈#1]") && text.contains("[图片附件]"))
        assertEquals(1, parsed.getJSONArray("images").length()) // stickers are not sent as pictures
    }

    @Test fun `表情包只从 QQ 自己的图床下载`() {
        assertNotNull(StickerReader.stickerUrl("https://gxh.vip.qq.com/club/item/parcel/item/ab/abcd/raw300.gif"))
        assertNotNull(StickerReader.stickerUrl("https://multimedia.nt.qq.com/download?appid=1407"))
        assertNull(StickerReader.stickerUrl("https://example.com/a.gif"))
        assertNull(StickerReader.stickerUrl("https://gxh.vip.qq.com.evil.test/a.gif"))
    }

    @Test fun `说话提醒里总带着不谈后台设置那一条`() {
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "在吗"))))
        PersonaVoice.inject(contents, "口语一点", 0, "")
        assertTrue(contents.getJSONObject(0).getJSONArray("parts").getJSONObject(1).getString("text").contains(PersonaVoice.DISCRETION))
    }
}
