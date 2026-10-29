package io.deepseekharness.mobile.companion.presence

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class PresenceContextGateTest {
    private class Store : PresenceContextAccess {
        var epoch = 1L
        val sources = linkedSetOf("first", "second")
        @Synchronized override fun snapshot(query: String) = JSONObject().put("epoch", epoch)
            .put("memories", JSONArray().put(JSONObject().put("text", "synthetic memory")))
            .put("sourceRefs", JSONArray().put(JSONObject().put("kind", "memory").put("scope", "owner:local").put("id", query.ifEmpty { "first" })))
        @Synchronized override fun current(epoch: Long) = epoch == this.epoch
        @Synchronized override fun commit(ticket: JSONObject, mutation: () -> Unit) {
            check(current(ticket.getLong("epoch")))
            val refs = ticket.getJSONArray("sourceRefs")
            for (i in 0 until refs.length()) check(refs.getJSONObject(i).getString("id") in sources)
            mutation()
        }
        @Synchronized fun delete(id: String) { sources.remove(id); epoch++ }
    }
    @Test fun deletionBeforeEventDeliveryRejectsOldAnswerAndPersistence() {
        val store = Store(); val gate = PresenceContextGate { store }
        val old = gate.snapshot("first"); var writes = 0
        store.delete("first") // Native Handler has not received life.contentChanged yet.
        assertFalse(gate.current(old))
        assertTrue(runCatching { gate.commit(old) { writes++ } }.isFailure)
        assertEquals(0, writes)
        assertFalse(gate.sameWindow(old, gate.snapshot("second")))
    }
    @Test fun localResetFencesLateCallbacksEvenWhenStoreEpochIsUnchanged() {
        val store = Store(); val gate = PresenceContextGate { store }
        val old = gate.snapshot("first"); gate.invalidate(); val next = gate.snapshot("second")
        assertFalse(gate.current(old)); assertTrue(gate.current(next))
        assertTrue(runCatching { gate.inherit(next, old) }.isFailure)
    }
    @Test fun subsequentTurnRetainsEarlierPersistedAncestors() {
        val store = Store(); val gate = PresenceContextGate { store }
        val next = gate.inherit(gate.snapshot("second"), gate.snapshot("first"))
        assertEquals(2, next.value.getJSONArray("sourceRefs").length())
        var saved = false
        gate.commit(next) { saved = true }; assertTrue(saved)
        store.delete("first")
        assertTrue(runCatching { gate.commit(next) { fail("deleted ancestor restored") } }.isFailure)
    }
    @Test fun deletionWinningConcurrentSavePreventsMutation() {
        val store = Store(); val gate = PresenceContextGate { store }; val ticket = gate.snapshot("first")
        val ready = CountDownLatch(1); val proceed = CountDownLatch(1); var writes = 0
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val saver = thread {
            try {
                ready.countDown(); check(proceed.await(2, TimeUnit.SECONDS))
                assertTrue(runCatching { gate.commit(ticket) { writes++ } }.isFailure)
            } catch (error: Throwable) { failure.set(error) }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS)); store.delete("first"); proceed.countDown(); saver.join(2000)
        assertFalse(saver.isAlive); assertNull(failure.get()); assertEquals(0, writes)
    }
    @Test fun missingBindingDoesNotReadMemoryOrPretendToSave() {
        val gate = PresenceContextGate { null }; val ticket = gate.snapshot("")
        assertEquals(0, ticket.memories.length()); assertTrue(gate.current(ticket))
        assertTrue(runCatching { gate.commit(ticket) { fail("save without transaction") } }.isFailure)
    }
}
