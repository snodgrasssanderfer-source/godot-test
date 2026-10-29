package io.deepseekharness.mobile.companion.social

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionSecrets
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.OneBotBridge
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
    private val runtime = SocialRuntime(ledger, ports, SocialTransport(bridge::callAction), accountState = {
        bridge.state().put("account", store.settings().optString("oneBotAccount")).put("route", "onebot")
    }, deleteMedia = vault::delete, targetAllowed = { action ->
        if (action.optString("kind") == "reply") {
            val conversation = store.get("conversation", action.getString("scope"))
            conversation != null && conversation.optString("account") == action.optString("account") &&
                conversation.optString("targetId") == action.optString("target") && conversation.optString("targetType") == action.getJSONObject("payload").optString("targetType") &&
                conversation.optBoolean("captureEnabled") && conversation.optString("route") == "onebot" && conversation.optBoolean("reliable")
        } else true
    })
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "xiaoke-social-background").apply { isDaemon = true } }
    @Volatile private var lastError = ""
    @Volatile private var syncClient: SocialSync? = null
    private val syncing = AtomicBoolean(false)
    init {
        bridge.setModuleListener { type, event ->
            runCatching {
                onEvent(ModuleEvent(type, event.getString("scope"), event.getString("id"), event))
                val evidence = copy(event)
                objects(evidence.optJSONArray("attachments") ?: JSONArray()).forEach { it.remove("url"); if (it.optString("kind") != "forward") it.remove("platformId") }
                evidence.remove("images")
                ports.emit("social.event", JSONObject().put("type", type).put("scope", event.getString("scope")).put("sourceId", event.getString("id")).put("data", evidence))
            }
                .onFailure { lastError = "部分社交事件未入账，请检查本机存储" }
        }
        timer.scheduleWithFixedDelay({ runCatching { runtime.tick() }.onFailure { lastError = "后台处理暂未完成，请查看任务状态" } }, 1, 5, TimeUnit.SECONDS)
    }
    override fun snapshot(principal: CommandPrincipal): JSONObject = runtime.snapshot(principal).also {
        it.getJSONObject("service").put("error", lastError)
        if (runCatching { SocialRules.owner(principal) }.isSuccess) it.getJSONObject("sync")
            .put("tokenConfigured", syncToken.configured()).put("keyConfigured", syncKey.configured())
        if (runCatching { SocialRules.owner(principal) }.isSuccess) it.getJSONObject("service").put("conversations", JSONArray(objects(store.list("conversation"))
            .filter { c -> c.optString("route") == "onebot" }.map { c -> JSONObject().put("id", c.getString("id")).put("title", c.optString("title"))
                .put("images", c.optBoolean("allowImages")).put("audio", c.optBoolean("allowAudio")).put("video", c.optBoolean("allowVideo")) }))
    }
    override fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject = when (action) {
        "sync.configure" -> configureSync(data, principal)
        "sync.run" -> {
            SocialRules.owner(principal)
            check(syncing.compareAndSet(false, true)) { "同步正在执行" }
            try {
            check(store.settings().optBoolean("networkEnabled")) { "请先开启联网" }
            val config = ledger.read { rows(it, "sync") }
            check(config.optBoolean("enabled") && syncToken.configured() && syncKey.configured()) { "请先完成同步配对" }
            val client = synchronized(this) {
                syncClient ?: SocialSync(runtime, SyncCipher(SyncCipher.decodeKey(syncKey.read())), HttpsSyncTransport(config.getString("endpoint"), syncToken::read)) {
                    ledger.read { rows(it, "sync").optBoolean("enabled") } && store.settings().optBoolean("networkEnabled")
                }.also { syncClient = it }
            }
            client.run().also { ports.emit("moduleState", JSONObject().put("domain", domain)) }
            } finally { syncing.set(false) }
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
            onEvent(ModuleEvent("conversation.message", scope, "forward-" + hash(source.getString("id") + forwardId), parsed.put("sourceIds", JSONArray().put(source.getString("sourceId")))))
            parsed
        }
        else -> runtime.execute(action, data, principal)
    }
    private fun configureSync(data: JSONObject, principal: CommandPrincipal): JSONObject {
        SocialRules.owner(principal)
        check(!syncing.get()) { "同步正在执行，请结束后再修改配对配置" }
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
        synchronized(this) { syncClient = null }
        ports.emit("moduleState", JSONObject().put("domain", domain)); return result
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
    override fun onEvent(event: ModuleEvent) = runtime.onEvent(event)
    /** A can call before encrypted backup; no raw secret or approval grants are returned. */
    fun exportBackup(): JSONObject = ledger.exportEncryptedInput().also { root ->
        values(rows(root, "actions")).forEach { it.remove("grant"); if (it.optString("state") == "approved") it.put("state", "pending") }
        root.remove("sync"); root.remove("sealed")
        // Blob bytes are not silently included. The owner can reimport explicit assets on another device.
        values(rows(root, "media")).forEach { it.remove("blobId"); it.put("status", "bytes_not_in_backup") }
    }
    override fun close() { timer.shutdownNow(); bridge.setModuleListener(null); runtime.close() }
}
