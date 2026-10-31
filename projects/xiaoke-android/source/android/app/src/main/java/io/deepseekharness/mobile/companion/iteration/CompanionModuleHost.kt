package io.deepseekharness.mobile.companion.iteration

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONObject
import org.json.JSONArray

/** Process-wide bridge; no Activity or WebView ownership of durable modules. */
class CompanionModuleHost(context: Context, private val engine: CompanionEngine, private val store: CompanionStore) : HostPorts {
    val router = ModuleRouter()
    private val eventWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "companion-events").apply { isDaemon = true } }
    private val seenEvents = linkedSetOf<String>()
    private val lastContentEpoch = java.util.concurrent.atomic.AtomicLong(-1)
    val localOwner = CommandPrincipal("local-device-owner", "owner_ui", CompanionPolicy.OWNER, true)
    val life = io.deepseekharness.mobile.companion.life.LifeModule(context, store, this)
    val device = io.deepseekharness.mobile.companion.devicev2.DeviceModule(context, this, store)
    val social = io.deepseekharness.mobile.companion.social.SocialModule(context, store, this)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val connectionWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refreshConnectivity() }
        override fun onLost(network: Network) { refreshConnectivity() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshConnectivity() }
    }
    private val proactiveShare = ProactiveShareRunner(store, this)
    val biliWatcher = io.deepseekharness.mobile.companion.BilibiliWatcher(context, store, this)
    private val nightlyDiary = NightlyDiary(store, this)
    val learner = io.deepseekharness.mobile.companion.chat.ChatLearner(store, this)
    val profiles = io.deepseekharness.mobile.companion.memory.Profiles(store)
    val memoryIndex = io.deepseekharness.mobile.companion.memory.MemoryIndex(store, io.deepseekharness.mobile.companion.memory.Embeddings(context.applicationContext, store, engine.secrets))
    private val consolidator = io.deepseekharness.mobile.companion.memory.MemoryConsolidator(store, this, memoryIndex, profiles)
    private val clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "companion-clock").apply { isDaemon = true } }
    private val unlockWatcher = object : android.content.BroadcastReceiver() {
        override fun onReceive(receiver: Context, intent: android.content.Intent) {
            // The unlock that ends night quiet is evaluated first, so quiet hours never trigger a share.
            eventWorker.execute { runCatching { io.deepseekharness.mobile.companion.NightQuiet.evaluate(receiver, store); proactiveShare.ownerReturned() } }
        }
    }
    init {
        router.register(life); router.register(device); router.register(social)
        androidx.core.content.ContextCompat.registerReceiver(context.applicationContext, unlockWatcher,
            android.content.IntentFilter(android.content.Intent.ACTION_USER_PRESENT), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        connectivity.registerDefaultNetworkCallback(connectionWatcher)
        clock.scheduleWithFixedDelay({ runCatching { nightlyDiary.tick() }; runCatching { consolidator.tick() }; memoryIndex.refresh(); runCatching { biliWatcher.tick() } }, 1, 2, java.util.concurrent.TimeUnit.MINUTES)
        refreshConnectivity()
        contentChanged()
    }
    fun refreshConnectivity() {
        eventWorker.execute {
            val settings = store.settings()
            val active = connectivity.activeNetwork
            val online = settings.optBoolean("networkEnabled") && !settings.optBoolean("paused") && active != null &&
                connectivity.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            router.event(ModuleEvent("connectivity.changed", CompanionPolicy.OWNER, "connection-${System.nanoTime()}", JSONObject().put("online", online)))
        }
    }

    fun principal(scope: String): CommandPrincipal {
        CompanionPolicy.scope(scope)
        if (scope == CompanionPolicy.OWNER) return localOwner
        val conversation = store.get("conversation", scope)
        val settings = store.settings()
        val verified = conversation?.optString("route") == "onebot" && conversation.optBoolean("reliable") &&
            conversation.optString("account").isNotBlank() && conversation.optString("account") == settings.optString("oneBotAccount")
        val owner = verified && conversation?.optString("targetType") == "private" &&
            settings.optString("ownerQqId").isNotBlank() && conversation.optString("targetId") == settings.optString("ownerQqId")
        val channel = if (owner) "owner_qq" else if (scope.startsWith("qq:group:")) "group_qq" else "friend_qq"
        return CommandPrincipal(if (verified) conversation!!.optString("targetId") else "unresolved", channel, scope, owner)
    }
    /** Also called by legacy content-management paths, so deletion reaches every output cache. */
    fun contentChanged() {
        val epoch = store.contentEpoch()
        if (lastContentEpoch.getAndSet(epoch) == epoch) return
        engine.contextChanged(store.removalEpoch())
        val deleted = store.tombstones()
        val ids = JSONArray()
        for (i in 0 until deleted.length()) ids.put(deleted.getJSONObject(i).getString("id"))
        // Cancellation and deletion propagation must precede a later outgoing platform claim.
        router.event(ModuleEvent("data.deleted", CompanionPolicy.OWNER, "content-$epoch", JSONObject().put("sourceIds", ids).put("contentEpoch", epoch)))
    }
    override fun emit(topic: String, payload: JSONObject) {
        router.signal(payload.optString("domain", "all"), topic)
        if (topic == "life.contentChanged") { contentChanged(); return }
        if (topic == "budget.changed") {
            // Group chat and browsing pause on their own; the owner hears about it at most once a day, never during night quiet.
            val stopped = payload.optBoolean("configured") && payload.optBoolean("stopActivity")
            val today = java.time.LocalDate.now().toString()
            if (stopped && !io.deepseekharness.mobile.companion.NightQuiet.active(store)) store.lifeTransaction {
                if (store.lifeState("activityBudgetNotice").optString("date") != today) {
                    store.saveLifeState("activityBudgetNotice", JSONObject().put("date", today))
                    store.message(CompanionPolicy.OWNER, "notice", "今天的活动额度快见底了，我先把群聊和看视频停了，留着额度陪你。", "本机预算账本")
                }
            }
            return
        }
        if (topic == "social.reminder" || topic == "social.job.completed") {
            recordJobOutcome(topic, JSONObject(payload.toString()))
            return
        }
        val eventTopic = when (topic) {
            "experience", "social.event" -> payload.optString("type")
            "data.deleted", "action.completed", "control.stopped", "observation.summary", "connectivity.changed" -> topic
            else -> return
        }
        val scope = CompanionPolicy.scope(payload.getString("scope"))
        val type = eventTopic
        val source = payload.getString("sourceId")
        require(type.matches(Regex("[a-z][a-zA-Z0-9.]{0,79}")) && source.length in 1..160)
        val event = ModuleEvent(type, scope, source, JSONObject((payload.optJSONObject("data") ?: JSONObject()).toString()))
        if (type == "data.deleted") { applyDeletion(event.data); return }
        if (topic == "observation.summary" && store.get("observation", source) == null && !store.isDeleted("observation", source)) {
            val summary = event.data.optString("summary", event.data.optString("text")).take(24000)
            if (summary.isNotBlank()) {
                store.put("observation", JSONObject().put("id", source).put("scope", scope).put("summary", summary).put("analysis", summary)
                    .put("analyzed", true).put("source", event.data.optString("source", "本次已授权观察的模型摘要"))
                    .put("coverage", "已授权临时片段的模型理解，属于可更正推断；原始媒体未长期保存")
                    .put("kind", "inference").put("createdAt", event.data.optLong("createdAt", System.currentTimeMillis())))
                event.data.put("meaningful", true)
            }
        }
        if (type == "action.completed" && event.data.optString("state") == "completed" && store.get("task", source) == null && !store.isDeleted("task", source)) {
            store.put("task", JSONObject().put("id", source).put("scope", scope).put("type", "device_input").put("state", "done")
                .put("progress", "系统确认完成本次已授权手势；游戏内目标结果尚待后续观察").put("deviceReceipt", event.data))
        }
        if (topic == "social.event" && payload.optBoolean("replayed")) {
            acceptSynchronizedFact(event)
            return // Sync transfers facts only, never replays a model request or external action.
        }
        eventWorker.execute {
            val dedup = "$type\u0000$scope\u0000$source\u0000${CompanionPolicy.digest(event.data.toString())}"
            if (seenEvents.add(dedup)) {
                while (seenEvents.size > 2000) seenEvents.remove(seenEvents.first())
                router.event(event)
            }
        }
    }
    fun applyDeletion(data: JSONObject) {
        val raw = data.optJSONArray("sourceIds") ?: JSONArray()
        val incoming = data.optJSONArray("sourceHashes") ?: JSONArray()
        val saved = store.lifeState("socialDeletionHashes").optJSONArray("hashes") ?: JSONArray()
        val hashes = linkedSetOf<String>()
        for (array in listOf(saved, incoming)) for (i in 0 until array.length()) {
            val hash = array.getString(i); require(hash.matches(Regex("[a-f0-9]{64}"))); hashes.add(hash)
        }
        for (i in 0 until raw.length()) hashes.add(CompanionPolicy.digest(raw.getString(i)))
        store.lifeTransaction {
            store.saveLifeState("socialDeletionHashes", JSONObject().put("hashes", JSONArray(hashes.toList())))
            val roots = store.contentRecords().filter { (_, value) -> CompanionPolicy.digest(value.getString("id")) in hashes ||
                value.optString("syncSourceId").takeIf { it.isNotBlank() }?.let { CompanionPolicy.digest(it) in hashes } == true }
                .map { (kind, value) -> io.deepseekharness.mobile.companion.life.LifePolicy.reference(kind, value) }.toSet()
            if (roots.isNotEmpty()) store.deleteContent(roots)
        }
        contentChanged()
    }
    private fun acceptSynchronizedFact(event: ModuleEvent) {
        if (event.type !in setOf("conversation.message", "model.completed", "observation.summary")) return
        val id = "synced-" + CompanionPolicy.digest(event.type + "\u0000" + event.scope + "\u0000" + event.sourceId)
        val deletedHashes = store.lifeState("socialDeletionHashes").optJSONArray("hashes") ?: JSONArray()
        if ((0 until deletedHashes.length()).any { deletedHashes.getString(it) == CompanionPolicy.digest(event.sourceId) }) return
        if (store.get("message", id) != null || store.get("observation", id) != null || store.isDeleted("message", id) || store.isDeleted("observation", id)) return
        if (event.type == "observation.summary") {
            val summary = event.data.optString("summary", event.data.optString("text")).take(24000)
            if (summary.isBlank()) return
            store.put("observation", JSONObject().put("id", id).put("scope", event.scope).put("syncSourceId", event.sourceId)
                .put("summary", summary).put("analysis", summary).put("analyzed", true).put("source", "已配对设备的经历概要")
                .put("coverage", "同步的文字概要，不包含原始视听资料").put("createdAt", event.data.optLong("createdAt", System.currentTimeMillis())))
            life.onEvent(ModuleEvent("observation.summary", event.scope, id, JSONObject().put("meaningful", true)))
        } else {
            val text = event.data.optString("text").take(128000)
            if (text.isBlank()) return
            val role = event.data.optString("role", if (event.type == "model.completed") "assistant" else "user").takeIf { it in setOf("user", "assistant", "notice") } ?: "notice"
            store.put("message", JSONObject().put("id", id).put("scope", event.scope).put("syncSourceId", event.sourceId)
                .put("role", role).put("text", text).put("source", "已配对设备同步的可见交流")
                .put("createdAt", event.data.optLong("createdAt", System.currentTimeMillis())))
        }
        router.signal("all", "sync.fact")
    }
    private fun recordJobOutcome(topic: String, payload: JSONObject) {
        val id = payload.optString("id")
        if (id.isBlank()) return
        val scope = CompanionPolicy.scope(payload.optString("scope", CompanionPolicy.OWNER))
        val existing = store.get("task", id)
        if (existing?.optString("state") == "done" || store.isDeleted("task", id)) return
        val reminder = topic == "social.reminder"
        val state = if (reminder || payload.optString("state") == "done") "done" else "failed"
        val text = (if (reminder) payload.optString("text") else payload.optString("result", payload.optString("error"))).take(24000)
        val refs = JSONArray()
        val ids = payload.optJSONArray("sourceIds") ?: JSONArray()
        for (i in 0 until ids.length()) {
            val source = store.get("message", ids.getString(i)) ?: continue
            if (source.optString("scope") == scope) refs.put(JSONObject().put("kind", "message").put("id", source.getString("id")).put("scope", scope))
        }
        store.put("task", JSONObject().put("id", id).put("scope", scope).put("type", if (reminder) "reminder" else "summary")
            .put("state", state).put("progress", text).put("sourceRefs", refs))
        // The result goes back to the chat that asked (she sees it next turn), never into the diary: a reminder's
        // text is often private ("复查别跟别人说").
        io.deepseekharness.mobile.companion.chat.BackgroundJobs.record(store, scope, if (reminder) "reminder" else "summary",
            if (reminder) "提醒：${text.take(40)}" else "整理资料", state == "done", if (reminder) "已到时间提醒" else text.take(200))
        // A reminder is what the owner asked for at that time: it is delivered whatever the attention mode says.
        if (reminder && scope == CompanionPolicy.OWNER) store.message(scope, "notice", "提醒：$text", "已到期的本机提醒")
        else if (reminder && principal(scope).owner) runCatching { engine.remindOverQq(scope, "提醒：$text") }
            .onFailure { io.deepseekharness.mobile.companion.XLog.w("reminder over QQ failed: ${it.message}") }
        router.signal("all", "job.outcome")
    }
    /** Owner review list: social actions awaiting approval plus legacy send drafts. Used for the ball badge and one mention. */
    fun pendingApprovals(): List<String> {
        val drafts = store.list("task")
        val now = System.currentTimeMillis()
        return social.pendingApprovalIds() + (0 until drafts.length()).map(drafts::getJSONObject)
            .filter { it.optString("type") == "send" && it.optString("state") == "draft" }
            .filter { draft ->
                // QQ reply drafts expire like the social review items (3 days) instead of counting on the badge forever.
                if (now - draft.optLong("updatedAt") <= DRAFT_TTL_MS) true
                else { store.transitionTask(draft.getString("id"), setOf("draft"), "cancelled", "草稿放了 3 天没处理，已过期"); false }
            }.map { it.getString("id") }
    }
    private val DRAFT_TTL_MS = 3 * 86_400_000L
    fun activityStopped(): Boolean = io.deepseekharness.mobile.companion.life.BudgetLedger(store.lifeState("budget")).status().optBoolean("stopActivity")

    override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String = engine.enqueueModuleRequest(request, onEvent)
    override fun cancelModel(requestId: String) = engine.cancelModuleRequest(requestId)

    /** Host activation gate. Modules additionally validate current account, exact approval and control lease. */
    override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal): JSONObject {
        val type = effect.optString("type")
        val target = effect.optString("target")
        val settings = store.settings()
        fun decision(allowed: Boolean, reason: String = "") = JSONObject().put("allowed", allowed).put("reason", reason)
        if (target.isBlank()) return decision(false, "缺少明确目标")
        val localOwnerGrant = principal.owner && principal.channel == "owner_ui" && principal.scope == CompanionPolicy.OWNER
        if (!localOwnerGrant && principal.channel != "background" && effect.optString("scope", principal.scope) != principal.scope)
            return decision(false, "请求会话与可信身份不符")
        if (type in setOf("phone_input", "camera")) {
            if (!principal.owner) return decision(false, "只有主人可授权设备控制或摄像头")
            if (type == "camera" && principal.channel != "owner_ui") return decision(false, "摄像头需要本机明确开启")
            return decision(true) // Device module checks exact session, generation, permission and foreground target.
        }
        if (type !in setOf("social_send", "public_post", "public_comment", "add_friend", "join_group", "download", "like", "follow", "block", "leave_group"))
            return decision(false, "未知外部动作")
        if (!settings.optBoolean("networkEnabled") || settings.optBoolean("paused")) return decision(false, "联网关闭或助手已暂停")
        if (io.deepseekharness.mobile.companion.devicev2.EmergencyHold.sensitiveHeld) return decision(false, "急停中：主人说继续之后再发")
        val groupOrPublic = effect.optString("scope", principal.scope).startsWith("qq:group:") || type !in setOf("social_send", "add_friend", "block")
        if (!principal.owner && groupOrPublic && io.deepseekharness.mobile.companion.NightQuiet.active(store)) return decision(false, "夜间安静：不在群里发言或浏览B站")
        if (type == "social_send") {
            if (!settings.optBoolean("oneBotEnabled")) return decision(false, "QQ 独立通道未启用")
        } else if (!settings.optBoolean("activityEnabled")) return decision(false, "外部活动尚未由主人开启")
        if (!principal.owner && principal.channel != "background" && type != "social_send")
            return decision(false, "朋友请求需先进入主人设定的活动与审批队列")
        return decision(true) // No approval is created here; a changed/expired module grant still fails.
    }
}
