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
    @Volatile private var moduleListener: ((String, JSONObject) -> Unit)? = null
    fun setModuleListener(listener: ((String, JSONObject) -> Unit)?) { moduleListener = listener }
    @Volatile private var effectGate: ((JSONObject) -> Boolean)? = null
    fun setEffectGate(gate: ((JSONObject) -> Boolean)?) { effectGate = gate }
    /** Fingerprint of a certificate the server presented that is not trusted yet; the owner confirms it once. */
    @Volatile private var untrustedCertificate = ""
    // Heartbeat watch: NapCat sends meta_event heartbeats (every 30 s) with status.online; a QQ login revoked by Tencent
    // keeps the socket open, so the socket alone says nothing about whether messages still arrive.
    @Volatile private var lastHeartbeat = 0L
    @Volatile private var heartbeatInterval = 30_000L
    @Volatile private var qqOnline = true
    private val watch = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "xiaoke-qq-watch").apply { isDaemon = true } }
    @Volatile private var watchTask: java.util.concurrent.ScheduledFuture<*>? = null
    /** Socket verified and QQ itself online. */
    val online get() = verified && qqOnline

    fun state() = JSONObject().put("connected", verified && qqOnline).put("status", status).put("qqOnline", qqOnline).put("lastHeartbeat", lastHeartbeat)
        .put("tokenConfigured", CompanionSecrets(context, "onebot").configured())
        .put("account", engine.store.settings().optString("oneBotAccount")).put("untrustedCertificate", untrustedCertificate)
    @Synchronized fun ensureStarted() { if (socket == null) runCatching { start() }.onFailure { status = "配置不完整，请重新连接" } }

    @Synchronized fun stop() {
        generation++; verified = false; socket?.cancel(); socket = null
        watchTask?.cancel(false); watchTask = null
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
        // Blank until the first connection: the bridge tells us which QQ account it is logged in as.
        var account = settings.optString("oneBotAccount").also { if (it.isNotBlank()) OneBotProtocol.numeric(it) }
        val gen = generation
        status = "连接中，等待核对账号"
        val request = Request.Builder().url(endpoint)
        val secret = CompanionSecrets(context, "onebot")
        if (secret.configured()) request.header("Authorization", "Bearer ${secret.read()}")
        val trust = PinnedTls.Trust(settings.optString("oneBotCertPin"))
        socket = PinnedTls.client(client, trust).newWebSocket(request.build(), object : WebSocketListener() {
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
                        val loggedIn = value.optJSONObject("data")?.optString("user_id").orEmpty()
                        if (value.optInt("retcode", -1) != 0 || loggedIn.isBlank() || (account.isNotBlank() && loggedIn != account)) {
                            status = "桥上登录的 QQ 和之前连接的不是同一个，已断开"; generation++; verified = false; webSocket.cancel(); return
                        }
                        if (account.isBlank()) { account = OneBotProtocol.numeric(loggedIn); engine.store.saveSettings(engine.store.settings().put("oneBotAccount", account)) }
                        untrustedCertificate = ""
                        verified = true; failures = 0; status = "已连接 · 小克的 QQ $account"; qqOnline = true; lastHeartbeat = System.currentTimeMillis()
                        startWatch(gen, account)
                        applyTargets(engine.store); return
                    }
                    pending.remove(echo)?.let { it.response = value; it.latch.countDown(); return }
                    if (value.optString("post_type") == "meta_event") {
                        if (value.optString("meta_event_type") == "heartbeat") heartbeat(value.optJSONObject("status"), value.optLong("interval", 30_000L))
                        return
                    }
                    if (!verified || engine.store.settings().optBoolean("paused")) return
                    if (value.optString("post_type") == "request" && value.optString("self_id") == account) {
                        val kind = value.optString("request_type")
                        if (kind !in setOf("friend", "group")) return
                        if (kind == "group" && value.optString("sub_type") != "invite") return
                        val target = OneBotProtocol.numeric(value.optString(if (kind == "group") "group_id" else "user_id"))
                        val flag = value.optString("flag"); if (flag.isBlank() || flag.length > 512) return
                        val event = JSONObject().put("id", "qqreq-" + CompanionPolicy.digest("$account\u0000$kind\u0000$target\u0000$flag"))
                            .put("scope", "qq:${if (kind == "group") "group" else "private"}:${CompanionPolicy.digest("onebot\u0000$account\u0000$target").take(40)}")
                            .put("account", account).put("targetId", target).put("requestType", kind).put("flag", flag)
                        try { receiveWorker.execute { if (gen == generation) moduleListener?.invoke("social.request", event) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) { status = "请求接收繁忙，请稍后查看平台" }
                        return
                    }
                    if (value.optString("post_type") == "notice") {
                        val poke = OneBotProtocol.poke(value, account) ?: return
                        val c = engine.store.get("conversation", poke.getString("scope")) ?: return
                        if (!ParticipationPolicy.mayReceive(c)) return
                        try { receiveWorker.execute { if (gen == generation) receivePoke(c, poke, account) } }
                        catch (_: java.util.concurrent.RejectedExecutionException) { status = "消息接收繁忙，部分消息未处理；没有自动补发" }
                        return
                    }
                    val address = OneBotProtocol.address(value, account) ?: return
                    val scope = address.getString("scope")
                    val store = engine.store
                    val c = store.get("conversation", scope) ?: JSONObject().put("id", scope).put("scope", scope)
                        .put("mode", "quiet").put("autoReply", false).put("captureEnabled", false).put("cooldownSeconds", 60).put("hourlyLimit", 6)
                        .put("title", "QQ ${address.getString("targetType")} ${address.getString("targetId")}")
                        .put("account", account).put("targetId", address.getString("targetId")).put("targetType", address.getString("targetType"))
                        .put("route", "onebot").put("reliable", true)
                    if (value.optString("post_type") == "message_sent") {
                        val messageId = value.opt("message_id")?.toString().orEmpty()
                        recordSelf(scope, value.optString("raw_message"), messageId)
                        // NapCat reports her own API sends too. Only a send that is not hers means the owner is typing on her account.
                        val hers = System.currentTimeMillis() - (ownSendAt[scope] ?: 0L) < 10_000 ||
                            c.optJSONArray("myMessageIds")?.let { a -> (0 until a.length()).any { a.optString(it) == messageId } } == true
                        if (!hers && c.optBoolean("captureEnabled")) store.put("conversation", c.put("humanActivityAt", System.currentTimeMillis()))
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
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (gen == generation) XLog.w("QQ bridge socket failed (HTTP ${response?.code ?: "-"})", t)
                if (gen == generation && t is javax.net.ssl.SSLException && trust.presented.isNotBlank() && !trust.pinned) {
                    // Self-signed server certificate: wait for the owner to confirm it instead of retrying.
                    untrustedCertificate = trust.presented; verified = false; status = "第一次连接：请核对服务器证书指纹后信任"; return
                }
                if (gen == generation && (response?.code == 401 || response?.code == 403)) { status = "令牌不对，服务器拒绝了连接"; verified = false; return }
                disconnected(gen)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { disconnected(gen) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        })
    }
    private fun receiveEvent(gen: Int, event: JSONObject) {
        val store = engine.store
        val scope = event.getString("scope")
        fun allowed(kind: String = ""): Boolean {
            val settings = store.settings()
            val c = store.get("conversation", scope) ?: return false
            return gen == generation && verified && settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") &&
                !settings.optBoolean("paused") && c.optBoolean("captureEnabled") && when (kind) {
                    "image", "mface" -> c.optBoolean("allowImages")
                    "record" -> c.optBoolean("allowAudio")
                    "video" -> c.optBoolean("allowVideo")
                    else -> true
                }
        }
        if (!allowed()) return
        val attachments = event.optJSONArray("attachments") ?: JSONArray()
        var text = event.getString("text")
        // Voice: NapCat's own speech-to-text first; the audio itself is only read when that gives nothing.
        store.get("conversation", scope)?.let { c ->
            for (i in 0 until attachments.length()) attachments.getJSONObject(i).takeIf { it.optString("kind") == "record" }?.let { a ->
                io.deepseekharness.mobile.companion.chat.QqExtras.voiceText(context, c, event.optString("platformMessageId"))?.let { heard ->
                    text = text.replaceFirst(" [语音待理解]", " [语音转文字：$heard]")
                    a.put("kind", "record-text").put("status", "transcribed")
                }
            }
        }
        text = io.deepseekharness.mobile.companion.chat.StickerReader.describe(engine, scope, text, attachments, allowed("image"))
        if (!allowed()) return
        val (parts, read) = media.fetchSegments(attachments, ::allowed)
        if (!allowed()) return
        moduleListener?.invoke("conversation.message", JSONObject(event.toString()))
        var observationId = ""
        synchronized(store) {
            if (read > 0 && allowed()) observationId = store.put("observation", JSONObject().put("scope", scope).put("temporary", true)
                .put("source", "OneBot 消息 ${event.getString("id")} 的媒体").put("sourceIds", JSONArray().put(event.getString("id")))
                .put("coverage", "已读取 $read 个媒体片段（每条消息最多三个）；还未取得模型理解结果，未读取附件不能描述")
                .put("createdAt", System.currentTimeMillis()).put("analyzed", false).put("parts", parts)).getString("id")
        }
        val c = store.get("conversation", scope) ?: return
        val missed = (0 until attachments.length()).map { attachments.getJSONObject(it) }
            .filter { it.optString("kind") in setOf("image", "mface", "record", "video") && it.optString("status") != "bytes_ready_for_model" }
        val why = missed.map { a -> when (a.optString("status")) {
            "not_authorized" -> "这个会话没开读图/听语音"
            "batch_limit" -> "一条消息最多读三个"
            "cache_limit" -> "太大了放不下"
            else -> a.optString("reason").ifBlank { "没读到" }
        } }.distinct()
        val summary = if (missed.isNotEmpty()) " [已读取 $read 个媒体片段；${missed.size} 个没读到：${why.joinToString("；")}]" else ""
        val replyTo = event.optString("replyTo")
        val mine = c.optJSONArray("myMessageIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        val replyToHer = replyTo.isNotBlank() && replyTo in mine
        CompanionRouting.receive(context, c, "${event.optString("sender")}（${event.getString("senderId")}）：$text$summary",
            "OneBot 消息 ${event.getString("id")}", event.optBoolean("mentioned"), event.getString("senderId"), observationId, event.optString("platformMessageId"),
            replyToHer, event.optBoolean("mentionsOther") || (replyTo.isNotBlank() && !replyToHer))
    }
    private fun heartbeat(status: JSONObject?, interval: Long) {
        lastHeartbeat = System.currentTimeMillis()
        heartbeatInterval = interval.coerceIn(5_000L, 300_000L)
        val nowOnline = status?.let { if (it.has("online") && !it.isNull("online")) it.optBoolean("online") else true } ?: true
        if (nowOnline != qqOnline) setQqOnline(nowOnline, if (nowOnline) "QQ 重新在线" else "QQ 已掉线（NapCat 报告不在线），需要在服务器上重新扫码登录")
    }

    private fun setQqOnline(value: Boolean, why: String) {
        qqOnline = value
        status = if (value) "已连接 · 小克的 QQ ${engine.store.settings().optString("oneBotAccount")}" else why
        io.deepseekharness.mobile.companion.XLog.w("QQ online=$value: $why")
        runCatching { engine.modules.router.signal("all", "bridge.state") }
        CompanionOverlayService.refreshBadge(context)
    }

    /** Every heartbeat period: two missed heartbeats mean offline; every five minutes ask get_status directly. */
    private fun startWatch(gen: Int, account: String) {
        watchTask?.cancel(false)
        var ticks = 0
        watchTask = watch.scheduleWithFixedDelay({
            if (gen != generation || !verified) return@scheduleWithFixedDelay
            val silent = System.currentTimeMillis() - lastHeartbeat
            if (silent > 2 * heartbeatInterval + 5_000L && qqOnline) setQqOnline(false, "超过两个心跳周期（${silent / 1000} 秒）没收到 NapCat 心跳，可能已掉线")
            if (++ticks % 10 == 0 || !qqOnline) runCatching {
                val data = callAction(account, "get_status", JSONObject()).optJSONObject("data")
                if (data != null && data.has("online")) {
                    val online = data.optBoolean("online")
                    if (online) lastHeartbeat = System.currentTimeMillis()
                    if (online != qqOnline) setQqOnline(online, if (online) "QQ 重新在线" else "QQ 已掉线（get_status 报告不在线），需要在服务器上重新扫码登录")
                }
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    /** Sticker shelf lookup by name, provided by the social module that holds the owner's media. */
    @Volatile private var stickerSource: ((String) -> io.deepseekharness.mobile.companion.chat.OutgoingSegments.Sticker?)? = null
    fun setStickerSource(source: ((String) -> io.deepseekharness.mobile.companion.chat.OutgoingSegments.Sticker?)?) { stickerSource = source }

    private fun textOf(message: JSONArray) = (0 until message.length()).joinToString("") { message.getJSONObject(it).optJSONObject("data")?.optString("text").orEmpty() }

    /** Her own message ids per chat, so a quote of one of them counts as talking to her. */
    /** Her own sends as QQ reports them back (message_sent): scope → (time, text, message id). */
    private val selfEvents = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ConcurrentLinkedDeque<Triple<Long, String, String>>>()
    /** When she last sent in a scope; message_sent events around then are hers, not the owner typing on her account. */
    private val ownSendAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun recordSelf(scope: String, text: String, messageId: String) {
        val q = selfEvents.getOrPut(scope) { java.util.concurrent.ConcurrentLinkedDeque() }
        q.addLast(Triple(System.currentTimeMillis(), text, messageId))
        while (q.size > 30) q.pollFirst()
    }
    /** The message id of a message_sent since [since] whose text carries [text]'s opening, or null. */
    private fun selfEchoSince(scope: String, since: Long, text: String): String? {
        val probe = text.trim().take(20)
        if (probe.isEmpty()) return null
        return selfEvents[scope]?.lastOrNull { it.first >= since - 1000 && probe in it.second }?.third
    }

    private fun rememberMine(scope: String, ids: List<String>) = runCatching {
        val store = engine.store
        synchronized(store) {
            val c = store.get("conversation", scope) ?: return@runCatching
            val old = c.optJSONArray("myMessageIds")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            store.put("conversation", c.put("myMessageIds", JSONArray((old + ids).takeLast(100))))
        }
    }

    /** A poke reaches her like a short message from the poker, so she knows who poked whom (and can answer one aimed at her). */
    private fun receivePoke(conversation: JSONObject, poke: JSONObject, account: String) {
        val store = engine.store
        val scope = poke.getString("scope")
        val c = store.get("conversation", scope) ?: return
        val old = c.optJSONArray("seen") ?: JSONArray()
        val seen = (0 until old.length()).map { old.getString(it) }
        if (poke.getString("id") in seen) return
        store.put("conversation", c.put("seen", JSONArray((seen + poke.getString("id")).takeLast(256))))
        val owner = store.settings().optString("ownerQqId")
        val group = if (poke.getString("targetType") == "group") poke.getString("targetId") else ""
        fun name(id: String): String = when (id) {
            account -> "你"
            owner -> "主人"
            else -> runCatching {
                val info = if (group.isNotBlank()) callAction(account, "get_group_member_info", JSONObject().put("group_id", group).put("user_id", id))
                    else callAction(account, "get_stranger_info", JSONObject().put("user_id", id))
                info.optJSONObject("data")?.let { it.optString("card").ifBlank { it.optString("nickname") } }?.takeIf { it.isNotBlank() }
            }.getOrNull()?.take(30) ?: id
        }
        val poker = poke.getString("poker")
        val who = name(poker)
        val text = "$who（$poker）：[戳一戳] $who ${poke.optString("action", "戳了戳")} ${name(poke.getString("target"))}${poke.optString("suffix")}"
        CompanionRouting.receive(context, conversation, text, "OneBot 戳一戳 ${poke.getString("id")}", poke.optBoolean("atSelf"), poker)
    }

    /** Trust the certificate the owner just compared on the server, then reconnect. */
    @Synchronized fun trustCertificate(fingerprint: String) {
        check(fingerprint.isNotBlank() && fingerprint.equals(untrustedCertificate, ignoreCase = true)) { "证书指纹已变化，请重新连接后再核对" }
        engine.store.saveSettings(engine.store.settings().put("oneBotCertPin", fingerprint.uppercase()))
        untrustedCertificate = ""; start()
    }

    /** The owner's own chat and the groups she may talk in are open to her; every other conversation is ignored. */
    fun applyTargets(store: CompanionStore) {
        val settings = store.settings()
        val account = settings.optString("oneBotAccount").takeIf { it.isNotBlank() } ?: return
        val groups = settings.optJSONArray("qqGroups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().toSet()
        fun scopeOf(type: String, target: String) = "qq:$type:${CompanionPolicy.digest("onebot\u0000$account\u0000$target").take(40)}"
        fun open(type: String, target: String, title: String) {
            val scope = scopeOf(type, target)
            val c = store.get("conversation", scope) ?: JSONObject().put("id", scope).put("scope", scope).put("mode", "natural")
                .put("cooldownSeconds", 60).put("hourlyLimit", 20).put("threshold", 0.65).put("nightQuiet", true).put("title", title)
            store.put("conversation", c.put("account", account).put("targetId", target).put("targetType", type).put("route", "onebot")
                .put("reliable", true).put("captureEnabled", true).put("autoReply", true).put("allowImages", c.optBoolean("allowImages", true)))
        }
        groups.forEach { open("group", it, "QQ群 $it") }
        settings.optString("ownerQqId").takeIf { it.isNotBlank() }?.let { open("private", it, "主人的 QQ") }
        val all = store.list("conversation")
        for (i in 0 until all.length()) all.getJSONObject(i).let { c ->
            if (c.optString("route") == "onebot" && c.optString("targetType") == "group" && c.optString("targetId") !in groups && c.optBoolean("captureEnabled"))
                store.put("conversation", c.put("captureEnabled", false).put("autoReply", false))
        }
    }
    private fun disconnected(gen: Int) {
        if (gen != generation) return
        XLog.w("QQ bridge disconnected; retry in ${(5000L shl failures.coerceAtMost(4)) / 1000} s")
        verified = false; status = "连接中断；等待重连，未确认发送不会重发"
        pending.values.forEach { it.latch.countDown() }; pending.clear()
        val delay = (5000L shl failures.coerceAtMost(4)); failures++
        handler.postDelayed({ if (gen == generation) runCatching { start() } }, delay)
    }
    /** Module-owned durable ledger must claim a write before calling. No automatic retry here. */
    fun callAction(account: String, action: String, params: JSONObject): JSONObject {
        check(action in setOf("send_msg", "send_qzone_msg", "get_group_member_info", "get_stranger_info", "get_status", "comment_qzone_public_feed", "set_friend_add_request", "set_group_add_request", "set_group_leave", "get_qzone_public_feeds", "get_forward_msg",
            "get_qzone_feeds", "get_qzone_msg_list", "get_qzone_msg_detail", "comment_qzone", "like_qzone") + EXTRAS) { "不支持的桥动作" }
        val settings = engine.store.settings()
        check(verified && settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") && !settings.optBoolean("paused")) { "消息桥未连接" }
        check(OneBotProtocol.numeric(account) == settings.optString("oneBotAccount")) { "桥接账号已变化" }
        val echo = CompanionPolicy.id(); val wait = Pending(); val gen = generation
        pending[echo] = wait
        try {
            check(socket?.send(JSONObject().put("action", action).put("params", params).put("echo", echo).toString()) == true) { "桥未接受请求" }
            check(wait.latch.await(15, TimeUnit.SECONDS) && gen == generation) { "桥响应未确认" }
            return wait.response ?: error("桥响应未确认")
        } finally { pending.remove(echo) }
    }
    /** NapCat extras (reaction, typing, poke, read, voice text) for a conversation already bound to this account. */
    fun extra(conversation: JSONObject, action: String, params: JSONObject): JSONObject {
        require(action in EXTRAS)
        val settings = engine.store.settings()
        check(conversation.optString("route") == "onebot" && conversation.optString("account") == settings.optString("oneBotAccount")) { "账号或会话绑定已变化" }
        return callAction(conversation.getString("account"), action, params)
    }

    fun send(task: JSONObject): JSONObject {
        check(effectGate?.invoke(JSONObject(task.toString())) == true) { "宿主尚未授权此消息发送" }
        val store = engine.store
        val settings = store.settings()
        check(verified && settings.optBoolean("oneBotEnabled") && settings.optBoolean("networkEnabled") && !settings.optBoolean("paused")) { "消息桥未连接或助手已暂停" }
        val conversation = store.get("conversation", task.getString("scope")) ?: error("会话不存在")
        check(conversation.optString("route") == "onebot" && task.optString("account") == settings.optString("oneBotAccount") && conversation.optString("account") == task.optString("account")) { "账号或会话绑定已变化" }
        store.claimSend(task.getString("id"), CompanionPolicy.draftDigest(task))
        ownSendAt[task.getString("scope")] = System.currentTimeMillis()
        val ids = mutableListOf<String>()
        var segments = listOf(JSONArray())
        try {
            // A longer reply goes out as a few messages with typing-like pauses (AstrBot's segmented reply);
            // emoji markers become faces, stickers go out as their own picture messages.
            val source = stickerSource
            segments = io.deepseekharness.mobile.companion.chat.ReplySegmenter.split(task.getString("body"))
                .flatMap { io.deepseekharness.mobile.companion.chat.OutgoingSegments.build(it) { name -> source?.invoke(name) } }
            if (segments.isEmpty()) return store.transitionTask(task.getString("id"), setOf("sending"), "failed", "回复里只有发不出去的表情标记，没有可发的内容")
                ?: store.get("task", task.getString("id"))!!
            for ((index, segment) in segments.withIndex()) {
                if (index > 0) Thread.sleep(io.deepseekharness.mobile.companion.chat.ReplySegmenter.pauseMs(textOf(segment)))
                check(verified && !store.settings().optBoolean("paused")) { "连接已断开或已暂停" }
                fun submit(): JSONObject? {
                    val echo = CompanionPolicy.id()
                    ownSendAt[task.getString("scope")] = System.currentTimeMillis()
                    val wait = Pending(); pending[echo] = wait
                    try {
                        check(socket?.send(OneBotProtocol.send(conversation, segment, echo).toString()) == true) { "连接未接受提交" }
                        wait.latch.await(15, TimeUnit.SECONDS)
                    } finally { pending.remove(echo) }
                    return wait.response
                }
                val segmentStart = System.currentTimeMillis()
                var ack = submit()
                var id = ack?.optJSONObject("data")?.optString("message_id").orEmpty()
                if (ack == null) {
                    // No answer in 15 s. If QQ already reported this text as sent, it went out; otherwise try once more.
                    val seen = selfEchoSince(task.getString("scope"), segmentStart, textOf(segment))
                    if (seen != null) { ack = JSONObject().put("retcode", 0).put("status", "ok"); id = seen.ifBlank { "unconfirmed-" + CompanionPolicy.id() } }
                    else { XLog.i("send: no ack in 15 s and not seen as sent; retrying once"); ack = submit(); id = ack?.optJSONObject("data")?.optString("message_id").orEmpty() }
                }
                if (ack == null || ack.optInt("retcode", -1) != 0 || ack.optString("status") != "ok" || id.isBlank()) {
                    val state = if (ack != null && ids.isEmpty() && (ack.optInt("retcode", -1) != 0 || ack.optString("status") != "ok")) "failed" else "unknown"
                    val said = ack?.let { (it.optString("wording").ifBlank { it.optString("message") }).take(80) }.orEmpty()
                    if (ids.isNotEmpty()) rememberMine(task.getString("scope"), ids)
                    return store.transitionTask(task.getString("id"), setOf("sending", "unknown"), state,
                        if (state == "failed") "QQ 拒绝了发送（retcode ${ack?.optInt("retcode")}${if (said.isNotBlank()) "：$said" else ""}）"
                        else if (ack == null) "已发出 ${ids.size}/${segments.size} 段，后面一段等了两次都没收到 QQ 确认"
                        else "已发出 ${ids.size}/${segments.size} 段，后面的结果不确定，未自动重发",
                        JSONObject().put("platformMessageIds", org.json.JSONArray(ids))) ?: error("任务状态已变化")
                }
                ids.add(id)
            }
            rememberMine(task.getString("scope"), ids)
            ownSendAt[task.getString("scope")] = System.currentTimeMillis()
            return store.transitionTask(task.getString("id"), setOf("sending", "unknown"), "done", "已发出 ${segments.size} 段（不等于对方已读）",
                JSONObject().put("platformMessageId", ids.first()).put("platformMessageIds", org.json.JSONArray(ids))) ?: error("任务状态已变化")
        } catch (e: Exception) {
            return store.transitionTask(task.getString("id"), setOf("sending"), "unknown", "提交过程被中断（${e.message?.take(60) ?: "未知原因"}；已发出 ${ids.size}/${segments.size} 段），不自动重发") ?: store.get("task", task.getString("id"))!!
        }
    }
    companion object {
        private val EXTRAS = setOf("fetch_ptt_text", "set_msg_emoji_like", "set_input_status", "send_poke", "mark_msg_as_read")
        @Volatile private var instance: OneBotBridge? = null
        fun get(context: Context) = instance ?: synchronized(this) { instance ?: OneBotBridge(context.applicationContext).also { instance = it } }
    }
}
