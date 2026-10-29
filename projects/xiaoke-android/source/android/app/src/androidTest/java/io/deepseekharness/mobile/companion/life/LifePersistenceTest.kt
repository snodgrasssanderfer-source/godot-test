package io.deepseekharness.mobile.companion.life

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Android SQLite tests, isolated database per test; no provider/account/network execution. */
@RunWith(AndroidJUnit4::class)
class LifePersistenceTest {
    private lateinit var context: Context
    private lateinit var dbName: String
    private lateinit var store: CompanionStore
    private lateinit var module: LifeModule
    private val principal = CommandPrincipal("fixture-owner", "owner_ui", CompanionPolicy.OWNER, true)
    private val scope = CompanionPolicy.OWNER
    private val ports = object : HostPorts {
        override fun emit(topic: String, payload: JSONObject) {}
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String = error("本测试不允许调用模型")
        override fun cancelModel(requestId: String) = Unit
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal) = JSONObject().put("allowed", false)
    }
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbName = "life-test-" + UUID.randomUUID() + ".db"
        store = CompanionStore(context, dbName)
        module = LifeModule(context, store, ports)
    }
    @After fun tearDown() { store.close(); context.deleteDatabase(dbName) }
    private fun action(name: String, data: JSONObject = JSONObject()) = module.execute(name, data, principal)
    private fun source(text: String) = store.message(scope, "user", text, "synthetic fixture")
    private fun memory(text: String, source: JSONObject, id: String = UUID.randomUUID().toString(), key: String = "", at: Long = System.currentTimeMillis()) =
        action("memory.upsert", JSONObject().put("id", id).put("text", text).put("sourceId", source.getString("id")).put("kind", "fact").put("factKey", key).put("occurredAt", at))

    @Test fun memoryCorrectionPersistsAndStaleEditorCannotOverwriteIt() {
        val s = source("fixture fact")
        val saved = memory("first", s)
        action("memory.upsert", JSONObject().put("id", saved.getString("id")).put("text", "corrected").put("expectedRevision", 1))
        assertThrows(IllegalStateException::class.java) { action("memory.upsert", JSONObject().put("id", saved.getString("id")).put("text", "stale").put("expectedRevision", 1)) }
        store.close(); store = CompanionStore(context, dbName); module = LifeModule(context, store, ports)
        assertEquals("corrected", store.get("memory", saved.getString("id"))!!.getString("text"))
        assertEquals(2, action("memory.history", JSONObject().put("id", saved.getString("id"))).getJSONArray("items").length())
    }

    @Test fun sourceOccurrenceTimeWinsOverLateArrivalAndKeepsHistoricalFact() {
        val s = source("synthetic preference"); val now = System.currentTimeMillis()
        val newer = memory("now", s, key = "fixture-slot", at = now)
        val late = memory("older", s, key = "fixture-slot", at = now - 100000)
        assertEquals(newer.getString("id"), store.memoriesFor(scope).getJSONObject(0).getString("id"))
        assertTrue(store.get("memory", late.getString("id"))!!.getBoolean("superseded"))
        assertEquals(2, action("memory.search", JSONObject().put("includeHistory", true)).getJSONArray("items").length())
    }

    @Test fun forgetSuppressesHistoryAndCanBeExplicitlyRestored() {
        val s = source("synthetic secret"); val saved = memory("meaningful fixture", s)
        action("memory.forget", JSONObject().put("id", saved.getString("id")))
        assertEquals(0, store.contextMessages(scope).length()); assertEquals(0, store.memoriesFor(scope).length())
        assertNotNull(store.get("message", s.getString("id")))
        assertEquals(1, action("memory.search", JSONObject().put("includeForgotten", true)).getJSONArray("items").length())
        action("memory.forget", JSONObject().put("id", saved.getString("id")).put("forgotten", false))
        assertEquals(1, store.contextMessages(scope).length()); assertEquals(1, store.memoriesFor(scope).length())
    }

    @Test fun deletionErasesTrackedDerivativesAndOldBackupCannotResurrectThem() {
        val s = source("deleted synthetic fixture"); val saved = memory("derived fixture", s)
        val ticket = store.contextSnapshot(scope)
        val reply = store.commitContext(ticket.getLong("epoch"), ticket.getJSONArray("sourceRefs")) {
            store.message(scope, "assistant", "derived answer", "fixture", replyTo = s.getString("id"))
        }
        val old = store.exportData()
        action("memory.delete", JSONObject().put("id", saved.getString("id")))
        assertNull(store.get("message", s.getString("id"))); assertNull(store.get("message", reply.getString("id")))
        assertNull(store.get("memory", saved.getString("id"))); assertEquals(0, store.memoryHistory(scope, saved.getString("id")).length())
        store.restoreData(old)
        assertNull(store.get("message", s.getString("id"))); assertNull(store.get("message", reply.getString("id")))
        assertFalse(store.exportData().toString().contains("deleted synthetic fixture"))
        assertThrows(IllegalStateException::class.java) { store.put("message", s) }
    }

    @Test fun deletionRacingWithModelCommitCannotLeaveStaleResult() {
        val s = source("racing fixture"); val saved = memory("race-memory", s); val ticket = store.contextSnapshot(scope)
        val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        val late = pool.submit {
            start.await()
            try { store.commitContext(ticket.getLong("epoch"), ticket.getJSONArray("sourceRefs")) {
                store.put("message", JSONObject().put("id", "late-response").put("scope", scope).put("role", "assistant").put("text", "stale fixture"))
            } } catch (_: IllegalStateException) { }
        }
        val deletion = pool.submit { start.await(); action("memory.delete", JSONObject().put("id", saved.getString("id"))) }
        start.countDown(); late.get(5, TimeUnit.SECONDS); deletion.get(5, TimeUnit.SECONDS); pool.shutdownNow()
        assertNull(store.get("message", "late-response")); assertNull(store.get("memory", saved.getString("id")))
    }

    @Test fun structuredMeaningfulEventsAreGroundedDeduplicatedAndCannotInventCompletion() {
        val s = source("fixture experience")
        val event = ModuleEvent("conversation.message", scope, s.getString("id"), JSONObject().put("meaningful", true).put("memory", JSONObject().put("text", "fixture experience").put("kind", "event")))
        module.onEvent(event); module.onEvent(event)
        assertEquals(1, store.list("memory", scope).length())
        val task = store.put("task", JSONObject().put("scope", scope).put("state", "failed").put("progress", "fixture task"))
        assertThrows(IllegalStateException::class.java) { module.onEvent(ModuleEvent("action.completed", scope, task.getString("id"), JSONObject())) }
        assertEquals(0, store.list("diary", scope).length())
        store.put("task", task.put("state", "done")); module.onEvent(ModuleEvent("action.completed", scope, task.getString("id"), JSONObject()))
        assertTrue(store.list("diary", scope).getJSONObject(0).getString("text").startsWith("我"))
    }

    @Test fun twoDatabaseConnectionsCannotOversubscribeActivityAndRestartKeepsUnknownHold() {
        action("budget.configure", JSONObject().put("unit", "calls").put("currency", "").put("ownerLimit", 10).put("activityLimit", 20).put("warningRemaining", 2).put("allowUnknownPrice", true).put("unknownPriceCeiling", 1))
        val secondStore = CompanionStore(context, dbName); val second = LifeModule(context, secondStore, ports)
        val accepted = AtomicInteger(); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(4)
        val futures = (0 until 40).map { i -> pool.submit {
            start.await()
            try {
                (if (i % 2 == 0) module else second).execute("budget.reserve", JSONObject().put("requestId", "r-$i").put("scope", scope).put("lane", "activity").put("purpose", "fixture").put("maximum", 1).put("priceKnown", false), principal)
                accepted.incrementAndGet()
            } catch (_: IllegalStateException) { }
        } }
        start.countDown(); futures.forEach { it.get(15, TimeUnit.SECONDS) }; pool.shutdownNow(); secondStore.close()
        assertEquals(18, accepted.get())
        assertEquals(18L, action("budget.status").getJSONObject("activity").getLong("held"))
        assertEquals(10L, action("budget.status").getJSONObject("owner").getLong("remaining"))
    }

    @Test fun groupCannotRewriteRulesAndSharingReturnsOnlyApprovedSummary() {
        val friend = CommandPrincipal("fixture-friend", "group_qq", "qq:group:fixture", true)
        assertThrows(IllegalStateException::class.java) { module.execute("budget.configure", JSONObject(), friend) }
        assertThrows(IllegalStateException::class.java) { module.execute("memory.search", JSONObject().put("scope", scope), friend) }
        val saved = action("memory.upsert", JSONObject().put("text", "private fixture source").put("shareSummary", "approved summary")
            .put("shareWithScopes", JSONArray().put(friend.scope)))
        val result = module.execute("memory.search", JSONObject().put("scope", scope).put("shareSummary", true), friend).getJSONArray("items")
        assertEquals(saved.getString("id"), result.getJSONObject(0).getString("id"))
        assertEquals("approved summary", result.getJSONObject(0).getString("summary"))
        assertFalse(result.toString().contains("private fixture")); assertFalse(result.getJSONObject(0).has("sourceRefs"))
    }

    @Test fun versionOneSchemaMigratesWithoutLosingContent() {
        store.close(); context.deleteDatabase(dbName)
        val old = context.openOrCreateDatabase(dbName, Context.MODE_PRIVATE, null)
        old.execSQL("CREATE TABLE records(kind TEXT NOT NULL,id TEXT NOT NULL,scope TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,deleted INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(kind,id))")
        old.execSQL("CREATE TABLE memory_history(id INTEGER PRIMARY KEY AUTOINCREMENT,memory_id TEXT NOT NULL,scope TEXT NOT NULL,previous_value TEXT,new_value TEXT,action TEXT NOT NULL,created_at INTEGER NOT NULL,is_deleted INTEGER NOT NULL DEFAULT 0)")
        val fixture = JSONObject().put("id", "legacy").put("scope", scope).put("text", "legacy fixture")
        old.execSQL("INSERT INTO records VALUES('memory','legacy',?,?,1,0)", arrayOf(scope, fixture.toString()))
        old.version = 1; old.close()
        store = CompanionStore(context, dbName)
        assertEquals("legacy fixture", store.get("memory", "legacy")!!.getString("text"))
        assertEquals(2, store.readableDatabase.version)
        store.deleteMemory(scope, "legacy"); assertTrue(store.isDeleted("memory", "legacy"))
    }
}
