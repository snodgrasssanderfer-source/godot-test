package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import io.deepseekharness.mobile.companion.presence.PresenceModelJob
import io.deepseekharness.mobile.companion.iteration.CompanionModuleHost
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import io.deepseekharness.mobile.companion.iteration.ModelWorkQueue
import io.deepseekharness.mobile.companion.iteration.ModelAccounting
import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.ProviderHistoryPrivacy
import io.deepseekharness.mobile.companion.life.LifeMemoryRepository
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** One serialized queue; external messages never execute the owner's Android tools. */
class CompanionEngine private constructor(private val context: Context) {
    val store = CompanionStore(context)
    val secrets = CompanionSecrets(context).also { XLog.init(context) }
    private val web by lazy { WebResearch(store, model) }
    @Volatile private var activeWeb: Triple<JSONObject, JSONObject, WebResearch.Billing>? = null
    private val model = GeminiClient(secrets)
    private val worker = ModelWorkQueue()
    /** Claude turns run beside Gemini, not behind it: a long coding task must not hold up group replies. */
    private val claudeWorker = ModelWorkQueue()
    val claude by lazy { io.deepseekharness.mobile.companion.claude.ClaudeRuntime(context) }
    private val claudeTurns = ConcurrentHashMap<String, io.deepseekharness.mobile.companion.claude.ClaudeTurn>()
    val modules by lazy { CompanionModuleHost(context, this, store) }
    private val moduleRequests = ConcurrentHashMap<String, AtomicBoolean>()
    private val preempted = ConcurrentHashMap.newKeySet<String>()
    private val turnLatency = ConcurrentHashMap<String, TurnLatency>()
    private val modelLease = Any()
    @Volatile private var activeOwner = true
    @Volatile private var activeContextEpoch: Long? = null
    @Volatile var activeTask: String? = null
        private set
    init {
        store.recoverJobs()
        io.deepseekharness.mobile.companion.presence.PresenceContexts.access = object : io.deepseekharness.mobile.companion.presence.PresenceContextAccess {
            override fun snapshot(query: String) = store.contextSnapshot(CompanionPolicy.OWNER, query)
            override fun current(epoch: Long) = epoch == store.removalEpoch()
            override fun commit(ticket: JSONObject, mutation: () -> Unit) {
                store.commitContext(ticket.getLong("epoch"), ticket.getJSONArray("sourceRefs"), mutation)
            }
        }
    }

    fun submit(scope: String, text: String, source: String = "本机主人", observationId: String = "", actorId: String = ""): JSONObject {
        CompanionPolicy.scope(scope)
        // Only the owner's own chat counts; nobody in a group can lift a hold.
        if ((scope == CompanionPolicy.OWNER || modules.principal(scope).owner) && io.deepseekharness.mobile.companion.devicev2.EmergencyHold.isContinue(text))
            io.deepseekharness.mobile.companion.devicev2.DeviceModule.continueAfterHold()
        val userMessage = store.message(scope, "user", CompanionPolicy.text(text), source).let { message ->
            store.put("message", message.put("actorId", if (scope == CompanionPolicy.OWNER) "local-device-owner" else actorId))
        }
        modules.router.event(ModuleEvent("conversation.message", scope, userMessage.getString("id"), JSONObject(userMessage.toString()).apply { remove("wire") }))
        return respond(scope, userMessage.getString("id"), observationId)
    }

    // Follow-up merge (mechanism from AstrBot's pipeline/process_stage/follow_up.py): while a conversation already has a
    // chat turn queued or running, new messages join that turn instead of getting a second reply.
    private val busyScopes = HashMap<String, String>()
    private val followUps = HashMap<String, MutableList<Pair<String, String>>>()

    /** Answer a message that is already stored (QQ batches arrive this way), or fold it into the turn in progress. */
    /** [ownerAsked]: the owner is talking to her in a group; that turn is the owner's, billed and queued like one. */
    fun respond(scope: String, messageId: String, observationId: String = "", ownerAsked: Boolean = false): JSONObject {
        val latency = TurnLatency()
        CompanionPolicy.scope(scope)
        val task = synchronized(followUps) {
            busyScopes[scope]?.let { store.get("task", it) }?.takeIf { it.optString("state") in setOf("queued", "running") }?.let { busy ->
                followUps.getOrPut(scope) { mutableListOf() }.add(messageId to observationId)
                return busy
            }
            // The engine is fixed here, for this whole reply; a changed setting applies from the next message.
            // The owner speaking to her in a group is answered like the owner's own chat (Claude when it is on).
            val engine = io.deepseekharness.mobile.companion.claude.ClaudeChat.engineFor(store, scope, modules.principal(scope).owner || ownerAsked,
                store.get("conversation", scope), runCatching { claude.version() != null }.getOrDefault(false))
            store.put("task", JSONObject().put("scope", scope).put("type", "chat").put("state", "queued").put("engine", engine)
                .put("messageId", messageId).put("observationId", observationId).put("ownerAsked", ownerAsked).put("progress", "等待处理")).also { busyScopes[scope] = it.getString("id"); turnLatency[it.getString("id")] = latency }
        }
        val owner = modules.principal(scope).owner || ownerAsked
        if (task.optString("engine") == io.deepseekharness.mobile.companion.claude.ClaudeChat.ENGINE_CLAUDE) claudeWorker.submit(task.getString("id"), owner) { runChat(task.getString("id")) }
        else {
            if (owner) yieldActivityToOwner()
            worker.submit(task.getString("id"), owner) { runChat(task.getString("id")) }
        }
        return task
    }
    /** A reply for [scope] is queued or being written right now. */
    fun responding(scope: String): Boolean = synchronized(followUps) { busyScopes[scope]?.let { store.get("task", it)?.optString("state") in setOf("queued", "running") } == true }
    private fun takeFollowUps(scope: String): List<Pair<String, String>> = synchronized(followUps) { followUps.remove(scope).orEmpty() }
    private fun finishTurn(scope: String, id: String) {
        turnLatency.remove(id)
        val leftover = synchronized(followUps) {
            if (busyScopes[scope] == id) busyScopes.remove(scope)
            followUps.remove(scope).orEmpty()
        }
        // Arrived after her reply was saved: that is a new turn.
        leftover.lastOrNull()?.let { (message, observation) -> respond(scope, message, leftover.lastOrNull { it.second.isNotBlank() }?.second ?: observation) }
    }

    /** [reason] goes to the log: who stopped a turn should never be a guess. */
    fun cancel(id: String, reason: String = "主人点了停止") {
        XLog.i("turn $id stopped: $reason")
        worker.cancelQueued(id)
        claudeWorker.cancelQueued(id)
        claudeTurns[id]?.cancel(reason)
        io.deepseekharness.mobile.companion.claude.ClaudePermissions.dropTask(id)
        synchronized(modelLease) { if (id == activeTask) model.cancel() }
        store.cancelTask(id)
        turnLatency.remove(id)?.let { it.finish(); saveLatency(id, it) }
    }

    /** Pause, going offline and "cancel" also stop Claude turns, which run outside the Gemini lease. */
    fun cancelClaudeTurns(reason: String) = claudeTurns.keys.toList().forEach { cancel(it, reason) }

    /** What each running chat turn has read, so a deletion only stops the turns that actually used the deleted thing. */
    private val turnSources = ConcurrentHashMap<String, Pair<Long, JSONArray>>()
    /** Turns stopped because something they read was removed; they are answered again instead of failing. */
    private val removedContext = ConcurrentHashMap<String, String>()

    private fun beginModelTask(id: String, owner: Boolean = true) = synchronized(modelLease) {
        activeTask = id; activeOwner = owner; model.reset()
        if (!owner && worker.hasOwnerWaiting()) { preempted.add(id); model.cancel() }
    }
    private fun endModelTask(id: String) = synchronized(modelLease) { if (activeTask == id) { activeTask = null; activeContextEpoch = null }; preempted.remove(id) }
    private fun yieldActivityToOwner() = synchronized(modelLease) {
        if (!activeOwner) activeTask?.let { XLog.i("turn $it paused: the owner is waiting"); preempted.add(it); model.cancel() }
    }

    /**
     * Something was deleted, forgotten or restored. Presence and module requests (no source list) stop on any removal;
     * a chat turn stops only if one of its own sources is gone, and is then answered again without it.
     */
    fun contextChanged(epoch: Long) {
        synchronized(modelLease) {
            if (activeContextEpoch != null && activeContextEpoch != epoch) { XLog.i("presence/module request stopped: content removed"); model.cancel() }
        }
        for ((id, ticket) in turnSources) {
            if (ticket.first == epoch) continue
            val broken = synchronized(ticket.second) { store.contextBroken(ticket.second) } ?: continue
            XLog.i("turn $id stopped: $broken")
            removedContext[id] = broken
            claudeTurns[id]?.cancel(broken)
            synchronized(modelLease) { if (id == activeTask) model.cancel() }
        }
    }
    fun stopActivityRequests() = synchronized(modelLease) {
        if (!activeOwner) activeTask?.let { preempted.add(it); model.cancel() }
    }
    private fun checkEpoch(epoch: Long) { check(store.removalEpoch() == epoch) { "上下文已被忘掉或删除，请重新开始本轮" } }
    /** A chat turn is only void when something it actually read was taken away. */
    private fun checkSources(epoch: Long, sources: JSONArray) {
        if (store.removalEpoch() != epoch) synchronized(sources) { store.contextBroken(sources) }?.let { throw ContextRemoved(it) }
    }

    private fun accounting(scope: String, lane: String, purpose: String, guard: () -> Unit) = ModelAccounting(
        ledger = { action, data -> modules.router.execute("life", action, data, modules.localOwner) },
        scope = if (lane == "owner") CompanionPolicy.OWNER else scope, lane = lane,
        purpose = if (lane == "owner" && scope != CompanionPolicy.OWNER) "$purpose:${CompanionPolicy.digest(scope).take(12)}" else purpose,
        legacyRequestGuard = guard, // The two durable ledgers replace the former shared daily gate.
    )

    /** Presence is ephemeral, tool-free owner work on the SAME model worker/client.
     * No raw frames, STT, synthetic prompts or unplayed model text enter the Store.
     */
    fun enqueuePresence(job: PresenceModelJob) {
        val epoch = store.removalEpoch()
        yieldActivityToOwner()
        worker.submit(job.id, true) {
            if (!job.isCurrent()) return@submit
            beginModelTask(job.id)
            activeContextEpoch = epoch
            try {
                fun checkCurrent() { model.checkCancelled(); checkEpoch(epoch); check(job.isCurrent()) { "陪伴轮次已失效" } }
                checkCurrent()
                val settings = store.settings()
                check(!settings.optBoolean("paused") && settings.optBoolean("networkEnabled")) { "助手暂停或联网已关闭" }
                val profiles = settings.getJSONArray("profiles")
                val selected = (0 until profiles.length()).map(profiles::getJSONObject)
                    .first { it.getString("model") == settings.getString("activeModel") }
                val profile = JSONObject(selected.toString()).put("tools", false).put("maxHops", 1)
                    .put("maxOutputTokens", selected.optInt("maxOutputTokens", 512).coerceIn(1, 512))
                    .put("timeoutSeconds", selected.optLong("timeoutSeconds", 30).coerceIn(1, 30))
                for (i in 0 until job.contents.length()) CompanionPolicy.validateMedia(profile, job.contents.getJSONObject(i).getJSONArray("parts"))
                var estimatedInput = CompanionContext.cost(job.system)
                for (i in 0 until job.contents.length()) {
                    val parts = job.contents.getJSONObject(i).getJSONArray("parts")
                    for (j in 0 until parts.length()) {
                        val part = parts.getJSONObject(j)
                        estimatedInput += if (part.has("inlineData")) 4096 else CompanionContext.cost(part.optString("text"))
                    }
                }
                check(estimatedInput + profile.getInt("maxOutputTokens") + 1024 <= profile.optInt("contextBudget", 65536)) { "陪伴上下文超过配置预算" }
                val billing = accounting(CompanionPolicy.OWNER, "owner", "presence", ::checkCurrent)
                val reply = model.generate(settings, profile, job.contents, job.system, JSONArray(),
                    progress = {}, beforeRequest = billing::beforeRequest,
                    onText = { text -> checkCurrent(); store.commitContext(epoch) { job.onPartial(text) } }, onAttemptFinished = billing::finished)
                checkCurrent()
                val parts = reply.getJSONArray("parts")
                check(GeminiWire.calls(parts).isEmpty()) { "陪伴响应不得执行工具" }
                store.commitContext(epoch) { job.onComplete(GeminiWire.text(parts)) }
            } catch (_: Exception) {
                if (job.isCurrent()) job.onFailure()
            } finally { endModelTask(job.id) }
        }
    }

