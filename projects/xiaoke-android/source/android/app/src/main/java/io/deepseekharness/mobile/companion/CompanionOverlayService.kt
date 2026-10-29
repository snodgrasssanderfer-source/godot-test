package io.deepseekharness.mobile.companion

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.MainActivity
import io.deepseekharness.mobile.overlay.OverlayBallPolicy
import io.deepseekharness.mobile.companion.presence.CompanionPresenceController
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native companion window; drag/clamp/click policies reuse the DSH overlay implementation.
 * The optional presence controller uses explicit bounded screen/audio consent.
 */
class CompanionOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private lateinit var windows: WindowManager
    private var bubble: View? = null
    private var characterWeb: WebView? = null
    private var characterReady = false
    private var presence: CompanionPresenceController? = null
    private var microphoneForeground = false
    private var panel: ScrollView? = null
    private var content: LinearLayout? = null
    private var transcript: TextView? = null
    private var stateText: TextView? = null
    private var composer: EditText? = null
    private var taskId = ""
    private var observationId = ""
    private var lastObservationId = ""
    private var localStatus = "按需观察尚未开始"
    private var hidden = false
    private val engine get() = CompanionEngine.get(this)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private val theme by lazy { ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar) }

    override fun onCreate() {
        super.onCreate(); windows = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("companion-overlay", "小克悬浮陪伴", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        val config = intent?.getStringExtra("presenceConfig")?.let(::JSONObject)
        if (config != null && !CompanionPresenceHost.current(intent.getLongExtra("presenceGeneration", -1))) { stopSelf(); return START_NOT_STICKY }
        presence?.close(); presence = null
        closePanel(); bubble?.let { runCatching { windows.removeView(it) } }; bubble = null
        characterReady = false; characterWeb?.destroy(); characterWeb = null
        allowedPackage = config?.optString("package") ?: intent?.getStringExtra("package").orEmpty()
        deadline = SystemClock.elapsedRealtime() + (config?.optInt("minutes", 20) ?: intent?.getIntExtra("minutes", 15) ?: 15).coerceIn(1, 180) * 60000L
        taskId = intent?.getStringExtra("taskId").orEmpty()
        microphoneForeground = config?.optBoolean("voiceInput") ?: false
        try { updateForeground(config != null) } catch (e: Exception) {
            CompanionPresenceHost.publish(CompanionPresenceHost.state().put("phase", "error").put("running", false)
                .put("text", "系统未允许后台语音启动，请回到助手重试").put("error", "foreground-start-denied"))
            stopSelf(); return START_NOT_STICKY
        }
        running = true
        CompanionPresenceHost.attach(this)
        if (config != null) {
            presence = CompanionPresenceController(this) { event -> CompanionPresenceHost.publish(event); updateCharacter(event) }
            presence!!.start(config)
        }
        runCatching { showBubble(); if (taskId.isNotBlank()) showPanel() }.onFailure { stopSelf() }
        main.removeCallbacks(tick); main.post(tick)
        return START_NOT_STICKY
    }
    private fun updateForeground(continuous: Boolean) {
        val stop = PendingIntent.getService(this, 821, Intent(this, CompanionOverlayService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 822, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "companion-overlay").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小克悬浮陪伴 · 本次限时会话").setContentText(if (continuous) "按本次设置感知与交流；点停止立即结束" else "只在点击后看一次画面；点停止结束本次会话")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
        var types = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        if (continuous && Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (microphoneForeground && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, 820, notification, types)
    }
    fun presenceCommand(action: String, data: JSONObject) {
        val controller = presence ?: error("请先开启持续陪伴会话")
        when (action) {
            "presenceInterrupt" -> controller.interrupt()
            "presenceListen" -> { microphoneForeground = true; updateForeground(true); controller.listenOnce() }
            "presenceMute" -> controller.setMuted(data.optBoolean("muted", true))
            "presenceMode" -> controller.setMode(data.optString("mode", "companion"))
            "presenceText" -> controller.textInput(data.getString("text"))
            "presenceSave" -> controller.saveCurrentEvent()
            else -> error("不支持的陪伴命令")
        }
    }
    fun endPresence() { presence?.stop() }
    private fun updateCharacter(event: JSONObject) {
        if (characterReady) {
            val artwork = CompanionCharacterStore.metadata(this)
            if (artwork.has("path")) artwork.put("overlayUrl", "https://xiaoke.local/character.png")
            val value = JSONObject(event.toString()).put("character", artwork)
            characterWeb?.evaluateJavascript("window.setCompanionPresenceState?.(${value})", null)
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (SystemClock.elapsedRealtime() >= deadline) { stopSelf(); return }
            val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked || !getSystemService(PowerManager::class.java).isInteractive
            if (locked) { stopSelf(); return }
            if (presence != null && !presence!!.state().optBoolean("running")) { stopSelf(); return }
            val visible = !locked && !hidden
            bubble?.visibility = if (visible) View.VISIBLE else View.GONE
            panel?.visibility = if (visible) View.VISIBLE else View.GONE
            val presenceState = presence?.state()
            if (presenceState != null) updateCharacter(presenceState)
            stateText?.text = "${if (engine.store.settings().optBoolean("paused")) "已暂停" else "剩余 ${(deadline - SystemClock.elapsedRealtime()) / 60000 + 1} 分钟"} · $localStatus" +
                (presenceState?.optString("statusMessage")?.let { "\n$it" } ?: "")
            val messages = engine.store.list("message", CompanionPolicy.OWNER, 6)
            val text = (0 until messages.length()).joinToString("\n\n") { val m = messages.getJSONObject(it); "${if (m.optString("role") == "assistant") engine.store.persona().optString("name", "小克") else "你"}：${m.optString("text").takeLast(2500)}" }
            val active = engine.activeTask?.let { engine.store.get("task", it) }
            transcript?.text = text + (if (active != null) "\n\n${active.optString("partialText").ifBlank { active.optString("progress") }}" else "") +
                (presenceState?.optString("text")?.ifBlank { presenceState.optString("lastReply") }?.let { "\n\n陪伴：$it" } ?: "")
            main.postDelayed(this, 1000)
        }
    }
    private fun background() = GradientDrawable().apply { setColor(Color.rgb(246, 249, 239)); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(161, 181, 155)) }
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble?.isAttachedToWindow == true) return
        val size = if (presence != null) dp(96) else dp(52)
        val params = WindowManager.LayoutParams(size, if (presence != null) dp(136) else size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(180) }
        val view: View = if (presence != null) createCharacterView() else TextView(theme).apply { text = "小克"; gravity = Gravity.CENTER; textSize = 15f; background = background() }
        view.contentDescription = "打开小克悬浮对话，可拖动"
        view.setOnClickListener { if (panel == null) showPanel() else closePanel() }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { _, e -> when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; true }
            MotionEvent.ACTION_MOVE -> { val metrics = resources.displayMetrics; val position = OverlayBallPolicy.clampPosition(startX + (e.rawX - downX).toInt(), startY + (e.rawY - downY).toInt(), metrics.widthPixels, metrics.heightPixels - (params.height - size), size); params.x = position.first; params.y = position.second; runCatching { windows.updateViewLayout(view, params) }; true }
            MotionEvent.ACTION_UP -> { if (OverlayBallPolicy.isClick(e.rawX - downX, e.rawY - downY, ViewConfiguration.get(this).scaledTouchSlop)) view.performClick(); true }
            else -> true
        } }
        windows.addView(view, params); bubble = view
    }
    @SuppressLint("SetJavaScriptEnabled")
    private fun createCharacterView(): View {
        val wrapper = object : FrameLayout(theme) { override fun onInterceptTouchEvent(event: MotionEvent?) = true }
        val web = WebView(theme)
        web.setBackgroundColor(Color.TRANSPARENT)
        web.settings.javaScriptEnabled = true
        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
        web.settings.blockNetworkLoads = true
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                val path = uri?.path.orEmpty()
                if (uri?.scheme != "https" || uri.host != "xiaoke.local" || path.contains("..") || !path.matches(Regex("/[A-Za-z0-9_./-]+")))
                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                return try {
                    if (path == "/character.png") return WebResourceResponse("image/png", null, CompanionCharacterStore.file(this@CompanionOverlayService).inputStream())
                    val mime = when { path.endsWith(".html") -> "text/html"; path.endsWith(".js") -> "text/javascript"; path.endsWith(".css") -> "text/css"; else -> "application/octet-stream" }
                    WebResourceResponse(mime, "UTF-8", assets.open("public$path"))
                } catch (_: Exception) { WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf())) }
            }
            override fun onPageFinished(view: WebView?, url: String?) { characterReady = true; updateCharacter(CompanionPresenceHost.state()) }
        }
        characterWeb = web; wrapper.addView(web, FrameLayout.LayoutParams(-1, -1))
        web.loadUrl("https://xiaoke.local/presence-overlay.html")
        return wrapper
    }
    private fun label(text: String) = TextView(theme).apply { this.text = text; textSize = 14f; setTextColor(Color.rgb(37, 65, 47)); setPadding(0, dp(6), 0, dp(6)) }
    private fun button(text: String, action: () -> Unit) = Button(theme).apply { this.text = text; isAllCaps = false; setOnClickListener { action() } }
    private fun showPanel() {
        if (panel != null) return
        val box = LinearLayout(theme).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(14)); background = background() }
        content = box
        box.addView(label(engine.store.persona().optString("name", "小克") + " · 悬浮对话"))
        stateText = label(localStatus); box.addView(stateText)
        box.addView(button("收起，继续使用当前应用") { closePanel() })
        transcript = label(""); box.addView(transcript)
        composer = EditText(theme).apply { hint = "和我聊聊，或说明这次想看什么"; minLines = 2; maxLines = 4; setTextColor(Color.BLACK); filters = arrayOf(android.text.InputFilter.LengthFilter(12000)) }
        box.addView(composer)
        box.addView(button("发送给助手") {
            val text = composer?.text?.toString().orEmpty()
            if (text.isBlank()) { localStatus = "请先输入消息"; return@button }
            if (presence != null && observationId.isBlank()) { presence!!.textInput(text); composer?.setText(""); localStatus = "已交给当前陪伴会话" }
            else work { val task = engine.submit(CompanionPolicy.OWNER, text, observationId = observationId); observationId = ""; main.post { composer?.setText("") }; "已加入任务 ${task.optString("id").take(6)}" }
        })
        if (presence != null) {
            box.addView(button("打断当前回应") { presence!!.interrupt() })
            box.addView(button("打断并听我说一次") { runCatching { presenceCommand("presenceListen", JSONObject()) }.onFailure { localStatus = "请回助手开启麦克风后重试" } })
            box.addView(button("保存当前陪伴事件") { presence!!.saveCurrentEvent(); localStatus = "已请求保存当前有依据的事件" })
            box.addView(button("暂停 / 继续陪伴") { engine.store.saveSettings(engine.store.settings().put("paused", !engine.store.settings().optBoolean("paused"))) })
        }
        if (allowedPackage.isNotBlank()) {
            box.addView(label("画面范围：$allowedPackage。点击后只采一张；发送消息时才交给模型。"))
            box.addView(button("看一次当前画面，暂存为附件") { withoutWindow {
                val observation = CompanionDevice(this).captureOnce(allowedPackage); observationId = observation.getString("id"); lastObservationId = observationId
                "已临时附当前画面；发送才交给模型，未保存画面不会进入备份"
            } })
            box.addView(button("保存这次画面和已有分析") { work {
                check(lastObservationId.isNotBlank()) { "请先看一次画面" }
                engine.store.saveTemporaryObservation(lastObservationId); "已按你的操作保存，可在资料页删除"
            } })
            box.addView(button("移除待发画面") {
                if (engine.store.get("observation", observationId)?.optBoolean("temporary") == true) engine.store.deleteObservation(observationId)
                observationId = ""; localStatus = "已移除待发画面"
            })
        }
        if (taskId.isNotBlank()) {
            val task = engine.store.get("task", taskId)
            box.addView(label("待提交：${task?.optString("target")}\n账号：${task?.optString("account").orEmpty().ifBlank { "由你核对当前登录账号" }}\n正文：${task?.optString("body")}"))
            box.addView(button("我已打开正确目标，选择界面标题") { withoutWindow {
                val tree = CompanionDevice(this).observe()
                check(tree.optString("packageName") == task?.optString("package")) { "请先切到目标应用" }
                val nodes = tree.getJSONArray("nodes")
                val choices = (0 until nodes.length()).map(nodes::getJSONObject).filter {
                    it.optString("viewId").isNotBlank() && !it.optBoolean("editable") && it.optString("text").length in 1..160
                }.distinctBy { it.optString("viewId") + it.optString("text") }.take(40)
                main.post { showTargetChoices(choices) }
                "选择标题前请核对账号、群/好友或视频；重名时不能只凭名称判断"
            } })
            box.addView(button("确认账号和目标，以 UI 提交已绑定草稿") { withoutWindow {
                val current = engine.store.get("task", taskId) ?: error("任务不存在")
                check(current.has("uiBinding")) { "请先选择界面标题并确认目标" }
                CompanionDevice(this).sendUsingUi(current).optString("progress")
            } })
        }
        box.addView(button("取消当前模型任务") { engine.activeTask?.let(engine::cancel); localStatus = "已请求取消" })
        box.addView(button("回到助手") { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); closePanel() })
        box.addView(button("结束本次悬浮会话") { stopSelf() })
        val scroll = ScrollView(theme).apply { addView(box); isFillViewport = false }
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(minOf(dp(360), metrics.widthPixels - dp(20)), minOf(dp(560), metrics.heightPixels - dp(100)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END; x = dp(10); y = dp(50); softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        windows.addView(scroll, params); panel = scroll
    }
    private fun showTargetChoices(choices: List<JSONObject>) {
        val box = content ?: return
        box.addView(label("点选当前会话/视频标题，表示你已核对目标及登录账号；QQ 授权两分钟，B站五分钟有效。"))
        choices.forEach { node -> box.addView(button(node.optString("text")) {
            runCatching {
                val current = engine.store.get("task", taskId) ?: error("任务不存在")
                check(current.optString("state") in setOf("draft", "approved")) { "此任务已不能重新授权" }
                engine.store.approveDraft(current.getString("id"), CompanionPolicy.draftDigest(current), binding =
                    JSONObject().put("title", node.optString("text")).put("viewId", node.optString("viewId")))
                localStatus = "已绑定「${node.optString("text")}」，请点击 UI 提交"
            }.onFailure { localStatus = it.message.orEmpty() }
        }) }
    }
    private fun work(action: () -> String) {
        if (!busy.compareAndSet(false, true)) { localStatus = "上一步仍在处理"; return }
        worker.execute { try { check(!engine.store.settings().optBoolean("paused")) { "助手已暂停" }; localStatus = action() }
            catch (e: Exception) { localStatus = (e.message ?: "操作失败").take(180) }
            finally { hidden = false; busy.set(false) } }
    }
    private fun withoutWindow(action: () -> String) {
        if (busy.get()) { localStatus = "上一步仍在处理"; return }
        hidden = true; bubble?.visibility = View.GONE; panel?.visibility = View.GONE
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(panel?.windowToken, 0)
        main.postDelayed({ work { try { action() } finally { hidden = false } } }, 300)
    }
    private fun closePanel() { panel?.let { getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0); runCatching { windows.removeView(it) } }; panel = null; content = null; transcript = null; stateText = null; composer = null }
    override fun onDestroy() {
        presence?.close(); presence = null; CompanionPresenceHost.detach(this)
        characterReady = false; characterWeb?.destroy(); characterWeb = null
        running = false; deadline = 0; allowedPackage = ""; main.removeCallbacksAndMessages(null)
        if (taskId.isNotBlank()) engine.store.get("task", taskId)?.let { if (it.optString("state") in setOf("draft", "approved")) engine.cancel(taskId) }
        engine.store.clearTemporaryObservations()
        closePanel(); bubble?.let { runCatching { windows.removeView(it) } }; bubble = null
        worker.shutdownNow(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile private var deadline = 0L
        @Volatile private var allowedPackage = ""
        @Volatile var running = false; private set
        fun permits(pkg: String) = running && pkg == allowedPackage && SystemClock.elapsedRealtime() < deadline
        fun state() = JSONObject().put("running", running).put("package", allowedPackage).put("remainingSeconds", ((deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0))
        fun start(context: Context, packageName: String = "", minutes: Int = 15, taskId: String = "") {
            check(Settings.canDrawOverlays(context)) { "请先开启悬浮窗权限" }
            ContextCompat.startForegroundService(context, Intent(context, CompanionOverlayService::class.java).putExtra("package", packageName).putExtra("minutes", minutes).putExtra("taskId", taskId))
        }
    }
}
