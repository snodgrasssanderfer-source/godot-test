package io.deepseekharness.mobile.companion.devicev2

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import io.deepseekharness.mobile.accessibility.AccessibilityAutomationStore
import io.deepseekharness.mobile.accessibility.DeepSeekAccessibilityService
import io.deepseekharness.mobile.companion.CompanionDevice
import io.deepseekharness.mobile.companion.sha256
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ControlFrame(val id: String, val session: String, val packageName: String, val atMs: Long,
    val windowId: Int, val rotation: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val treeDigest: String, val screenHash: String, val displayId: Int = 0, val fingerprint: Long = 0)

/** With Shizuku, whole gestures (holds, drags, several fingers) go through the shell injector and stop at once.
 * Without it, accessibility gestures in short continued segments bound stop latency without injecting a replacement tap. */
class GameInputController(private val context: Context, private val changed: () -> Unit) {
    val fence = InputFence(SystemClock::elapsedRealtime)
    /** Lets the owner-touch watcher ignore the echo of our own injected gesture. */
    @Volatile var lastGestureEndedAt = 0L; private set
    private val main = Handler(Looper.getMainLooper())
    @Volatile var background = false; private set
    fun stop(reason: String = "owner_stop"): JSONObject {
        fence.stop(reason); ShizukuInput.cancel(); changed(); return state()
    }
    fun state() = JSONObject(fence.status()).put("channel", if (ShizukuInput.ready()) "shizuku" else "accessibility").put("background", background)
    fun start(packageName: String, seconds: Int, expectedEpoch: Long, background: Boolean = false): JSONObject {
        if (background) check(ShizukuInput.ready()) { "后台副屏需要 Shizuku 已运行并授权" }
        else {
            check(AccessibilityAutomationStore.packageAllowed(context, packageName)) { "请先在 DSH 无障碍设置中授权目标应用" }
            check(DeepSeekAccessibilityService.current() != null) { "请先开启无障碍服务" }
        }
        fence.start(packageName, seconds, expectedEpoch); this.background = background; changed(); return state()
    }

