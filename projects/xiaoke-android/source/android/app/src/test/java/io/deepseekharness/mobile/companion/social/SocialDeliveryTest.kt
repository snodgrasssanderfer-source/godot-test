package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Recovery boundaries between a durable module and the host, without real accounts or models. */
class SocialDeliveryTest {
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    private class Host : HostPorts {
        val delivered = mutableListOf<JSONObject>()
        val cancelled = mutableListOf<String>()
        var unavailable = false
        var requests = 0
        var beforeRequestReturns: (() -> Unit)? = null
        override fun emit(topic: String, payload: JSONObject) {
            if (topic == "social.event") {
                if (unavailable) error("host recovering")
                delivered.add(copy(payload))
            }
        }
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String {
            requests++; beforeRequestReturns?.invoke(); return "request-$requests"
        }
        override fun cancelModel(requestId: String) { cancelled.add(requestId) }
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal) = JSONObject().put("allowed", true)
    }
    private fun runtime(ledger: SocialLedger, host: Host, network: () -> Boolean = { true }) = SocialRuntime(
        ledger, host, SocialTransport { _, _, _ -> error("recovery must not send") }, networkAllowed = network)
    private fun job() = JSONObject().put("kind", "summary").put("text", "synthetic source").put("idempotencyKey", "summary")

    @Test fun downloadedExperienceHasDurableHostDeliveryAndCannotExecuteOnReplay() {
        val sourceLedger = SocialLedger(); val source = runtime(sourceLedger, Host())
        source.onEvent(ModuleEvent("conversation.message", "qq:private:synthetic", "remote-source", JSONObject().put("text", "synthetic experience")
            .put("attachments", JSONArray().put(JSONObject().put("kind", "image").put("url", "https://example.test/temporary").put("platformId", "temporary-reference")))))
        val cipher = SyncCipher(ByteArray(32) { 4 })
        val sealed = cipher.seal(sourceLedger.read { values(rows(it, "events")).single() })
        val ledger = SocialLedger(); ledger.change { rows(it, "sync").put("vault", "0".repeat(32)) }
        val firstHost = Host().apply { unavailable = true }; val first = runtime(ledger, firstHost)
        SocialSync(first, cipher, SyncTransport { request -> JSONObject().put("version", 1).put("generation", 0).put("cursor", 1)
            .put("events", JSONArray().put(sealed)).put("deletions", JSONArray()).put("ackDeletions", request.getJSONArray("deletions")).put("ackIds", JSONArray()) }).run()
        assertEquals(1, ledger.read { rows(it, "notifications").length() })
        assertEquals(0, firstHost.requests)
        val secondHost = Host(); val restored = runtime(SocialLedger(ledger.exportEncryptedInput()), secondHost)
        restored.tick(); restored.tick()
        assertEquals(1, secondHost.delivered.size)
        val delivered = secondHost.delivered.single()
        assertEquals("remote-source", delivered.getString("sourceId")); assertTrue(delivered.getBoolean("replayed"))
        assertFalse(delivered.toString().contains("https://example.test")); assertEquals(0, secondHost.requests)
    }
    @Test fun deletionNotificationSurvivesUnavailableHostAndSuppressesStaleReceive() {
        val ledger = SocialLedger(); val host = Host(); val runtime = runtime(ledger, host)
        val event = ModuleEvent("conversation.message", "owner:local", "deleted-source", JSONObject().put("text", "synthetic"))
        runtime.onEvent(event, notifyHost = true); host.delivered.clear(); host.unavailable = true
        runtime.deleteSources(listOf("deleted-source")); runtime.onEvent(event, notifyHost = true)
        assertEquals(0, ledger.read { rows(it, "events").length() })
        val recovery = Host(); runtime(SocialLedger(ledger.exportEncryptedInput()), recovery).tick()
        assertEquals(1, recovery.delivered.size)
        assertEquals("data.deleted", recovery.delivered.single().getString("type"))
        assertTrue(strings(recovery.delivered.single().getJSONObject("data").getJSONArray("sourceIds")).contains("deleted-source"))
    }
    @Test fun networkSettingWaitsWithoutFailingOrRequestingUntilEnabled() {
        var enabled = false; val host = Host(); val runtime = runtime(SocialLedger(), host) { enabled }
        runtime.execute("jobs.create", job(), owner); repeat(4) { runtime.tick() }
        assertEquals(0, host.requests); assertEquals("queued", runtime.snapshot(owner).getJSONArray("scheduled").getJSONObject(0).getString("state"))
        enabled = true; runtime.tick(); runtime.tick(); assertEquals(1, host.requests)
    }
    @Test fun restoreRevokesCurrentAndImportedGrantsAndPausesQueuedWork() {
        val ledger = SocialLedger(); val runtime = runtime(ledger, Host())
        val action = runtime.execute("social.plan", JSONObject().put("kind", "post").put("account", "10001").put("target", "10001")
            .put("content", "synthetic draft").put("idempotencyKey", "post"), owner)
        runtime.execute("approvals.approveBatch", JSONObject().put("items", JSONArray().put(JSONObject().put("id", action.getString("id")).put("digest", action.getString("digest")))), owner)
        runtime.execute("jobs.create", job(), owner)
        runtime.restoreBackup(ledger.exportEncryptedInput(), owner)
        val state = runtime.snapshot(owner)
        assertEquals("expired", state.getJSONArray("activity").getJSONObject(0).getString("state"))
        assertFalse(state.getJSONArray("activity").getJSONObject(0).has("grantPrincipal"))
        assertEquals("interrupted", state.getJSONArray("scheduled").getJSONObject(0).getString("state"))
    }
    @Test fun deleteWhileHostRegistersModelStillCancelsReturnedRequest() {
        val host = Host(); val runtime = runtime(SocialLedger(), host)
        val item = runtime.execute("jobs.create", job().put("sourceIds", JSONArray().put("deleted-during-register")), owner)
        host.beforeRequestReturns = { runtime.deleteSources(listOf("deleted-during-register")) }
        runtime.tick()
        assertEquals(listOf("request-1"), host.cancelled)
        assertFalse(runtime.snapshot(owner).getJSONArray("scheduled").toString().contains(item.getString("id")))
    }
}