    /** Queued jobs are dropped by isCurrent(); only a matching active lease may cancel HTTP. */
    fun cancelPresence(id: String) {
        worker.cancelQueued(id)
        synchronized(modelLease) { if (activeTask == id) model.cancel() }
    }

    /** All module requests use this serialized provider lease, never a second credential/client. */
    fun enqueueModuleRequest(input: JSONObject, onEvent: (JSONObject) -> Unit): String {
        val latency = TurnLatency()
        val request = JSONObject(input.toString())
        val scope = CompanionPolicy.scope(request.getString("scope"))
        val lane = request.optString("lane", "activity")
        require(lane in setOf("owner", "activity")) { "模型队列类型无效" }
        require(lane != "owner" || modules.principal(scope).owner) { "此会话不能占用主人保留额度" }
        val id = CompanionPolicy.id()
        val cancelled = AtomicBoolean(false)
        val suppliedEpoch = request.optLong("contextEpoch", -1)
        val epoch = if (suppliedEpoch >= 0) suppliedEpoch else store.removalEpoch()
        checkEpoch(epoch)
        moduleRequests[id] = cancelled
        fun emit(type: String, text: String? = null, error: String? = null, usage: JSONObject? = null) {
            val event = JSONObject().put("requestId", id).put("type", type)
            if (text != null) event.put("text", text)
            if (error != null) event.put("error", error)
            if (usage != null) event.put("usage", usage)
            if (type in setOf("completed", "failed", "cancelled")) { latency.finish(); event.put("latency", latency.snapshot()) }
            try { onEvent(event) } catch (_: Exception) { /* Caller lifecycle cannot replay a provider request. */ }
        }
        emit("queued")
        if (lane == "owner") yieldActivityToOwner()
        worker.submit(id, lane == "owner") {
            if (cancelled.get()) { moduleRequests.remove(id); emit("cancelled"); return@submit }
            latency.running()
            val preparationStarted = latency.now()
            beginModelTask(id, lane == "owner")
            activeContextEpoch = epoch
            try {
                checkEpoch(epoch)
                val settings = store.settings()
                check(!settings.optBoolean("paused") && settings.optBoolean("networkEnabled")) { "助手暂停或联网已关闭" }
                val profiles = settings.getJSONArray("profiles")
                val selected = (0 until profiles.length()).map(profiles::getJSONObject).first { it.getString("model") == settings.getString("activeModel") }
                val profile = JSONObject(selected.toString()).put("tools", false)
                    .put("maxOutputTokens", request.optInt("maxOutputTokens", selected.getInt("maxOutputTokens")).coerceIn(1, selected.getInt("maxOutputTokens")))
                val prompt = CompanionPolicy.text(request.getString("prompt"), 120000)
                val parts = request.optJSONArray("parts")?.let { JSONArray(it.toString()) } ?: JSONArray()
                CompanionPolicy.validateMedia(profile, parts)
                parts.put(JSONObject().put("text", prompt))
                val persona = store.persona()
                val system = latency.measure("promptAssemblyMs") { CompanionRequestPrompts.backgroundSystem(persona, request.optString("purpose")) }
                val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", parts))
                val estimate = CompanionContext.cost(system) + CompanionContext.cost(prompt) + (parts.length() - 1) * 4096
                check(estimate + profile.getInt("maxOutputTokens") <= profile.optInt("contextBudget", 65536)) { "请求资料超过当前模型上下文预算" }
                fun checkCurrent() { model.checkCancelled(); checkEpoch(epoch); check(!cancelled.get()) { "任务已取消" } }
                val billing = accounting(scope, lane, request.optString("purpose", "module"), ::checkCurrent)
                // Her own web lookups ("web": true) use the built-in search when the relay is known to support it.
                val builtins = request.optBoolean("web") && web.cached(settings, selected) in setOf("combined", "split")
                check(!request.optBoolean("web") || builtins) { "中转还没确认支持联网搜索" }
                latency.estimate(estimate)
                latency.duration("preparationMs", preparationStarted)
                val reply = model.generate(settings, profile, contents, system, JSONArray(), progress = {}, builtins = builtins,
                    beforeRequest = billing::beforeRequest,
                    onText = { text ->
                        checkCurrent()
                        if (text.isNotBlank()) store.commitContext(epoch) { latency.visible(); emit("delta", text) }
                    }, onAttemptFinished = { result ->
                        latency.request("module", result.timing, result.usage, result.outcome)
                        billing.finished(result)
                    })
                checkCurrent()
                check(GeminiWire.calls(reply.getJSONArray("parts")).isEmpty()) { "模块请求不能直接执行模型工具" }
                store.commitContext(epoch) { emit("completed", GeminiWire.visible(GeminiWire.text(reply.getJSONArray("parts"))).first, usage = reply.optJSONObject("usageMetadata")) }
            } catch (e: Exception) {
                if (!cancelled.get() && id !in preempted) XLog.w("module request ${request.optString("purpose")} failed: ${e.message}" + ((e as? ModelFailure)?.detail?.takeIf { it.isNotBlank() }?.let { " | $it" } ?: ""))
                emit(if (cancelled.get() || id in preempted) "cancelled" else "failed", error = when {
                    id in preempted -> "为主人交流暂停活动；费用待核对，不自动重放请求"
                    cancelled.get() -> null
                    else -> (e.message ?: "模型请求失败").take(240)
                })
            } finally { moduleRequests.remove(id); endModelTask(id); modules.router.signal("all", "model.finished") }
        }
        return id
    }

    fun cancelModuleRequest(id: String) {
        moduleRequests[id]?.set(true)
        // Leave a queued cancellation in the queue so the caller gets its terminal callback.
        synchronized(modelLease) { if (activeTask == id) model.cancel() }
    }

    fun silenceGroups(): JSONObject {
        val conversations = store.list("conversation")
        for (i in 0 until conversations.length()) {
            val c = conversations.getJSONObject(i)
            if (c.optString("scope").startsWith("qq:group:")) store.put("conversation", c.put("mode", "quiet").put("autoReply", false))
        }
        val tasks = store.list("task")
        for (i in 0 until tasks.length()) {
            val t = tasks.getJSONObject(i)
            if (t.optString("scope").startsWith("qq:group:") && t.optString("state") !in setOf("done", "cancelled", "unknown")) cancel(t.getString("id"))
        }
        return JSONObject().put("ok", true).put("message", "群回复队列已停止；已进入平台提交的操作仍需核对")
    }

    private fun update(id: String, state: String, message: String = "") {
        store.transitionTask(id, setOf("queued", "running"), state, message)
    }

    private fun checkTask(id: String) {
        model.checkCancelled()
        check(store.get("task", id)?.optString("state") in setOf("queued", "running")) { "任务已停止" }
        check(!store.settings().optBoolean("paused")) { "助手已暂停" }
    }