    fun perform(kind: String, data: JSONObject, frame: ControlFrame): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "动作不能阻塞界面线程" }
        require(kind in KINDS) { "不支持的动作：$kind" }
        val shizuku = ShizukuInput.ready()
        check(shizuku || frame.displayId == 0) { "后台副屏需要 Shizuku" }
        val bounds = intArrayOf(frame.left, frame.top, frame.right, frame.bottom)
        val plan = plan(kind, data, bounds, if (!shizuku) 1500 else if (frame.displayId > 0) 30_000 else 10_000)
        val id = data.getString("requestId")
        val digest = listOf(kind, frame.id, data.getString("sessionId"), data.getLong("inputEpoch"), plan).joinToString("|").toByteArray().sha256()
        fence.receipt(id)?.let { check(it.digest == digest) { "重复编号的动作已更改" }; return receipt(it) }
        // Games animate constantly: same window, same rotation and a recent frame is enough. Pixel-exact rechecks refused nearly every tap.
        validate(frame)
        val token = fence.current() ?: error("输入已停止")
        check(token.packageName == frame.packageName) { "观察画面与控制范围不一致" }
        val item = fence.enqueue(id, digest, data.getString("sessionId"), data.getLong("inputEpoch"))
        if (shizuku) return viaShizuku(id, plan.put("displayId", frame.displayId))
        val service = DeepSeekAccessibilityService.current() ?: run { fence.finish(id, "refused", "无障碍已断开"); error("无障碍已断开") }
        if (kind !in setOf("tap", "swipe", "longPress")) return strokes(id, plan, service)
        val x = data.getDouble("x").toFloat(); val y = data.getDouble("y").toFloat()
        val endX = if (kind == "swipe") data.getDouble("toX").toFloat() else x
        val endY = if (kind == "swipe") data.getDouble("toY").toFloat() else y
        val duration = when (kind) { "tap" -> 70L; "longPress" -> data.optLong("durationMs", 600L).coerceIn(500, 1500); else -> data.optLong("durationMs", 400L).coerceIn(100, 1500) }
        val finished = CountDownLatch(1)
        val segments = gestureSegments(duration)
        val pieces = segments.size
        var previous: GestureDescription.StrokeDescription? = null
        var lastX = x; var lastY = y
        fun complete(state: String, detail: String = "") { lastGestureEndedAt = SystemClock.elapsedRealtime(); fence.finish(id, state, detail); finished.countDown(); changed() }
        fun release() {
            val stroke = previous
            if (stroke == null) { complete("stopped"); return }
            val accepted = runCatching {
                val path = Path().apply { moveTo(lastX, lastY) }
                val end = stroke.continueStroke(path, 0, 1, false)
                service.dispatchGesture(GestureDescription.Builder().addStroke(end).build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { complete("stopped", "触点已释放") }
                override fun onCancelled(gestureDescription: GestureDescription?) { complete("stopped", "系统已取消触点") }
            }, main) }.getOrDefault(false)
            // If Android refuses a continuation, do not emit another touch. The OS owns release.
            if (!accepted) complete("outcome_unknown", "系统未确认释放；请手动触屏接管，禁止自动重试")
        }
        fun step(index: Int) {
            if (!fence.isLive(item.lease)) { release(); return }
            try { validate(frame, enforceAge = index == 0) } catch (e: Exception) { stop("window_changed"); release(); return }
            val nextX = x + (endX - x) * (index + 1) / pieces
            val nextY = y + (endY - y) * (index + 1) / pieces
            val path = Path().apply { moveTo(lastX, lastY); if (kind == "swipe") lineTo(nextX, nextY) }
            val more = index + 1 < pieces
            val segmentMs = segments[index]
            val stroke = previous?.continueStroke(path, 0, segmentMs, more)
                ?: GestureDescription.StrokeDescription(path, 0, segmentMs, more)
            val accepted = fence.dispatch(id) {
                service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        previous = if (more) stroke else null; lastX = nextX; lastY = nextY
                        try { if (more) step(index + 1) else complete("completed", "系统已完成手势；游戏结果尚待后置画面核对") }
                        catch (_: Exception) { stop("dispatch_failed"); release() }
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) { previous = null; complete("cancelled", "系统取消手势，不自动重试") }
                }, main)
            }
            if (!accepted) { if (previous != null) release() else complete("refused", "未派发动作") }
        }
        main.post { try { step(0) } catch (_: Exception) { stop("dispatch_failed"); release() } }
        if (!finished.await(5, TimeUnit.SECONDS)) {
            stop("callback_timeout")
            // A missing native callback is an unknown outcome; keep the active fence until release is known.
            return JSONObject().put("requestId", id).put("state", "outcome_unknown").put("detail", "系统手势回执超时；已停止输入，不能自动重试")
        }
        return receipt(fence.receipt(id) ?: error("动作记录丢失"))
    }
    /** The shell injector runs the whole gesture; a stop cancels it at the next event and lifts every finger. */
    private fun viaShizuku(id: String, plan: JSONObject): JSONObject {
        if (!fence.dispatch(id) { true }) { fence.finish(id, "stopped", "输入已停止"); return receipt(fence.receipt(id)!!) }
        val outcome = try { ShizukuInput.inject(context, plan) } catch (e: Exception) { JSONObject().put("state", "failed").put("detail", e.message?.take(160)) }
        lastGestureEndedAt = SystemClock.elapsedRealtime()
        when (outcome.optString("state")) {
            "completed" -> fence.finish(id, "completed", "系统已完成手势；游戏结果尚待后置画面核对")
            "cancelled" -> fence.finish(id, "stopped", "已中止，手指已抬起")
            else -> fence.finish(id, "refused", outcome.optString("detail", "注入失败"))
        }
        changed()
        return receipt(fence.receipt(id) ?: error("动作记录丢失")).put("channel", "shizuku").put("millis", outcome.optLong("millis"))
    }

    /** Accessibility fallback for holds and several fingers: one bounded dispatch (at most 1.5 s, so a stop is never far off). */
    private fun strokes(id: String, plan: JSONObject, service: DeepSeekAccessibilityService): JSONObject {
        val pointers = plan.getJSONArray("pointers")
        val builder = GestureDescription.Builder()
        for (i in 0 until pointers.length()) {
            val p = pointers.getJSONObject(i); val path = p.getJSONArray("path")
            val line = Path().apply {
                moveTo(path.getJSONArray(0).getDouble(0).toFloat(), path.getJSONArray(0).getDouble(1).toFloat())
                for (k in 1 until path.length()) lineTo(path.getJSONArray(k).getDouble(0).toFloat(), path.getJSONArray(k).getDouble(1).toFloat())
            }
            builder.addStroke(GestureDescription.StrokeDescription(line, p.optLong("startMs"), p.getLong("durationMs")))
        }
        val finished = CountDownLatch(1)
        fun complete(state: String, detail: String) { lastGestureEndedAt = SystemClock.elapsedRealtime(); fence.finish(id, state, detail); finished.countDown(); changed() }
        val accepted = fence.dispatch(id) {
            service.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { complete("completed", "系统已完成手势；游戏结果尚待后置画面核对") }
                override fun onCancelled(gestureDescription: GestureDescription?) { complete("cancelled", "系统取消手势，不自动重试") }
            }, main)
        }
        if (!accepted) complete("refused", "未派发动作")
        if (!finished.await(5, TimeUnit.SECONDS)) { stop("callback_timeout"); return JSONObject().put("requestId", id).put("state", "outcome_unknown").put("detail", "系统手势回执超时；已停止输入") }
        return receipt(fence.receipt(id) ?: error("动作记录丢失")).put("channel", "accessibility")
    }

    private fun validate(frame: ControlFrame, enforceAge: Boolean = true) {
        val age = SystemClock.elapsedRealtime() - frame.atMs
        if (enforceAge) check(age in 0..15_000) { "画面已过期，请重新观察" }
        if (frame.displayId > 0) { check(BackgroundScreen.session(frame.packageName)?.displayId == frame.displayId) { "后台副屏已关闭，请重新开始" }; return }
        check(AccessibilityAutomationStore.packageAllowed(context, frame.packageName)) { "应用控制授权已撤回" }
        val tree = CompanionDevice(context).observe(frame.packageName)
        check(tree.optInt("windowId", -1) == frame.windowId && rotation(context) == frame.rotation) { "窗口或旋转已变化，请重新观察" }
    }
    private fun receipt(r: InputFence.Receipt) = JSONObject().put("requestId", r.requestId).put("state", r.state).put("detail", r.detail)
    companion object {
        val KINDS = setOf("tap", "swipe", "longPress", "hold", "multi")
        @Suppress("DEPRECATION") fun rotation(context: Context) = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation

        /**
         * The gesture as finger strokes in screen pixels (see GestureTimeline). `hold` presses, optionally drags to
         * toX,toY within moveMs, then stays until durationMs: a joystick or a charge button. `multi` takes up to five
         * pointers, each {x,y,toX?,toY?,startMs?,durationMs?,moveMs?}.
         */
        fun plan(kind: String, data: JSONObject, bounds: IntArray, maxMs: Long): JSONObject {
            fun inside(x: Double, y: Double) = x.isFinite() && y.isFinite() && x >= bounds[0] && x < bounds[2] && y >= bounds[1] && y < bounds[3]
            fun stroke(p: JSONObject, kind: String): JSONObject {
                val x = p.getDouble("x"); val y = p.getDouble("y")
                val moving = p.has("toX") && p.has("toY")
                val toX = if (moving) p.getDouble("toX") else x; val toY = if (moving) p.getDouble("toY") else y
                require(inside(x, y) && inside(toX, toY)) { "动作坐标超出观察画面" }
                val duration = when (kind) {
                    "tap" -> 70L
                    "longPress" -> p.optLong("durationMs", 600)
                    "swipe" -> p.optLong("durationMs", 300)
                    else -> p.optLong("durationMs", 1000)
                }
                require(duration in 30..maxMs) { "这次动作最长 ${maxMs} 毫秒" }
                val path = org.json.JSONArray().put(org.json.JSONArray().put(x).put(y).put(0))
                if (kind == "swipe") path.put(org.json.JSONArray().put(toX).put(toY).put(duration))
                else if (moving) path.put(org.json.JSONArray().put(toX).put(toY).put(p.optLong("moveMs", 150).coerceIn(16, duration)))
                return JSONObject().put("path", path).put("startMs", p.optLong("startMs", 0).coerceIn(0, maxMs - duration)).put("durationMs", duration)
            }
            val pointers = org.json.JSONArray()
            when (kind) {
                "multi" -> {
                    val list = data.getJSONArray("pointers")
                    require(list.length() in 1..5) { "最多五根手指" }
                    for (i in 0 until list.length()) pointers.put(stroke(list.getJSONObject(i), "hold"))
                }
                "swipe" -> { require(data.has("toX") && data.has("toY")) { "滑动需要 toX,toY" }; pointers.put(stroke(data, kind)) }
                else -> pointers.put(stroke(data, kind))
            }
            return JSONObject().put("pointers", pointers)
        }
    }
}
