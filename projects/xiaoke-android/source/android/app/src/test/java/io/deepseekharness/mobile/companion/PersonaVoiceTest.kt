package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaVoiceTest {
    private val extras = PersonaVoice.sanitize(JSONObject("""{
        "postHistory":"回复前想想她会怎么说",
        "depthPrompt":{"prompt":"别像客服","depth":9,"role":"system"},
        "characterBook":{"name":"书","entries":[
          {"keys":["猫"],"content":"她小时候养过一只橘猫","order":20},
          {"keys":["学校"],"secondaryKeys":["食堂"],"selective":true,"content":"她讨厌学校食堂","order":10},
          {"keys":[],"constant":true,"content":"她住在海边","order":1},
          {"keys":["猫"],"content":"关掉的条目","enabled":false},
          {"keys":["空"],"content":"   "}
        ]},
        "regex_scripts":[{"findRegex":".*"}]
      }"""))
    private val persona = JSONObject().put("text", "人设").put("version", 3).put("extras", extras)

    @Test fun `只留下认识的字段，空条目丢掉`() {
        assertEquals(setOf("postHistory", "depthPrompt", "characterBook"), extras.keys().asSequence().toSet())
        assertEquals(4, extras.getJSONObject("characterBook").getJSONArray("entries").length())
        assertEquals(PersonaVoice.MAX_DEPTH, PersonaVoice.depth(persona)) // card asked for 9; capped so most of the history stays cached
    }

    @Test fun `世界书按最近消息里的词带出，常驻条目总在，选择性条目要两组词都有`() {
        assertEquals("她住在海边\n她小时候养过一只橘猫", PersonaVoice.lore(persona, listOf("今天看到一只猫")))
        assertEquals("她住在海边", PersonaVoice.lore(persona, listOf("学校今天放假")))
        assertEquals("她住在海边\n她讨厌学校食堂", PersonaVoice.lore(persona, listOf("学校", "食堂的饭")))
    }

    @Test fun `提醒放在倒数第几条用户消息，历史后指令放在最新一条最后`() {
        fun turn(role: String, text: String) = JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))
        val contents = JSONArray().put(turn("user", "一")).put(turn("model", "a")).put(turn("user", "二")).put(turn("model", "b")).put(turn("user", "三"))
        PersonaVoice.inject(contents, "别像客服", 1, "回复前想想")
        val second = contents.getJSONObject(2).getJSONArray("parts")
        assertTrue(second.getJSONObject(1).getString("text").endsWith("别像客服"))
        val last = contents.getJSONObject(4).getJSONArray("parts")
        assertEquals(2, last.length())
        assertTrue(last.getJSONObject(1).getString("text").endsWith("回复前想想"))
        assertFalse(contents.getJSONObject(0).getJSONArray("parts").toString().contains("别像客服"))
    }
}