    private fun runChat(id: String) {
        val latency = turnLatency.getOrPut(id) { TurnLatency() }
        latency.running()
        val preparationStarted = latency.now()
        val task = store.get("task", id) ?: run { turnLatency.remove(id); return }
        if (task.optString("state") != "queued") { finishTurn(task.getString("scope"), id); return }
        val viaClaude = task.optString("engine") == io.deepseekharness.mobile.companion.claude.ClaudeChat.ENGINE_CLAUDE
        // Claude has its own process per turn; the Gemini lease (and its preemption) is only for Gemini turns.
        if (!viaClaude) beginModelTask(id, modules.principal(task.getString("scope")).owner || task.optBoolean("ownerAsked"))
        var handedOver = false
        try {
            val settings = store.settings()
            check(!settings.optBoolean("paused")) { "助手已暂停" }
            check(settings.optBoolean("networkEnabled")) { "请在模型设置中开启联网" }
            val profiles = settings.getJSONArray("profiles")
            val originText = store.get("task", id)?.optString("messageId")?.let { store.get("message", it)?.optString("text") }.orEmpty()
            val chosen = CompanionPolicy.chooseModel(settings, originText)
            val configuredProfile = (0 until profiles.length()).map(profiles::getJSONObject).first { it.getString("model") == chosen }
            var profile = configuredProfile
            val scope = task.getString("scope")
            // Tools follow who is speaking, not where: the owner gets the owner's tools in the app, their QQ private chat
            // and (when the account says it is them) in a group; the phone itself is only reachable from their own chats.
            val ownersOwnChat = scope == CompanionPolicy.OWNER || modules.principal(scope).owner
            val speaker = Speaker(ownersOwnChat || task.optBoolean("ownerAsked"), ownersOwnChat)
            // Messages that arrived while this turn was still queued simply extend it.
            var boundId = task.getString("messageId")
            var observationId = task.optString("observationId")
            takeFollowUps(scope).forEach { (message, observation) -> boundId = message; if (observation.isNotBlank()) observationId = observation }
            // Kept on the task: a replay or a hand-over to Gemini starts from the newest message, not the first one.
            if (observationId != task.optString("observationId") || boundId != task.optString("messageId"))
                store.put("task", task.put("observationId", observationId).put("messageId", boundId))
            val origin = store.get("message", boundId) ?: error("原消息已删除")
            val ticket = store.contextSnapshot(scope, origin.optString("text"), 256)
            val epoch = ticket.getLong("epoch")
            val sources = JSONArray()
            turnSources[id] = epoch to sources
            fun checkCurrent() { checkTask(id); removedContext[id]?.let { throw ContextRemoved(it) }; checkSources(epoch, sources) }
            sources.put(JSONObject().put("kind", "message").put("id", boundId).put("scope", scope))
            val plan = LatencyPolicy.plan(origin.optString("text"), LatencyPolicy.Input(
                localOwner = scope == CompanionPolicy.OWNER && !viaClaude,
                online = settings.optBoolean("networkEnabled") && !settings.optBoolean("paused"),
                toolsEnabled = profile.optBoolean("tools", true),
                researchAvailable = web.offered(settings, profile) == "split",
                screenObservationAuthorized = DeepSeekAccessibilityService.current() != null,
                backgroundAvailable = modules.router.available("social"),
                recallAvailable = true, jobStatusAvailable = true,
                emergencyHeld = io.deepseekharness.mobile.companion.devicev2.EmergencyHold.ordinaryHeld,
            ), profile.optString("thinking", "low"))
            if (observationId.isBlank() && !viaClaude && plan.thinking != profile.optString("thinking"))
                profile = JSONObject(profile.toString()).put("thinking", plan.thinking)
            val history = ticket.getJSONArray("messages")
            // Bound the turn to its initiating message: later queued user messages belong to later tasks.
            val all = (0 until history.length()).map(history::getJSONObject)
            val end = all.indexOfFirst { it.optString("id") == boundId }
            check(end >= 0) { "任务原消息已删除" }
            plan.acknowledgement?.let { acknowledgement ->
                checkCurrent()
                if (store.get("task", id)?.optString("acknowledgedMessageId") != boundId)
                    publishPartial(id, epoch, sources, acknowledgement.text, latency, true, boundId)
            }
            val selected = all.take(end + 1).filter { it.optString("role") != "assistant" || it.optString("replyTo").isEmpty() }
            val messages = JSONArray().also { ordered -> selected.forEach { m ->
                ordered.put(m)
                all.filter { it.optString("replyTo") == m.optString("id") }.forEach(ordered::put)
            } }
            val toolFreeChat = scope == CompanionPolicy.OWNER && !viaClaude && LatencyPolicy.toolFreeChat(
                origin.optString("text"), all.take(end).filter { it.optString("role") == "user" }.takeLast(3).map { it.optString("text") },
                observationId.isNotBlank(),
            )
            if (toolFreeChat && profile.optString("thinking") in setOf("medium", "high"))
                profile = JSONObject(profile.toString()).put("thinking", "low")
            val fingerprint = CompanionPolicy.sessionFingerprint(settings, profile)
            val budget = profile.optInt("contextBudget", 65536) - profile.optInt("maxOutputTokens", 32768)
            check(budget >= 4096) { "上下文预算须比输出预算至少多 4096" }
            val memoryBudget = (budget / 4).coerceAtMost(6000)
            val persona = store.persona()
            // The last few lines, not just the last one: "那个呢？" needs what came before to find anything.
            val recallQuery = all.take(end + 1).takeLast(4).joinToString("\n") { it.optString("text").take(400) }
            val recallStarted = latency.now()
            val remembered = recallMemories(scope, recallQuery)
            latency.duration("memoryRecallMs", recallStarted)
            // Pending review items are mentioned once, in passing, the next time the owner talks to her.
            val pendingReview = if (scope == CompanionPolicy.OWNER) runCatching { modules.pendingApprovals() }.getOrDefault(emptyList()) else emptyList()
            val mentioned = store.lifeState("approvalMention").optJSONArray("ids") ?: JSONArray()
            val unmentioned = pendingReview.filter { id -> (0 until mentioned.length()).none { mentioned.getString(it) == id } }
            val memories = CompanionContext.memories(remembered, memoryBudget)
            for (i in 0 until memories.length()) memories.getJSONObject(i).let {
                sources.put(JSONObject().put("kind", "memory").put("id", it.getString("id")).put("scope", it.getString("scope")))
            }
            // Implicit caching keys on an identical prefix: persona and fixed mechanics first (system), the
            // append-only history next, and everything that changes per turn last, inside the newest user turn.
            val owner = scope == CompanionPolicy.OWNER
            val learned = if (scope.startsWith("qq:group:")) modules.learner.forGroup(scope)
                else if (owner || modules.principal(scope).owner) modules.learner.forOwner() else ""
            val onebot = store.get("conversation", scope)?.optString("route") == "onebot"
            val shelf = if (onebot) store.lifeState("stickerShelf").optJSONArray("items")?.let { a ->
                (0 until a.length()).map(a::getJSONObject)
            }.orEmpty().take(60) else emptyList()
            val system = latency.measure("promptAssemblyMs") { CompanionPrompt.system(persona, CompanionPrompt.SystemInput(
                scope, owner, modules.principal(scope).owner, settings.optString("ownerQqId"), task.optBoolean("ownerAsked"),
                onebot, shelf, learned,
            )) }
            val now = java.time.LocalDateTime.now()
            val lastModel = all.take(end).lastOrNull { it.optString("role") == "assistant" }?.optString("source")
                ?.removePrefix("模型 ")?.takeIf { it.startsWith("gemini") }
            val device = if (ownersOwnChat) modules.device.snapshot(modules.localOwner) else JSONObject()
            val playing = device.optJSONObject("control")?.optString("packageName").orEmpty()
                .ifBlank { device.optJSONObject("capture")?.optString("packageName").orEmpty() }
            val deviceNotes = if (playing.isNotBlank()) modules.device.notes.describe(playing) else ""
            val emergencyHeld = ownersOwnChat && io.deepseekharness.mobile.companion.devicev2.EmergencyHold.ordinaryHeld
            val people = modules.profiles.relevant(scope, all[end].optString("text"), CompanionPolicy.sharesOwnerMemory(store.get("conversation", scope), settings))
            val lore = PersonaVoice.lore(persona, all.take(end + 1).takeLast(5).map { it.optString("text") })
            val failed = io.deepseekharness.mobile.companion.chat.DeliveryLog.describe(store, scope, owner || modules.principal(scope).owner)
            val jobs = io.deepseekharness.mobile.companion.chat.BackgroundJobs.describe(store, scope, owner || modules.principal(scope).owner)
            val situation = latency.measure("promptAssemblyMs") { CompanionPrompt.situation(CompanionPrompt.SituationInput(
                now, profile.getString("model"), settings.optString("activeModel"), lastModel, viaClaude, task.optString("fallbackReason"),
                ownersOwnChat, device, deviceNotes, emergencyHeld, unmentioned.isNotEmpty(), pendingReview.size,
                if (people.isNotEmpty()) io.deepseekharness.mobile.companion.memory.Profiles.describe(people) else "", lore, memories, failed, jobs,
            )) }
            val selection = latency.measure("promptAssemblyMs") { CompanionPrompt.history(messages, fingerprint, budget, system, situation) }
            val contents = selection.contents
            selection.messageIds.forEach { sources.put(JSONObject().put("kind", "message").put("id", it).put("scope", scope)) }
            val turnHistory = JSONArray()
            if (observationId.isNotBlank()) {
                val observation = store.get("observation", observationId) ?: error("观察资料不存在")
                check(observation.getString("scope") == scope) { "不能跨会话附加观察资料" }
                sources.put(JSONObject().put("kind", "observation").put("id", observationId).put("scope", scope))
                val media = store.observationParts(observationId)
                CompanionPolicy.validateMedia(profile, media)
                CompanionPrompt.observation(contents, observation, media)
            }
            // Her voice, close to the newest messages so long chats do not drift.
            latency.measure("promptAssemblyMs") {
                PersonaVoice.inject(contents, PersonaVoice.reminder(store, modules::requestModel), PersonaVoice.depth(persona), PersonaVoice.postHistory(persona))
            }
            if (viaClaude) {
                val media = if (observationId.isNotBlank()) store.observationParts(observationId) else JSONArray()
                val images = (0 until media.length()).mapNotNull { media.getJSONObject(it).optJSONObject("inlineData") }
                // Claude reads pictures but not video or audio: such a turn goes to Gemini without counting as an outage.
                val reason = if (images.any { !it.optString("mimeType").startsWith("image/") }) "这条带了 Claude 读不了的视频或音频"
                    else claudeReply(id, scope, speaker, epoch, sources, system, situation, all.take(end + 1), boundId, observationId,
                        images.map { it.optString("mimeType") to it.optString("data") }, unmentioned, pendingReview)
                if (reason == null) return
                if (store.transitionTask(id, setOf("queued", "running"), "queued", "Claude 不可用（$reason），换成 Gemini") != null) {
                    store.get("task", id)?.let { store.put("task", it.put("engine", io.deepseekharness.mobile.companion.claude.ClaudeChat.ENGINE_GEMINI).put("fallbackReason", reason).put("messageId", boundId).put("observationId", observationId)) }
                    handedOver = true
                    val owner = modules.principal(scope).owner || task.optBoolean("ownerAsked")
                    if (owner) yieldActivityToOwner()
                    worker.submit(id, owner) { runChat(id) }
                }
                return
            }
            store.commitContext(epoch, sources) { update(id, "running", "正在连接 ${profile.getString("model")}") }
            var lastPartialAt = 0L
            var firstModelPartial = true
            var merges = 0
            val lane = if (modules.principal(scope).owner || task.optBoolean("ownerAsked")) "owner" else "activity"
            val webBilling = accounting(scope, lane, "web_search", ::checkCurrent)
            val webBill = WebResearch.Billing(webBilling::beforeRequest) { result ->
                latency.request("web_search", result.timing, result.usage, result.outcome)
                webBilling.finished(result)
            }
            // Discover support on an actual lookup; ordinary chat must not wait for a second model request.
            val webMode = if (profile.optBoolean("tools", true)) web.offered(settings, profile) else "none"
            latency.duration("preparationMs", preparationStarted)
            activeWeb = Triple(settings, profile, webBill)
            // When the relay refuses or fails, step down instead of giving up: the same model once more (argon is slow
            // and its streams get cut), then flash without the web tool, then no tools. Every failed attempt stays on the task.
            val flash = flashProfile(settings, profile)
            var level = 0
            fun nextLevel(): Int? {
                var n = level + 1
                if (n == 2 && flash.getString("model") == profile.getString("model")) n = 3
                if (n == 3 && turnHistory.length() > 0) return null // tool calls already made need their declarations
                return n.takeIf { it <= 3 }
            }
            val attempts = JSONArray()
            var used = profile
            var round = 0
            while (round < 12) {
                checkCurrent()
                used = if (level >= 2) flash else profile
                val declared = latency.measure("toolSchemaMs") {
                    if (level >= 3 || !used.optBoolean("tools", true) || (toolFreeChat && round == 0 && merges == 0)) JSONArray()
                    else declarations(scope, speaker, webMode == "split" && level <= 1)
                }
                latency.estimate(ProviderHistoryPrivacy.estimatedInput(contents) + CompanionContext.cost(system) + CompanionContext.cost(declared.toString()))
                check(ProviderHistoryPrivacy.estimatedInput(contents) + CompanionContext.cost(system) + used.getInt("maxOutputTokens") + 1024 <= used.optInt("contextBudget", 65536)) { "新增资料超过当前模型上下文预算，请减少材料" }
                val billing = accounting(scope, lane, "chat", ::checkCurrent)
                // Another model cannot verify this one's thought signatures; Gemini's documented placeholder skips the check.
                val sent = if (used === profile) contents else GeminiWire.foreignSignatures(contents)
                val reply = try { model.generate(settings, used, sent, system, declared,
                    progress = { update(id, "running", it) }, beforeRequest = billing::beforeRequest,
                    onText = { text ->
                        checkCurrent()
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (text.isNotBlank() && (firstModelPartial || now - lastPartialAt >= 100)) {
                            firstModelPartial = false; lastPartialAt = now
                            publishPartial(id, epoch, sources, text, latency)
                        }
                    },
                    onAttemptFinished = { result ->
                        latency.request("chat", result.timing, result.usage, result.outcome)
                        billing.finished(result)
                    })
                } catch (e: ModelFailure) {
                    attempts.put(ChatDiagnostics.attempt(e, used, declared))
                    val next = nextLevel() ?: throw ChatDiagnostics.Failed(e, attempts)
                    level = next
                    store.transitionTask(id, setOf("running"), "running", "上一次失败，换个方式重试（${ChatDiagnostics.LEVELS[level]}）",
                        JSONObject().put("diagnostic", ChatDiagnostics.summary(attempts, scope)))
                    continue
                }
                checkCurrent()
                val parts = reply.getJSONArray("parts")
                val calls = GeminiWire.calls(parts)
                check(calls.isEmpty() || profile.optBoolean("tools")) { "当前模型配置关闭了工具，未执行返回的调用" }
                val modelTurn = JSONObject().put("role", "model").put("parts", parts)
                turnHistory.put(modelTurn)
                if (calls.isEmpty() && merges < 3) {
                    // They kept talking while she was writing: drop this draft and answer everything at once.
                    val more = takeFollowUps(scope).mapNotNull { (message, _) -> store.get("message", message) }
                    if (more.isNotEmpty()) {
                        merges++
                        profile = configuredProfile
                        activeWeb = Triple(settings, profile, webBill)
                        turnHistory.remove(turnHistory.length() - 1)
                        boundId = more.last().getString("id")
                        more.forEach { sources.put(JSONObject().put("kind", "message").put("id", it.getString("id")).put("scope", scope)) }
                        contents.getJSONObject(contents.length() - 1).getJSONArray("parts")
                            .put(JSONObject().put("text", "（你还没回，对方又发了）\n" + more.joinToString("\n") { it.optString("text") }))
                        continue
                    }
                }
                if (calls.isEmpty()) {
                    val (seen, cut) = GeminiWire.visible(GeminiWire.text(parts).ifBlank { "模型本轮没有可见回答。" })
                    // She sometimes echoes the host's own placeholders ("[图片附件]"); they are never part of a reply.
                    val text = io.deepseekharness.mobile.companion.chat.OutgoingSegments.stripInternal(seen).ifBlank { seen }
                    if (cut) io.deepseekharness.mobile.companion.XLog.w("reply carried unmarked reasoning; kept only the final part (${used.optString("model")})")
                    var draft: JSONObject? = null
                    val message = store.commitContext(epoch, sources) {
                        val storedFingerprint = if (used === profile) fingerprint else CompanionPolicy.sessionFingerprint(settings, used)
                        val saved = store.message(scope, "assistant", text, "模型 ${used.getString("model")}", ProviderHistoryPrivacy.forStorage(storedFingerprint, turnHistory), boundId)
                            .let { m -> task.optString("fallbackReason").takeIf { it.isNotBlank() }?.let { store.put("message", m.put("fallback", it)) } ?: m }
                        store.pruneProviderHistory(scope)
                        // Her reply to the owner's own QQ chat is approved as it is written: it never sits as a draft.
                        if (scope.startsWith("qq:")) draft = createReplyDraft(scope, text, "qq", "").let { d ->
                            if (d.optBoolean("ownerRequested")) store.approveDraft(d.getString("id"), CompanionPolicy.draftDigest(d), automatic = true) else d
                        }
                        if (observationId.isNotBlank()) store.get("observation", observationId)?.let {
                            store.put("observation", it.put("analysis", text).put("analyzed", true))
                        }
                        update(id, "done", listOfNotNull(
                            if (reply.optString("finishReason") == "MAX_TOKENS") "已到本轮输出预算；回答可能不完整" else "完成",
                            if (attempts.length() > 0) "降级后成功：${ChatDiagnostics.LEVELS[level]}（${used.optString("model")}）" else null,
                            if (cut) "回复里混进了思考过程，已只保留最后的正文" else null).joinToString("；"))
                        if (unmentioned.isNotEmpty()) store.saveLifeState("approvalMention", JSONObject().put("ids", JSONArray(pendingReview)))
                        saved
                    }
                    modules.router.event(ModuleEvent("conversation.message", scope, message.getString("id"), JSONObject(message.toString()).apply { remove("wire") }))
                    draft?.let { checkSources(epoch, sources); CompanionRouting.maybeAutoSend(context, it) }
                    return
                }
                check(calls.size <= 16) { "单轮工具数量超过限制" }
                if (scope == CompanionPolicy.OWNER && GeminiWire.text(parts).isBlank() &&
                    !io.deepseekharness.mobile.companion.devicev2.EmergencyHold.ordinaryHeld &&
                    store.get("task", id)?.optString("acknowledgedMessageId") != boundId) {
                    LatencyPolicy.forReadTools(calls.map { it.optString("name") })?.let {
                        checkCurrent()
                        publishPartial(id, epoch, sources, it.text, latency, true, boundId)
                    }
                }
                contents.put(modelTurn)
                val responses = JSONArray()
                for (call in calls) {
                    checkCurrent()
                    recordStep(id, io.deepseekharness.mobile.companion.claude.ClaudeStream.step(call.optString("name"), call.optJSONObject("args") ?: JSONObject()))
                    val toolStarted = latency.now()
                    var toolCompleted = false
                    val result = try {
                        executeTool(scope, boundId, epoch, sources, call.getString("name"), call.optJSONObject("args") ?: JSONObject(), speaker)
                            .also { toolCompleted = !it.has("error") }
                    } finally { latency.tool(call.getString("name"), toolStarted, toolCompleted) }
                    responses.put(GeminiWire.toolResponse(call, result))
                    if (call.optString("name") == "device_action") {
                        val frame = if (result.has("preview")) result else result.optJSONObject("after")
                        val preview = frame?.optString("preview").orEmpty()
                        frame?.remove("preview")
                        if (preview.startsWith("data:image/jpeg;base64,") && preview.length < 6 * 1024 * 1024 && profile.optBoolean("images"))
                            responses.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", preview.substringAfter(','))))
                        else if (preview.isNotEmpty()) result.put("visionUnavailable", "所选模型尚未开启图片能力，未提供画面给模型")
                    }
                }
                val responseTurn = JSONObject().put("role", "user").put("parts", responses)
                contents.put(responseTurn)
                turnHistory.put(responseTurn)
                round++
            }
            error("本轮工具步数达到上限，已停止")
        } catch (e: Exception) {
            val removed = removedContext[id] ?: generateSequence<Throwable>(e) { it.cause }.filterIsInstance<ContextRemoved>().firstOrNull()?.message
            val replays = store.get("task", id)?.optInt("replays") ?: 0
            if (id in preempted) update(id, "waiting", "为主人交流暂停；稍后可继续，未自动重复收费请求")
            else if (removed != null && replays < 2 && store.get("message", store.get("task", id)?.optString("messageId").orEmpty()) != null) {
                // Answer again from what is still there, rather than drop the reply the owner is waiting for.
                XLog.i("turn $id answered again: $removed")
                if (store.transitionTask(id, setOf("queued", "running"), "queued", "$removed，重新回答", JSONObject().put("replays", replays + 1)) != null) {
                    handedOver = true
                    val owner = modules.principal(task.getString("scope")).owner || task.optBoolean("ownerAsked")
                    if (viaClaude) claudeWorker.submit(id, owner) { runChat(id) } else worker.submit(id, owner) { runChat(id) }
                }
            }
            else {
                val scope = task.getString("scope")
                val failure = e as? ChatDiagnostics.Failed
                val reason = (failure?.cause?.message ?: e.message ?: "任务失败").take(200)
                store.transitionTask(id, setOf("queued", "running"), "failed", (if (failure != null) "模型请求失败：$reason（已换方式重试 ${failure.attempts.length() - 1} 次）" else reason).take(240),
                    failure?.let { JSONObject().put("diagnostic", ChatDiagnostics.summary(it.attempts, scope)) })
                XLog.w("chat failed in ${scope.take(24)}: $reason" + (failure?.attempts?.let { a -> " | " + (0 until a.length()).joinToString(" ; ") { i -> a.getJSONObject(i).let { "${it.optString("model")} ${it.opt("status")} ${it.optString("error")} ${it.optString("detail").take(160)}" } } } ?: ""))
                // Cancelled by the owner or stopped by a pause is not a failure she needs to explain.
                if (e.message?.let { "取消" in it || "暂停" in it || "任务已停止" in it } != true)
                    io.deepseekharness.mobile.companion.chat.DeliveryLog.record(store, scope, "model", if (failure != null) "模型请求失败（$reason）" else reason)
            }
        } finally {
            if (!handedOver) latency.finish()
            saveLatency(id, latency)
            turnSources.remove(id); removedContext.remove(id)
            if (!viaClaude) endModelTask(id)
            if (!handedOver) finishTurn(task.getString("scope"), id)
        }
    }

