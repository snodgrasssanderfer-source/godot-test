package io.deepseekharness.mobile.companion.life

import io.deepseekharness.mobile.companion.CompanionPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrivateDomainIsolationTest {
    private val group = "qq:group:g1"
    private val ownerQq = "qq:private:ownerchat"
    private val settings = JSONObject().put("ownerQqId", "10001")
    private val conversations = mapOf(ownerQq to JSONObject().put("targetType", "private").put("targetId", "10001"),
        "qq:private:friend" to JSONObject().put("targetType", "private").put("targetId", "20002"))
    private fun private(scope: String) = CompanionPolicy.ownerPrivate(scope, conversations[scope], settings)
    private fun shared(id: String, scope: String, audience: String = "*") = JSONObject().put("id", id).put("scope", scope).put("sourceScope", scope)
        .put("text", "t").put("shareSummary", "summary $id").put("shareWithScopes", JSONArray().put(audience))

    @Test fun ownerDomainCoversLocalOwnerAndOwnQqPrivateChatOnly() {
        assertTrue(private(CompanionPolicy.OWNER))
        assertTrue(private(ownerQq))
        assertFalse(private("qq:private:friend"))
        assertFalse(private(group))
        assertFalse(CompanionPolicy.ownerPrivate(ownerQq, conversations[ownerQq], JSONObject()))
    }

    @Test fun shareSummaryReturnsNothingFromThePrivateDomainWhateverItsShareFlags() {
        val records = listOf(shared("owner", CompanionPolicy.OWNER), shared("ownerQq", ownerQq, group))
        assertEquals(0, LifePolicy.shareSummaries(records, group, "", ::private).length())
        assertEquals(0, LifePolicy.shareSummaries(records, "qq:private:friend", "", ::private).length())
    }

    @Test fun derivedRecordsCarryingPrivateLineageAreAlsoWithheld() {
        val viaSourceScope = shared("a", group).put("sourceScope", CompanionPolicy.OWNER)
        val viaRef = shared("b", group).put("sourceRefs", JSONArray().put(RecordRef("message", "m1", CompanionPolicy.OWNER).json()))
        val malformed = shared("c", group).put("sourceRefs", JSONArray().put(JSONObject().put("kind", "message").put("id", "m2").put("scope", "bogus")))
        val result = LifePolicy.shareSummaries(listOf(viaSourceScope, viaRef, malformed), group, "", ::private)
        assertEquals(0, result.length())
    }

    @Test fun externalSharesStillWorkAndInboundDirectionIsUntouched() {
        val result = LifePolicy.shareSummaries(listOf(shared("g", group, "qq:group:g2")), "qq:group:g2", "summary", ::private)
        assertEquals("g", result.getJSONObject(0).getString("id"))
        // Something seen in a group may still be used in the owner's private chat.
        assertFalse(LifePolicy.touchesPrivate(shared("g", group), ::private))
    }
}
