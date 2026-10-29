package io.deepseekharness.mobile.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue

/** Optional companion message transport. Android UI automation remains independent and available. */
class OneBotBridge private constructor(private val context: Context) {
    private val engine get() = CompanionEngine.get(context)
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).followRedirects(false).build()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var verified = false
    @Volatile private var status = "未连接"
    private var failures = 0
    private val receiveWorker = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64))
    private val media = OneBotMedia()
    private data class Pending(val latch: CountDownLatch = CountDownLatch(1), var response: JSONObject? = null)
    private val pending = ConcurrentHashMap<String, Pending>()
    fun state() = JSONObject().put("connected", verified).put("status", status)
        .put("tokenConfigured", CompanionSecrets(context, "onebot").configured())
    @Synchronized fun ensureStarted() { if (socket == null) runCatching { start() }.onFailure { status = "配置不完整，请重新连接" } }

    @Synchronized fun stop() {
        generation++; verified = false; socket?.cancel(); socket = null
        handler.removeCallbacksAndMessages(null); status = "已断开"
        receiveWorker.queue.clear()
        pending.values.forEach { it.latch.countDown() }; pending.clear()
    }
    @Synchronized fun start() {
        stop()
        val settings = engine.store.settings()
        if (!settings.optBoolean("oneBotEnabled") || !settings.optBoolean("networkEnabled") || settings.optBoolean("paused")) return
        val endpoint = settings.optString("oneBotUrl")
        val uri = URI(endpoint)
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "消息桥使用无凭据参数的 wss 地址" }
        val account = OneBotProtocol.numeric(settings.optString("oneBotAccount"))
        val gen = generation
        status = "连接中，等待核对账号"
        val request = Request.Builder().url(endpoint)
        val secret = CompanionSecrets(context, "onebot")
        if (secret.configured()) request.header("Authorization", "Bearer ${secret.read()}")
        socket = client.newWebSocket(request.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (gen != generation) { webSocket.cancel(); return }
                webSocket.send(JSONObject().put("action", "get_login_info").put("params", JSONObject()).put("echo", "verify-$gen").toString())
                handler.postDelayed({ if (gen == generation && !verified) { status = "账号核对超时，已断开"; socket?.cancel() } }, 15000)
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (gen != generation || text.length > 1024 * 1024) return
                runCatching {
                    val value = JSONObject(text)
                    val echo = value.optString("echo")
                    if (echo == "verify-$gen") {
                        if (value.optInt("retcode", -1) != 0 || value.optJSONObject("data")?.optString("user_id") != account) {
                            status = "桥接账号与本机配置不符，已断开"; generation++; verified = false; webSocket.cancel(); return
                        }
                        verified = true; failures = 0; status = "已连接，账号已核对"; return
                    }
                    pending.remove(echo)?.let { it.response = value; it.latch.countDown(); return }
                    if (!verified || engine.store.settings().optBoolean("paused")) return
                    val address = OneBotProtocol.address(value, account) ?: return
                    val scope = address.getString("scope")
                    val store = engine.store
                    val c = store.get("conversation", scope) ?: JSONObject().put("id", scope).put("scope", scope)
                        .put("mode", "quiet").put("autoReply", false).put("captureEnabled", false).put("cooldownSeconds", 60).put("hourlyLimit", 6)
                        .put("title", "QQ ${address.getString("targetType")} ${address.getString("targetId")}")
                        .put("account", account).put("targetId", address.getString("targetId")).put("targetType", address.getString("targetType"))
                        .put("route", "onebot").put("reliable", true)
                    if (value.optString("post_type") == "message_sent") {
                        if (c.optBoolean("captureEnabled")) store.put("conversation", c.put("humanActivityAt", System.currentTimeMillis()))
                        return
                    }
                    if (value.optString("post_type") != "message") return
                    store.put("conversation", c)
                    // Only routing metadata is retained until the owner explicitly enables this conversation.
                    val ownerControl = store.settings().optBoolean("allowOwnerQqMute") && address.optString("targetType") == "private" && address.optString("targetId") == store.settings().optString("ownerQqId")
                    if (!ParticipationPolicy.mayReceive(c) && !ownerControl) return
                    val event = OneBotProtocol.event(value, account) ?: return
                    if (ParticipationPolicy.isOwnerMute(event, store.settings())) { engine.silenceGroups(); return }
                    if (!ParticipationPolicy.mayReceive(c)) return
                    val old = c.optJSONArray("seen") ?: JSONArray()
                    val seen = (0 until old.length()).map { old.getString(it) }
                    if (event.getString("id") in seen) return
                    c.put("seen", JSONArray((seen + event.getString("id")).takeLast(256)))
                        .put("account", account).put("targetId", event.getString("targetId")).put("targetType", event.getString("targetType"))
                        .put("route", "onebot").put("reliable", true).put("canReply", true)
                        .put("title", (if (event.getString("targetType") == "group") "QQ群 " else "QQ好友 ") + event.getString("targetId"))
                        .put("identitySource", "OneBot 核对 self_id + 平台数字会话 ID；不授予设备主人权限")
                    store.put("conversation", c)
                    try { receiveWorker.execute { receiveEvent(gen, event) } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { status = "消息接收繁忙，部分消息未处理；没有自动补发" }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { disconnected(gen) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(gen) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
    }
    private fun receiveEvent(gen: Int, event: JSONObject) {
        val store = engine.store
        val scope = event.getString("scope")
        fun allowed(images: Boolean = false): Boolean {
            val settings = store.settings()
            val c = store.get("conversation", scope) ?: return false
            return gen == generation && verified && settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") &&
                !settings.optBoolean("paused") && c.optBoolean("captureEnabled") && (!images || c.optBoolean("allowImages"))
        }
        if (!allowed()) return
        val images = event.optJSONArray("images") ?: JSONArray()
        val (parts, read) = if (allowed(true)) media.fetch(images) { allowed(true) } else JSONArray() to 0
        if (!allowed()) return
        var observationId = ""
        synchronized(store) {
            if (read > 0 && allowed(true)) observationId = store.put("observation", JSONObject().put("scope", scope)
                .put("source", "OneBot 消息 ${event.getString("id")} 的图片")
                .put("coverage", "已读取 $read 张图片（最多处理前三张）；未读取的附件不能描述")
                .put("createdAt", System.currentTimeMillis()).put("analyzed", false).put("parts", parts)).getString("id")
        }
        val c = store.get("conversation", scope) ?: return
        val summary = if (images.length() > 0) " [已读取 $read 张图片；其余未读取]" else ""
        CompanionRouting.receive(context, c, "${event.optString("sender")}（${event.getString("senderId")}）：${event.getString("text")}$summary",
            "OneBot 消息 ${event.getString("id")}", event.optBoolean("mentioned"), event.getString("senderId"), observationId)
    }
    private fun disconnected(gen: Int) {
        if (gen != generation) return
        verified = false; status = "连接中断；等待重连，未确认发送不会重发"
        pending.values.forEach { it.latch.countDown() }; pending.clear()
        val delay = (5000L shl failures.coerceAtMost(4)); failures++
        handler.postDelayed({ if (gen == generation) runCatching { start() } }, delay)
    }
    fun send(task: JSONObject): JSONObject {
        val store = engine.store
        val settings = store.settings()
        check(verified && settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") && !settings.optBoolean("paused")) { "消息桥未连接或助手已暂停" }
        val conversation = store.get("conversation", task.getString("scope")) ?: error("会话不存在")
        check(conversation.optString("route") == "onebot" && task.optString("account") == settings.optString("oneBotAccount") && conversation.optString("account") == task.optString("account")) { "账号或会话绑定已变化" }
        val echo = CompanionPolicy.id()
        val payload = OneBotProtocol.send(conversation, task.getString("body"), echo)
        store.claimSend(task.getString("id"), CompanionPolicy.draftDigest(task))
        val wait = Pending(); pending[echo] = wait
        try {
            check(socket?.send(payload.toString()) == true) { "连接未接受提交" }
            wait.latch.await(15, TimeUnit.SECONDS)
            val ack = wait.response
            val state = when {
                ack == null -> "unknown"
                ack.optInt("retcode", -1) != 0 || ack.optString("status") != "ok" -> "failed"
                ack.optJSONObject("data")?.optString("message_id").isNullOrBlank() -> "unknown"
                else -> "done"
            }
            return store.transitionTask(task.getString("id"), setOf("sending", "unknown"), state,
                when(state) { "done" -> "桥已返回平台消息 ID（不等于对方已读）"; "failed" -> "桥明确拒绝提交，请检查桥状态"; else -> "没有收到明确提交结果，请核对平台，未自动重发" },
                JSONObject().put("platformMessageId", ack?.optJSONObject("data")?.optString("message_id").orEmpty())) ?: error("任务状态已变化")
        } catch (_: Exception) {
            return store.transitionTask(task.getString("id"), setOf("sending"), "unknown", "提交过程被中断，请核对平台，不自动重发") ?: store.get("task", task.getString("id"))!!
        } finally { pending.remove(echo) }
    }
    companion object {
        @Volatile private var instance: OneBotBridge? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: OneBotBridge(context.applicationContext).also { instance = it } }
    }
}
