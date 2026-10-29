package io.deepseekharness.mobile.companion.life

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DailyBudgetTest {
    private val day = 86_400_000L
    private fun request(id: String) = JSONObject().put("requestId", id).put("lane", "activity").put("scope", "owner:local")
        .put("purpose", "test").put("maximum", 1).put("priceKnown", false)

    @Test fun activityPoolRefillsTheNextDay() {
        val noon = java.time.LocalDate.of(2026, 10, 29).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ledger = BudgetLedger().apply { configure(JSONObject().put("unit", "calls").put("ownerLimit", 50).put("activityLimit", 2)
            .put("warningRemaining", 0).put("allowUnknownPrice", true).put("unknownPriceCeiling", 1).put("daily", true)) }
        repeat(2) { i ->
            ledger.reserve(request("r$i"), noon); ledger.reserve(request("r$i").put("submitted", true), noon)
            ledger.settle(JSONObject().put("requestId", "r$i").put("actualKnown", true).put("actual", 1), noon)
        }
        assertTrue(ledger.status(noon).getBoolean("stopActivity"))
        assertFalse(ledger.status(noon + day).getBoolean("stopActivity"))
        assertEquals(2L, ledger.status(noon + day).getJSONObject("activity").getLong("remaining"))
    }
}
