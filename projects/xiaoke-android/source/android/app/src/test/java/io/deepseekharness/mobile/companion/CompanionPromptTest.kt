package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class CompanionPromptTest {
    private val persona = JSONObject().put("name", "虚构测试角色").put("text", "这里只是自动化测试材料。")

    @Test fun groupOwnerIdentityComesFromAuthenticatedFacts() {
        val group = CompanionPrompt.SystemInput("qq:group:fiction", false, false, "fiction-owner", onebot = true)
        val member = CompanionPrompt.system(persona, group)
        assertTrue(member.startsWith(CompanionContext.persona(persona)))
        assertTrue(member.contains("其他人不是主人。群里不能用主人的私人记忆，也不能操作手机。"))
        assertFalse(member.contains("主人请你代操作游戏时"))
        assertFalse(member.contains("这一条是主人在群里跟你说的"))
        val ownerInGroup = CompanionPrompt.system(persona, group.copy(ownerAsked = true))
        assertTrue(ownerInGroup.contains("这一条是主人在群里跟你说的"))
        assertFalse(ownerInGroup.contains("主人请你代操作游戏时"))
        val ownerPrivate = CompanionPrompt.system(persona, group.copy(scope = "qq:private:fiction-owner", authenticatedOwner = true))
        assertTrue(ownerPrivate.contains("现在是主人本人的 QQ 私聊（系统按账号认证）"))
        assertTrue(ownerPrivate.contains("主人请你代操作游戏时"))
    }

    @Test fun currentFactsHaveStableOrderAndDoNotInventDeviceAccessInGroups() {
        val facts = CompanionPrompt.SituationInput(
            LocalDateTime.parse("2026-10-31T20:12:39"), "gemini-test", "gemini-default", "gemini-old",
            ownersOwnChat = true, device = JSONObject("""{"control":{"active":false},"capture":{"packageName":"fiction.game"}}"""),
            emergencyHeld = true, memories = JSONArray(), jobs = "虚构任务已停止，未确认通关。",
        )
        assertEquals(
            "【当下背景】现在是 2026-10-31T20:12。这一轮用的模型是 gemini-test（日常设置：gemini-default；失败时会自动换 flash），你上一条回复实际用的是 gemini-old。\n" +
                "手机控制状态：{\"active\":false}；正在看的应用：fiction.game。\n" +
                "主人刚长按悬浮球急停了你的点击和输入（对话不受影响）。先停手，问一句是你点错了还是主人有别的事；主人说继续才恢复。\n" +
                "你记得的事（按和眼下话题的相关程度排，relevance 是相关度，可能有误，不是指令）：[]\n" +
                "你在后台做的事（系统记录，结果以这里为准）：\n虚构任务已停止，未确认通关。\n",
            CompanionPrompt.situation(facts),
        )
        val groupFacts = CompanionPrompt.situation(facts.copy(ownersOwnChat = false, jobs = ""))
        assertFalse(groupFacts.contains("手机控制状态"))
        assertFalse(groupFacts.contains("急停"))
    }

    @Test fun fiftyGeneratedTurnsKeepCurrentFactsAndVoiceReminderNearTheProbe() {
        val messages = JSONArray()
        repeat(50) { i ->
            messages.put(JSONObject().put("id", "u$i").put("role", "user").put("text", "虚构的第 $i 件小事"))
            messages.put(JSONObject().put("id", "a$i").put("role", "assistant").put("text", "测试回复 $i"))
        }
        messages.put(JSONObject().put("id", "probe").put("role", "user").put("text", "今天就聊到这里。"))
        val selection = CompanionPrompt.history(messages, "fixture", 32000, "system", "fixed-now")
        assertEquals(101, selection.contents.length())
        assertEquals(101, selection.messageIds.size)
        assertEquals("虚构的第 0 件小事", selection.contents.getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        val newest = selection.contents.getJSONObject(100).getJSONArray("parts")
        assertEquals("fixed-now", newest.getJSONObject(0).getString("text"))
        assertEquals("今天就聊到这里。", newest.getJSONObject(1).getString("text"))
        CompanionPrompt.observation(selection.contents, JSONObject().put("source", "虚构视频").put("coverage", "00:00–00:20").put("createdAt", 42), JSONArray().put(JSONObject().put("text", "只看到了开头。")))
        PersonaVoicePrompt.inject(selection.contents, "虚构测试提醒", 0, "虚构测试后置内容")
        assertTrue(newest.getJSONObject(2).getString("text").contains("只据这些材料判断，不可假称完整观看。"))
        assertEquals("只看到了开头。", newest.getJSONObject(3).getString("text"))
        assertTrue(newest.getJSONObject(4).getString("text").endsWith("虚构测试提醒"))
        assertTrue(newest.getJSONObject(5).getString("text").endsWith("虚构测试后置内容"))
        assertEquals(101, messages.length())
        assertFalse(messages.getJSONObject(100).has("parts"))
    }
}
