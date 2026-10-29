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
import io.deepseekharness.mobile.companion.devicev2.DeviceModule
import io.deepseekharness.mobile.companion.devicev2.EmergencyHold
import io.deepseekharness.mobile.companion.devicev2.FiveSecondHold
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
    private var bubbleLabel: TextView? = null
    private var bubbleBadge: TextView? = null
    private var pendingCount = 0
    private var badgeCheckedAt = 0L
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
    private var visibleByOwner = true
    private var cancelHold: (() -> Unit)? = null
    private val engine get() = CompanionEngine.get(this)
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private val theme by lazy { ContextThemeWrapper(this, android.R.style.Theme_Material_Light_NoActionBar) }

    override fun onCreate() {
        super.onCreate(); activeService = this; windows = getSystemService(WindowManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("companion-overlay", "小克悬浮陪伴", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop_input") { DeviceModule.stopInputFromSurface(); return START_NOT_STICKY }
        if (intent?.action == "visibility") { applyVisibility(intent.getBooleanExtra("visible", true)); return START_NOT_STICKY }
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
        main.removeCallbacks(tick); main.post(tick); DeviceModule.overlayChanged()
        return START_NOT_STICKY
    }
    private fun updateForeground(continuous: Boolean) {
        val stop = PendingIntent.getService(this, 821, Intent(this, CompanionOverlayService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val inputStop = PendingIntent.getService(this, 823, Intent(this, CompanionOverlayService::class.java).setAction("stop_input"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 822, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "companion-overlay").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小克悬浮陪伴 · 本次限时会话").setContentText(if (continuous) "按本次设置感知与交流；点停止立即结束" else "只在点击后看一次画面；点停止结束本次会话")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "仅停止输入", inputStop).build()).addAction(Notification.Action.Builder(null, "停止", stop).build()).build()
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
    fun externalMicrophone(busy: Boolean) { presence?.externalMicrophone(busy) }
    fun invalidatePresenceContext() { presence?.invalidateContext() }
    private fun applyVisibility(visible: Boolean) {
        visibleByOwner = visible
        if (!visible) { cancelHold?.invoke(); closePanel() }
        bubble?.visibility = if (visible && !hidden) View.VISIBLE else View.GONE
        DeviceModule.overlayChanged()
    }
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
            val visible = !locked && !hidden && visibleByOwner
            bubble?.visibility = if (visible) View.VISIBLE else View.GONE
            panel?.visibility = if (visible) View.VISIBLE else View.GONE
            bubbleLabel?.text = if (DeviceModule.controlState().optString("state") == "authorized") "操作中" else "小克"
            refreshBubble()
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
    private fun background(fill: Int = Color.rgb(246, 249, 239)) = GradientDrawable().apply { setColor(fill); cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(161, 181, 155)) }
    /** Hold colour (red while input is held, amber while only sensitive effects are) and the pending-approval badge. */
    private fun refreshBubble() {
        val fill = when {
            EmergencyHold.ordinaryHeld -> Color.rgb(239, 154, 154)
            EmergencyHold.sensitiveHeld -> Color.rgb(255, 213, 128)
            else -> Color.rgb(246, 249, 239)
        }
        bubbleLabel?.background = background(fill)
        val now = SystemClock.elapsedRealtime()
        if (now - badgeCheckedAt >= 5_000) { badgeCheckedAt = now; pendingCount = runCatching { engine.modules.pendingApprovals().size }.getOrDefault(0) }
        val pending = pendingCount
        bubbleBadge?.text = if (pending > 99) "99+" else pending.toString()
        bubbleBadge?.visibility = if (pending > 0) View.VISIBLE else View.GONE
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble?.isAttachedToWindow == true) return
        val size = dp(52)
        // WATCH_OUTSIDE_TOUCH lets an owner touch on the game hand control back during delegated input.
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(180) }
        val view = FrameLayout(theme)
        val label = TextView(theme).apply { text = "小克"; gravity = Gravity.CENTER; textSize = 15f; background = background() }
        val badge = TextView(theme).apply {
            textSize = 10f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); minWidth = dp(18); setPadding(dp(4), 0, dp(4), 0); visibility = View.GONE
            background = GradientDrawable().apply { setColor(Color.rgb(211, 47, 47)); cornerRadius = dp(9).toFloat() }
        }
        view.addView(label, FrameLayout.LayoutParams(-1, -1))
        view.addView(badge, FrameLayout.LayoutParams(-2, dp(18), Gravity.TOP or Gravity.END))
        bubbleLabel = label; bubbleBadge = badge
        view.contentDescription = "打开小克悬浮对话，可拖动；在球上按住五秒仅停止输入"
        view.setOnClickListener { if (panel == null) showPanel() else closePanel() }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        var fired = false; var cancelled = false
        val hold = FiveSecondHold(SystemClock::elapsedRealtime, ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        val timer = Runnable { if (hold.fire()) {
            fired = true; DeviceModule.emergencyHoldFromSurface(); refreshBubble()
            localStatus = "已急停点击、输入和外发；对话照常。说「继续」或点恢复就接着来"
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        } }
        cancelHold = { hold.cancel(); main.removeCallbacks(timer) }
        view.setOnTouchListener { _, e -> when (e.actionMasked) {
            // Our own panel taps are not a takeover; the controller ignores the echo of its own gestures.
            MotionEvent.ACTION_OUTSIDE -> { if (panel == null) DeviceModule.yieldToOwnerTouch(); false }
            MotionEvent.ACTION_DOWN -> { fired = false; cancelled = false; downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; hold.down(e.rawX, e.rawY); main.postDelayed(timer, 5000); true }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> { cancelled = true; cancelHold?.invoke(); true }
            MotionEvent.ACTION_MOVE -> {
                hold.move(e.rawX, e.rawY, e.pointerCount)
                if (!fired && !cancelled && e.pointerCount == 1) {
                    val metrics = resources.displayMetrics
                    val position = OverlayBallPolicy.clampPosition(startX + (e.rawX - downX).toInt(), startY + (e.rawY - downY).toInt(), metrics.widthPixels, metrics.heightPixels, size)
                    params.x = position.first; params.y = position.second; runCatching { windows.updateViewLayout(view, params) }
                }; true
            }
            MotionEvent.ACTION_UP -> { cancelHold?.invoke(); if (!fired && !cancelled && OverlayBallPolicy.isClick(e.rawX - downX, e.rawY - downY, ViewConfiguration.get(this).scaledTouchSlop)) view.performClick(); true }
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
        box.addView(button("仅停止输入，继续陪聊") { DeviceModule.stopInputFromSurface(); localStatus = "输入已停止；观察、收音与陪聊不受影响" })
        if (EmergencyHold.anyHeld) box.addView(button("恢复操作") {
            DeviceModule.continueAfterHold(); refreshBubble(); localStatus = "急停已解除"; closePanel(); showPanel()
        })
        box.addView(button("隐藏浮球，保留本次陪伴") { applyVisibility(false) })
        val deviceFrame = DeviceModule.surfaceFrame()
        if (deviceFrame.optString("packageName").isNotEmpty()) {
            box.addView(label("单机游戏操作 · 授权与陪聊独立"))
            box.addView(button("刷新游戏画面") { withoutWindow {
                DeviceModule.captureFromSurface()
                main.post { closePanel(); showPanel() }
                "画面已刷新，请尽快选择位置"
            } })
            val preview = deviceFrame.optString("preview")
            if (preview.startsWith("data:image/jpeg;base64,")) {
                val bytes = android.util.Base64.decode(preview.substringAfter(','), android.util.Base64.DEFAULT)
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    var pointX = -1f; var pointY = -1f
                    val selected = label("点画面选位置；五秒内确认，过期请刷新")
                    val image = ImageView(theme).apply {
                        setImageBitmap(bitmap); scaleType = ImageView.ScaleType.FIT_XY
                        contentDescription = "授权游戏画面，触摸选择坐标"
                        layoutParams = LinearLayout.LayoutParams(dp(260), (dp(260) * bitmap.height.toFloat() / bitmap.width).toInt().coerceAtMost(dp(340)))
                        setOnTouchListener { view, event ->
                            if (event.actionMasked == MotionEvent.ACTION_UP) {
                                pointX = deviceFrame.optInt("left") + event.x / view.width * deviceFrame.optInt("width")
                                pointY = deviceFrame.optInt("top") + event.y / view.height * deviceFrame.optInt("height")
                                selected.text = "选点：${pointX.toInt()}, ${pointY.toInt()}"; view.performClick()
                            }; true
                        }
                    }
                    box.addView(image); box.addView(selected)
                    fun executePoint(kind: String) {
                        if (pointX < 0 || pointY < 0) { localStatus = "请先在画面选择位置"; return }
                        withoutWindow {
                            val result = DeviceModule.inputFromSurface(kind, deviceFrame.optString("frameId"), pointX, pointY)
                            main.post { closePanel(); showPanel() }
                            result.optString("detail", result.optString("state"))
                        }
                    }
                    box.addView(button("确认点击选点") { executePoint("tap") })
                    box.addView(button("确认长按选点") { executePoint("longPress") })
                }
            }
        }
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
        cancelHold?.invoke(); cancelHold = null; if (activeService === this) activeService = null
        presence?.close(); presence = null; CompanionPresenceHost.detach(this)
        characterReady = false; characterWeb?.destroy(); characterWeb = null
        running = false; DeviceModule.overlayChanged(); deadline = 0; allowedPackage = ""; main.removeCallbacksAndMessages(null)
        if (taskId.isNotBlank()) engine.store.get("task", taskId)?.let { if (it.optString("state") in setOf("draft", "approved")) engine.cancel(taskId) }
        engine.store.clearTemporaryObservations()
        closePanel(); bubble?.let { runCatching { windows.removeView(it) } }; bubble = null; bubbleLabel = null; bubbleBadge = null
        worker.shutdownNow(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile private var activeService: CompanionOverlayService? = null
        fun setVisible(context: Context, visible: Boolean) {
            val owner = activeService
            if (owner != null) owner.main.post { owner.applyVisibility(visible) }
            else if (visible) start(context)
        }
        @Volatile private var deadline = 0L
        @Volatile private var allowedPackage = ""
        @Volatile var running = false; private set
        fun observedPackage() = if (running && SystemClock.elapsedRealtime() < deadline) allowedPackage else ""
        fun permits(pkg: String) = (running && pkg == allowedPackage && SystemClock.elapsedRealtime() < deadline) || DeviceModule.permitsObservation(pkg)
        fun state() = JSONObject().put("visible", activeService?.visibleByOwner == true && running).put("running", running).put("package", allowedPackage).put("remainingSeconds", ((deadline - SystemClock.elapsedRealtime()) / 1000).coerceAtLeast(0))
        fun start(context: Context, packageName: String = "", minutes: Int = 15, taskId: String = "") {
            check(Settings.canDrawOverlays(context)) { "请先开启悬浮窗权限" }
            ContextCompat.startForegroundService(context, Intent(context, CompanionOverlayService::class.java).putExtra("package", packageName).putExtra("minutes", minutes).putExtra("taskId", taskId))
        }
    }
}
