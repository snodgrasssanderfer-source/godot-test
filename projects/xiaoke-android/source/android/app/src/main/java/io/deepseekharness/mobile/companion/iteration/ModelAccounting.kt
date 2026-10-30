package io.deepseekharness.mobile.companion.iteration

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.GeminiClient
import org.json.JSONObject

/** One ledger reservation per actual HTTP attempt, including retry and continuation. */
class ModelAccounting(
    private val ledger: (String, JSONObject) -> JSONObject,
    private val scope: String,
    private val lane: String,
    private val purpose: String,
    private val legacyRequestGuard: () -> Unit,
) {
    private var current: String? = null
    private var unit = ""
    private var pricing: JSONObject? = null

    fun beforeRequest() {
        check(current == null) { "上一笔请求费用尚未处理" }
        val status = ledger("budget.status", JSONObject())
        // Talking with the owner never waits on budget setup; only her own activity needs a configured pool.
        if (!status.optBoolean("configured") && lane == "owner") { legacyRequestGuard(); return }
        check(status.optBoolean("configured")) { "活动额度还没设置，先不做自主活动" }
        unit = status.getString("unit")
        pricing = status.optJSONObject("pricing")?.takeIf { it.optLong("inputPrice") > 0 || it.optLong("outputPrice") > 0 }
        // This relay has no verified price/invoice contract. Never label a guessed token estimate known.
        val maximum = if (unit == "calls") 1L else {
            val config = status.optJSONObject("config") ?: status
            status.optJSONObject("unknownPrice")?.optLong("ceiling", 0L) ?: config.optLong("unknownPriceCeiling", 0L)
        }
        check(maximum > 0L) { "中转价格上界未知；请选择明确的次数额度，或配置并接受保守金额预留" }
        val id = CompanionPolicy.id()
        val data = JSONObject().put("requestId", id).put("scope", scope).put("lane", lane)
            .put("purpose", purpose).put("maximum", maximum).put("priceKnown", false)
        val result = ledger("budget.reserve", data)
        check(!result.optBoolean("duplicate")) { "预算请求已存在，未重复发起" }
        current = id
        try {
            legacyRequestGuard()
            val claim = ledger("budget.reserve", JSONObject(data.toString()).put("submitted", true))
            check(claim.optBoolean("sendAuthorized")) { "此请求未取得唯一发送权，未重复发出" }
        } catch (e: Exception) {
            ledger("budget.release", JSONObject().put("requestId", id).put("reason", "请求发送前被取消"))
            current = null
            throw e
        }
    }

    fun finished(result: GeminiClient.AttemptResult) {
        val id = current ?: return
        try {
            val tokens = io.deepseekharness.mobile.companion.life.BudgetLedger.tokens(result.usage)
            val settlement = JSONObject().put("requestId", id).put("tokens", tokens)
            val prices = pricing
            if (!result.transmitted) settlement.put("actualKnown", true).put("actual", 0)
            else if (unit == "calls") settlement.put("actualKnown", true).put("actual", 1)
            // With token prices configured and usage reported, the cost is known; cached input counts at the cached price.
            else if (prices != null && tokens.optLong("prompt") > 0) settlement.put("actualKnown", true).put("actual", io.deepseekharness.mobile.companion.life.BudgetLedger.cost(tokens, prices))
            else settlement.put("actualKnown", false)
            ledger("budget.settle", settlement)
        } finally { current = null }
    }
}
