package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Scenario two: what she saves up while the owner is busy waits for them, and one tap keeps or discards it. */
class ApprovalListTest {
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    private val host = object : HostPorts {
        override fun emit(topic: String, payload: JSONObject) {}
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal) = JSONObject().put("allowed", true)
        override fun cancelModel(requestId: String) {}
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit) = "r"
    }
    private var now = 1_000L
    private val deleted = mutableListOf<String>()
    private val runtime = SocialRuntime(SocialLedger(), host, { _, _, _ -> JSONObject() }, { now }, deleteMedia = { deleted.add(it) })
    private fun picture(id: String) = JSONObject().put("id", id).put("scope", "owner:local").put("sourceId", "msg-1").put("sourceIds", JSONArray().put("msg-1"))
        .put("mime", "image/png").put("blobId", "blob-$id").put("status", "proposed").put("retention", "proposed")
    private fun media(id: String) = runtime.ledger.read { rows(it, "media").optJSONObject(id) }
    private fun approve(item: JSONObject) = runtime.execute("approvals.approveBatch",
        JSONObject().put("items", JSONArray().put(JSONObject().put("id", item.getString("id")).put("digest", item.getString("digest")))), owner)

    @Test fun approvedPictureJoinsTheLibraryAndLeavesTheList() {
        val item = runtime.proposeSave("qq:group:100", "msg-1", listOf(picture("m1")), "示意图", "值得留着")
        assertEquals(listOf(item.getString("id")), runtime.pendingApprovalIds())
        now += 6 * 60 * 60_000L // Hours later the owner is free; the item is still there.
        approve(item)
        assertEquals("explicit", media("m1")!!.getString("retention"))
        assertTrue(runtime.pendingApprovalIds().isEmpty())
        assertTrue(deleted.isEmpty())
    }

    @Test fun deletedPictureLeavesNothingBehind() {
        val item = runtime.proposeSave("qq:group:100", "msg-1", listOf(picture("m1")), "模糊的截图", "可能有用")
        runtime.execute("approvals.reject", JSONObject().put("id", item.getString("id")), owner)
        assertNull(media("m1"))
        assertEquals(listOf("blob-m1"), deleted)
    }

    @Test fun herCommentDraftsWaitDaysNotMinutes() {
        val draft = runtime.execute("social.plan", JSONObject().put("kind", "post").put("account", "10001").put("target", "10001")
            .put("content", "合成测试内容").put("idempotencyKey", "k").put("payload", JSONObject()), owner)
        assertTrue(draft.getLong("expiresAt") - now <= 15 * 60_000L) // Owner-authored: acted on right away.
        val own = runtime.proposeSave("qq:group:100", "msg-2", listOf(picture("m2")), "图", "理由")
        assertTrue(own.getLong("expiresAt") - now >= 2 * 24 * 60 * 60_000L)
    }
}
