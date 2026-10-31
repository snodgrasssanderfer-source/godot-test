package io.deepseekharness.mobile.companion.devicev2

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import io.deepseekharness.mobile.AppForeground
import io.deepseekharness.mobile.companion.CompanionDevice
import io.deepseekharness.mobile.companion.CompanionOverlayService
import io.deepseekharness.mobile.companion.CompanionPresenceHost
import io.deepseekharness.mobile.companion.iteration.*
import io.deepseekharness.mobile.companion.presence.PresenceContextGate
import io.deepseekharness.mobile.companion.presence.PresenceContexts
import io.deepseekharness.mobile.companion.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Real native device endpoint. No model credentials, external accounts, persistent frames or PCM. */
class DeviceModule(context: Context, private val ports: HostPorts, store: io.deepseekharness.mobile.companion.CompanionStore) : ModuleEndpoint {
    override val domain = "device"
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureEpoch = AtomicLong()
    private val input = GameInputController(app, ::changed)
    private val frames = linkedMapOf<String, ControlFrame>()
    private val grays = linkedMapOf<String, FrameMatcher.Gray>()
    val notes = GameNotes(store)
    /** Successful steps since control started (or the last saved route), for route.save. */
    private val recording = mutableListOf<JSONObject>()
    @Volatile private var backgroundSession: BackgroundScreen.Session? = null
    @Volatile private var capture = JSONObject().put("state", "idle").toString()
    @Volatile private var capturePackage = ""
    @Volatile private var captureDeadline = 0L
    @Volatile private var captureSession = ""
    @Volatile private var analysis = JSONObject().put("state", "idle").toString()
    @Volatile private var analysisGeneration = ""
    @Volatile private var analysisRequest = ""
    private val analysisLock = Any()
    private val analysisContext = PresenceContextGate { PresenceContexts.access }
    private val lastContentEpoch = AtomicLong(-1)
    @Volatile private var closed = false
    private val preferences = app.getSharedPreferences("companion-device-v2", Context.MODE_PRIVATE)
    @Volatile private var surfacePrincipal: CommandPrincipal? = null
    @Volatile private var audioConfig = runCatching { JSONObject(preferences.getString("audio", "{}")!!) }.getOrDefault(JSONObject())

