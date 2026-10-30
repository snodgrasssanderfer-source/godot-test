package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.companion.life.BudgetLedger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiWebAndCacheTest {
    private val profile = JSONObject().put("maxOutputTokens", 512).put("thinking", "low")
    private val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "hi"))))
    private val functions = JSONArray().put(JSONObject().put("name", "recall"))

    @Test fun `内置搜索和函数一起时带上服务端工具回传`() {
        val combined = GeminiWire.request(profile, contents, "sys", functions, builtins = true)
        val tools = combined.getJSONArray("tools")
        assertEquals(3, tools.length())
        assertTrue(tools.getJSONObject(0).has("functionDeclarations"))
        assertTrue(tools.getJSONObject(1).has("googleSearch") && tools.getJSONObject(2).has("urlContext"))
        assertTrue(combined.getJSONObject("toolConfig").getBoolean("includeServerSideToolInvocations"))
        val split = GeminiWire.request(profile, contents, "sys", JSONArray(), builtins = true)
        assertEquals(2, split.getJSONArray("tools").length())
        assertFalse(split.has("toolConfig"))
        val plain = GeminiWire.request(profile, contents, "sys", functions)
        assertEquals(1, plain.getJSONArray("tools").length())
    }

    @Test fun `搜索来源和网页读取状态被收下`() {
        val acc = GeminiWire.Accumulator()
        acc.accept(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"答案"}]},"finishReason":"STOP",
            "groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://example.com/a","title":"A"}}]},
            "urlContextMetadata":{"urlMetadata":[{"retrievedUrl":"https://example.com/b","urlRetrievalStatus":"URL_RETRIEVAL_STATUS_SUCCESS"}]}}],
            "usageMetadata":{"promptTokenCount":1000,"cachedContentTokenCount":800,"candidatesTokenCount":50,"thoughtsTokenCount":10}}"""))
        val result = acc.result("m")
        assertEquals("https://example.com/a", result.getJSONArray("sources").getJSONObject(0).getString("uri"))
        assertEquals("URL_RETRIEVAL_STATUS_SUCCESS", result.getJSONArray("pages").getJSONObject(0).getString("status"))
        val tokens = BudgetLedger.tokens(result.getJSONObject("usageMetadata"))
        assertEquals(listOf(1000L, 800L, 60L), listOf(tokens.getLong("prompt"), tokens.getLong("cached"), tokens.getLong("output")))
    }

    @Test fun `缓存命中的输入按缓存价计费`() {
        val pricing = JSONObject().put("inputPrice", 300_000).put("cachedInputPrice", 75_000).put("outputPrice", 2_500_000)
        val tokens = JSONObject().put("prompt", 1_000_000).put("cached", 800_000).put("output", 100_000)
        // 200k fresh × 0.3 + 800k cached × 0.075 + 100k out × 2.5 (per token in micro-units: price / 1e6)
        assertEquals(60_000L + 60_000L + 250_000L, BudgetLedger.cost(tokens, pricing))
        assertEquals(1L, BudgetLedger.cost(JSONObject().put("prompt", 1).put("output", 0), pricing)) // rounds up, never free
    }

    @Test fun `结算时记下用量，状态里给出缓存命中率`() {
        val ledger = BudgetLedger()
        ledger.configure(JSONObject().put("unit", "micros").put("currency", "CNY").put("ownerLimit", 10_000_000L).put("activityLimit", 1_000_000L)
            .put("warningRemaining", 0L).put("unknownPriceCeiling", 100_000L).put("allowUnknownPrice", true)
            .put("inputPrice", 300_000L).put("cachedInputPrice", 75_000L).put("outputPrice", 2_500_000L))
        val now = System.currentTimeMillis()
        ledger.reserve(JSONObject().put("requestId", "r1").put("lane", "owner").put("scope", "owner:local").put("purpose", "chat").put("maximum", 100_000L).put("priceKnown", false), now)
        ledger.reserve(JSONObject().put("requestId", "r1").put("lane", "owner").put("scope", "owner:local").put("purpose", "chat").put("maximum", 100_000L).put("priceKnown", false).put("submitted", true), now)
        ledger.settle(JSONObject().put("requestId", "r1").put("actualKnown", true).put("actual", 120L)
            .put("tokens", JSONObject().put("prompt", 400).put("cached", 300).put("output", 20)), now)
        val owner = ledger.status(now).getJSONObject("owner")
        assertEquals(120L, owner.getLong("spent"))
        assertEquals(75L, owner.getJSONObject("tokens").getLong("cacheRate"))
        assertEquals(75_000L, ledger.status(now).getJSONObject("pricing").getLong("cachedInputPrice"))
    }
}
