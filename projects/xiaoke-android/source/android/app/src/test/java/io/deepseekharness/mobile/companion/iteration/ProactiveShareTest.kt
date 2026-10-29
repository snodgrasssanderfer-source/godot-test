package io.deepseekharness.mobile.companion.iteration

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProactiveShareTest {
    private val now = 10 * ProactiveShare.IDLE_MS
    private val idle = now - ProactiveShare.IDLE_MS

    @Test fun atMostTwoSharesPerDay() {
        var state = JSONObject()
        repeat(ProactiveShare.DAILY_LIMIT) {
            assertTrue(ProactiveShare.allowed(state, "2026-10-29", now, idle, false))
            state = ProactiveShare.recorded(state, "2026-10-29", now)
        }
        assertFalse(ProactiveShare.allowed(state, "2026-10-29", now, idle, false))
        assertTrue(ProactiveShare.allowed(state, "2026-10-30", now, idle, false))
        assertEquals(1, ProactiveShare.recorded(state, "2026-10-30", now).getInt("count"))
    }

    @Test fun nightQuietNeverShares() {
        assertFalse(ProactiveShare.allowed(JSONObject(), "2026-10-29", now, idle, true))
    }
}
