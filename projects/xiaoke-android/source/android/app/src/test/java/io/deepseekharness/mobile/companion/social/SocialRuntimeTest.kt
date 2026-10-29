package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class SocialRuntimeTest {
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    private class Host : HostPorts {
        val requests = mutableListOf<JSONObject>(); val callbacks = mutableListOf<(JSONObject) -> Unit>(); val cancelled = mutableListOf<String>()
        val emitted = mutableListOf<Pair<String, JSONObject>>()
        var allowed = true; var beforeGate: (() -> Unit)? = null; var failReminder = false
        override fun emit(topic: String, payload: JSONObject) { if (topic == "social.reminder" && failReminder) error("host unavailable"); synchronized(emitted) { emitted.add(topic to copy(payload)) } }
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal): JSONObject { beforeGate?.invoke(); return JSONObject().put("allowed", allowed) }
        override fun cancelModel(requestId: String) { cancelled.add(requestId) }
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String { requests.add(copy(request)); callbacks.add(onEvent); return "request-${requests.size}" }
    }
    private fun runtime(ledger: SocialLedger = SocialLedger(), host: Host = Host(), send: SocialTransport = SocialTransport { _, _, _ -> ack() }, now: () -> Long = { 1000L }) =
        SocialRuntime(ledger, host, send, now, { JSONObject().put("connected", true).put("account", "10001") })
    private fun draft(kind: String = "post", key: String = "one") = JSONObject().put("kind", kind).put("account", "10001").put("target", "10001")
        .put("content", "合成测试内容").put("idempotencyKey", key).put("payload", JSONObject())
    private fun approve(rt: SocialRuntime, a: JSONObject) = rt.execute("approvals.approveBatch", JSONObject().put("items", JSONArray().put(JSONObject().put("id", a.getString("id")).put("digest", a.getString("digest")))), owner)
    private fun job(key: String = "job") = JSONObject().put("kind", "summary").put("text", "合成资料").put("idempotencyKey", key)
    private fun event(source: String = "source") = ModuleEvent("conversation.message", "qq:group:synthetic", source, JSONObject().put("text", "synthetic-private-content"))
    companion object { fun ack() = JSONObject().put("status", "ok").put("retcode", 0).put("data", JSONObject().put("tid", "post-fixture").put("message_id", "message-fixture")) }

    @Test fun failedDurableWriteRollsBackMemory() {
        val ledger = SocialLedger(persist = { error("disk full") })
        assertThrows(IllegalStateException::class.java) { ledger.change { it.put("value", "must-not-commit") } }
        assertFalse(ledger.exportEncryptedInput().has("value"))
    }
    @Test fun callerCannotMutateCommittedSnapshot() {
        val ledger = SocialLedger(); val escaped = ledger.change { rows(it, "jobs").put("x", JSONObject().put("id", "x")) }
        escaped.remove("x"); assertTrue(ledger.read { rows(it, "jobs").has("x") })
    }
    @Test fun batchApprovalIsAllOrNothingAndGroupCannotForgeOwner() {
        val rt = runtime(); val a = rt.execute("social.plan", draft(key="a"), owner); val b = rt.execute("social.plan", draft(key="b"), owner)
        val items = JSONArray().put(JSONObject().put("id", a.getString("id")).put("digest", a.getString("digest")))
            .put(JSONObject().put("id", b.getString("id")).put("digest", "stale"))
        assertThrows(IllegalStateException::class.java) { rt.execute("approvals.approveBatch", JSONObject().put("items", items), owner) }
        assertEquals(setOf("pending"), objects(rt.snapshot(owner).getJSONArray("approvals")).map { it.getString("state") }.toSet())
        assertThrows(IllegalArgumentException::class.java) { rt.execute("approvals.approveBatch", JSONObject().put("items", JSONArray()), owner.copy(channel="group_qq", scope="qq:group:synthetic")) }
    }
    @Test fun duplicateClickReturnsSameDraftButChangedPayloadCannotReuseKey() {
        val rt = runtime(); val first = rt.execute("social.plan", draft(), owner)
        assertEquals(first.getString("id"), rt.execute("social.plan", draft(), owner).getString("id"))
        assertThrows(IllegalArgumentException::class.java) { rt.execute("social.plan", draft().put("content", "changed"), owner) }
    }
    @Test fun concurrentDispatchWritesExactlyOnceAndUnknownDoesNotRetry() {
        val entered = CountDownLatch(1); val finish = CountDownLatch(1); val calls = AtomicInteger()
        val rt = runtime(send=SocialTransport { _, _, _ -> calls.incrementAndGet(); entered.countDown(); finish.await(2, TimeUnit.SECONDS); throw IllegalStateException("connection lost after send") })
        val a = rt.execute("social.plan", draft(), owner); approve(rt, a)
        val t = thread { rt.dispatchAction(a.getString("id")) }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); rt.dispatchAction(a.getString("id")); finish.countDown(); t.join()
        assertEquals("outcome_unknown", rt.dispatchAction(a.getString("id")).getString("state")); assertEquals(1, calls.get())
    }
    @Test fun deniedHostGateDoesNotInvokeTransport() {
        val host=Host().apply { allowed=false }; val calls=AtomicInteger(); val rt=runtime(host=host,send=SocialTransport { _,_,_->calls.incrementAndGet(); ack() })
        val a=rt.execute("social.plan",draft(),owner); approve(rt,a); rt.dispatchAction(a.getString("id")); assertEquals(0,calls.get())
    }
    @Test fun deletionWhileHostChecksCannotStartExternalAction() {
        val ledger=SocialLedger(); val host=Host(); val calls=AtomicInteger(); val rt=runtime(ledger,host,SocialTransport { _,_,_->calls.incrementAndGet(); ack() })
        rt.onEvent(event()); val a=rt.execute("social.plan",draft().put("sourceIds",JSONArray().put("source")),owner); approve(rt,a)
        host.beforeGate={rt.deleteSources(listOf("source"))}
        assertThrows(Exception::class.java) { rt.dispatchAction(a.getString("id")) }; assertEquals(0,calls.get())
        assertFalse(ledger.exportEncryptedInput().toString().contains("synthetic-private-content"))
    }
    @Test fun diskRecoveryPreservesUnknownAndInvalidatesExecutableApproval() {
        val path=Files.createTempFile("social-state-test", ".json")
        try {
            val ledger=SocialLedger(persist={Files.writeString(path,it.toString())}); val rt=runtime(ledger)
            val a=rt.execute("social.plan",draft(key="a"),owner); approve(rt,a)
            val b=rt.execute("social.plan",draft(key="b"),owner)
            ledger.change { rows(it,"actions").getJSONObject(b.getString("id")).put("state","submitting") }
            val recovered=runtime(SocialLedger(JSONObject(Files.readString(path))))
            val states=objects(recovered.snapshot(owner).getJSONArray("activity")).associate { it.getString("id") to it.getString("state") }
            assertEquals("pending",states[a.getString("id")]); assertEquals("outcome_unknown",states[b.getString("id")])
        } finally { Files.deleteIfExists(path) }
    }
    @Test fun autonomousDailyPostAndCommentKeepDifferentApprovalRules() {
        val ledger=SocialLedger(); val rt=runtime(ledger)
        rt.execute("social.configure",JSONObject().put("autonomousDailyPost",true),owner)
        assertEquals("ready",rt.execute("social.plan",draft(),owner).getString("state"))
        ledger.change { rows(it,"feeds").put("feed",JSONObject().put("id","feed").put("account","10001").put("feed",JSONObject())) }
        assertEquals("pending",rt.execute("social.plan",draft("comment","comment").put("target","feed"),owner).getString("state"))
        assertThrows(IllegalStateException::class.java) { rt.execute("social.plan",draft(key="background"),CommandPrincipal("companion","background","owner:local",false)) }
    }
    @Test fun receivedRequestCreatesExactPendingApprovalWithoutTrustingClaimedOwner() {
        val rt=runtime()
        rt.onEvent(ModuleEvent("social.request","qq:private:fixture","req1",JSONObject().put("requestType","friend").put("account","10001").put("targetId","10002").put("flag","synthetic-flag").put("owner",true)))
        val pending=rt.snapshot(owner).getJSONArray("approvals").getJSONObject(0)
        assertEquals("pending",pending.getString("state")); assertEquals("10002",pending.getString("target")); assertFalse(pending.getBoolean("ownerAuthored"))
    }
    @Test fun friendTaskUsesActivityLaneAndCannotInspectAnotherSession() {
        val host=Host(); val rt=runtime(host=host); val friend=CommandPrincipal("qq:10002","friend_qq","qq:private:a",false)
        rt.execute("jobs.create",job(),friend); rt.tick()
        assertEquals("activity",host.requests.single().getString("lane")); assertEquals(friend.scope,host.requests.single().getString("scope"))
        assertEquals(0,rt.snapshot(friend.copy(scope="qq:private:b",actorId="qq:10003")).getJSONArray("scheduled").length())
        assertThrows(IllegalArgumentException::class.java) { rt.execute("jobs.create",job("other").put("scope","owner:local"),friend) }
    }
    @Test fun cancellationFencesLateCompletionAndExplicitResumeCreatesNewRequest() {
        val host=Host(); val rt=runtime(host=host); val j=rt.execute("jobs.create",job(),owner); rt.tick()
        rt.execute("jobs.cancel",JSONObject().put("id",j.getString("id")),owner)
        host.callbacks[0](JSONObject().put("type","completed").put("text","late text"))
        assertEquals("cancelled",rt.snapshot(owner).getJSONArray("scheduled").getJSONObject(0).getString("state"))
        assertFalse(rt.snapshot(owner).toString().contains("late text")); assertEquals(listOf("request-1"),host.cancelled)
        rt.execute("jobs.resume",JSONObject().put("id",j.getString("id")),owner); rt.tick()
        host.callbacks[0](JSONObject().put("type","completed").put("text","old generation"))
        host.callbacks[1](JSONObject().put("type","completed").put("text","new result"))
        assertEquals("new result",rt.snapshot(owner).getJSONArray("scheduled").getJSONObject(0).getString("result"))
    }
    @Test fun offlineAndManualConditionsDoNotConsumeActivityCalls() {
        val host=Host(); val rt=runtime(host=host)
        rt.onEvent(ModuleEvent("connectivity.changed","owner:local","net",JSONObject().put("online",false)))
        val j=rt.execute("jobs.create",job().put("condition","manual"),owner)
        rt.execute("jobs.create",job("remind").put("kind","reminder").put("condition","online"),owner); rt.tick()
        assertTrue(host.requests.isEmpty()); assertFalse(host.emitted.any { it.first=="social.reminder" })
        rt.execute("jobs.resume",JSONObject().put("id",j.getString("id")),owner); rt.tick(); assertTrue(host.requests.isEmpty())
        rt.onEvent(ModuleEvent("connectivity.changed","owner:local","net2",JSONObject().put("online",true)));rt.tick(); assertEquals(1,host.requests.size)
    }
    @Test fun deletionCancelsModelWipesDerivedContentAndRejectsReplayedEvent() {
        val ledger=SocialLedger(); val host=Host(); val rt=runtime(ledger,host); rt.onEvent(event())
        rt.execute("jobs.create",job().put("sourceIds",JSONArray().put("source")),owner);rt.tick();rt.deleteSources(listOf("source"))
        host.callbacks[0](JSONObject().put("type","completed").put("text","must not return"));rt.onEvent(event())
        assertEquals(0,rt.snapshot(owner).getJSONArray("scheduled").length());assertEquals(listOf("request-1"),host.cancelled)
        assertEquals(0,ledger.read { rows(it,"events").length() }); assertFalse(ledger.exportEncryptedInput().toString().contains("synthetic-private-content"))
    }
    @Test fun deletionDuringSubmittedEffectRetainsOnlyRedactedUnknownReceipt() {
        val entered=CountDownLatch(1);val done=CountDownLatch(1);val rt=runtime(send=SocialTransport { _,_,_->entered.countDown();done.await(2,TimeUnit.SECONDS);ack() })
        rt.onEvent(event());val a=rt.execute("social.plan",draft().put("sourceIds",JSONArray().put("source")),owner);approve(rt,a)
        val t=thread { rt.dispatchAction(a.getString("id")) };assertTrue(entered.await(2,TimeUnit.SECONDS));rt.deleteSources(listOf("source"));done.countDown();t.join()
        val receipt=rt.snapshot(owner).getJSONArray("activity").getJSONObject(0)
        assertTrue(receipt.getBoolean("redacted"));assertEquals("outcome_unknown",receipt.getString("state"));assertFalse(receipt.has("content"))
    }
    @Test fun transientMediaExpiryDoesNotDeleteCuratedAsset() {
        val deleted=mutableListOf<String>();var now=1000L;val ledger=SocialLedger();val rt=SocialRuntime(ledger,Host(),SocialTransport { _,_,_->ack() },{now},deleteMedia=deleted::add)
        rt.onEvent(ModuleEvent("conversation.message","owner:local","image-source",JSONObject().put("attachments",JSONArray().put(JSONObject().put("kind","image")))))
        val mediaId=rt.snapshot(owner).getJSONArray("media").getJSONObject(0).getString("id")
        rt.attachBlob(mediaId,"blob",42,"image/png");rt.execute("media.retain",JSONObject().put("id",mediaId).put("retention","curated"),owner)
        now+=2*24*60*60_000;rt.tick();assertTrue(deleted.isEmpty())
        rt.execute("media.delete",JSONObject().put("id",mediaId),owner);assertEquals(listOf("blob"),deleted)
    }
    @Test fun undeliveredReminderSurvivesRestartWithoutRepeatingItsTask() {
        val ledger=SocialLedger();val host=Host().apply { failReminder=true };val rt=runtime(ledger,host)
        rt.execute("jobs.create",job().put("kind","reminder"),owner);rt.tick()
        assertEquals(1,ledger.read { rows(it,"notifications").length() })
        val next=Host();val restarted=runtime(SocialLedger(ledger.exportEncryptedInput()),next);restarted.tick();restarted.tick()
        assertEquals(1,next.emitted.count { it.first=="social.reminder" });assertTrue(next.requests.isEmpty())
    }
    @Test fun expiredApprovalCannotSendAndIdempotentTickDoesNotRewriteDisk() {
        var now=1000L;var writes=0;val ledger=SocialLedger(persist={writes++});val calls=AtomicInteger()
        val rt=runtime(ledger,send=SocialTransport { _,_,_->calls.incrementAndGet();ack() },now={now})
        val a=rt.execute("social.plan",draft().put("expiresAt",2000),owner);approve(rt,a);now=3000
        assertEquals("expired",rt.dispatchAction(a.getString("id")).getString("state"));assertEquals(0,calls.get())
        rt.tick();val first=writes;rt.tick();assertEquals(first,writes)
    }
    @Test fun backupMergePreservesDeletionAndDoesNotRestoreExecutableGrants() {
        val sourceLedger=SocialLedger();val sourceRuntime=runtime(sourceLedger);sourceRuntime.onEvent(event())
        val action=sourceRuntime.execute("social.plan",draft(),owner);approve(sourceRuntime,action)
        val targetLedger=SocialLedger();val targetRuntime=runtime(targetLedger);targetRuntime.deleteSources(listOf("source"))
        targetRuntime.restoreBackup(sourceLedger.exportEncryptedInput(),owner)
        assertEquals(0,targetLedger.read { rows(it,"events").length() })
        val restored=targetRuntime.snapshot(owner).getJSONArray("activity").getJSONObject(0)
        assertEquals("expired",restored.getString("state"));assertFalse(restored.has("grant"));assertFalse(restored.has("grantPrincipal"))
    }
}
