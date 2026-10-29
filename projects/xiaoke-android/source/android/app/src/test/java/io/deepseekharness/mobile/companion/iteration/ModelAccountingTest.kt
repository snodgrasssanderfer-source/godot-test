package io.deepseekharness.mobile.companion.iteration

import io.deepseekharness.mobile.companion.GeminiClient
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelAccountingTest {
    @Test fun eachAttemptReservesBeforeSubmissionAndUnknownMoneyStaysHeld() {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val accounting = ModelAccounting({ action, data ->
            calls.add(action to JSONObject(data.toString()))
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "micros").put("unknownPriceCeiling", 2500)
            else JSONObject().put("duplicate", false)
        }, "owner:local", "activity", "test", {})
        accounting.beforeRequest()
        accounting.finished(GeminiClient.AttemptResult("unknown", JSONObject(), true, null))
        assertEquals(listOf("budget.status","budget.reserve","budget.reserve","budget.settle"), calls.map { it.first })
        assertFalse(calls[1].second.optBoolean("submitted"))
        assertTrue(calls[2].second.getBoolean("submitted"))
        assertFalse(calls.last().second.getBoolean("actualKnown"))
        val previous = calls[1].second.getString("requestId")
        accounting.beforeRequest()
        assertNotEquals(previous, calls[calls.size - 2].second.getString("requestId"))
    }
    @Test fun neverTransmittedCancellationSettlesZeroButSentCountIsOne() {
        val settled = mutableListOf<JSONObject>()
        val accounting = ModelAccounting({ action, data ->
            if (action == "budget.settle") settled.add(JSONObject(data.toString()))
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "calls") else JSONObject().put("duplicate", false)
        }, "owner:local", "owner", "test", {})
        accounting.beforeRequest(); accounting.finished(GeminiClient.AttemptResult("not_sent", JSONObject(), false, null))
        accounting.beforeRequest(); accounting.finished(GeminiClient.AttemptResult("unknown", JSONObject(), true, null))
        assertEquals(0, settled[0].getInt("actual")); assertEquals(1, settled[1].getInt("actual"))
    }
    @Test fun dailyGuardReleasesOnlyTheUnsubmittedReservation() {
        val calls = mutableListOf<String>()
        val accounting = ModelAccounting({ action, _ ->
            calls.add(action)
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "calls") else JSONObject().put("duplicate", false)
        }, "owner:local", "owner", "test", { error("daily limit") })
        assertThrows(IllegalStateException::class.java) { accounting.beforeRequest() }
        assertEquals(listOf("budget.status", "budget.reserve", "budget.release"), calls)
    }
}
