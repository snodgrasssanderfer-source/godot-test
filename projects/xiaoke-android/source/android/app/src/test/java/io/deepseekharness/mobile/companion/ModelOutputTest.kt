package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelOutputTest {
    @Test fun `思考按标记取回，正文里漏出来的自检段落只留最后的回复`() {
        val request = GeminiWire.request(JSONObject().put("maxOutputTokens", 512).put("thinking", "low"), JSONArray(), "sys", JSONArray())
        assertTrue(request.getJSONObject("generationConfig").getJSONObject("thinkingConfig").getBoolean("includeThoughts"))
        val leaked = "💭 等等，我发现一个严重问题！\n\n检查一下：有没有反问句？没有。非常稳妥！直接输出。\n\n今天辛苦啦，早点睡。"
        assertEquals("今天辛苦啦，早点睡。" to true, GeminiWire.visible(leaked))
        assertEquals("好的" to true, GeminiWire.visible("<think>先想想</think>好的"))
        assertEquals("你检查一下作业吧" to false, GeminiWire.visible("你检查一下作业吧")) // one paragraph is left alone
    }

    @Test fun `换模型时旧签名换成占位，原历史不动`() {
        val contents = JSONArray().put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", "recall")).put("thoughtSignature", "argon-sig"))))
        val sent = GeminiWire.foreignSignatures(contents)
        assertEquals("skip_thought_signature_validator", sent.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertEquals("argon-sig", contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("thoughtSignature"))
        assertFalse(sent.toString().contains("argon-sig"))
    }
}
