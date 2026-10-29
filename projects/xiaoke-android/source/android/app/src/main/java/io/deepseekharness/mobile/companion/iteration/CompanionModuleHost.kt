package io.deepseekharness.mobile.companion.iteration

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONObject

/** Process-wide bridge; no Activity or WebView ownership of durable modules. */
class CompanionModuleHost(context: Context, private val engine: CompanionEngine, private val store: CompanionStore) : HostPorts {
    val router = ModuleRouter()
    private val eventWorker = java.util.concurrent.Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "companion-events").apply { isDaemon = true } }
    private val seenEvents = linkedSetOf<String>()
    val localOwner = CommandPrincipal("local-device-owner", "owner_ui", CompanionPolicy.OWNER, true)
    val device = io.deepseekharness.mobile.companion.devicev2.DeviceModule(context, this)
    val social = io.deepseekharness.mobile.companion.social.SocialModule(context, store, this)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val connectionWatcher = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refreshConnectivity() }
        override fun onLost(network: Network) { refreshConnectivity() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { refreshConnectivity() }
    }
    init {
        router.register(device); router.register(social)
        connectivity.registerDefaultNetworkCallback(connectionWatcher)
        refreshConnectivity()
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
    override fun emit(topic: String, payload: JSONObject) {
        router.signal(payload.optString("domain", "all"), topic)
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
        eventWorker.execute {
            val dedup = "$type\u0000$scope\u0000$source\u0000${CompanionPolicy.digest(event.data.toString())}"
            if (seenEvents.add(dedup)) {
                while (seenEvents.size > 2000) seenEvents.remove(seenEvents.first())
                router.event(event)
            }
        }
    }
    override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String = engine.enqueueModuleRequest(request, onEvent)
    override fun cancelModel(requestId: String) = engine.cancelModuleRequest(requestId)

    /** Host activation gate. Modules additionally validate current account, exact approval and control lease. */
    override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal): JSONObject {
        val type = effect.optString("type")
        val target = effect.optString("target")
        val settings = store.settings()
        fun decision(allowed: Boolean, reason: String = "") = JSONObject().put("allowed", allowed).put("reason", reason)
        if (target.isBlank()) return decision(false, "缺少明确目标")
        if (effect.optString("scope", principal.scope) != principal.scope) return decision(false, "请求会话与可信身份不符")
        if (type in setOf("phone_input", "camera")) {
            if (!principal.owner) return decision(false, "只有主人可授权设备控制或摄像头")
            if (type == "camera" && principal.channel != "owner_ui") return decision(false, "摄像头需要本机明确开启")
            return decision(true) // Device module checks exact session, generation, permission and foreground target.
        }
        if (type !in setOf("social_send", "public_post", "public_comment", "add_friend", "join_group", "download", "like", "follow", "block", "leave_group"))
            return decision(false, "未知外部动作")
        if (!settings.optBoolean("networkEnabled") || settings.optBoolean("paused")) return decision(false, "联网关闭或助手已暂停")
        if (type == "social_send") {
            if (!settings.optBoolean("oneBotEnabled")) return decision(false, "QQ 独立通道未启用")
        } else if (!settings.optBoolean("activityEnabled")) return decision(false, "外部活动尚未由主人开启")
        if (!principal.owner && principal.channel != "background" && type != "social_send")
            return decision(false, "朋友请求需先进入主人设定的活动与审批队列")
        return decision(true) // No approval is created here; a changed/expired module grant still fails.
    }
}
