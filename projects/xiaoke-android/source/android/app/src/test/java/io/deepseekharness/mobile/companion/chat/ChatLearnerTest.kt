package io.deepseekharness.mobile.companion.chat

import io.deepseekharness.mobile.companion.CompanionPolicy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChatLearnerTest {
    private fun line(scope: String, role: String, text: String) = JSONObject().put("scope", scope).put("role", role).put("text", text)

    @Test fun nothingFromPrivateChatsEverBecomesLearningMaterial() {
        val owner = listOf(line(CompanionPolicy.OWNER, "user", "我们的私房话"))
        assertTrue(ChatLearner.learningLines(CompanionPolicy.OWNER, owner).isEmpty())
        assertTrue(ChatLearner.learningLines("qq:private:abc", listOf(line("qq:private:abc", "user", "私聊"))).isEmpty())
        assertEquals("", ChatLearner.describe(JSONObject(), 3, 3))
    }

    @Test fun groupLearningSkipsHerOwnLinesAndOtherScopes() {
        val g = "qq:group:abc"
        val lines = ChatLearner.learningLines(g, listOf(line(g, "user", "群友：yyds"), line(g, "assistant", "我说的话"), line("qq:group:other", "user", "别的群")))
        assertEquals(listOf("群友：yyds"), lines)
    }

    @Test fun repeatedFindingsGainWeight() {
        val found = ChatLearner.parse("""```json
{"styles":[{"situation":"惊讶","style":"用我嘞个去"}],"jargon":[{"term":"yyds","meaning":"永远的神"}]}
```""")
        val state = ChatLearner.merge(ChatLearner.merge(JSONObject(), found, 1), found, 2)
        assertEquals(2, state.getJSONArray("jargon").getJSONObject(0).getInt("count"))
        assertTrue(ChatLearner.describe(state, 3, 3).contains("yyds=永远的神"))
    }
}
