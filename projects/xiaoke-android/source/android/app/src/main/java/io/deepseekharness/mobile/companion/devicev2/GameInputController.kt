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
import kotlin.math.ceil

data class ControlFrame(val id: String, val session: String, val packageName: String, val atMs: Long,
    val windowId: Int, val rotation: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val treeDigest: String)

/** Short, continued gesture segments bound stop latency without injecting a replacement tap. */
class GameInputController(private val context: Context, private val changed: () -> Unit) {
    val fence = InputFence(SystemClock::elapsedRealtime)
    private val main = Handler(Looper.getMainLooper())
    fun stop(reason: String = "owner_stop"): JSONObject {
        fence.stop(reason); changed(); return state()
    }
    fun state() = JSONObject(fence.status())
    fun start(packageName: String, seconds: Int): JSONObject {
        check(AccessibilityAutomationStore.packageAllowed(context, packageName)) { "请先在 DSH 无障碍设置中授权目标应用" }
        check(DeepSeekAccessibilityService.current() != null) { "请先开启无障碍服务" }
        fence.start(packageName, seconds); changed(); return state()
    }

    fun perform(kind: String, data: JSONObject, frame: ControlFrame): JSONObject {
        check(Looper.myLooper() != Looper.getMainLooper()) { "动作不能阻塞界面线程" }
        require(kind in setOf("tap", "swipe", "longPress"))
        val x = data.getDouble("x").toFloat(); val y = data.getDouble("y").toFloat()
        val endX = if (kind == "swipe") data.getDouble("toX").toFloat() else x
        val endY = if (kind == "swipe") data.getDouble("toY").toFloat() else y
        fun inside(a: Float, b: Float) = a.isFinite() && b.isFinite() && a >= frame.left && a < frame.right && b >= frame.top && b < frame.bottom
        require(inside(x, y) && inside(endX, endY)) { "动作坐标超出观察窗口" }
        val duration = when (kind) {
            "tap" -> 70L
            "longPress" -> data.optLong("durationMs", 600L).also { require(it in 500..1500) }
            else -> data.optLong("durationMs", 400L).also { require(it in 100..1500) }
        }
        val id = data.getString("requestId")
        val digest = listOf(kind, frame.id, data.getString("sessionId"), data.getLong("inputEpoch"), x, y, endX, endY, duration).joinToString("|").toByteArray().sha256()
        fence.receipt(id)?.let { check(it.digest == digest) { "重复编号的动作已更改" }; return receipt(it) }
        validate(frame)
        val token = fence.current() ?: error("输入已停止")
        check(token.packageName == frame.packageName) { "观察画面与控制范围不一致" }
        val item = fence.enqueue(id, digest, data.getString("sessionId"), data.getLong("inputEpoch"))
        val service = DeepSeekAccessibilityService.current() ?: run { fence.finish(id, "refused", "无障碍已断开"); error("无障碍已断开") }
        val finished = CountDownLatch(1)
        val pieces = ceil(duration / 80.0).toInt()
        var previous: GestureDescription.StrokeDescription? = null
        var lastX = x; var lastY = y
        fun complete(state: String, detail: String = "") { fence.finish(id, state, detail); finished.countDown(); changed() }
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
            val segmentMs = if (index + 1 == pieces) duration - (duration / pieces) * index else duration / pieces
            val stroke = previous?.continueStroke(path, 0, segmentMs, more)
                ?: GestureDescription.StrokeDescription(path, 0, segmentMs, more)
            val accepted = fence.dispatch(id) {
                service.dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        previous = if (more) stroke else null; lastX = nextX; lastY = nextY
                        if (more) step(index + 1) else complete("completed", "系统已完成手势；游戏结果尚待后置画面核对")
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
    private fun validate(frame: ControlFrame, enforceAge: Boolean = true) {
        val age = SystemClock.elapsedRealtime() - frame.atMs
        if (enforceAge) check(age in 0..5_000) { "画面已过期，请重新观察" }
        check(AccessibilityAutomationStore.packageAllowed(context, frame.packageName)) { "应用控制授权已撤回" }
        val tree = CompanionDevice(context).observe(frame.packageName)
        check(tree.optInt("windowId", -1) == frame.windowId && rotation(context) == frame.rotation) { "窗口或旋转已变化，请重新观察" }
        if (enforceAge) check(tree.getJSONArray("nodes").toString().toByteArray().sha256() == frame.treeDigest) { "界面已变化，请重新观察" }
    }
    private fun receipt(r: InputFence.Receipt) = JSONObject().put("requestId", r.requestId).put("state", r.state).put("detail", r.detail)
    companion object {
        @Suppress("DEPRECATION") fun rotation(context: Context) = context.getSystemService(WindowManager::class.java).defaultDisplay.rotation
    }
}
