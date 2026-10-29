package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class ChooseModelTest {
    private val settings = CompanionPolicy.defaultSettings()

    @Test fun everydayTalkStaysLightAndHeavyWorkGoesDeep() {
        assertEquals("gemini-3.8-flash", CompanionPolicy.chooseModel(settings, "今天的饭太甜了"))
        assertEquals("gemini-4-argon", CompanionPolicy.chooseModel(settings, "这段日志里的报错帮我看看"))
        assertEquals("gemini-4-argon", CompanionPolicy.chooseModel(settings, "```kotlin\nfun a() = 1\n```"))
        // Without a second model configured, everything stays on the active one.
        val single = CompanionPolicy.defaultSettings().put("profiles", org.json.JSONArray().put(CompanionPolicy.profile("gemini-3.8-flash")))
        assertEquals("gemini-3.8-flash", CompanionPolicy.chooseModel(single, "帮我分析这段代码"))
    }
}
