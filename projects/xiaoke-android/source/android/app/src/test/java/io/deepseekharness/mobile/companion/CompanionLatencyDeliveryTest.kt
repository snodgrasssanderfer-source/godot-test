package io.deepseekharness.mobile.companion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.deepseekharness.mobile.companion.life.RecordRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompanionLatencyDeliveryTest {
    private val publishMethod = CompanionEngine::class.java.getDeclaredMethod("publishPartial", String::class.java,
        Long::class.javaPrimitiveType, JSONArray::class.java, String::class.java, TurnLatency::class.java,
        Boolean::class.javaPrimitiveType, String::class.java).apply { isAccessible = true }

    private fun publish(engine: CompanionEngine, task: JSONObject, epoch: Long, sources: JSONArray,
                        text: String, latency: TurnLatency, acknowledgement: Boolean = false, messageId: String = "") {
        try { publishMethod.invoke(engine, task.getString("id"), epoch, sources, text, latency, acknowledgement, messageId) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun task(store: CompanionStore, state: String = "queued", messageId: String = "") = store.put("task", JSONObject()
        .put("scope", CompanionPolicy.OWNER).put("type", "chat").put("state", state).put("messageId", messageId))

    @Test fun partialDeliveryKeepsCancellationAndDeletionFencesAndSeparatesAcknowledgements() {
        FakeAndroidKeyStore.install()
        val context = ApplicationProvider.getApplicationContext<Context>()
        try {
            val engine = CompanionEngine::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
            val store = engine.store
            store.saveSettings(store.settings().put("networkEnabled", false).put("paused", true).put("oneBotEnabled", false))
            val notifications = mutableListOf<JSONObject>()
            val visibleAtNotification = mutableListOf<String>()
            var watchedId = ""
            val listener: (JSONObject) -> Unit = { event ->
                if (event.optString("reason") == "chat.partial") {
                    notifications.add(JSONObject(event.toString()))
                    visibleAtNotification.add(store.get("task", watchedId)?.optString("partialText").orEmpty())
                }
            }
            engine.modules.router.subscribe(listener)
            try {
                val normal = task(store); watchedId = normal.getString("id")
                var clock = 0L
                val normalTiming = TurnLatency { clock }
                clock = 12_000_000
                publish(engine, normal, store.removalEpoch(), JSONArray(), "虚构模型片段", normalTiming)
                assertEquals(listOf("虚构模型片段"), visibleAtNotification)
                assertEquals("running", store.get("task", watchedId)!!.getString("state"))
                assertEquals("model", store.get("task", watchedId)!!.getString("partialKind"))
                assertEquals(1, notifications.size)
                assertEquals(setOf("domain", "reason"), notifications.single().keys().asSequence().toSet())
                assertEquals("all", notifications.single().getString("domain"))
                assertEquals(12.0, normalTiming.snapshot().getDouble("firstModelTextMs"), 0.0)
                assertFalse(normalTiming.snapshot().has("acknowledgementMs"))

                val stopped = task(store, "running"); watchedId = stopped.getString("id")
                val cancelled = store.cancelTask(watchedId)!!.toString()
                val stoppedTiming = TurnLatency { clock }
                val beforeCancel = notifications.size
                publish(engine, stopped, store.removalEpoch(), JSONArray(), "不得复活的片段", stoppedTiming)
                assertEquals(cancelled, store.get("task", watchedId)!!.toString())
                assertEquals(beforeCancel, notifications.size)
                assertFalse(stoppedTiming.snapshot().has("firstVisibleTextMs"))

                val removed = store.message(CompanionPolicy.OWNER, "user", "虚构可删除来源", "测试")
                val deletedTask = task(store, messageId = removed.getString("id")); watchedId = deletedTask.getString("id")
                val sources = JSONArray().put(JSONObject().put("kind", "message").put("id", removed.getString("id")).put("scope", CompanionPolicy.OWNER))
                val epoch = store.removalEpoch()
                store.deleteContent(setOf(RecordRef("message", removed.getString("id"), CompanionPolicy.OWNER)))
                val afterDelete = store.get("task", watchedId)?.toString()
                val beforeDeletePublish = notifications.size
                val removedTiming = TurnLatency { clock }
                assertThrows(ContextRemoved::class.java) {
                    publish(engine, deletedTask, epoch, sources, "已删除内容不得回流", removedTiming)
                }
                assertEquals(afterDelete, store.get("task", watchedId)?.toString())
                assertEquals(beforeDeletePublish, notifications.size)
                assertFalse(removedTiming.snapshot().has("firstVisibleTextMs"))

                clock = 0
                val acknowledged = task(store); watchedId = acknowledged.getString("id")
                val timing = TurnLatency { clock }
                clock = 7_000_000
                publish(engine, acknowledged, store.removalEpoch(), JSONArray(), "虚构本地确认", timing, true, "synthetic-message")
                assertEquals("acknowledgement", store.get("task", watchedId)!!.getString("partialKind"))
                assertEquals("synthetic-message", store.get("task", watchedId)!!.getString("acknowledgedMessageId"))
                assertFalse(timing.snapshot().has("firstModelTextMs"))
                clock = 44_000_000
                publish(engine, acknowledged, store.removalEpoch(), JSONArray(), "虚构模型回答", timing)
                clock = 67_000_000
                publish(engine, acknowledged, store.removalEpoch(), JSONArray(), "虚构模型回答继续", timing)
                val measured = timing.snapshot()
                assertEquals(7.0, measured.getDouble("firstVisibleTextMs"), 0.0)
                assertEquals(7.0, measured.getDouble("acknowledgementMs"), 0.0)
                assertEquals(44.0, measured.getDouble("firstModelTextMs"), 0.0)
                assertEquals("model", store.get("task", watchedId)!!.getString("partialKind"))
            } finally { engine.modules.router.unsubscribe(listener) }
            measureSyntheticDelivery(engine)
        } catch (e: Throwable) {
            throw AssertionError(generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
        } finally { java.security.Security.removeProvider("AndroidKeyStore") }
    }

    /** One fixed polling phase in a JVM fixture; these durations are not phone or model measurements. */
    private fun measureSyntheticDelivery(engine: CompanionEngine) {
        val store = engine.store
        val legacy = task(store, "running")
        val current = task(store, "running")
        val epoch = store.removalEpoch()
        val legacyDelivered = CountDownLatch(1)
        val oldDeliveredAt = AtomicLong(0)
        val newDeliveredAt = AtomicLong(0)
        val poller = Executors.newSingleThreadScheduledExecutor()
        val observer: (JSONObject) -> Unit = { event ->
            if (event.optString("reason") == "chat.partial" &&
                store.get("task", current.getString("id"))?.optString("partialText") == "synthetic-current")
                newDeliveredAt.compareAndSet(0, System.nanoTime())
        }
        engine.modules.router.subscribe(observer)
        try {
            assertFalse(store.get("task", legacy.getString("id"))!!.has("partialText"))
            val oldStarted = System.nanoTime()
            poller.scheduleWithFixedDelay({
                if (store.get("task", legacy.getString("id"))?.optString("partialText") == "synthetic-legacy" &&
                    oldDeliveredAt.compareAndSet(0, System.nanoTime())) legacyDelivered.countDown()
            }, 2500, 2500, TimeUnit.MILLISECONDS)
            store.commitContext(epoch, JSONArray()) {
                store.transitionTask(legacy.getString("id"), setOf("running"), "running", "正在生成", JSONObject().put("partialText", "synthetic-legacy"))
            }
            val oldCommitted = System.nanoTime()
            val newStarted = System.nanoTime()
            publish(engine, current, epoch, JSONArray(), "synthetic-current", TurnLatency())
            assertTrue("Existing router listener must observe the committed text before publishPartial returns", newDeliveredAt.get() > 0)
            assertTrue("Synthetic polling consumer must eventually read the same stored text", legacyDelivered.await(8, TimeUnit.SECONDS))
            val measurement = JSONObject().put("kind", "robolectric_jvm_synthetic_single_sample")
                .put("pollIntervalMs", 2500).put("pollPhase", "publish immediately after initial empty read")
                .put("samples", 1).put("phoneMeasurement", false).put("modelCall", false)
                .put("legacyStoreCommitMs", (oldCommitted - oldStarted) / 1_000_000.0)
                .put("legacyFirstDeliveryMs", (oldDeliveredAt.get() - oldStarted) / 1_000_000.0)
                .put("notifiedFirstDeliveryMs", (newDeliveredAt.get() - newStarted) / 1_000_000.0)
            System.getenv("XIAOKE_LATENCY_DELIVERY_OUTPUT")?.takeIf { it.isNotBlank() }?.let { target ->
                val file = File(target)
                require(file.isAbsolute && file.canonicalFile.path.contains("${File.separator}latency-output${File.separator}"))
                file.parentFile?.mkdirs()
                file.writeText(measurement.toString(2) + "\n")
            }
        } finally {
            engine.modules.router.unsubscribe(observer)
            poller.shutdownNow()
        }
    }
}
