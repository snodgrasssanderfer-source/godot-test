package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/** Service owns capture/voice; UI subscribers never own another companion loop. */
object CompanionPresenceHost {
    private val generation = AtomicLong()
    private val revision = AtomicLong()
    private val subscribers = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    @Volatile private var service: CompanionOverlayService? = null
    @Volatile private var latest = JSONObject().put("phase", "idle").put("displayName", "小克")
        .put("mode", "companion").put("running", false).put("available", true).toString()
    fun reserveStart(): Long = generation.incrementAndGet()
    fun current(token: Long) = token == generation.get()
    fun state() = JSONObject(latest)
    fun subscribe(listener: (JSONObject) -> Unit) { subscribers.add(listener); listener(state()) }
    fun unsubscribe(listener: (JSONObject) -> Unit) { subscribers.remove(listener) }
    fun attach(owner: CompanionOverlayService) { service = owner }
    fun detach(owner: CompanionOverlayService) {
        if (service !== owner) return
        service = null
        publish(state().put("running", false).put("phase", "idle").put("text", "").put("lastReply", "")
            .put("audio", JSONObject.NULL).put("remainingSeconds", 0))
    }
    fun publish(value: JSONObject) {
        val event = JSONObject(value.toString()).put("scope", CompanionPolicy.OWNER).put("available", true)
            .put("revision", revision.incrementAndGet())
        latest = event.toString()
        subscribers.forEach { listener -> runCatching { listener(JSONObject(latest)) } }
    }
    fun start(context: Context, config: JSONObject, token: Long) {
        check(current(token)) { "开启已取消，请重新点击" }
        ContextCompat.startForegroundService(context, Intent(context, CompanionOverlayService::class.java)
            .putExtra("presenceConfig", config.toString()).putExtra("presenceGeneration", token))
    }
    fun stop(context: Context) {
        generation.incrementAndGet() // Invalidates a permission dialog or an undelivered start Intent.
        service?.endPresence()
        context.stopService(Intent(context, CompanionOverlayService::class.java))
        publish(state().put("running", false).put("phase", "idle").put("text", "").put("lastReply", "").put("audio", JSONObject.NULL))
    }
    fun externalMicrophone(busy: Boolean) { service?.externalMicrophone(busy) }
    fun invalidateContext() {
        service?.invalidatePresenceContext()
        publish(state().put("text", "").put("lastReply", "").put("canSaveEvent", false).put("audio", JSONObject.NULL))
    }
    fun command(action: String, data: JSONObject): JSONObject {
        val owner = service ?: error("请先开启本次陪伴")
        owner.presenceCommand(action, data)
        return state()
    }
}
