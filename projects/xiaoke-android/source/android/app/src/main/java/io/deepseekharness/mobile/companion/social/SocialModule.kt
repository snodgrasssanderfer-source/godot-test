package io.deepseekharness.mobile.companion.social

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionSecrets
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.OneBotBridge
import io.deepseekharness.mobile.companion.QqNotificationBridge
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.BilibiliReader
import io.deepseekharness.mobile.companion.NightQuiet
import io.deepseekharness.mobile.companion.life.BudgetLedger
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEndpoint
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SocialModule(context: Context, private val store: CompanionStore, private val ports: HostPorts) : ModuleEndpoint {
    override val domain = "social"
    private val app = context.applicationContext
    private val vault = SocialVault(app)
    private val ledger = SocialLedger(vault.load(), vault::persist)
    private val bridge = OneBotBridge.get(app)
    private val syncToken = CompanionSecrets(app, "social-sync-token")
    private val syncKey = CompanionSecrets(app, "social-sync-key")
    private val biliSecret = CompanionSecrets(app, "social-bilibili")
    private val biliReader = BilibiliReader()
    private val biliActions = BilibiliActions({ String(Base64.getDecoder().decode(biliSecret.read()), Charsets.UTF_8) }, ::networkPermitted)
    private val biliDownload = BilibiliDownload()
    private val runtime = SocialRuntime(ledger, ports, SocialTransport { account, method, params ->
        if (method.startsWith("bili.")) {
            check(biliState().optBoolean("connected")) { "请先在本机核对B站账号" }
            if (method == "bili.download") downloadVideo(account, params) else biliActions.execute(account, method, params)
        } else bridge.callAction(account, method, params)
    }, accountState = {
        bridge.state().put("account", store.settings().optString("oneBotAccount")).put("route", "onebot")
    }, deleteMedia = vault::delete, targetAllowed = { action ->
        if (action.optString("kind") == "reply") {
            val conversation = store.get("conversation", action.getString("scope"))
            conversation != null && conversation.optString("account") == action.optString("account") &&
                conversation.optString("targetId") == action.optString("target") && conversation.optString("targetType") == action.getJSONObject("payload").optString("targetType") &&
                conversation.optBoolean("captureEnabled") && conversation.optString("route") == "onebot" && conversation.optBoolean("reliable")
        } else true
    }, networkAllowed = ::networkPermitted, platformState = { platform -> if (platform == "bilibili") biliState() else bridge.state() },
        ownerPrivate = store::ownerPrivate)
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "xiaoke-social-background").apply { isDaemon = true } }
    private val effectWorker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "xiaoke-social-effects").apply { isDaemon = true } }
    private val syncWorker = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "xiaoke-event-sync").apply { isDaemon = true } }
    @Volatile private var lastError = ""
    @Volatile private var syncClient: SocialSync? = null
    @Volatile private var syncTransport: HttpsSyncTransport? = null
    @Volatile private var closing = false
    private val syncing = AtomicBoolean(false)
    private fun networkPermitted() = !closing && store.settings().optBoolean("networkEnabled") && !store.settings().optBoolean("paused")
    private fun biliState() = ledger.read { root -> copy(rows(root, "bilibili")).put("route", "bilibili").put("platform", "bilibili")
        .put("credentialConfigured", biliSecret.configured()).put("connected", rows(root, "bilibili").optBoolean("enabled") && biliSecret.configured()) }
    init {
        val legacyGate: (JSONObject) -> Boolean = { task ->
            val current = store.get("task", task.optString("id"))
            if (current == null || !CompanionPolicy.canSend(current, System.currentTimeMillis()) || CompanionPolicy.draftDigest(task) != CompanionPolicy.draftDigest(current)) false
            else {
                // Authority is derived only from the native store's exact, unexpired owner grant.
                val principal = if (current.optBoolean("automaticGrant")) CommandPrincipal("companion", "background", current.getString("scope"), false)
                    else CommandPrincipal("owner", "owner_ui", "owner:local", true)
                ports.authorizeEffect(JSONObject().put("type", "social_send").put("target", current.getString("target"))
                    .put("scope", current.getString("scope")).put("contentDigest", CompanionPolicy.draftDigest(current)).put("requestId", current.getString("id")), principal).optBoolean("allowed")
            }
        }
        bridge.setEffectGate(legacyGate); QqNotificationBridge.setEffectGate(legacyGate)
        bridge.setModuleListener { type, event ->
            runCatching {
                runtime.onEvent(ModuleEvent(type, event.getString("scope"), event.getString("id"), event), notifyHost = true)
            }
                .onFailure { lastError = "部分社交事件未入账，请检查本机存储" }
        }
        timer.scheduleWithFixedDelay({
            runCatching { NightQuiet.evaluate(app, store) }
            runCatching { runtime.tick(processEffects = false) }.onFailure { lastError = "后台处理暂未完成，请查看任务状态" }
        }, 1, 5, TimeUnit.SECONDS)
        effectWorker.scheduleWithFixedDelay({ runCatching { runtime.tickEffects() }.onFailure { lastError = "社交动作暂未完成，请查看记录" } }, 1, 5, TimeUnit.SECONDS)
        syncWorker.scheduleWithFixedDelay({
            if (canSync()) runCatching { runSync() }
        }, 15, 60, TimeUnit.SECONDS)
    }
    override fun snapshot(principal: CommandPrincipal): JSONObject = runtime.snapshot(principal).also {
        it.getJSONObject("service").put("error", lastError)
        if (runCatching { SocialRules.owner(principal) }.isSuccess) it.getJSONObject("sync")
            .put("tokenConfigured", syncToken.configured()).put("keyConfigured", syncKey.configured())
        if (runCatching { SocialRules.owner(principal) }.isSuccess) {
            it.put("bilibili", biliState()); it.getJSONArray("accounts").put(biliState())
            it.getJSONObject("service").put("qqActiveAdd", JSONObject().put("supported", false).put("reason", "当前 NapCat 协议只处理实际收到的好友请求和群邀请，不提供主动申请接口"))
        }
        if (runCatching { SocialRules.owner(principal) }.isSuccess) it.getJSONObject("service").put("conversations", JSONArray(objects(store.list("conversation"))
            .filter { c -> c.optString("route") == "onebot" }.map { c -> JSONObject().put("id", c.getString("id")).put("title", c.optString("title"))
                .put("images", c.optBoolean("allowImages")).put("audio", c.optBoolean("allowAudio")).put("video", c.optBoolean("allowVideo")) }))
    }
    override fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject = when (action) {
        "bili.configure" -> configureBilibili(data, principal)
        "bili.inspect" -> inspectBilibili(data, principal)
        "bili.plan" -> planBilibili(data, principal)
        "sync.configure" -> configureSync(data, principal)
        "sync.run" -> {
            SocialRules.owner(principal)
            runSync()
        }
        "media.import" -> importMedia(data, principal)
        "social.conversationMedia" -> {
            SocialRules.owner(principal)
            val conversation = store.get("conversation", data.getString("scope")) ?: error("会话不存在")
            require(conversation.optString("route") == "onebot" && conversation.optBoolean("reliable"))
            store.put("conversation", conversation.put("allowImages", data.getBoolean("images")).put("allowAudio", data.getBoolean("audio")).put("allowVideo", data.getBoolean("video")))
            ports.emit("moduleState", JSONObject().put("domain", domain)); JSONObject().put("saved", true)
        }
        "social.feeds" -> {
            SocialRules.owner(principal)
            val account = store.settings().getString("oneBotAccount")
            val params = JSONObject(); if (data.optString("cursor").isNotBlank()) params.put("cursor", data.getString("cursor"))
            val result = bridge.callAction(account, "get_qzone_public_feeds", params)
            check(result.optInt("retcode", -1) == 0 && result.optString("status") == "ok") { "空间动态不可用" }
            val body = result.getJSONObject("data"); val feeds = objects(body.optJSONArray("feeds") ?: JSONArray()).take(40)
            val items = ledger.change { root -> feeds.map { feed ->
                val item = JSONObject().put("id", "feed-" + hash(canonical(feed))).put("account", account).put("feed", feed)
                rows(root, "feeds").put(item.getString("id"), item)
                JSONObject().put("id", item.getString("id")).put("summary", feed.toString().take(2000))
            } }
            JSONObject().put("items", JSONArray(items)).put("nextCursor", body.opt("next_cursor"))
        }
        "social.forward" -> {
            SocialRules.owner(principal)
            val scope = data.getString("scope")
            val conversation = store.get("conversation", scope) ?: error("会话不存在")
            check(conversation.optString("route") == "onebot" && conversation.optBoolean("captureEnabled"))
            val forwardId = SocialRules.bounded(data.getString("id"), 256)
            // The ID must have been observed in this exact conversation, not supplied as an arbitrary lookup.
            val source = ledger.read { root -> values(rows(root, "events")).firstOrNull { e ->
                e.optString("scope") == scope && objects(e.optJSONObject("data")?.optJSONArray("attachments") ?: JSONArray())
                    .any { it.optString("kind") == "forward" && it.optString("platformId") == forwardId } } } ?: error("此会话没有该转发记录")
            val result = bridge.callAction(conversation.getString("account"), "get_forward_msg", JSONObject().put("id", forwardId))
            check(result.optInt("retcode", -1) == 0 && result.optString("status") == "ok") { "转发记录已过期或不可读" }
            val segments = JSONArray().put(JSONObject().put("type", "forward").put("data", JSONObject().put("content", result.getJSONObject("data").getJSONArray("messages"))))
            val parsed = OneBotSegments.parse(segments, conversation.getString("account"))
            runtime.onEvent(ModuleEvent("conversation.message", scope, "forward-" + hash(source.getString("id") + forwardId), parsed.put("sourceIds", JSONArray().put(source.getString("sourceId")))), notifyHost = true)
            parsed
        }
        else -> runtime.execute(action, data, principal)
    }
    private fun platformPrincipal(principal: CommandPrincipal) {
        require(runCatching { SocialRules.owner(principal) }.isSuccess || principal.channel == "background" && principal.actorId == "companion" && principal.scope == "owner:local") { "群聊和朋友不能修改B站账号活动" }
    }
    private fun configureBilibili(data: JSONObject, principal: CommandPrincipal): JSONObject {
        SocialRules.owner(principal); require(principal.channel == "owner_ui") { "账号凭据只能在本机设置" }
        val enabled = data.getBoolean("enabled")
        if (!enabled) {
            ledger.change { rows(it, "bilibili").put("enabled", false).put("status", "已停用") }
            biliActions.cancel(); biliDownload.cancel()
            if (data.optBoolean("clearCredential")) biliSecret.clear()
        } else {
            check(networkPermitted()) { "请先开启联网" }
            check(ledger.read { root -> values(rows(root, "actions")).none { it.optString("platform") == "bilibili" && it.optString("state") == "submitting" } }) { "有B站操作正在执行，请先等待结果" }
            val raw = data.optString("cookie").ifBlank { String(Base64.getDecoder().decode(biliSecret.read()), Charsets.UTF_8) }
            val credentials = BiliCredential.parse(raw)
            val encoded = Base64.getEncoder().encodeToString(credentials.cookie.toByteArray(Charsets.UTF_8))
            require(encoded.length <= 4096) { "必要 Cookie 字段超过本机加密槽上限" }
            val identity = biliActions.verify(credentials.cookie)
            biliSecret.save(encoded)
            ledger.change { root ->
                values(rows(root, "actions")).filter { it.optString("platform") == "bilibili" && it.optString("account") != identity.getString("account") }
                    .forEach { if (it.optString("state") in setOf("pending", "ready", "approved")) it.put("state", "expired"); it.remove("grant"); it.remove("grantPrincipal") }
                root.put("bilibili", identity.put("enabled", true).put("verifiedAt", System.currentTimeMillis()).put("status", "本机账号已核对；每次写入前再次核对"))
            }
        }
        ports.emit("moduleState", JSONObject().put("domain", domain)); return biliState()
    }
    private fun inspectBilibili(data: JSONObject, principal: CommandPrincipal): JSONObject {
        platformPrincipal(principal); check(networkPermitted()) { "联网已关闭" }
        if (runCatching { SocialRules.owner(principal) }.isFailure) {
            // Autonomous browsing: never at night or on an empty activity pool, and only topics grounded in chat or recorded interests.
            check(!NightQuiet.active(store)) { "夜间安静，不浏览B站" }
            check(!BudgetLedger(store.lifeState("budget")).status().optBoolean("stopActivity")) { "活动额度已停，暂停浏览" }
            val topics = data.optJSONArray("sourceIds") ?: JSONArray()
            check(topics.length() > 0 && (0 until topics.length()).all { i ->
                store.get("message", topics.getString(i))?.optString("scope") == CompanionPolicy.OWNER || store.get("interest", topics.getString(i)) != null
            }) { "只浏览聊天中提到的话题或已记录的兴趣，不从首页推荐自由探索" }
        }
        val videoId = BilibiliActions.videoId(data.getString("videoId"))
        val info = biliReader.video(videoId)
        val key = "bili-resource-" + hash(listOf(info.getString("videoId"), info.getString("aid"), info.getLong("cid"), info.getString("upMid")).joinToString(":"))
        val item = JSONObject().put("id", key).put("sourceId", "bili-video-$videoId").put("sourceIds", JSONArray())
        listOf("videoId", "aid", "cid", "upMid", "upName", "title", "page", "durationSeconds").forEach { item.put(it, info.get(it)) }
        item.put("title", item.getString("title").take(400)).put("upName", item.optString("upName").take(160))
        item.put("meaning", "标题和简介只用于核对操作目标，不代表已观看视频")
        ledger.change { root -> check(!SocialRules.blocked(root, item)) { "该视频资料已删除" }; rows(root, "biliResources").put(key, item) }
        return copy(item)
    }
    private fun planBilibili(data: JSONObject, principal: CommandPrincipal): JSONObject {
        platformPrincipal(principal)
        val kind = data.getString("kind"); require(kind in setOf("like", "follow", "comment", "download"))
        val state = biliState(); check(state.optBoolean("connected")) { "请先核对并启用B站账号" }
        val resource = ledger.read { root -> rows(root, "biliResources").getJSONObject(data.getString("resourceId")) }
        val payload = JSONObject().put("resourceId", resource.getString("id"))
        if (kind == "download") payload.put("maxBytes", data.optInt("maxBytes", 16 * 1024 * 1024))
        val content = when (kind) { "like" -> "点赞视频：" + resource.getString("title"); "follow" -> "关注UP主：" + resource.getString("upName")
            "download" -> "保存公开首P单文件 MP4：" + resource.getString("title") + "；最多 " + payload.getInt("maxBytes") + " 字节"
            else -> data.getString("content") }
        return runtime.execute("social.plan", JSONObject().put("kind", "bili_$kind").put("account", state.getString("account"))
            .put("target", resource.getString(if (kind == "follow") "upMid" else "videoId")).put("scope", "owner:local")
            .put("idempotencyKey", data.getString("idempotencyKey")).put("motivation", SocialRules.bounded(data.getString("motivation"), 1000))
            .put("sourceIds", data.optJSONArray("sourceIds") ?: JSONArray()).put("content", content).put("payload", payload), principal)
    }
    private fun downloadVideo(account: String, params: JSONObject): JSONObject {
        val actionId = params.getString("actionId"); val mediaId = "media-" + hash(actionId); val blobId = "blob-" + hash(actionId)
        fun current() = networkPermitted() && biliState().let { it.optBoolean("connected") && it.optString("account") == account } &&
            ledger.read { root -> rows(root, "actions").optJSONObject(actionId)?.let { it.optString("state") == "submitting" && !SocialRules.blocked(root, it) } == true }
        check(current()) { "下载授权已变化" }
        val stream = biliReader.publicDownloadSource(params)
        val maximum = params.getInt("maxBytes"); require(stream.optLong("size", -1) <= maximum) { "视频超过批准的下载上限" }
        val bytes = biliDownload.read(stream.getString("url"), maximum, ::current)
        check(current()) { "下载授权已变化" }; vault.write(blobId, bytes)
        try {
            ledger.change { root ->
                val action = rows(root, "actions").getJSONObject(actionId)
                check(action.optString("state") == "submitting" && !SocialRules.blocked(root, action)) { "下载来源已删除" }
                rows(root, "media").put(mediaId, JSONObject().put("id", mediaId).put("sourceId", actionId).put("sourceIds", params.getJSONArray("sourceIds"))
                    .put("scope", "owner:local").put("kind", "video").put("name", params.getString("title").take(140) + ".mp4")
                    .put("mime", "video/mp4").put("bytes", bytes.size).put("blobId", blobId).put("status", "cached").put("retention", "explicit").put("expiresAt", 0))
            }
        } catch (error: Throwable) { vault.delete(blobId); throw error }
        return JSONObject().put("status", "ok").put("retcode", 0).put("data", JSONObject().put("mediaId", mediaId))
    }
    private fun configureSync(data: JSONObject, principal: CommandPrincipal): JSONObject {
        SocialRules.owner(principal)
        check(!syncing.get() || !data.getBoolean("enabled")) { "同步正在执行，请结束后再修改配对配置" }
        if (!data.getBoolean("enabled")) syncTransport?.cancel()
        val previous = ledger.read { rows(it, "sync") }
        val enabled = data.getBoolean("enabled")
        val endpoint = data.optString("endpoint", previous.optString("endpoint"))
        if (enabled) HttpsSyncTransport.validatedEndpoint(endpoint)
        val nextKey = data.optString("key"); val nextToken = data.optString("token")
        if (nextKey.isNotBlank()) SyncCipher.decodeKey(nextKey)
        if (nextToken.isNotBlank()) require(nextToken.length in 16..4096 && nextToken.none(Char::isWhitespace)) { "同步口令格式无效" }
        if (enabled) check((nextKey.isNotBlank() || syncKey.configured()) && (nextToken.isNotBlank() || syncToken.configured())) { "启用同步前需要密钥和认证口令" }
        // Credentials are never in snapshot, outbox, exported events or task records.
        if (nextKey.isNotBlank()) syncKey.save(nextKey)
        if (nextToken.isNotBlank()) syncToken.save(nextToken)
        val changedKey = syncKey.configured() && previous.optString("vault") != hash(syncKey.read()).take(32)
        val result = ledger.change { root ->
            val sync = rows(root, "sync")
            if (changedKey || endpoint != previous.optString("endpoint")) {
                sync.put("cursor", 0).put("generation", 0); root.put("sealed", JSONObject())
                values(rows(root, "events")).forEach { it.put("pendingSync", true) }
                values(rows(root, "tombstones")).forEach { it.put("acked", false) }
            }
            sync.put("enabled", enabled).put("endpoint", endpoint)
            if (syncKey.configured()) sync.put("vault", hash(syncKey.read()).take(32))
            copy(sync)
        }
        synchronized(this) { syncClient = null; syncTransport = null }
        ports.emit("moduleState", JSONObject().put("domain", domain)); return result
    }
    private fun canSync() = !closing && ledger.read { rows(it, "sync").optBoolean("enabled") } &&
        store.settings().optBoolean("networkEnabled") && syncToken.configured() && syncKey.configured()
    private fun runSync(): JSONObject {
        check(syncing.compareAndSet(false, true)) { "同步正在执行" }
        try {
            check(canSync()) { "请先完成同步配对并开启联网" }
            val config = ledger.read { rows(it, "sync") }
            val client = synchronized(this) {
                syncClient ?: run {
                    val transport = HttpsSyncTransport(config.getString("endpoint"), syncToken::read)
                    syncTransport = transport
                    SocialSync(runtime, SyncCipher(SyncCipher.decodeKey(syncKey.read())), transport, ::canSync).also { syncClient = it }
                }
            }
            return client.run()
        } finally { syncing.set(false); ports.emit("moduleState", JSONObject().put("domain", domain)) }
    }
    private fun importMedia(data: JSONObject, principal: CommandPrincipal): JSONObject {
        SocialRules.owner(principal)
        val encoded = data.getString("base64"); require(encoded.length <= 8 * 1024 * 1024)
        val bytes = Base64.getDecoder().decode(encoded); require(bytes.size in 1..6 * 1024 * 1024)
        val mime = data.getString("mime"); require(mime in setOf("image/jpeg", "image/png", "image/webp", "image/gif", "audio/mpeg", "audio/wav", "video/mp4", "application/pdf"))
        val retention = data.optString("retention", "transient"); require(retention in setOf("transient", "curated", "explicit"))
        val mediaId = "media-" + id(); val blobId = "blob-" + id(); val source = "import-" + id()
        vault.write(blobId, bytes)
        try {
            val result = ledger.change { root -> JSONObject().put("id", mediaId).put("scope", "owner:local").put("sourceId", source)
                .put("sourceIds", JSONArray().put(source)).put("name", data.optString("name").take(160)).put("mime", mime).put("bytes", bytes.size)
                .put("kind", mime.substringBefore('/')).put("blobId", blobId).put("status", "cached").put("retention", retention)
                .put("expiresAt", if (retention == "transient") System.currentTimeMillis() + 24 * 60 * 60_000 else 0)
                .also { rows(root, "media").put(mediaId, it) } }
            ports.emit("moduleState", JSONObject().put("domain", domain)); return result
        } catch (error: Throwable) { vault.delete(blobId); throw error }
    }
    override fun onEvent(event: ModuleEvent) {
        runtime.onEvent(event)
        if (event.type == "connectivity.changed" && event.data.optBoolean("online") && canSync()) syncWorker.execute { runCatching { runSync() } }
    }
    /** A can call before encrypted backup; no raw secret or approval grants are returned. */
    fun exportBackup(): JSONObject = ledger.exportEncryptedInput().also { root ->
        values(rows(root, "actions")).forEach { it.remove("grant"); it.remove("grantPrincipal"); if (it.optString("state") == "approved") it.put("state", "pending") }
        root.remove("sync"); root.remove("sealed"); root.remove("bilibili")
        // Blob bytes are not silently included. The owner can reimport explicit assets on another device.
        values(rows(root, "media")).forEach { it.remove("blobId"); it.put("status", "bytes_not_in_backup") }
    }
    fun restoreBackup(data: JSONObject, principal: CommandPrincipal): JSONObject = runtime.restoreBackup(data, principal)
    fun pendingApprovalIds(): List<String> = runtime.pendingApprovalIds()
    override fun close() { closing = true; timer.shutdownNow(); effectWorker.shutdownNow(); syncWorker.shutdownNow(); syncTransport?.cancel(); biliActions.cancel(); biliDownload.cancel(); bridge.setModuleListener(null); bridge.setEffectGate(null); QqNotificationBridge.setEffectGate(null); runtime.close() }
}