    private fun publishPartial(id: String, epoch: Long, sources: JSONArray, text: String, latency: TurnLatency,
                               acknowledgement: Boolean = false, messageId: String = "") {
        val saved = store.commitContext(epoch, sources) {
            store.transitionTask(id, setOf("queued", "running"), "running", "正在生成",
                JSONObject().put("partialText", text).put("partialKind", if (acknowledgement) "acknowledgement" else "model")
                    .also { if (acknowledgement) it.put("acknowledgedMessageId", messageId) })
        }
        if (saved != null) {
            latency.visible(acknowledgement)
            modules.router.signal("all", "chat.partial")
        }
    }

    private fun saveLatency(id: String, latency: TurnLatency) {
        runCatching { synchronized(store) {
            store.get("task", id)?.let { store.put("task", it.put("latency", latency.snapshot())) }
        } }
    }

    /** One tool call, the same for Gemini and Claude (over MCP): scope rules live in [tool]; what it read is recorded as a source. */
    private fun executeTool(scope: String, boundId: String, epoch: Long, sources: JSONArray, name: String, args: JSONObject, speaker: Speaker): JSONObject {
        val result = try {
            // Sending waits on QQ for seconds; it must not hold the store while it does.
            when (name) {
                "say_in_group" -> sayInGroup(scope, args, speaker) { block -> store.commitContext(epoch, sources, block) }
                "send_qq" -> sendQq(scope, args, speaker) { block -> store.commitContext(epoch, sources, block) }
                else -> store.commitContext(epoch, sources) { tool(scope, boundId, name, args, speaker) }
            }
        }
        catch (e: IllegalArgumentException) { JSONObject().put("error", e.message?.take(180) ?: "参数不正确") }
        catch (e: IllegalStateException) { JSONObject().put("error", e.message?.take(180) ?: "操作不可用") }
        synchronized(sources) {
            if (name == "recall") {
                for ((key, kind) in listOf("memories" to "memory", "messages" to "message")) {
                    val recalled = result.optJSONArray(key) ?: continue
                    for (i in 0 until recalled.length()) recalled.getJSONObject(i).let { item ->
                        sources.put(JSONObject().put("kind", kind).put("id", item.getString("id")).put("scope", item.optString("scope", scope)))
                    }
                }
            }
            if (name == "life_action") {
                val items = result.optJSONArray("items") ?: JSONArray()
                val kind = when (args.optString("action")) { "memory.search" -> "memory"; "diary.list" -> "diary"; "interest.list" -> "interest"; else -> "" }
                if (kind.isNotBlank()) for (i in 0 until items.length()) items.getJSONObject(i).let { item ->
                    sources.put(JSONObject().put("kind", kind).put("id", item.getString("id")).put("scope", item.optString("scope", scope)))
                }
            }
        }
        return result
    }

