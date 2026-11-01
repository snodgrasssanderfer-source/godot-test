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
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CompanionLatencyDeliveryTest {
    private val publishMethod = CompanionEngine::class.java.getDeclaredMethod("publishPartial", String::class.java,
        Long::class.javaPrimitiveType, JSONArray::class.java, String::class.java, TurnLatency::class.java).apply { isAccessible = true }

    private fun publish(engine: CompanionEngine, task: JSONObject, epoch: Long, sources: JSONArray,
                        text: String, latency: TurnLatency) {
        try { publishMethod.invoke(engine, task.getString("id"), epoch, sources, text, latency) }
        catch (e: InvocationTargetException) { throw e.targetException }
    }

    private fun task(store: CompanionStore, state: String = "running", messageId: String = "") = store.put("task", JSONObject()
        .put("scope", CompanionPolicy.OWNER).put("type", "chat").put("state", state).put("messageId", messageId))

    @Test fun partialDeliveryNotifiesAndKeepsCancellationAndDeletionFences() {
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
                assertEquals(1, notifications.size)
                assertEquals(setOf("domain", "reason"), notifications.single().keys().asSequence().toSet())
                assertEquals("all", notifications.single().getString("domain"))
                assertEquals(12.0, normalTiming.snapshot().getDouble("firstTextMs"), 0.0)

                val stopped = task(store, "running"); watchedId = stopped.getString("id")
                val cancelled = store.cancelTask(watchedId)!!.toString()
                val stoppedTiming = TurnLatency { clock }
                val beforeCancel = notifications.size
                publish(engine, stopped, store.removalEpoch(), JSONArray(), "不得复活的片段", stoppedTiming)
                assertEquals(cancelled, store.get("task", watchedId)!!.toString())
                assertEquals(beforeCancel, notifications.size)
                assertFalse(stoppedTiming.snapshot().has("firstTextMs"))

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
                assertFalse(removedTiming.snapshot().has("firstTextMs"))

            } finally { engine.modules.router.unsubscribe(listener) }
        } catch (e: Throwable) {
            throw AssertionError(generateSequence(e) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
        } finally { java.security.Security.removeProvider("AndroidKeyStore") }
    }
}
