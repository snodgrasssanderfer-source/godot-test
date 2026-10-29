package io.deepseekharness.mobile.companion.iteration

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ProviderHistoryPrivacyTest {
    @Test fun screenFramesStayEphemeralAcrossToolContinuation() {
        val turns = JSONArray("""[{"role":"model","parts":[{"functionCall":{"name":"device_action","args":{}},"thoughtSignature":"opaque"}]},{"role":"user","parts":[{"functionResponse":{"name":"device_action","response":{"frameId":"synthetic"}}},{"inlineData":{"mimeType":"image/jpeg","data":"synthetic"}}]}]""")
        assertNull(ProviderHistoryPrivacy.forStorage("profile", turns))
        assertTrue(turns.toString().contains("inlineData")) // The live request still has actual vision input.
    }
    @Test fun textOnlyToolPairsRemainIntactForGeminiReplay() {
        val turns = JSONArray("""[{"role":"model","parts":[{"functionCall":{"name":"recall","args":{}},"thoughtSignature":"opaque"}]},{"role":"user","parts":[{"functionResponse":{"name":"recall","response":{"items":[]}}}]}]""")
        val saved = ProviderHistoryPrivacy.forStorage("profile", turns)!!
        assertEquals(turns.toString(), saved.getJSONArray("turns").toString())
        assertEquals("profile", saved.getString("fingerprint"))
    }
    @Test fun mediaBudgetDoesNotTreatBase64LengthAsTextTokens() {
        val turns = JSONArray("""[{"role":"user","parts":[{"inlineData":{"mimeType":"image/jpeg","data":"synthetic"}},{"text":"hello"}]}]""")
        assertTrue(ProviderHistoryPrivacy.estimatedInput(turns) in 4097..4200)
    }
}