    /**
     * Claude's reply for a chat task, or why it could not give one (the task then goes to Gemini). Memory, profiles,
     * learned group style and the situation block are the same ones Gemini gets; Claude keeps its own session per
     * conversation and is sent whatever it has not seen since its last turn, including replies Gemini wrote meanwhile.
     */
    private fun claudeReply(id: String, scope: String, speaker: Speaker, epoch: Long, sources: JSONArray, system: String, situation: String, history: List<JSONObject>,
                            boundId: String, observationId: String, images: List<Pair<String, String>>, unmentioned: List<String>, pendingReview: List<String>): String? {
        val chat = io.deepseekharness.mobile.companion.claude.ClaudeChat
        val ownerChat = scope == CompanionPolicy.OWNER || modules.principal(scope).owner
        if (claude.version() == null) return chat.describe(io.deepseekharness.mobile.companion.claude.ClaudeStream.Failure.NOT_INSTALLED)
        val settings = chat.settings(store)
        // Transcripts from before a correction, "forget" or deletion are removed from the rootfs, not just abandoned.
        chat.staleSessions(store).forEach { sid -> runCatching {
            File(claude.hostDirectory(), "root/.claude/projects").listFiles()?.forEach { File(it, "$sid.jsonl").delete() }
        } }
        chat.forgetSessions(store)
        val scopeEpoch = store.scopeRemovalEpoch(scope)
        val saved = chat.session(store, scope, scopeEpoch)
        val cwd = saved?.optString("cwd")?.takeIf { it.isNotBlank() } ?: chat.cwd(scope, ownerChat, io.deepseekharness.mobile.companion.claude.ClaudeWorkspace.projectReady(claude))
        File(claude.hostDirectory(), cwd.removePrefix("/")).mkdirs()
        val name = store.persona().optString("name", "小克")
        val claudeSystem = system + "\n【在 Claude Code 里怎么做】你是$name，现在通过 Claude Code 运行。你最后写出的正文就是发给对方的消息，不要写成报告，也不要提“工具”“MCP”。" +
            "你的记忆、日记、QQ、手机、B站、待审清单这些能力在 xiaoke 这组工具里（remember、recall、job_status、say_in_group 等），用法和平时一样；remember 的 sourceId 留空，记在当前这条消息上。" +
            (if (ownerChat && settings.optBoolean("fullAccess")) "主人给了你完全访问权限：改文件、跑命令不用再问；动手前想清楚，删东西、推送、改系统设置这类收不回的事先跟主人说一声。" else "") +
            (if (ownerChat) "主人交代代码的事时：这个项目（你自己的 App）在 ${io.deepseekharness.mobile.companion.claude.ClaudeWorkspace.PROJECT}，先读那里的 CLAUDE.md；读代码随意；" + (if (settings.optBoolean("fullAccess")) "" else "改文件、跑命令会弹到主人手机上等主人点允许。") +
                "改代码一律在新分支上做（git switch -c 起个说明用途的名字），改完提交，用几句话告诉主人改了什么、在哪个分支、怎么验证的；不碰 main，不碰签名和密钥（签名和发版只在云端做）。手机上没法跑完整的 Gradle 构建就说没验证。" +
                (if (io.deepseekharness.mobile.companion.claude.ClaudeGithub.token(context) != null)
                    "主人配了 GitHub token（在环境变量 GH_TOKEN 里，git 推送已经能用）：改完推到 claude/ 开头的新分支，再开一个 PR，回复里给出 PR 链接。开 PR 用 gh（没装可以 apt-get install -y gh，它直接认 GH_TOKEN），装不上就用 curl 调 POST https://api.github.com/repos/snodgrasssanderfer-source/godot-test/pulls。token 本身绝不写进文件、提交、日志或回复。\n"
                else "没有推送凭据，提交在本地，请主人推送。\n")
            else "这里不是主人的私聊：只回这个对话里该说的话，不写代码、不读写文件、不提主人的私事。\n")
        val lastSeen = saved?.optString("lastMessageId")
        val transcript = chat.transcript(history, lastSeen, 24_000) { m ->
            if (m.optString("role") == "assistant") "你（$name）" else m.optString("source").ifBlank { "对方" }.take(40)
        }
        val content = io.deepseekharness.mobile.companion.claude.ClaudeStream.content(
            situation + "\n" + (if (lastSeen == null) "【最近的对话】\n" else "【你上次回复之后的新消息】\n") + transcript +
                (if (observationId.isNotBlank()) "\n（附了画面截图，只据这些图判断，不可假称看过完整视频。）" else "") + "\n\n请回复最后一条消息。", images)
        // Only the owner's own turns get the GitHub token; a group turn never has it in its environment.
        val github = if (ownerChat) io.deepseekharness.mobile.companion.claude.ClaudeGithub.token(context) else null
        fun redact(text: String) = io.deepseekharness.mobile.companion.claude.ClaudeGithub.redact(text, github)
        val grant = io.deepseekharness.mobile.companion.claude.XiaokeMcp.Grant(scope, declarations(scope, speaker, webTool = false)) { tool, args ->
            // Nothing she saves or sends through her own abilities (memory, QQ…) may carry the token.
            checkClaudeCurrent(id, epoch, sources); executeTool(scope, boundId, epoch, sources, tool, JSONObject(redact(args.toString())), speaker)
        }
        val token = io.deepseekharness.mobile.companion.claude.XiaokeMcpServer.register(grant)
        var lastPartialAt = 0L
        var model = settings.optString("model")
        try {
            store.commitContext(epoch, sources) { update(id, "running", "正在连接 Claude") }
            fun attempt(resume: String?): io.deepseekharness.mobile.companion.claude.ClaudeTurn.Outcome {
                val turn = io.deepseekharness.mobile.companion.claude.ClaudeTurn(claude, id)
                claudeTurns[id] = turn
                try {
                    return turn.run(io.deepseekharness.mobile.companion.claude.ClaudeTurn.Options(claudeSystem, content, resume, settings.optString("model"), settings.optString("effort"),
                        ownerTools = ownerChat, fullAccess = ownerChat && settings.optBoolean("fullAccess"), githubToken = github, cwd = cwd, mcpPort = io.deepseekharness.mobile.companion.claude.XiaokeMcpServer.port(), mcpToken = token,
                        timeoutMs = if (ownerChat) 45 * 60_000L else 4 * 60_000L),
                        onText = { text ->
                            runCatching { checkClaudeCurrent(id, epoch, sources) }.onFailure { e ->
                                if (e is ContextRemoved) removedContext[id] = e.message.orEmpty()
                                turn.cancel(e.message ?: "本轮已停止")
                            }
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastPartialAt >= 350) { lastPartialAt = now; runCatching { store.commitContext(epoch, sources) { store.transitionTask(id, setOf("running"), "running", "正在生成", JSONObject().put("partialText", text)) } } }
                        },
                        onTool = { tool, input -> recordStep(id, io.deepseekharness.mobile.companion.claude.ClaudeStream.step(tool, input)) },
                        onUsage = { usage -> store.saveLifeState("claudeUsage", io.deepseekharness.mobile.companion.claude.ClaudeStream.mergeUsage(store.lifeState("claudeUsage"), usage)) },
                        onPermission = { tool, input ->
                            // Only the owner's own chats get Claude Code's tools at all; a group never reaches this.
                            // With full access on, whatever is still asked (paths outside the project and the like) is a yes.
                            ownerChat && (settings.optBoolean("fullAccess") || io.deepseekharness.mobile.companion.claude.ClaudePermissions.ask(context, id, scope, tool,
                                redact(io.deepseekharness.mobile.companion.claude.ClaudeStream.describe(tool, input))) { turn.isCancelled || runCatching { checkClaudeCurrent(id, epoch, sources) }.isFailure })
                        })
                } finally { claudeTurns.remove(id, turn) }
            }
            var outcome = attempt(saved?.optString("id"))
            // A session Claude Code no longer has (cleared, or the rootfs was reinstalled): start a fresh one once.
            if (outcome.failure != null && saved != null && "no conversation found" in outcome.detail.lowercase()) outcome = attempt(null)
            removedContext[id]?.let { throw ContextRemoved(it) } // answered again by runChat
            if (store.get("task", id)?.optString("state") !in setOf("running", "queued")) return null // cancelled or paused meanwhile
            val failure = outcome.failure
            if (failure != null) {
                XLog.w("claude turn failed in ${scope.take(24)}: ${failure.name} ${outcome.detail.take(200)}")
                if (chat.recordOutage(store, failure, outcome.detail, outcome.usage))
                    chat.notifyOwner(context, "${chat.describe(failure)}，先由 Gemini 回复。" + when (failure) {
                        io.deepseekharness.mobile.companion.claude.ClaudeStream.Failure.LOGIN -> "到 我的 → Claude 重新登录后会换回来。"
                        io.deepseekharness.mobile.companion.claude.ClaudeStream.Failure.LIMIT -> "额度恢复后会自动换回来。"
                        else -> "过一会儿会再试 Claude。"
                    })
                return chat.describe(failure)
            }
            chat.clearOutage(store)
            val text = io.deepseekharness.mobile.companion.chat.OutgoingSegments.stripInternal(outcome.text.trim()).ifBlank { "（Claude 这轮没有写出回复）" }
            model = outcome.model.ifBlank { model.takeIf { it != "default" } ?: "默认模型" }
            var draft: JSONObject? = null
            val message = store.commitContext(epoch, sources) {
                val savedMessage = store.message(scope, "assistant", text, "Claude $model", null, boundId)
                if (scope.startsWith("qq:")) draft = createReplyDraft(scope, text, "qq", "").let { d ->
                    if (d.optBoolean("ownerRequested")) store.approveDraft(d.getString("id"), CompanionPolicy.draftDigest(d), automatic = true) else d
                }
                if (observationId.isNotBlank()) store.get("observation", observationId)?.let { store.put("observation", it.put("analysis", text).put("analyzed", true)) }
                update(id, "done", "完成（Claude）")
                if (unmentioned.isNotEmpty()) store.saveLifeState("approvalMention", JSONObject().put("ids", JSONArray(pendingReview)))
                chat.saveSession(store, scope, outcome.sessionId, scopeEpoch, savedMessage.getString("id"), cwd)
                savedMessage
            }
            modules.router.event(ModuleEvent("conversation.message", scope, message.getString("id"), JSONObject(message.toString()).apply { remove("wire") }))
            draft?.let { checkSources(epoch, sources); CompanionRouting.maybeAutoSend(context, it) }
            return null
        } finally { io.deepseekharness.mobile.companion.claude.XiaokeMcpServer.revoke(token) }
    }

    private fun checkClaudeCurrent(id: String, epoch: Long, sources: JSONArray) {
        check(store.get("task", id)?.optString("state") in setOf("queued", "running")) { "任务已停止" }
        check(!store.settings().optBoolean("paused")) { "助手已暂停" }
        removedContext[id]?.let { throw ContextRemoved(it) }
        checkSources(epoch, sources)
    }

    /** One line per tool call on the running task, so the chat can show what she is doing right now. */
    private fun recordStep(id: String, text: String) = runCatching {
        val steps = store.get("task", id)?.optJSONArray("steps") ?: JSONArray()
        val kept = JSONArray((maxOf(0, steps.length() - 19) until steps.length()).map { steps.get(it) }).put(JSONObject().put("text", text).put("at", System.currentTimeMillis()))
        store.transitionTask(id, setOf("running"), "running", text, JSONObject().put("steps", kept))
    }

    /** A life_action as a given speaker; for tests. */
    internal fun lifeActionAs(scope: String, owner: Boolean, ownersOwnChat: Boolean, action: String, data: JSONObject = JSONObject()) =
        tool(scope, "", "life_action", JSONObject().put("action", action).put("data", data), Speaker(owner, ownersOwnChat))

    /** Tool names and life actions a speaker would get here; for tests. */
    internal fun toolsFor(scope: String, owner: Boolean, ownersOwnChat: Boolean): List<String> = declarations(scope, Speaker(owner, ownersOwnChat)).let { a ->
        (0 until a.length()).flatMap { i -> a.getJSONObject(i).let { d ->
            listOf(d.getString("name")) + (d.optJSONObject("parameters")?.optJSONObject("properties")?.optJSONObject("action")?.optJSONArray("enum")
                ?.let { e -> (0 until e.length()).map { "life:" + e.getString(it) } }.orEmpty())
        } }
    }

    /** Who is talking this turn. [owner]: the authenticated owner (app, their QQ private chat, or their QQ id in a group). */
    private data class Speaker(val owner: Boolean, val ownersOwnChat: Boolean)

    /** Groups she may speak in: (group id, scope, name). */
    private fun allowedGroups(): List<Triple<String, String, String>> {
        val account = store.settings().optString("oneBotAccount")
        val ids = store.settings().optJSONArray("qqGroups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        return ids.map { gid ->
            val groupScope = "qq:group:${CompanionPolicy.digest("onebot\u0000$account\u0000$gid").take(40)}"
            Triple(gid, groupScope, store.get("conversation", groupScope)?.optString("title").orEmpty().ifBlank { "QQ群 $gid" })
        }
    }

    /** The QQ conversation with [targetId]; the owner's own private chat is set up on first use (it is their account). */
    private fun qqConversation(type: String, targetId: String, create: Boolean): JSONObject? {
        val account = store.settings().optString("oneBotAccount").takeIf { it.isNotBlank() } ?: return null
        val qqScope = "qq:$type:${CompanionPolicy.digest("onebot\u0000$account\u0000$targetId").take(40)}"
        store.get("conversation", qqScope)?.let { return it }
        if (!create) return null
        return store.put("conversation", JSONObject().put("id", qqScope).put("scope", qqScope).put("mode", "quiet").put("autoReply", false)
            .put("captureEnabled", false).put("cooldownSeconds", 60).put("hourlyLimit", 6).put("title", "QQ $type $targetId")
            .put("account", account).put("targetId", targetId).put("targetType", type).put("route", "onebot").put("reliable", true))
    }

    /**
     * The owner tells her to send a QQ message. To the owner's own QQ or a group she may speak in, their asking is the
     * approval; to anyone else's private chat it goes to the pending list for the owner to confirm.
     */
    private fun sendQq(scope: String, args: JSONObject, speaker: Speaker, inStore: (() -> JSONObject) -> JSONObject): JSONObject {
        check(speaker.owner) { "只有主人能让你发 QQ" }
        val settings = store.settings()
        check(settings.optBoolean("oneBotEnabled") && settings.optString("oneBotAccount").isNotBlank()) { "QQ 消息桥还没连上" }
        val text = (args.optString("text").trim() + args.optString("face").split(Regex("[,，、\\s]+")).filter { it.isNotBlank() }.joinToString("") { "[表情:$it]" } +
            args.optString("sticker").trim().takeIf { it.isNotBlank() }?.let { "[表情包:$it]" }.orEmpty()).trim()
        require(text.isNotBlank()) { "要发什么？" }
        if (args.optString("group").isNotBlank()) return sayInGroup(scope, JSONObject().put("group", args.optString("group")).put("text", text), speaker, inStore)
        val ownerQq = settings.optString("ownerQqId")
        val wanted = args.optString("target").trim().ifBlank { "主人" }
        val qq = if (wanted in setOf("主人", "owner")) ownerQq else wanted
        require(qq.isNotBlank()) { "主人的 QQ 号还没设置" }
        val toOwner = qq == ownerQq
        val conversation = qqConversation("private", io.deepseekharness.mobile.companion.OneBotProtocol.numeric(qq), create = toOwner)
            ?: error("还没和这个 QQ 号私聊过，发不了")
        val target = conversation.getString("id")
        val task = inStore {
            val draft = createReplyDraft(target, CompanionPolicy.text(text, 4000), "qq", "").put("ownerRequested", toOwner)
            store.put("task", draft)
            if (toOwner) store.approveDraft(draft.getString("id"), CompanionPolicy.draftDigest(draft), automatic = true) else draft
        }
        if (!toOwner) return JSONObject().put("sent", false).put("pending", true).put("note", "发给别人的私聊放进待审清单了，主人确认才发")
        val after = CompanionRouting.deliver(context, task)
        val done = after?.optString("state") == "done"
        if (done) store.message(target, "assistant", text, "主人让我发的")
        return JSONObject().put("sent", done).put("state", after?.optString("state").orEmpty()).put("detail", after?.optString("progress").orEmpty())
    }

    /** The owner asked her to say something in one of the groups she is allowed in. Their asking is the approval. */
    private fun sayInGroup(scope: String, args: JSONObject, speaker: Speaker, inStore: (() -> JSONObject) -> JSONObject): JSONObject {
        check(speaker.owner) { "只有主人能让你去群里说话" }
        val settings = store.settings()
        val account = settings.optString("oneBotAccount")
        check(settings.optBoolean("oneBotEnabled") && account.isNotBlank()) { "QQ 消息桥还没连上" }
        val ids = settings.optJSONArray("qqGroups")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val groups = ids.map { gid ->
            val groupScope = "qq:group:${CompanionPolicy.digest("onebot\u0000$account\u0000$gid").take(40)}"
            Triple(gid, groupScope, store.get("conversation", groupScope)?.optString("title").orEmpty().ifBlank { "QQ群 $gid" })
        }
        fun choices() = JSONObject().put("sent", false).put("needChoice", true)
            .put("groups", JSONArray(groups.map { JSONObject().put("id", it.first).put("name", it.third) }))
            .put("hint", if (groups.isEmpty()) "主人还没设置你能说话的群" else "问主人要发到哪个群")
        val wanted = args.optString("group").trim()
        if (wanted.isBlank()) return choices()
        val hits = groups.filter { it.first == wanted }.ifEmpty { groups.filter { wanted in it.third || it.third.contains(wanted.removePrefix("QQ群").trim()) } }
        if (hits.size != 1) return choices()
        val (_, groupScope, title) = hits.single()
        val text = CompanionPolicy.text(args.optString("text").trim(), 4000)
        require(text.isNotBlank()) { "要说什么？" }
        val task = inStore {
            val conversation = store.get("conversation", groupScope) ?: error("这个群还没连上")
            check(conversation.optBoolean("captureEnabled")) { "这个群没开接收" }
            val draft = createReplyDraft(groupScope, text, "qq", "").put("ownerRequested", true)
            store.put("task", draft)
            store.approveDraft(draft.getString("id"), CompanionPolicy.draftDigest(draft), automatic = true)
        }
        val after = CompanionRouting.deliver(context, task)
        val state = after?.optString("state").orEmpty()
        if (state == "done") store.message(groupScope, "assistant", text, "主人让我说的")
        return JSONObject().put("sent", state == "done").put("group", title).put("state", state)
            .put("detail", after?.optString("progress").orEmpty())
    }

    /** What the owner exports from 我的 → 导出诊断日志: state summaries and the log, with keys and tokens scrubbed. */
    fun diagnostics(): String {
        val settings = store.settings()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).let { "${it.versionName} (${it.longVersionCode})" } }.getOrDefault("?")
        val tasks = store.list("task", null, 300).let { a -> (0 until a.length()).map(a::getJSONObject) }
            .filter { it.optString("state") in setOf("failed", "unknown", "waiting") }.takeLast(12)
        val text = buildString {
            append("小克诊断日志 ").append(version).append(" · ").append(java.time.LocalDateTime.now().withNano(0)).append('\n')
            append("设备：Android ").append(android.os.Build.VERSION.RELEASE).append(" / ").append(android.os.Build.MODEL).append('\n')
            append("设置：模型 ").append(settings.optString("activeModel")).append("，中转 ").append(runCatching { java.net.URI(settings.optString("endpoint")).host }.getOrNull() ?: "?")
                .append("，联网 ").append(settings.optBoolean("networkEnabled")).append("，暂停 ").append(settings.optBoolean("paused"))
                .append("，后台常驻 ").append(settings.optBoolean("backgroundEnabled")).append("，QQ ").append(settings.optBoolean("oneBotEnabled")).append('\n')
            append("QQ 桥：").append(runCatching { OneBotBridge.get(context).state().apply { remove("untrustedCertificate") } }.getOrNull()).append('\n')
            val chat = io.deepseekharness.mobile.companion.claude.ClaudeChat
            append("Claude：Claude Code ").append(runCatching { claude.version() }.getOrNull() ?: "未安装").append("，设置 ").append(chat.settings(store))
                .append("，暂停中 ").append(chat.outage(store)?.let { "${it.optString("reason")} ${it.optString("detail").take(160)}" } ?: "无")
                .append("，进行中 ").append(claudeTurns.size).append("，排队 ").append(claudeWorker.pending()).append("；Gemini 排队 ").append(worker.pending()).append('\n')
            append("\n== 正在进行或排队的聊天 ==\n")
            store.list("task", null, 300).let { a -> (0 until a.length()).map(a::getJSONObject) }
                .filter { it.optString("type") == "chat" && it.optString("state") in setOf("queued", "running") }.forEach { t ->
                    append("- ").append(t.optString("engine", "gemini")).append(' ').append(t.optString("state")).append(' ')
                        .append((System.currentTimeMillis() - t.optLong("updatedAt", t.optLong("createdAt"))) / 1000).append(" 秒前更新：")
                        .append(t.optString("progress").take(160)).append('\n')
                }
            append("\n== 最近失败或待核对的任务 ==\n")
            tasks.forEach { t ->
                append("- ").append(t.optString("type")).append(' ').append(t.optString("state")).append("：").append(t.optString("progress").take(200)).append('\n')
                t.optJSONObject("diagnostic")?.optJSONArray("attempts")?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { at ->
                    append("    · ").append(at.optString("model")).append(' ').append(at.opt("status")).append(' ').append(at.optString("error")).append(" | ").append(at.optString("detail").take(300)).append('\n')
                } }
            }
            append("\n== 没发出去的回复 ==\n").append(io.deepseekharness.mobile.companion.chat.DeliveryLog.describe(store, CompanionPolicy.OWNER, true)).append('\n')
            append("\n== 后台任务 ==\n").append(io.deepseekharness.mobile.companion.chat.BackgroundJobs.items(store, CompanionPolicy.OWNER, true)
                .joinToString("\n") { io.deepseekharness.mobile.companion.chat.BackgroundJobs.line(it) }).append('\n')
            append("\n== 日志 ==\n").append(XLog.read())
        }
        return XLog.scrub(text)
    }

    /** One tiny real request with the saved settings, so setup can say "connected" or show the relay's answer. */
    fun testModel(): JSONObject {
        val settings = store.settings()
        val profiles = settings.getJSONArray("profiles")
        val profile = (0 until profiles.length()).map(profiles::getJSONObject).firstOrNull { it.getString("model") == settings.getString("activeModel") }
            ?: return JSONObject().put("ok", false).put("error", "模型 ${settings.optString("activeModel")} 不在配置列表里")
        val probe = JSONObject(profile.toString()).put("maxOutputTokens", 256).put("maxHops", 1).put("tools", false)
        val started = System.currentTimeMillis()
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "只回复 OK"))))
        return try {
            GeminiClient(secrets).generate(settings, probe, contents, "连通测试", JSONArray(), progress = {})
            JSONObject().put("ok", true).put("model", profile.getString("model")).put("seconds", (System.currentTimeMillis() - started) / 1000.0)
        } catch (e: ModelFailure) {
            JSONObject().put("ok", false).put("model", profile.getString("model")).put("error", e.message).put("detail", e.detail.take(300))
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("model", profile.getString("model")).put("error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** A reminder the owner set in their QQ chat arrives there too, as her message. */
    fun remindOverQq(scope: String, text: String) {
        check(modules.principal(scope).owner) { "只给主人的 QQ 私聊发提醒" }
        val draft = createReplyDraft(scope, text, "qq", "").put("ownerRequested", true)
        store.put("task", draft)
        val approved = store.approveDraft(draft.getString("id"), CompanionPolicy.draftDigest(draft), automatic = true)
        Thread({ if (CompanionRouting.deliver(context, approved)?.optString("state") == "done") store.message(scope, "assistant", text, "到期提醒") }, "xiaoke-reminder").start()
    }

    fun createReplyDraft(scope: String, body: String, platform: String, observationId: String): JSONObject {
        val conversation = store.get("conversation", scope)
        return store.put("task", JSONObject().put("scope", scope).put("type", "send")
            .put("state", "draft").put("body", CompanionPolicy.text(body, 4000))
            .put("target", conversation?.optString("title") ?: scope)
            .put("package", if (platform == "bili") "tv.danmaku.bili" else "com.tencent.mobileqq")
            .put("account", conversation?.optString("account").orEmpty()).put("route", conversation?.optString("route", "notification") ?: "ui")
            .put("observationId", observationId).put("ownerRequested", modules.principal(scope).owner))
    }

    private fun tool(scope: String, sourceId: String, name: String, args: JSONObject, speaker: Speaker): JSONObject = when (name) {
        "remember" -> {
            val actualSource = args.optString("sourceId").ifBlank { sourceId }
            val message = store.get("message", actualSource) ?: error("记忆来源消息不存在")
            check(message.optString("scope") == scope) { "不能借用其他会话的记忆来源" }
            val data = JSONObject().put("scope", scope).put("text", args.getString("text")).put("kind", args.optString("kind", "fact"))
                .put("factKey", args.optString("factKey")).put("sourceRefs", JSONArray().put(JSONObject().put("kind", "message").put("id", actualSource).put("scope", scope)))
            val anchor = io.deepseekharness.mobile.companion.memory.TimeAnchor
            anchor.parse(args.optString("validFrom"))?.let { data.put("validFrom", anchor.startMs(it)) }
            anchor.parse(args.optString("validUntil"))?.let { data.put("validUntil", anchor.startMs(it.plusDays(1)) - 1) }
            if (data.optString("kind") == "opinion") data.put("holder", "her")
            data.put("entities", JSONArray(args.optString("entities").split(',', '，', '、').map { it.trim() }.filter { it.isNotBlank() }.take(5)))
            // A host extraction can save grounded facts in its own scope; remote JSON never gains an owner principal.
            val repo = LifeMemoryRepository(store)
            val saved = repo.upsertMemory(scope, data.put("sourceId", actualSource).put("source", "本会话有来源的自动记录"), fromModel = true)
            args.optString("ends").takeIf { it.isNotBlank() }?.let { old ->
                runCatching { repo.end(scope, old, saved.optLong("validFrom", System.currentTimeMillis()), saved.getString("id")) }
            }
            modules.memoryIndex.refresh()
            saved
        }
        "poke" -> {
            val wanted = args.optString("target").trim(); val group = args.optString("group").trim()
            if (speaker.owner && (wanted.isNotEmpty() || group.isNotEmpty() || store.get("conversation", scope)?.optString("route") != "onebot")) {
                // The owner names who to poke, from anywhere (the app included): their QQ, a QQ number, optionally in a group.
                val settings = store.settings()
                val qq = if (wanted.isEmpty() || wanted in setOf("主人", "owner")) settings.optString("ownerQqId") else wanted
                require(qq.isNotBlank()) { "主人的 QQ 号还没设置" }
                val conversation = if (group.isNotEmpty()) allowedGroups().singleOrNull { it.first == group || group in it.third }?.second?.let { store.get("conversation", it) }
                        ?: error("找不到能去的这个群")
                    else qqConversation("private", io.deepseekharness.mobile.companion.OneBotProtocol.numeric(qq), create = qq == settings.optString("ownerQqId"))
                        ?: error("还没和这个 QQ 号聊过，戳不了")
                io.deepseekharness.mobile.companion.chat.QqExtras.poke(context, conversation, io.deepseekharness.mobile.companion.OneBotProtocol.numeric(qq), true)
            } else {
                val conversation = store.get("conversation", scope) ?: error("会话不存在")
                check(conversation.optString("route") == "onebot") { "这里不能戳" }
                val target = if (conversation.optString("targetType") == "private") conversation.getString("targetId")
                    else store.get("message", sourceId)?.optString("actorId")?.removePrefix("qq:")?.takeIf { it.isNotBlank() } ?: error("不知道该戳谁")
                io.deepseekharness.mobile.companion.chat.QqExtras.poke(context, conversation, target, speaker.owner)
            }
        }
        "web_research" -> {
            val (settings, profile, bill) = activeWeb ?: error("现在不能联网查")
            web.research(settings, profile, args.optString("query"), args.optString("urls").split(Regex("[\\s,，]+")).filter { it.isNotBlank() }, bill)
        }
        "recall" -> recallMemories(scope, args.optString("query"), forTool = true).let { found ->
            JSONObject().put("memories", found).put("messages", searchMessages(scope, args.optString("query"))).put("note", recallNote(found))
        }
        "job_status" -> {
            val ownerView = speaker.ownersOwnChat
            val items = io.deepseekharness.mobile.companion.chat.BackgroundJobs.items(store, scope, ownerView).takeLast(10)
            // Reminders and summaries waiting for their time live in the social ledger; they are jobs too.
            val clock = java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm")
            val scheduled = runCatching { modules.router.execute("social", "jobs.list", JSONObject(), if (ownerView) modules.localOwner else modules.principal(scope)).optJSONArray("items") }.getOrNull()
                ?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty().filter { it.optString("state") in setOf("queued", "running", "waiting") }.takeLast(10)
            JSONObject().put("jobs", JSONArray(items.map { JSONObject().put("line", io.deepseekharness.mobile.companion.chat.BackgroundJobs.line(it)).put("state", it.optString("state")).put("id", it.optString("id")).put("observationId", it.optString("observationId")) }))
                .put("scheduled", JSONArray(scheduled.map { j -> JSONObject().put("id", j.optString("id")).put("state", j.optString("state"))
                    .put("line", "${if (j.optString("kind") == "reminder") "提醒" else "摘要"} ${clock.format(java.time.Instant.ofEpochMilli(j.optLong("dueAt")).atZone(java.time.ZoneId.systemDefault()))}：${j.optString("text").take(80)}") }))
                .put("note", if (items.isEmpty() && scheduled.isEmpty()) "最近没有后台任务" else "没写结果的就是还不知道结果；job_cancel 可以取消排着或在跑的")
        }
        "job_cancel" -> {
            val jobId = args.getString("id").trim()
            when {
                jobId.startsWith("job-") -> modules.router.execute("social", "jobs.cancel", JSONObject().put("id", jobId), if (speaker.owner) modules.localOwner else modules.principal(scope))
                    .let { JSONObject().put("cancelled", it.optString("state") == "cancelled").put("state", it.optString("state")) }
                jobId.startsWith("bg-") -> { check(speaker.owner || scope.startsWith("qq:group:")) { "不能取消这个" }
                    JSONObject().put("cancelled", modules.biliWatcher.cancel(jobId)).put("note", "正在下载的那一步做完就停") }
                else -> throw IllegalArgumentException("id 用 job_status 里列出的（job-… 或 bg-…）")
            }
        }
        "bili_search" -> {
            val kind = if (args.optString("kind") == "article") "article" else "video"
            JSONObject().put("results", JSONArray(modules.social.biliReader().search(args.getString("query").take(100), kind, 8)))
                .put("note", "挑一个再用 watch_bilibili 看（videoId 填 BV 号，专栏填 articleId）")
        }
        "life_action" -> {
            val action = args.getString("action")
            require(action in LIFE_ACTIONS) { "没有这个生活操作" }
            require(speaker.owner || action in setOf("memory.search", "interest.list", "diary.list")) { "只有主人能改这些" }
            // Forgetting and deleting reach the owner's own memory, so only from the owner's own chats, never a group.
            require(speaker.ownersOwnChat || action !in OWN_CHAT_LIFE_ACTIONS) { "这个要在主人的私聊里说" }
            val data = JSONObject((args.optJSONObject("data") ?: JSONObject()).toString())
            data.remove("includeForgotten"); data.remove("includeHistory")
            // The record's own scope when it is one of the owner's chats (app and QQ private share memory); otherwise this one.
            val recordScope = data.optString("id").takeIf { it.isNotBlank() && speaker.ownersOwnChat }?.let { id ->
                (store.get("memory", id) ?: store.get("diary", id))?.optString("scope")?.takeIf { it == CompanionPolicy.OWNER || modules.principal(it).owner }
            }
            data.put("scope", recordScope ?: scope)
            if (action == "interest.upsert") { data.remove("id"); data.put("sourceRefs", JSONArray().put(JSONObject().put("kind", "message").put("id", sourceId).put("scope", scope))) }
            // Reading in a group always goes as the group, even when the owner is the one asking: the owner principal skips
            // the private-derivation filter, and what is read here goes into a reply posted in the group.
            val principal = if (speaker.ownersOwnChat || (speaker.owner && action in setOf("interest.upsert", "attention.set"))) modules.localOwner
                else modules.principal(scope)
            when (action) {
                "pending.list" -> JSONObject().put("items", JSONArray(modules.pendingApprovals().mapNotNull { id ->
                    store.get("task", id)?.let { t -> JSONObject().put("id", id).put("what", "发给 ${t.optString("target")}：${t.optString("body").take(120)}") }
                        ?: JSONObject().put("id", id).put("what", "外发或入库审批（在动态页的待审清单里看）")
                }))
                "budget.status" -> modules.router.execute("life", "budget.status", data, principal)
                "attention.status" -> modules.router.execute("life", "attention.status", data, principal).put("budget",
                    runCatching { modules.router.execute("life", "budget.status", JSONObject(), principal) }.getOrNull())
                else -> modules.router.execute("life", action, data, principal)
            }
        }
        "social_plan" -> {
            val data = JSONObject((args.optJSONObject("data") ?: JSONObject()).toString())
            data.put("idempotencyKey", CompanionPolicy.digest("$sourceId\u0000${data}"))
                .put("scope", scope).put("sourceIds", JSONArray().put(sourceId))
            if (data.optString("kind") == "reply") {
                val bound = store.get("conversation", scope) ?: error("缺少已绑定的目标会话")
                check(bound.optString("route") == "onebot" && bound.optBoolean("reliable")) { "目标会话尚未核对" }
                data.put("account", bound.getString("account")).put("target", bound.getString("targetId"))
                    .put("payload", JSONObject().put("targetType", bound.getString("targetType")))
            }
            // Model-authored plans never inherit the owner's direct-publication exception.
            modules.router.execute("social", "social.plan", data, CommandPrincipal("companion", "background", scope, false))
        }
        "background_task" -> {
            val data = JSONObject((args.optJSONObject("data") ?: JSONObject()).toString())
            require(data.optString("kind") in setOf("summary", "reminder")) { "此入口只接受摘要与提醒" }
            if (data.optString("kind") == "summary" && data.optString("condition").isBlank()) data.put("condition", "online")
            if (data.has("dueAt")) data.put("dueAt", io.deepseekharness.mobile.companion.chat.BackgroundJobs.dueAt(data.opt("dueAt")) ?: throw IllegalArgumentException("dueAt 看不懂，用“2026-11-04 09:00”这样的写法"))
            if (data.optString("kind") == "reminder") require(data.optLong("dueAt") > System.currentTimeMillis()) { "提醒要写将来的时间 dueAt，比如“2026-11-04 09:00”" }
            data.put("idempotencyKey", CompanionPolicy.digest("$sourceId\u0000${data}"))
                .put("scope", scope).put("sourceIds", JSONArray().put(sourceId))
            modules.router.execute("social", "jobs.create", data, modules.principal(scope))
        }
        "device_action" -> {
            check(speaker.ownersOwnChat) { "代操作手机要在主人的私聊里说（App 或 QQ 私聊）" }
            val action = args.getString("action")
            require(action in DEVICE_ACTIONS) { "不支持的设备动作" }
            val data = JSONObject((args.optJSONObject("data") ?: JSONObject()).toString())
            if (action !in setOf("control.start", "control.status", "capture.status", "capture.refresh", "notes.add")) data.put("requestId", CompanionPolicy.digest("$sourceId\u0000$action\u0000${data}"))
            if (action == "control.start") modules.device.delegateFromChat(data.optString("packageName"), data.optInt("durationSeconds", 600), data.optBoolean("background"))
            else if (action == "notes.add") addGameNote(sourceId, data.getString("text"))
            else if (action == "capture.refresh") {
                val control = modules.device.execute("control.status", JSONObject(), modules.localOwner)
                val capture = modules.device.execute("capture.status", JSONObject(), modules.localOwner)
                check(io.deepseekharness.mobile.companion.devicev2.DeviceModule.permitsObservation(capture.optString("packageName"))) { "观察授权已结束，请在本机重新开启" }
                if (control.optString("state") == "authorized") check(capture.optString("packageName") == control.optString("packageName")) { "观察与控制目标不一致，请在连接页重新选择" }
                io.deepseekharness.mobile.companion.devicev2.DeviceModule.captureFromSurface()
            } else modules.router.execute("device", action, data, modules.localOwner)
        }
        "propose_save" -> {
            check(!speaker.ownersOwnChat) { "主人的东西直接存就好" }
            val observationId = activeTask?.let { store.get("task", it) }?.optString("observationId").orEmpty()
            val observation = store.get("observation", observationId) ?: error("这条消息里没有你看到的图片")
            check(observation.optString("scope") == scope) { "图片不属于这个会话" }
            val parts = observation.optJSONArray("parts") ?: JSONArray()
            val images = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("inlineData") }
                .filter { it.optString("mimeType").startsWith("image/") }.take(3)
                .map { it.getString("mimeType") to android.util.Base64.decode(it.getString("data"), android.util.Base64.DEFAULT) }
            check(images.isNotEmpty()) { "这条消息里没有能保存的图片" }
            modules.social.proposeMediaSave(scope, sourceId, images, args.getString("title").take(140), args.optString("reason"))
            JSONObject().put("state", "pending").put("note", "已放进待审清单，主人确认后才进资料库")
        }
        "watch_bilibili" -> {
            check(speaker.owner || scope.startsWith("qq:group:")) { "这里不能引出看视频" }
            modules.biliWatcher.start(args.optString("videoId").trim(), args.optString("query").trim(), args.optString("reason"), sourceId,
                args.optString("articleId").removePrefix("cv").trim().toLongOrNull() ?: 0, if (args.optString("kind") == "article") "article" else "video")
        }
        "observe_screen" -> {
            check(speaker.ownersOwnChat) { "看屏幕要在主人的私聊里说" }
            CompanionDevice(context).observe()
        }
        "open_app" -> {
            check(speaker.ownersOwnChat) { "打开应用要在主人的私聊里说" }
            CompanionDevice(context).open(args.getString("package"), args.optString("videoId"))
        }
        "draft_comment" -> {
            check(speaker.ownersOwnChat) { "评论草稿要在主人的私聊里说" }
            val observation = store.get("observation", args.getString("observationId")) ?: error("没有这份视频资料")
            check(observation.optString("scope").let { it == CompanionPolicy.OWNER || it == scope } && observation.optString("videoId").isNotBlank()) { "这份资料不是主人这边的视频" }
            val inCurrentRequest = activeTask?.let { store.get("task", it)?.optString("observationId") } == observation.getString("id")
            check(observation.optBoolean("analyzed") || inCurrentRequest) { "请先将这份资料附加给模型分析" }
            val analysis = CompanionPolicy.text(args.getString("analysis"), 8000)
            store.put("observation", observation.put("analyzed", true).put("analysis", analysis))
            val videoId = observation.getString("videoId")
            val targetScope = "bili:public:${CompanionPolicy.digest(videoId).take(32)}"
            val draft = createReplyDraft(targetScope, args.getString("body"), "bili", observation.getString("id"))
            store.put("task", draft.put("target", videoId).put("evidenceSummary", analysis).put("coverage", observation.optString("coverage")))
        }
        else -> error("工具不在当前允许范围")
    }

    /** Her experience with a game: kept with the game for play, and as a memory the owner can read, edit or delete. */
    private fun addGameNote(sourceId: String, text: String): JSONObject {
        val pkg = modules.device.execute("control.status", JSONObject(), modules.localOwner).optString("packageName")
            .ifBlank { modules.device.execute("capture.status", JSONObject(), modules.localOwner).optString("packageName") }
        check(pkg.isNotBlank()) { "现在没有在玩的游戏" }
        val sourceScope = store.get("message", sourceId)?.optString("scope").orEmpty()
        check(sourceScope == CompanionPolicy.OWNER || modules.principal(sourceScope).owner) { "游戏经验要来自主人这边的对话" }
        val label = runCatching { context.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        val memory = LifeMemoryRepository(store).upsertMemory(CompanionPolicy.OWNER, JSONObject().put("text", "玩《$label》的经验：${text.take(300)}").put("kind", "fact")
            .put("sourceId", sourceId).put("source", "代玩游戏时自己总结")
            .put("sourceRefs", JSONArray().put(JSONObject().put("kind", "message").put("id", sourceId).put("scope", sourceScope))), fromModel = true, sourceScope = sourceScope)
        modules.device.notes.addNote(pkg, text, memory.optString("id"))
        return JSONObject().put("state", "saved").put("game", label)
    }

    /** Hybrid recall: meaning (vectors), words and named things together, so today's words can bring back last week's talk. */
    /**
     * Memories for this conversation. The owner's private domain is one (app chat and their own QQ chat read each
     * other's), and the owner's side also reads, without writing, what she learned in the groups she is in, labelled
     * with the group. Never the other way: a group does not see the owner's memories. [forTool]: an explicit recall,
     * cut to the few that actually match.
     */
    private fun recallMemories(scope: String, query: String, forTool: Boolean = false): JSONArray {
        val records = store.memoriesFor(scope, query, 2000)
        val settings = store.settings()
        val ownerSide = scope == CompanionPolicy.OWNER || CompanionPolicy.sharesOwnerMemory(store.get("conversation", scope), settings)
        val other = when {
            scope == CompanionPolicy.OWNER -> CompanionPolicy.ownerQqScope(settings)?.takeIf { store.get("conversation", it)?.let { c -> CompanionPolicy.sharesOwnerMemory(c, settings) } == true }
            ownerSide -> CompanionPolicy.OWNER
            else -> null
        }
        if (other != null) {
            val owner = store.memoriesFor(other, query, 2000)
            for (i in 0 until owner.length()) records.put(owner.getJSONObject(i))
        }
        if (ownerSide) for ((_, groupScope, title) in allowedGroups()) {
            val learned = store.memoriesFor(groupScope, query, 300)
            for (i in 0 until learned.length()) records.put(JSONObject(learned.getJSONObject(i).toString()).put("fromGroup", title))
        }
        val list = (0 until records.length()).map(records::getJSONObject)
        val semantic = runCatching { modules.memoryIndex.semantic(query, list) }.onFailure { XLog.w("memory vectors unavailable, matching on words only", it) }.getOrNull()
        // An explicit recall casts a wider net than the automatic pick, so looking again can find what the first pass left out.
        val ranked = if (forTool) MemoryRanking.scored(list, query, 8, semantic, relative = 0.5)
            else MemoryRanking.scored(list, query, 40, semantic)
        return JSONArray().also { out -> ranked.forEach { (m, relevance) ->
            out.put(io.deepseekharness.mobile.companion.memory.MemoryView.project(m).put("relevance", Math.round(relevance * 100) / 100.0)
                .also { v -> m.optString("fromGroup").takeIf { it.isNotBlank() }?.let { v.put("fromGroup", it) } })
        } }
    }

    /** What she is told with a recall result, so a near miss is not taken for a memory. */
    private fun recallNote(memories: JSONArray): String {
        val best = (0 until memories.length()).maxOfOrNull { memories.getJSONObject(it).optDouble("relevance") } ?: 0.0
        return (if (memories.length() == 0 || best < WEAK_RECALL) "这些和问题关系都不大，很可能没聊过。没印象就直接说没印象，别顺着相似的记录往下编。"
            else "relevance 是和问题的相关度（0–1），低于 $WEAK_RECALL 的只是字面相近。") +
            "只按记录里写了的回答：记录说的是相关的事、却没有问的那一点时，就说不记得这一点。没列出的可以换个说法再查。fromGroup 是在那个群里知道的，只读。"
    }

    /** Earlier messages in this conversation by meaning-ish match (the same bigram scoring as memories), newest first on ties. */
    private fun searchMessages(scope: String, query: String): JSONArray {
        val all = store.contextMessages(scope, 3000).let { a -> (0 until a.length()).map(a::getJSONObject) }
        val hits = MemoryRanking.scored(all, query, 10, null, 0.4)
        return JSONArray().also { result -> hits.forEach { (it, relevance) ->
            result.put(JSONObject().put("id", it.getString("id")).put("scope", it.getString("scope")).put("text", it.optString("text").take(2000))
                .put("source", it.optString("source")).put("at", it.optLong("createdAt")).put("relevance", Math.round(relevance * 100) / 100.0))
        } }
    }

    /** The everyday flash profile: the active model when it is a flash one, otherwise the first flash in the list. */
    private fun flashProfile(settings: JSONObject, fallback: JSONObject): JSONObject {
        val all = settings.getJSONArray("profiles").let { a -> (0 until a.length()).map(a::getJSONObject) }
        return all.firstOrNull { it.getString("model") == settings.optString("activeModel") && "flash" in it.getString("model") }
            ?: all.firstOrNull { "flash" in it.getString("model") } ?: fallback
    }

    /**
     * The tools for this turn. What she can do follows [speaker]; what it acts on is this conversation. The owner gets
     * the same set in the app, their QQ private chat and (by QQ id) in a group, except the phone itself, which is only
     * reachable from their own chats: a screen read in a group would land in the group.
     */
    private fun declarations(scope: String, speaker: Speaker, webTool: Boolean = false): JSONArray {
        val settings = store.settings()
        val qqReady = settings.optBoolean("oneBotEnabled") && settings.optString("oneBotAccount").isNotBlank()
        val stickers = if (speaker.owner && qqReady) store.lifeState("stickerShelf").optJSONArray("items")?.let { rows ->
            (0 until rows.length()).map { rows.getJSONObject(it).optString("name") }
        }.orEmpty() else emptyList()
        return CompanionTools.declarations(CompanionTools.Input(scope, speaker.owner, speaker.ownersOwnChat,
            qqReady, store.get("conversation", scope)?.optString("route") == "onebot",
            modules.router.available("life"), modules.router.available("social"), modules.router.available("device"), stickers, webTool))
    }

    companion object {
        /** Below this, a recalled memory only looks like the question (measured on the independent memory set). */
        private const val WEAK_RECALL = 0.5
        private val LIFE_ACTIONS = CompanionTools.LIFE_ACTIONS
        private val OWN_CHAT_LIFE_ACTIONS = CompanionTools.OWN_CHAT_LIFE_ACTIONS
        private val DEVICE_ACTIONS = CompanionTools.DEVICE_ACTIONS
        @Volatile private var instance: CompanionEngine? = null
        fun get(context: Context): CompanionEngine = instance ?: synchronized(this) {
            instance ?: CompanionEngine(context.applicationContext).also { instance = it }
        }
    }
}
