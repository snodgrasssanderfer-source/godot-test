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
import io.deepseekharness.mobile.companion.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Real native device endpoint. No model credentials, external accounts, persistent frames or PCM. */
class DeviceModule(context: Context, private val ports: HostPorts) : ModuleEndpoint {
    override val domain = "device"
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val captureEpoch = AtomicLong()
    private val input = GameInputController(app, ::changed)
    private val frames = linkedMapOf<String, ControlFrame>()
    @Volatile private var capture = JSONObject().put("state", "idle").toString()
    @Volatile private var capturePackage = ""
    @Volatile private var captureDeadline = 0L
    @Volatile private var captureSession = ""
    @Volatile private var analysis = JSONObject().put("state", "idle").toString()
    @Volatile private var analysisGeneration = ""
    @Volatile private var analysisRequest = ""
    @Volatile private var closed = false
    private val preferences = app.getSharedPreferences("companion-device-v2", Context.MODE_PRIVATE)
    @Volatile private var surfacePrincipal: CommandPrincipal? = null
    @Volatile private var audioConfig = runCatching { JSONObject(preferences.getString("audio", "{}")!!) }.getOrDefault(JSONObject())

    init {
        check(active == null) { "设备模块已存在" }
        active = this; DeviceAudioSession.onChanged = ::changed
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
            "capture.stop" -> { captureEpoch.incrementAndGet(); captureDeadline = 0; capturePackage = ""; synchronized(frames) { frames.clear() }
                capture = JSONObject().put("state", "stopped").toString(); changed(); JSONObject(capture) }
            "capture.status" -> JSONObject(capture)
            "control.start", "control.resume" -> {
                check(data.optBoolean("singlePlayer")) { "只支持本机单人游戏，需主人明确确认" }
                val pkg = packageName(data)
                val info = app.packageManager.getApplicationInfo(pkg, 0)
                check(info.flags and ApplicationInfo.FLAG_SYSTEM == 0 && pkg !in setOf(app.packageName, "com.tencent.mobileqq", "tv.danmaku.bili")) { "此入口仅用于主人选定的单机游戏" }
                authorize(principal, pkg, data.optString("requestId"), null)
                input.start(pkg, data.optInt("durationSeconds", 120), data.getLong("expectedInputEpoch"))
            }
            "control.stop" -> stopInput("owner_stop")
            "control.status" -> input.state()
            "control.tap", "control.swipe", "control.longPress" -> perform(action.substringAfter('.'), data, principal)
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
        capture = JSONObject().put("state", "queued").put("jobId", job).put("packageName", pkg).toString()
        main.postDelayed({
            if (captureEpoch.get() == generation && !closed) {
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
    private fun captureFrame(pkg: String, generation: Long): JSONObject {
        check(permits(pkg)) { "观察授权已结束" }
        val device = CompanionDevice(app)
        val capturedAt = SystemClock.elapsedRealtime()
        val rotation = GameInputController.rotation(app)
        val before = device.observe(pkg)
        val bytes = device.screenshot(pkg)
        val after = device.observe(pkg)
        check(before.optInt("windowId") == after.optInt("windowId") && rotation == GameInputController.rotation(app) && before.getJSONArray("nodes").toString() == after.getJSONArray("nodes").toString()) { "截图期间窗口发生变化" }
        val bounds = after.getJSONArray("nodes").getJSONObject(0).getJSONArray("bounds")
        val id = UUID.randomUUID().toString()
        val frame = ControlFrame(id, captureSession, pkg, capturedAt, after.getInt("windowId"), rotation,
            bounds.getInt(0), bounds.getInt(1), bounds.getInt(2), bounds.getInt(3), after.getJSONArray("nodes").toString().toByteArray().sha256(), bytes.sha256())
        check(frame.right > frame.left && frame.bottom > frame.top)
        check(generation == captureEpoch.get() && !closed) { "旧观察会话已经取消" }
        synchronized(frames) { frames[id] = frame; while (frames.size > 6) frames.remove(frames.keys.first()) }
        val result = JSONObject().put("state", "ready").put("frameId", id).put("captureSession", captureSession).put("packageName", pkg)
            .put("screenHash", frame.screenHash).put("capturedAtMs", frame.atMs).put("rotation", frame.rotation).put("left", frame.left).put("top", frame.top)
            .put("width", frame.right - frame.left).put("height", frame.bottom - frame.top).put("windowId", frame.windowId)
            .put("preview", "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("coverage", "当前应用窗口的单帧；尚未判断游戏目标是否完成")
        capture = result.toString(); changed(); return JSONObject(result.toString())
    }
    private fun perform(kind: String, data: JSONObject, principal: CommandPrincipal): JSONObject {
        val frame = synchronized(frames) { frames[data.getString("frameId")] } ?: error("观察帧已失效，请重新观察")
        check(frame.session == captureSession && permits(frame.packageName)) { "观察会话已经结束" }
        authorize(principal, frame.packageName, data.getString("requestId"), data.getString("sessionId"), data.toString().toByteArray().sha256())
        if (AppForeground.isForeground()) {
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
        val token = UUID.randomUUID().toString(); analysisGeneration = token
        analysis = JSONObject().put("state", "queued").put("captureSession", session).toString(); changed()
        val request = try { ports.requestModel(JSONObject().put("scope", OWNER).put("lane", "owner").put("purpose", "audio_understanding")
            .put("prompt", data.optString("prompt", "结合两路实际采到的音频和我交流；缺失、静音或无法分辨时说明，不编造。 ").take(4000))
            .put("parts", parts).put("maxOutputTokens", 1024)) { event ->
            if (!closed && token == analysisGeneration) {
                val type = event.optString("type")
                analysis = JSONObject(event.toString()).put("state", if (type == "delta") "streaming" else type).put("captureSession", session).toString()
                if (type == "completed" && event.optString("text").isNotBlank()) {
                    ports.emit("observation.summary", JSONObject().put("scope", OWNER).put("sourceId", "audio:$session:$token")
                        .put("data", JSONObject().put("text", event.getString("text")).put("summary", event.getString("text"))
                            .put("kind", "inference").put("source", "本次已授权的临时音频片段")
                            .put("captureSession", session).put("trackMetadata", tracks).put("createdAt", System.currentTimeMillis())))
                }
                changed()
            }
        }
        } catch (e: Exception) {
            analysis = JSONObject().put("state", "failed").put("error", e.message?.take(200)).toString(); changed(); throw e
        }
        analysisRequest = request
        return JSONObject().put("jobId", request).put("state", "queued")
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
    private fun permits(pkg: String) = !closed && pkg == capturePackage && SystemClock.elapsedRealtime() < captureDeadline
    private fun stopInput(reason: String): JSONObject = input.stop(reason).also {
        ports.emit("control.stopped", JSONObject().put("scope", OWNER).put("sourceId", "input:${it.optLong("inputEpoch")}").put("data", it))
    }
    private fun changed() { if (!closed) runCatching { ports.emit("moduleState", JSONObject().put("domain", domain)) } }
    private fun cancelAnalysis() { analysisGeneration = ""; analysisRequest.takeIf { it.isNotEmpty() }?.let(ports::cancelModel); analysisRequest = ""; analysis = JSONObject().put("state", "idle").toString() }
    override fun onEvent(event: ModuleEvent) {
        if (event.type == "connectivity.changed" && !event.data.optBoolean("online", true)) stopInput("connection_lost")
    }
    override fun close() {
        if (closed) return
        stopInput("module_closed"); cancelAnalysis(); closed = true; captureEpoch.incrementAndGet(); main.removeCallbacksAndMessages(null); worker.shutdownNow()
        if (active === this) { DeviceAudioSession.stop(); DeviceAudioSession.onChanged = null; active = null }
    }
    companion object {
        private const val OWNER = "owner:local"
        @Volatile private var active: DeviceModule? = null
        fun permitsObservation(pkg: String) = active?.permits(pkg) == true
        fun stopInputFromSurface() { active?.stopInput("owner_emergency_stop") }
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