    init {
        // A module host that failed half-way through construction leaves its device module behind; retire it so a retry can start.
        active?.let { old -> runCatching { old.close() } }
        active = this; DeviceAudioSession.onChanged = ::changed
        EmergencyHold.attach(app)
    }
    override fun snapshot(principal: CommandPrincipal): JSONObject {
        requireOwner(principal)
        return JSONObject().put("capture", JSONObject(capture)).put("audio", DeviceAudioSession.state().put("configuration", JSONObject(audioConfig.toString())).put("analysis", JSONObject(analysis)))
            .put("control", input.state()).put("overlay", CompanionOverlayService.state().put("globalFiveSecondGesture", false))
            .put("voice", CompanionPresenceHost.state())
    }
    override fun execute(action: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        requireOwner(principal); check(!closed); surfacePrincipal = principal
        return when (action) {
            "overlay.visibility" -> {
                val visible = data.getBoolean("visible")
                if (visible) check(Settings.canDrawOverlays(app)) { "请先开启悬浮窗权限" }
                onMainAndWait { CompanionOverlayService.setVisible(app, visible) }
                JSONObject().put("state", "requested").put("visible", visible)
            }
            "capture.request" -> requestCapture(data)
            "capture.stop" -> { endBackground(); captureEpoch.incrementAndGet(); captureDeadline = 0; capturePackage = ""; synchronized(frames) { frames.clear(); grays.clear() }
                capture = JSONObject().put("state", "stopped").toString(); changed(); JSONObject(capture) }
            "capture.status" -> JSONObject(capture)
            "control.start", "control.resume" -> {
                check(data.optBoolean("singlePlayer")) { "只支持本机单人游戏，需主人明确确认" }
                val pkg = packageName(data)
                checkGame(pkg)
                authorize(principal, pkg, data.optString("requestId"), null)
                input.start(pkg, data.optInt("durationSeconds", 120), data.getLong("expectedInputEpoch")).also {
                    // An explicit owner start in the control panel is at least as strong as saying "继续".
                    EmergencyHold.releaseOrdinary(); resumable = null
                }
            }
            "control.stop" -> stopInput("owner_stop")
            "control.status" -> input.state()
            "control.tap", "control.swipe", "control.longPress", "control.hold", "control.multi" -> perform(action.substringAfter('.'), data, principal)
            "landmark.save" -> saveLandmark(data)
            "landmark.find" -> findLandmark(data.getString("name"))
            "landmark.tap" -> tapLandmark(data, principal)
            "landmark.forget" -> notes.forget(currentGame(), "landmarks", data.getString("name"))
            "control.waitFor" -> waitFor(data)
            "route.save" -> saveRoute(data.getString("name"))
            "route.replay" -> replay(data, principal)
            "route.forget" -> notes.forget(currentGame(), "routes", data.getString("name"))
            "audio.configure" -> {
                check(DeviceAudioSession.state().optString("state") !in setOf("awaiting_permission", "starting", "recording", "stopping")) { "请先结束当前收音" }
                val c = decodeAudioConfig(data)
                audioConfig = JSONObject().put("microphone", c.microphone).put("playback", c.playback).put("durationSeconds", c.seconds).put("packageName", c.packageName)
                check(preferences.edit().putString("audio", audioConfig.toString()).commit()) { "音频设置保存失败" }
                changed(); JSONObject(audioConfig.toString())
            }
            "audio.start" -> {
                check(AppForeground.isForeground()) { "请回到助手界面开始收音" }
                val config = decodeAudioConfig(audioConfig)
                if (config.playback) app.packageManager.getApplicationInfo(config.packageName, 0)
                cancelAnalysis()
                val ticket = DeviceAudioSession.prepare(config)
                try { app.startActivity(Intent(app, DeviceConsentActivity::class.java).putExtra("ticket", ticket).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { DeviceAudioSession.ended(ticket, "error", "系统权限页面启动失败；请确认集成声明完整"); throw IllegalStateException("无法开启音频权限页面") }
                JSONObject().put("jobId", ticket).put("state", "awaiting_permission")
            }
            "audio.stop" -> { DeviceAudioSession.stop(data.optString("captureSession")); DeviceAudioSession.state() }
            "audio.read" -> {
                val bytes = DeviceAudioSession.read(data.getString("captureSession"), data.getString("source"))
                JSONObject().put("captureSession", data.getString("captureSession")).put("source", data.getString("source"))
                    .put("mime", "audio/wav").put("base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            "audio.cancelAnalysis" -> { cancelAnalysis(); analysis = JSONObject().put("state", "cancelled").toString(); changed(); JSONObject(analysis) }
            "audio.analyze" -> analyze(data)
            "speech.interrupt" -> { CompanionPresenceHost.command("presenceInterrupt", JSONObject()); JSONObject().put("state", "requested") }
            else -> error("不支持的设备动作：$action")
        }
    }
    private fun requestCapture(data: JSONObject): JSONObject {
        val pkg = packageName(data)
        check(Settings.canDrawOverlays(app)) { "请先开启悬浮窗权限，以便在目标应用上控制本次观察" }
        val launch = app.packageManager.getLaunchIntentForPackage(pkg) ?: error("无法打开目标应用")
        val generation = captureEpoch.incrementAndGet()
        val job = UUID.randomUUID().toString()
        capturePackage = pkg; captureSession = UUID.randomUUID().toString(); captureDeadline = SystemClock.elapsedRealtime() + 10 * 60_000
        val permissionSession = captureSession
        capture = JSONObject().put("state", "queued").put("jobId", job).put("packageName", pkg).toString()
        main.postDelayed({
            if (captureSession == permissionSession && !closed) {
                captureEpoch.incrementAndGet(); captureDeadline = 0; capturePackage = ""
                synchronized(frames) { frames.clear() }; capture = JSONObject().put("state", "expired").toString(); changed()
            }
        }, 10 * 60_000L)
        main.post {
            if (captureEpoch.get() == generation && !closed) runCatching {
                CompanionOverlayService.setVisible(app, true); app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure {
                if (captureEpoch.compareAndSet(generation, generation + 1)) {
                    captureDeadline = 0; capturePackage = ""
                    capture = JSONObject().put("state", "error").put("jobId", job).put("error", "系统拒绝打开应用或悬浮球，请重新确认权限").toString(); changed()
                }
            }
        }
        worker.execute {
            try {
                Thread.sleep(900) // Allow the explicitly selected application to reach foreground.
                if (captureEpoch.get() == generation && !closed) captureFrame(pkg, generation)
            } catch (e: Exception) {
                if (captureEpoch.get() == generation && !closed) { capture = JSONObject().put("state", "error").put("jobId", job).put("error", e.message?.take(200)).toString(); changed() }
            }
        }
        changed(); return JSONObject().put("jobId", job).put("state", "queued")
    }
    private class Shot(val jpeg: ByteArray, val gray: FrameMatcher.Gray, val windowId: Int, val rotation: Int, val left: Int, val top: Int,
        val right: Int, val bottom: Int, val tree: String, val displayId: Int, val covers: String)

    /** Background display first, then a Shizuku full-screen frame, then the accessibility window screenshot. */
    private fun shoot(pkg: String): Shot {
        backgroundSession?.let { session ->
            check(BackgroundScreen.session(pkg)?.displayId == session.displayId) { "后台副屏已关闭" }
            val bitmap = BackgroundScreen.frame(session)
            try { return Shot(ShizukuInput.jpeg(bitmap), ShizukuInput.gray(bitmap), -1, 0, 0, 0, bitmap.width, bitmap.height, "", session.displayId, "virtual_display") }
            finally { bitmap.recycle() }
        }
        val device = CompanionDevice(app)
        val rotation = GameInputController.rotation(app)
        val before = device.observe(pkg)
        val full = if (ShizukuInput.ready()) runCatching { ShizukuInput.screen(app) }.getOrNull() else null
        val jpeg = if (full == null) device.screenshot(pkg) else null
        val after = device.observe(pkg)
        check(before.optInt("windowId") == after.optInt("windowId") && rotation == GameInputController.rotation(app) && before.getJSONArray("nodes").toString() == after.getJSONArray("nodes").toString()) { "截图期间窗口发生变化" }
        val bounds = after.getJSONArray("nodes").getJSONObject(0).getJSONArray("bounds")
        val left = bounds.getInt(0); val top = bounds.getInt(1); val right = bounds.getInt(2); val bottom = bounds.getInt(3)
        val tree = after.getJSONArray("nodes").toString().toByteArray().sha256()
        if (full != null) try {
            return Shot(ShizukuInput.jpeg(full), ShizukuInput.gray(full), after.getInt("windowId"), rotation, left, top, right, bottom, tree, 0, "display")
        } finally { full.recycle() }
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg!!.size) ?: error("截图无法解码")
        try { return Shot(jpeg, ShizukuInput.gray(bitmap, left, top, (right - left).toFloat() / bitmap.width), after.getInt("windowId"), rotation, left, top, right, bottom, tree, 0, "window") }
        finally { bitmap.recycle() }
    }

    private fun captureFrame(pkg: String, generation: Long): JSONObject {
        check(permits(pkg)) { "观察授权已结束" }
        val capturedAt = SystemClock.elapsedRealtime()
        val shot = shoot(pkg)
        val id = UUID.randomUUID().toString()
        val frame = ControlFrame(id, captureSession, pkg, capturedAt, shot.windowId, shot.rotation, shot.left, shot.top, shot.right, shot.bottom,
            shot.tree, shot.jpeg.sha256(), shot.displayId, FrameMatcher.fingerprint(shot.gray))
        check(frame.right > frame.left && frame.bottom > frame.top)
        val result = JSONObject().put("state", "ready").put("frameId", id).put("captureSession", captureSession).put("packageName", pkg)
            .put("screenHash", frame.screenHash).put("capturedAtMs", frame.atMs).put("rotation", frame.rotation).put("left", frame.left).put("top", frame.top)
            .put("width", frame.right - frame.left).put("height", frame.bottom - frame.top).put("windowId", frame.windowId)
            .put("image", when (shot.covers) {
                "display" -> "整块屏幕；图片按比例缩小，坐标用屏幕像素（图片坐标 × 屏幕宽 / 图片宽）"
                "virtual_display" -> "后台副屏；坐标用副屏像素（左上角为 0,0）"
                else -> "游戏窗口；坐标用屏幕像素（left/top 加上按比例换算的图片坐标）"
            })
            .put("preview", "data:image/jpeg;base64," + Base64.encodeToString(shot.jpeg, Base64.NO_WRAP))
            .put("coverage", "当前应用画面的单帧；尚未判断游戏目标是否完成")
        synchronized(frames) {
            check(generation == captureEpoch.get() && !closed) { "旧观察会话已经取消" }
            frames[id] = frame; while (frames.size > 6) frames.remove(frames.keys.first())
            grays[id] = shot.gray; while (grays.size > 3) grays.remove(grays.keys.first())
            capture = result.toString()
        }
        changed(); return JSONObject(result.toString())
    }
    private fun perform(kind: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        val frame = synchronized(frames) { frames[data.getString("frameId")] } ?: error("观察帧已失效，请重新观察")
        check(frame.session == captureSession && permits(frame.packageName)) { "观察会话已经结束" }
        authorize(principal, frame.packageName, data.getString("requestId"), data.getString("sessionId"), data.toString().toByteArray().sha256())
        if (frame.displayId == 0 && AppForeground.isForeground()) {
            val target = app.packageManager.getLaunchIntentForPackage(frame.packageName) ?: error("无法打开目标应用")
            onMainAndWait {
                val lease = input.fence.current()
                check(lease != null && lease.sessionId == data.getString("sessionId") && lease.epoch == data.getLong("inputEpoch")) { "输入授权已经失效" }
                app.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            Thread.sleep(600)
        }
        val result = input.perform(kind, data, frame)
        if (result.optString("state") == "completed") {
            synchronized(recording) {
                val step = JSONObject().put("kind", kind).put("before", frame.fingerprint.toString()).put("width", frame.right - frame.left).put("height", frame.bottom - frame.top)
                for (key in listOf("x", "y", "toX", "toY", "durationMs", "moveMs", "pointers", "landmark")) if (data.has(key)) step.put(key, data.get(key))
                recording.add(step); while (recording.size > 64) recording.removeAt(0)
            }
            try { Thread.sleep(400); result.put("after", captureFrame(frame.packageName, captureEpoch.get())) }
            catch (e: Exception) { result.put("observationError", e.message?.take(160)).put("gameOutcome", "unknown") }
        }
        ports.emit("action.completed", JSONObject().put("scope", OWNER).put("sourceId", data.getString("requestId"))
            .put("data", JSONObject(result.toString()).removeAndReturn("after")))
        return result
    }
    private fun analyze(data: JSONObject): JSONObject {
        check(JSONObject(analysis).optString("state") !in setOf("queued", "streaming")) { "上一段音频仍在分析，可先取消当前回应" }
        val session = data.getString("captureSession")
        val state = DeviceAudioSession.state()
        check(session == state.getString("captureSession")) { "录音会话已更换" }
        val parts = JSONArray()
        val tracks = state.getJSONObject("tracks")
        for (source in listOf("mic", "playback")) if (tracks.has(source)) {
            val wave = DeviceAudioSession.read(session, source)
            parts.put(JSONObject().put("text", "$source 音轨；麦克风可能含外放回声。来源标签不是说话人身份认证。静音可能是目标应用限制捕获。"))
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wave, Base64.NO_WRAP))))
        }
        check(parts.length() > 0)
        val context = analysisContext.snapshot("")
        val token = UUID.randomUUID().toString()
        synchronized(analysisLock) {
            check(analysisContext.current(context) && DeviceAudioSession.state().optString("captureSession") == session) { "录音或上下文已经更新" }
            analysisGeneration = token
            analysis = JSONObject().put("state", "queued").put("captureSession", session).toString()
        }
        changed()
        val request = try { ports.requestModel(JSONObject().put("scope", OWNER).put("lane", "owner").put("purpose", "audio_understanding")
            .put("contextEpoch", context.epoch)
            .put("prompt", data.optString("prompt", "结合两路实际采到的音频和我交流；缺失、静音或无法分辨时说明，不编造。 ").take(4000))
            .put("parts", parts).put("maxOutputTokens", 1024)) { event ->
            synchronized(analysisLock) { if (!closed && token == analysisGeneration && analysisContext.current(context)) {
                val type = event.optString("type")
                analysis = JSONObject(event.toString()).put("state", if (type == "delta") "streaming" else type).put("captureSession", session).toString()
                if (type == "completed" && event.optString("text").isNotBlank()) {
                    ports.emit("observation.summary", JSONObject().put("scope", OWNER).put("sourceId", "audio:$session:$token")
                        .put("data", JSONObject().put("text", event.getString("text")).put("summary", event.getString("text"))
                            .put("kind", "inference").put("source", "本次已授权的临时音频片段")
                            .put("captureSession", session).put("trackMetadata", tracks).put("contextEpoch", context.epoch)
                            .put("sourceRefs", context.value.optJSONArray("sourceRefs")).put("createdAt", System.currentTimeMillis())))
                }
                changed()
            } }
        }
        } catch (e: Exception) {
            synchronized(analysisLock) { if (token == analysisGeneration && analysisContext.current(context))
                analysis = JSONObject().put("state", "failed").put("error", e.message?.take(200)).toString() }
            changed(); throw e
        }
        val cancelLateRequest = synchronized(analysisLock) {
            if (closed || token != analysisGeneration || !analysisContext.current(context)) true
            else { analysisRequest = request; false }
        }
        if (cancelLateRequest) ports.cancelModel(request)
        return JSONObject().put("jobId", request).put("state", if (cancelLateRequest) "cancelled" else JSONObject(analysis).optString("state", "queued"))
    }
    private fun authorize(p: CommandPrincipal, pkg: String, requestId: String, session: String?, digest: String? = null) {
        val effect = JSONObject().put("type", "phone_input").put("target", pkg).put("scope", OWNER).put("requestId", requestId)
        if (session != null) effect.put("controlSessionId", session)
        if (digest != null) effect.put("contentDigest", digest)
        val decision = ports.authorizeEffect(effect, p)
        check(decision.optBoolean("allowed")) { decision.optString("reason", "宿主没有授权此次输入") }
    }
    private fun onMainAndWait(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { action(); return }
        val done = java.util.concurrent.CountDownLatch(1)
        val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        main.post {
            try { if (!expired.get() && !closed) action() }
            catch (e: Exception) { failure.set(e) }
            finally { done.countDown() }
        }
        if (!done.await(3, java.util.concurrent.TimeUnit.SECONDS)) { expired.set(true); error("界面调度超时，操作未确认") }
        failure.get()?.let { throw it }
    }
    private fun decodeAudioConfig(data: JSONObject): DeviceAudioSession.Config {
        val mic = data.optBoolean("microphone", true); val playback = data.optBoolean("playback", false)
        require(mic || playback); val seconds = data.optInt("durationSeconds", 20); require(seconds in 1..30)
        return DeviceAudioSession.Config(mic, playback, seconds, if (playback) packageName(data) else data.optString("packageName"))
    }
    private fun packageName(data: JSONObject): String = data.getString("packageName").also {
        require(it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "应用包名无效" }
        require(it != app.packageName) { "请选择目标应用" }
    }
    private fun requireOwner(principal: CommandPrincipal) {
        check(principal.owner && principal.channel == "owner_ui" && principal.scope == OWNER) { "设备权限只接受本机主人界面操作" }
    }
    private fun checkGame(pkg: String) {
        val info = app.packageManager.getApplicationInfo(pkg, 0)
        check(info.flags and ApplicationInfo.FLAG_SYSTEM == 0 && pkg !in setOf(app.packageName, "com.tencent.mobileqq", "tv.danmaku.bili")) { "此入口仅用于主人选定的单机游戏" }
    }
    /** The owner asked in chat to play a stretch for them: take over the game they are already letting her watch. Touching the screen takes it back. */
    fun delegateFromChat(requested: String, seconds: Int, background: Boolean = false): JSONObject {
        check(!closed)
        check(!EmergencyHold.ordinaryHeld) { "主人刚急停了点击和输入，要等主人说继续" }
        if (background) return startBackground(resolvePackage(requested.ifBlank { capturePackage.ifBlank { CompanionOverlayService.observedPackage() } }), seconds)
        endBackground()
        val pkg = requested.ifBlank { capturePackage.ifBlank { CompanionOverlayService.observedPackage() } }.let { if (it.isBlank()) it else resolvePackage(it) }
        check(pkg.isNotBlank() && (permits(pkg) || CompanionOverlayService.permits(pkg))) { "现在没有在陪主人看游戏，先请主人打开伴玩" }
        checkGame(pkg)
        val principal = CommandPrincipal("local-device-owner", "owner_ui", OWNER, true).also { surfacePrincipal = it }
        if (!permits(pkg)) {
            // Watching through the floating ball already counts as the owner's consent to see this game; open a control frame session in place.
            captureEpoch.incrementAndGet(); synchronized(frames) { frames.clear() }
            capturePackage = pkg; captureSession = UUID.randomUUID().toString(); captureDeadline = SystemClock.elapsedRealtime() + 30 * 60_000
            capture = JSONObject().put("state", "idle").put("packageName", pkg).toString()
        }
        authorize(principal, pkg, UUID.randomUUID().toString(), null)
        resumable = null; synchronized(recording) { recording.clear() }
        return input.start(pkg, seconds.coerceIn(10, 1800), input.state().getLong("inputEpoch")).also { changed() }
    }

    /** The owner asked her to play on her own: the game moves to the virtual display and the phone stays the owner's. */
    private fun startBackground(pkg: String, seconds: Int): JSONObject {
        check(ShizukuInput.ready()) { "后台玩需要 Shizuku 已运行并授权" }
        checkGame(pkg)
        val principal = CommandPrincipal("local-device-owner", "owner_ui", OWNER, true).also { surfacePrincipal = it }
        authorize(principal, pkg, UUID.randomUUID().toString(), null)
        val session = BackgroundScreen.start(app, pkg)
        captureEpoch.incrementAndGet(); synchronized(frames) { frames.clear(); grays.clear() }
        val duration = seconds.coerceIn(10, 1800)
        capturePackage = pkg; captureSession = UUID.randomUUID().toString(); captureDeadline = SystemClock.elapsedRealtime() + duration * 1000L + 60_000
        capture = JSONObject().put("state", "idle").put("packageName", pkg).put("background", true).toString()
        backgroundSession = session
        resumable = null; synchronized(recording) { recording.clear() }
        val state = input.start(pkg, duration, input.state().getLong("inputEpoch"), background = true)
        val epoch = state.getLong("inputEpoch")
        main.postDelayed({ if (input.background && input.state().optLong("inputEpoch") == epoch) worker.execute { endBackground() } }, duration * 1000L + 1000)
        changed(); return state.put("displayId", session.displayId).put("width", session.width).put("height", session.height)
    }

    private fun endBackground() {
        if (backgroundSession == null) return
        backgroundSession = null
        if (input.state().optString("state") == "authorized" && input.background) input.stop("background_ended")
        BackgroundScreen.stop()
    }

    /** A package name, or an app's name as the owner says it. */
    private fun resolvePackage(name: String): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "要玩哪个游戏？" }
        if (trimmed.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) return trimmed
        val pm = app.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        return (apps.firstOrNull { it.loadLabel(pm).toString() == trimmed } ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(trimmed, ignoreCase = true) })
            ?.activityInfo?.packageName ?: error("没找到叫“$trimmed”的应用")
    }

    private fun currentGame(): String = input.state().optString("packageName").ifBlank { capturePackage }.also { check(it.isNotBlank()) { "现在没有在玩的游戏" } }

    private fun freshFrame(): Pair<JSONObject, FrameMatcher.Gray> {
        val pkg = currentGame()
        val frame = captureFrame(pkg, captureEpoch.get())
        val gray = synchronized(frames) { grays[frame.getString("frameId")] } ?: error("画面已失效")
        return frame to gray
    }

    private fun saveLandmark(data: JSONObject): JSONObject {
        val name = data.getString("name").trim().take(24).also { require(it.isNotBlank()) }
        val frameId = data.optString("frameId").ifBlank { JSONObject(capture).optString("frameId") }
        val (frame, gray) = synchronized(frames) { frames[frameId] to grays[frameId] }
        check(frame != null && gray != null) { "画面已失效，请重新看一眼再标记" }
        val gx = ((data.getDouble("x") - gray.originX) / gray.scale).toInt(); val gy = ((data.getDouble("y") - gray.originY) / gray.scale).toInt()
        val gw = (data.getDouble("w") / gray.scale).toInt(); val gh = (data.getDouble("h") / gray.scale).toInt()
        require(gw in 8..96 && gh in 8..96) { "标记区域太小或太大（约 ${(8 * gray.scale).toInt()} 到 ${(96 * gray.scale).toInt()} 像素见方）" }
        notes.saveLandmark(frame.packageName, name, FrameMatcher.crop(gray, gx, gy, gw, gh))
        return JSONObject().put("state", "saved").put("name", name)
    }

    private fun locate(name: String, gray: FrameMatcher.Gray): JSONObject {
        val template = notes.landmark(currentGame(), name) ?: error("没有叫“$name”的标记")
        val match = FrameMatcher.find(gray, template) ?: return JSONObject().put("found", false).put("score", 0)
        val (x, y) = FrameMatcher.screenCenter(gray, match, template)
        return JSONObject().put("found", match.score >= FrameMatcher.FOUND).put("score", "%.2f".format(match.score)).put("x", x).put("y", y)
    }

    private fun findLandmark(name: String): JSONObject {
        val (frame, gray) = freshFrame()
        return locate(name, gray).put("after", frame)
    }

    private fun tapLandmark(data: JSONObject, principal: CommandPrincipal): JSONObject {
        val name = data.getString("name")
        val (frame, gray) = freshFrame()
        val spot = locate(name, gray)
        if (!spot.getBoolean("found")) return spot.put("state", "not_found").put("after", frame)
        val control = input.state()
        return perform("tap", JSONObject().put("requestId", data.optString("requestId").ifBlank { UUID.randomUUID().toString() })
            .put("sessionId", control.optString("sessionId")).put("inputEpoch", control.optLong("inputEpoch"))
            .put("frameId", frame.getString("frameId")).put("x", spot.getInt("x")).put("y", spot.getInt("y")).put("landmark", name), principal).put("landmark", spot)
    }

    /** Waits on the device without spending model calls: until a landmark shows up, or (without one) until the screen changes. */
    private fun waitFor(data: JSONObject): JSONObject {
        val name = data.optString("landmark")
        val deadline = SystemClock.elapsedRealtime() + data.optLong("timeoutMs", 8000).coerceIn(500, 20_000)
        var (frame, gray) = freshFrame()
        val first = FrameMatcher.fingerprint(gray)
        while (true) {
            if (name.isNotBlank()) { val spot = locate(name, gray); if (spot.getBoolean("found")) return spot.put("state", "found").put("after", frame) }
            else if (FrameMatcher.distance(first, FrameMatcher.fingerprint(gray)) > FrameMatcher.SAME_SCREEN) return JSONObject().put("state", "changed").put("after", frame)
            if (SystemClock.elapsedRealtime() >= deadline) return JSONObject().put("state", "timeout").put("after", frame)
            Thread.sleep(350)
            freshFrame().let { frame = it.first; gray = it.second }
        }
    }

    private fun saveRoute(name: String): JSONObject {
        val steps = synchronized(recording) { JSONArray(recording.map { JSONObject(it.toString()) }).also { recording.clear() } }
        check(steps.length() > 0) { "这次还没有成功的操作可以存成路线" }
        notes.saveRoute(currentGame(), name.trim().take(24), steps)
        return JSONObject().put("state", "saved").put("name", name).put("steps", steps.length())
    }

    /**
     * Replays a saved route step by step. Before each step the screen must look like it did when the step was
     * recorded (or the step's landmark must be visible); otherwise it stops and hands back to her with the frame.
     */
    private fun replay(data: JSONObject, principal: CommandPrincipal): JSONObject {
        val name = data.getString("name")
        val steps = notes.route(currentGame(), name) ?: error("没有叫“$name”的路线")
        var (frame, gray) = freshFrame()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            if (step.optInt("width") != frame.optInt("width") || step.optInt("height") != frame.optInt("height"))
                return JSONObject().put("state", "diverged").put("stoppedAt", i).put("reason", "画面尺寸和录制时不同").put("after", frame)
            val deadline = SystemClock.elapsedRealtime() + 3000
            var target: JSONObject? = null
            while (true) {
                if (EmergencyHold.ordinaryHeld || input.state().optString("state") != "authorized")
                    return JSONObject().put("state", "stopped").put("stoppedAt", i).put("after", frame)
                val landmark = step.optString("landmark")
                if (landmark.isNotBlank()) { val spot = locate(landmark, gray); if (spot.getBoolean("found")) { target = spot; break } }
                else if (FrameMatcher.distance(step.optString("before").toLong(), FrameMatcher.fingerprint(gray)) <= FrameMatcher.SAME_SCREEN) break
                if (SystemClock.elapsedRealtime() >= deadline) return JSONObject().put("state", "diverged").put("stoppedAt", i)
                    .put("reason", if (landmark.isNotBlank()) "没找到标记“$landmark”" else "画面和录制时对不上").put("after", frame)
                Thread.sleep(300)
                freshFrame().let { frame = it.first; gray = it.second }
            }
            val control = input.state()
            val args = JSONObject(step.toString()).put("requestId", UUID.randomUUID().toString()).put("frameId", frame.getString("frameId"))
                .put("sessionId", control.optString("sessionId")).put("inputEpoch", control.optLong("inputEpoch"))
            target?.let { args.put("x", it.getInt("x")).put("y", it.getInt("y")) }
            listOf("kind", "before", "width", "height").forEach(args::remove)
            val result = perform(step.getString("kind"), args, principal)
            if (result.optString("state") != "completed") return result.put("stoppedAt", i).put("state", "stopped")
            result.optJSONObject("after")?.let { after -> synchronized(frames) { grays[after.getString("frameId")] }?.let { frame = after; gray = it } }
                ?: freshFrame().let { frame = it.first; gray = it.second }
        }
        return JSONObject().put("state", "completed").put("steps", steps.length()).put("after", frame)
    }
    private fun permits(pkg: String) = !closed && pkg == capturePackage && SystemClock.elapsedRealtime() < captureDeadline
    @Volatile private var resumable: Pair<String, Long>? = null
    /** Remember the running lease so an ordinary resume restarts it for its remaining time. */
    private fun holdInput(reason: String) {
        val state = input.state()
        if (state.optString("state") == "authorized") resumable = state.getString("packageName") to state.getLong("expiresAtMs") - SystemClock.elapsedRealtime()
        stopInput(reason)
    }
    private fun resumeInput() {
        val (pkg, remaining) = resumable ?: return
        resumable = null
        val principal = surfacePrincipal ?: return
        if (!permits(pkg)) return
        runCatching {
            authorize(principal, pkg, UUID.randomUUID().toString(), null)
            input.start(pkg, (remaining / 1000).toInt().coerceIn(10, 300), input.state().getLong("inputEpoch"), background = backgroundSession != null)
        }
    }
    private fun stopInput(reason: String): JSONObject = input.stop(reason).also {
        if (reason != "owner_emergency_stop") { if (backgroundSession != null) worker.execute { endBackground() }; ShizukuInput.release() }
        ports.emit("control.stopped", JSONObject().put("scope", OWNER).put("sourceId", "input:${it.optLong("inputEpoch")}").put("data", it))
    }
    private fun changed() { if (!closed) runCatching { ports.emit("moduleState", JSONObject().put("domain", domain)) } }
    private fun cancelAnalysis() {
        analysisContext.invalidate()
        val request = synchronized(analysisLock) {
            analysisGeneration = ""; analysis = JSONObject().put("state", "idle").toString()
            analysisRequest.also { analysisRequest = "" }
        }
        request.takeIf { it.isNotEmpty() }?.let(ports::cancelModel)
    }
    override fun onEvent(event: ModuleEvent) {
        if (event.type == "connectivity.changed" && !event.data.optBoolean("online", true)) stopInput("connection_lost")
        if (event.type == "life.contentChanged" || event.type == "data.deleted") {
            val epoch = event.data.optLong("contentEpoch", -1)
            if (epoch >= 0 && lastContentEpoch.getAndUpdate { maxOf(it, epoch) } >= epoch) return
            cancelAnalysis(); analysis = JSONObject().put("state", "cleared").toString()
            DeviceAudioSession.clear()
            synchronized(frames) {
                captureEpoch.incrementAndGet(); frames.clear()
                capture = JSONObject().put("state", "cleared").put("packageName", capturePackage).put("detail", "上下文已更新，请重新观察").toString()
            }
            CompanionPresenceHost.invalidateContext()
            changed()
        }
    }
    override fun close() {
        if (closed) return
        stopInput("module_closed"); BackgroundScreen.stop(); cancelAnalysis(); closed = true; captureEpoch.incrementAndGet(); main.removeCallbacksAndMessages(null); worker.shutdownNow()
        if (active === this) { DeviceAudioSession.stop(); DeviceAudioSession.onChanged = null; active = null }
    }
    companion object {
        private const val OWNER = "owner:local"
        @Volatile private var active: DeviceModule? = null
        fun permitsObservation(pkg: String) = active?.permits(pkg) == true
        fun stopInputFromSurface() { active?.stopInput("owner_emergency_stop") }
        /** Five-second hold on the ball. */
        fun emergencyHoldFromSurface() {
            EmergencyHold.engage(); active?.holdInput("owner_emergency_stop")
            runCatching { io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService.current()?.cancelAutomationTask() }
        }
        /** The owner touched the screen outside our windows while we held control: yield silently. */
        fun yieldToOwnerTouch() {
            val owner = active ?: return
            val control = owner.input.state()
            if (control.optString("state") != "authorized" || control.optBoolean("releasePending") || owner.input.background) return
            // Her own gesture (in flight, or just ended) shows up as an outside touch too; that is not the owner.
            if (owner.input.gestureInFlight || SystemClock.elapsedRealtime() - owner.input.lastGestureEndedAt < 500) return
            io.deepseekharness.mobile.companion.XLog.i("owner touch outside the ball: yielding game control")
            owner.stopInput("owner_touch") // The owner took the game back; nothing to resume.
        }
        /** Owner said "继续" (own chat or voice) or pressed resume: everything the hold stopped comes back. */
        fun continueAfterHold() {
            if (!EmergencyHold.anyHeld) return
            EmergencyHold.releaseAll()
            val owner = active ?: return
            owner.worker.execute { owner.resumeInput() }
        }
        fun overlayChanged() { active?.changed() }
        fun surfaceFrame(): JSONObject = active?.let { JSONObject(it.capture) } ?: JSONObject()
        fun captureFromSurface(): JSONObject {
            val owner = active ?: error("设备模块未开启")
            check(owner.surfacePrincipal != null)
            return owner.captureFrame(owner.capturePackage, owner.captureEpoch.get())
        }
        fun inputFromSurface(kind: String, frameId: String, x: Float, y: Float): JSONObject {
            val owner = active ?: error("设备模块未开启")
            val principal = owner.surfacePrincipal ?: error("请先由主人在界面开启观察")
            val control = owner.input.state()
            return owner.perform(kind, JSONObject().put("requestId", UUID.randomUUID().toString()).put("frameId", frameId)
                .put("sessionId", control.optString("sessionId")).put("inputEpoch", control.optLong("inputEpoch"))
                .put("x", x.toDouble()).put("y", y.toDouble()), principal)
        }
        fun controlState(): JSONObject = active?.input?.state() ?: JSONObject().put("state", "stopped").put("releasePending", false)
    }
}

private fun JSONObject.removeAndReturn(key: String): JSONObject { remove(key); return this }
