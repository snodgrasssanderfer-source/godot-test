package io.deepseekharness.mobile.companion.life

import io.deepseekharness.mobile.companion.CompanionBackupCrypto
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.MemoryRanking
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LifeCoreTest {
    private val owner = CompanionPolicy.OWNER
    private fun config(unit: String = "calls", activity: Long = 20, warning: Long = 2, unknown: Boolean = true) = JSONObject()
        .put("unit", unit).put("currency", if (unit == "micros") "CNY" else "").put("ownerLimit", 80)
        .put("activityLimit", activity).put("warningRemaining", warning).put("allowUnknownPrice", unknown)
        .put("unknownPriceCeiling", if (unknown) 1 else 0)
    private fun ledger() = BudgetLedger().apply { configure(config()) }
    private fun request(id: String, lane: String = "activity", known: Boolean = false, maximum: Long = 1) = JSONObject()
        .put("requestId", id).put("lane", lane).put("scope", owner).put("purpose", "test").put("maximum", maximum).put("priceKnown", known)
    private fun sent(b: BudgetLedger, id: String, lane: String = "activity") {
        b.reserve(request(id, lane), 1); assertTrue(b.reserve(request(id, lane).put("submitted", true), 2).getBoolean("sendAuthorized"))
    }
    private fun settle(id: String, actual: Long) = JSONObject().put("requestId", id).put("actualKnown", true).put("actual", actual)
    private fun row(id: String, text: String = id, refs: List<RecordRef> = emptyList(), scope: String = owner) = JSONObject()
        .put("id", id).put("scope", scope).put("text", text).put("revision", 1).put("updatedAt", 10)
        .put("sourceRefs", JSONArray().also { a -> refs.forEach { a.put(it.json()) } })
    private fun backup() = JSONObject().put("format", "dsh-companion-backup").put("version", 2)

    @Test fun unknownPricesAndUnconfiguredBudgetFailClosed() {
        assertThrows(IllegalStateException::class.java) { BudgetLedger().reserve(request("a"), 1) }
        val b = BudgetLedger().apply { configure(config(unknown = false)) }
        assertThrows(IllegalStateException::class.java) { b.reserve(request("a"), 1) }
        assertEquals(1L, b.reserve(request("known", known = true), 1).getJSONObject("reservation").getLong("maximum"))
        assertFalse(b.status().getBoolean("monetaryGuarantee"))
    }

    @Test fun duplicatedRequestCannotChangeScopePurposeOrMaximum() {
        val b = ledger()
        assertFalse(b.reserve(request("same"), 1).getBoolean("duplicate"))
        assertTrue(b.reserve(request("same"), 2).getBoolean("duplicate"))
        assertEquals(1L, b.status().getJSONObject("activity").getLong("held"))
        for (changed in listOf(request("same").put("scope", "qq:group:other"), request("same").put("purpose", "changed"), request("same").put("lane", "owner"))) {
            assertThrows(IllegalStateException::class.java) { b.reserve(changed, 3) }
        }
    }

    @Test fun concurrentSendClaimHasOneWinner() {
        val b = ledger(); b.reserve(request("one"), 1)
        val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1); val wins = AtomicInteger()
        val futures = (0 until 32).map { pool.submit { start.await(); if (b.reserve(request("one").put("submitted", true), 2).getBoolean("sendAuthorized")) wins.incrementAndGet() } }
        start.countDown(); futures.forEach { it.get(5, TimeUnit.SECONDS) }; pool.shutdownNow()
        assertEquals(1, wins.get()); assertEquals(1L, b.status().getJSONObject("activity").getLong("held"))
    }

    @Test fun concurrentReservationsCountAllHoldsAndPreserveOwnerFunds() {
        val b = ledger(); val pool = Executors.newFixedThreadPool(8); val start = CountDownLatch(1); val accepted = AtomicInteger()
        val futures = (0 until 60).map { i -> pool.submit { start.await(); try { b.reserve(request("r-$i"), 1); accepted.incrementAndGet() } catch (_: IllegalStateException) { } } }
        start.countDown(); futures.forEach { it.get(5, TimeUnit.SECONDS) }; pool.shutdownNow()
        assertEquals(18, accepted.get()); assertEquals(18L, b.status().getJSONObject("activity").getLong("held"))
        assertTrue(b.status().getBoolean("stopActivity")); assertEquals(80L, b.status().getJSONObject("owner").getLong("remaining"))
        b.reserve(request("owner-turn", "owner"), 3)
        assertEquals(79L, b.status().getJSONObject("owner").getLong("remaining"))
    }

    @Test fun cancelledOrDisconnectedSentCallsRetainUnknownCharge() {
        val b = ledger(); sent(b, "stream")
        assertEquals("outcome_unknown", b.release("stream", 3).getString("state"))
        assertEquals("outcome_unknown", b.release("stream", 4).getString("state"))
        b.settle(JSONObject().put("requestId", "stream").put("actualKnown", false), 5)
        assertEquals(1L, b.status().getJSONObject("activity").getLong("held"))
        assertThrows(IllegalStateException::class.java) { b.reserve(request("stream").put("submitted", true), 6) }
        b.settle(settle("stream", 1), 7)
        assertEquals(0L, b.status().getJSONObject("activity").getLong("held"))
        assertEquals(1L, b.status().getJSONObject("activity").getLong("spent"))
    }

    @Test fun unsentReleaseIsIdempotentAndNeverBecomesAnotherPaidCall() {
        val b = ledger(); b.reserve(request("prepared"), 1); b.release("prepared", 2); b.release("prepared", 3)
        assertEquals(20L, b.status().getJSONObject("activity").getLong("remaining"))
        assertEquals("released", b.reserve(request("prepared"), 4).getString("state"))
        assertThrows(IllegalStateException::class.java) { b.reserve(request("prepared").put("submitted", true), 4) }
    }

    @Test fun concurrentReleaseAndSettlementConvergeWithoutRefundingSentCost() {
        val b = ledger(); sent(b, "race")
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val cancel = pool.submit { start.await(); b.release("race", 4) }
        val complete = pool.submit { start.await(); b.settle(settle("race", 1), 4) }
        start.countDown(); cancel.get(5, TimeUnit.SECONDS); complete.get(5, TimeUnit.SECONDS); pool.shutdownNow()
        assertEquals("settled", b.reservation("race")!!.getString("state"))
        assertEquals(1L, b.status().getJSONObject("activity").getLong("spent"))
        assertThrows(IllegalStateException::class.java) { b.settle(settle("race", 0), 5) }
    }

    @Test fun processRecoveryKeepsSentHoldButReleasesUnsent() {
        val b = ledger(); b.reserve(request("unsent"), 1); sent(b, "sent")
        val restored = BudgetLedger(JSONObject(b.encode().toString())); restored.recover(9)
        assertEquals("released", restored.reservation("unsent")!!.getString("state"))
        assertEquals("outcome_unknown", restored.reservation("sent")!!.getString("state"))
        assertEquals(1L, restored.status().getJSONObject("activity").getLong("held"))
    }

    @Test fun realBillOverMaximumIsNotHiddenAndStopsThatLane() {
        val b = BudgetLedger().apply { configure(config(unit = "micros", unknown = false)) }
        val r = request("money", "owner", known = true, maximum = 10)
        b.reserve(r, 1); b.reserve(JSONObject(r.toString()).put("submitted", true), 2)
        b.settle(settle("money", 15), 3)
        assertEquals(15L, b.status().getJSONObject("owner").getLong("spent")); assertTrue(b.status().getBoolean("stopOwner"))
        assertThrows(IllegalStateException::class.java) { b.reserve(request("more", "owner", known = true), 4) }
    }

    @Test fun configureAndOldBackupCannotResetConsumedOrHeldBudget() {
        val b = ledger(); sent(b, "paid"); val older = b.encode(); b.settle(settle("paid", 1), 3)
        sent(b, "unknown"); b.release("unknown", 4)
        b.configure(config(activity = 50))
        val merged = BudgetLedger(BudgetLedger.merge(b.encode(), older))
        assertEquals(1L, merged.status().getJSONObject("activity").getLong("spent"))
        assertEquals(1L, merged.status().getJSONObject("activity").getLong("held"))
        assertEquals(50L, merged.status().getJSONObject("activity").getLong("limit"))
        assertThrows(IllegalStateException::class.java) { merged.configure(config(unit = "micros")) }
    }

    @Test fun unknownMoneyRequiresExplicitPerRequestCeiling() {
        val b = BudgetLedger().apply { configure(config(unit = "micros").put("unknownPriceCeiling", 7)) }
        assertThrows(IllegalStateException::class.java) { b.reserve(request("expensive", maximum = 8), 1) }
        b.reserve(request("capped", maximum = 7), 1)
        assertEquals(7L, b.status().getJSONObject("activity").getLong("held"))
        assertFalse(b.status().getBoolean("monetaryGuarantee"))
    }

    @Test fun integerValidationRejectsFractionsNegativeAndOverflow() {
        for (bad in listOf<Any>(-1L, 1.2, "10", java.math.BigInteger("18446744073709551617"))) {
            assertThrows(RuntimeException::class.java) { BudgetLedger().configure(config().put("ownerLimit", bad)) }
        }
        val b = ledger(); val copy = b.encode(); copy.put("activityLimit", 999)
        assertEquals(20L, b.status().getJSONObject("activity").getLong("limit"))
    }

    @Test fun groupOrJsonClaimCannotBecomeOwner() {
        assertFalse(LifePolicy.owner(CommandPrincipal("same-display-name", "group_qq", "qq:group:123", true)))
        assertFalse(LifePolicy.owner(CommandPrincipal("friend", "friend_qq", "qq:private:123", false)))
        assertTrue(LifePolicy.owner(CommandPrincipal("verified-account", "owner_qq", "qq:private:verified", true)))
        val friend = CommandPrincipal("friend", "friend_qq", "qq:private:123", false)
        assertThrows(IllegalStateException::class.java) { LifePolicy.scope(JSONObject().put("scope", owner).put("owner", true), friend) }
    }

    @Test fun dependencyDeletionIncludesMultiHopAndCrossScopeSummaryWithoutDeletingUnrelatedData() {
        val root = RecordRef("message", "source", owner)
        val memory = row("memory", "fixture", listOf(root))
        val shared = row("shared", "sanitized fixture", listOf(RecordRef("memory", "memory", owner)), "qq:group:abc")
        val diary = row("diary", "我记录了经历", listOf(RecordRef("memory", "shared", "qq:group:abc")))
        val result = LifePolicy.descendants(setOf(root), listOf("memory" to memory, "memory" to shared, "diary" to diary, "message" to row("other")))
        assertEquals(setOf("source", "memory", "shared", "diary"), result.map { it.id }.toSet())
    }

    @Test fun typedReferencesDoNotConfuseIdenticalMessageAndMemoryIds() {
        val result = LifePolicy.descendants(setOf(RecordRef("message", "same", owner)), listOf("memory" to row("other", refs = listOf(RecordRef("memory", "same", owner)))))
        assertEquals(1, result.size)
    }

    @Test fun forgottenPinnedAndSupersededMemoryNeverEntersAutomaticRanking() {
        val records = JSONArray().put(row("forgotten").put("forgotten", true).put("pinned", true))
            .put(row("suppressed").put("suppressed", true)).put(row("old").put("superseded", true)).put(row("current"))
        val result = MemoryRanking.rank(records, "current")
        assertEquals(1, result.length()); assertEquals("current", result.getJSONObject(0).getString("id"))
    }

    @Test fun staleRevisionDoesNotOverwriteUserCorrection() {
        assertThrows(IllegalStateException::class.java) { LifePolicy.checkRevision(row("m").put("revision", 3), JSONObject().put("expectedRevision", 2)) }
        LifePolicy.checkRevision(row("m").put("revision", 3), JSONObject().put("expectedRevision", 3))
    }

    @Test fun attentionExpiresAndNeverParsesCasualSpeechAsCommands() {
        val saved = LifePolicy.attention(JSONObject().put("mode", "busy").put("until", 2000), 1000)
        assertFalse(LifePolicy.attentionStatus(saved, 1500).getBoolean("mayInterruptOrdinary"))
        assertTrue(LifePolicy.attentionStatus(saved, 2000).getBoolean("mayInterruptOrdinary"))
        assertEquals("busy", saved.getString("mode"))
        assertThrows(IllegalArgumentException::class.java) { LifePolicy.attention(JSONObject().put("mode", "别说话"), 1000) }
        assertThrows(IllegalArgumentException::class.java) { LifePolicy.attention(JSONObject().put("mode", "busy").put("until", 999), 1000) }
    }

    @Test fun oldBackupCannotRestoreDeletedContentRevisionsOrDerivedDiary() {
        val ref = RecordRef("message", "source", owner)
        val current = backup().put("tombstones", JSONArray().put(ref.json().put("epoch", 3)))
        val old = backup().put("message", JSONArray().put(row("source", "deleted fixture")))
            .put("memory", JSONArray().put(row("m", "deleted fixture", listOf(ref))))
            .put("diary", JSONArray().put(row("d", "我记下了 deleted fixture", listOf(RecordRef("memory", "m", owner)))))
            .put("revisions", JSONArray().put(row("m", "deleted fixture", listOf(ref)).put("recordKind", "memory")))
        val plan = LifeBackup.plan(current, old)
        assertTrue(plan.rows.isEmpty()); assertTrue(plan.revisions.isEmpty())
        assertEquals(setOf("source", "m", "d"), plan.tombstones.map { it.getString("id") }.toSet())
    }

    @Test fun restorationPreservesNewerLocalCorrectionAndLocalForget() {
        val local = backup().put("memory", JSONArray().put(row("m", "new").put("updatedAt", 20)).put(row("f").put("forgotten", true).put("suppressed", true)))
        val old = backup().put("memory", JSONArray().put(row("m", "old")).put(row("f").put("updatedAt", 30)))
        val plan = LifeBackup.plan(local, old)
        assertEquals("new", plan.rows.first { it.second.getString("id") == "m" }.second.getString("text"))
        assertTrue(plan.rows.first { it.second.getString("id") == "f" }.second.getBoolean("forgotten"))
    }

    @Test fun executableGrantsAndProviderWireNeverReturnFromBackup() {
        val task = LifeBackup.sanitize("task", row("t").put("state", "sending").put("grantDigest", "fixture-grant").put("grantExpiresAt", 1000).put("uiBinding", JSONObject()))
        assertEquals("unknown", task.getString("state")); assertFalse(task.has("grantDigest")); assertFalse(task.has("uiBinding"))
        assertFalse(LifeBackup.sanitize("message", row("m").put("wire", "opaque")).has("wire"))
        assertFalse(LifeBackup.sanitize("observation", row("o").put("parts", JSONArray())).has("parts"))
    }

    @Test fun actualEncryptedRoundTripIncludesLedgerAndTombstones() {
        val b = ledger(); sent(b, "unknown"); b.release("unknown", 4)
        val data = backup().put("lifeState", JSONArray().put(b.encode().put("id", "budget").put("scope", owner)))
            .put("tombstones", JSONArray().put(RecordRef("memory", "removed", owner).json().put("epoch", 2)))
        val encrypted = CompanionBackupCrypto.encrypt(data, "test-only-password")
        assertFalse(encrypted.contains("removed"))
        val decoded = CompanionBackupCrypto.decrypt(encrypted, "test-only-password")
        val plan = LifeBackup.plan(backup(), decoded)
        val restored = BudgetLedger(plan.rows.first().second)
        assertEquals(1L, restored.status().getJSONObject("activity").getLong("held"))
        assertEquals("removed", plan.tombstones.single().getString("id"))
        assertThrows(IllegalStateException::class.java) { CompanionBackupCrypto.decrypt(encrypted, "wrong-test-password") }
    }
}
