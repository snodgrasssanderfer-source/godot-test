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
            else JSONObject().put("duplicate", false).put("sendAuthorized", true)
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
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "calls") else JSONObject().put("duplicate", false).put("sendAuthorized", true)
        }, "owner:local", "owner", "test", {})
        accounting.beforeRequest(); accounting.finished(GeminiClient.AttemptResult("not_sent", JSONObject(), false, null))
        accounting.beforeRequest(); accounting.finished(GeminiClient.AttemptResult("unknown", JSONObject(), true, null))
        assertEquals(0, settled[0].getInt("actual")); assertEquals(1, settled[1].getInt("actual"))
    }
    @Test fun unconfiguredBudgetNeverBlocksOwnerChatButStopsActivity() {
        val calls = mutableListOf<String>()
        val ledger: (String, JSONObject) -> JSONObject = { action, _ -> calls.add(action); JSONObject().put("configured", false) }
        ModelAccounting(ledger, "owner:local", "owner", "chat", {}).apply { beforeRequest(); finished(GeminiClient.AttemptResult("ok", JSONObject(), true, null)) }
        assertEquals(listOf("budget.status"), calls)
        assertThrows(IllegalStateException::class.java) { ModelAccounting(ledger, "qq:group:g", "activity", "chat", {}).beforeRequest() }
    }
    @Test fun dailyGuardReleasesOnlyTheUnsubmittedReservation() {
        val calls = mutableListOf<String>()
        val accounting = ModelAccounting({ action, _ ->
            calls.add(action)
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "calls") else JSONObject().put("duplicate", false).put("sendAuthorized", true)
        }, "owner:local", "owner", "test", { error("daily limit") })
        assertThrows(IllegalStateException::class.java) { accounting.beforeRequest() }
        assertEquals(listOf("budget.status", "budget.reserve", "budget.release"), calls)
    }
    @Test fun realLedgerKeepsSentUnknownCostAndProtectsOwnerReserve() {
        val ledger = io.deepseekharness.mobile.companion.life.BudgetLedger()
        ledger.configure(JSONObject().put("unit", "micros").put("currency", "CNY").put("ownerLimit", 6000)
            .put("activityLimit", 3000).put("warningRemaining", 500).put("allowUnknownPrice", true).put("unknownPriceCeiling", 2500))
        val route: (String, JSONObject) -> JSONObject = { action, data -> when(action) {
            "budget.status" -> ledger.status()
            "budget.reserve" -> ledger.reserve(data, 100)
            "budget.release" -> ledger.release(data.getString("requestId"), 200)
            "budget.settle" -> ledger.settle(data, 200)
            else -> error(action)
        } }
        val activity = ModelAccounting(route, "owner:local", "activity", "summary", {})
        activity.beforeRequest(); activity.finished(GeminiClient.AttemptResult("unknown", JSONObject(), true, null))
        assertEquals(2500, ledger.status().getJSONObject("activity").getInt("held"))
        assertThrows(IllegalStateException::class.java) { activity.beforeRequest() }
        val owner = ModelAccounting(route, "owner:local", "owner", "chat", {})
        owner.beforeRequest(); owner.finished(GeminiClient.AttemptResult("not_sent", JSONObject(), false, null))
        assertEquals(6000, ledger.status().getJSONObject("owner").getInt("remaining"))
    }
    @Test fun duplicateSendClaimNeverAuthorizesAnotherHttpAttempt() {
        val adapter = ModelAccounting({ action, _ ->
            if (action == "budget.status") JSONObject().put("configured", true).put("unit", "calls")
            else JSONObject().put("duplicate", false).put("sendAuthorized", false)
        }, "owner:local", "owner", "chat", {})
        assertThrows(IllegalStateException::class.java) { adapter.beforeRequest() }
    }
}
