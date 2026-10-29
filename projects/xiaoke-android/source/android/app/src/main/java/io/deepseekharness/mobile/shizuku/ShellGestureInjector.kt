package io.deepseekharness.mobile.shizuku

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs in the Shizuku user service (shell uid). Injects whole gestures through the same hidden InputManager
 * call `input` uses, but in-process (about 1 ms per event instead of a 140 ms process launch), so held
 * joysticks and multi-finger gestures play at their real speed. Works on the main display and on our
 * virtual display. A cancel from another binder thread ends the gesture at the next event with ACTION_CANCEL.
 */
class ShellGestureInjector {
    private class Access(val manager: Any, val inject: java.lang.reflect.Method, val setDisplayId: java.lang.reflect.Method)
    private val access: Access? by lazy {
        runCatching {
            val type = Class.forName("android.hardware.input.InputManager")
            val manager = type.getMethod("getInstance").invoke(null) ?: return@runCatching null
            Access(manager, type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType),
                InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType))
        }.getOrNull()
    }
    private val running = AtomicReference<AtomicBoolean?>(null)

    fun inject(planJson: String): String {
        require(planJson.length <= 32 * 1024)
        val plan = GestureTimeline.parse(JSONObject(planJson))
        val events = GestureTimeline.events(plan)
        val a = access ?: return JSONObject().put("state", "failed").put("detail", "系统不允许注入触摸").toString()
        val cancel = AtomicBoolean(false)
        check(running.compareAndSet(null, cancel)) { "上一段手势还没结束" }
        val start = SystemClock.uptimeMillis()
        var sent = 0
        var current: GestureTimeline.Event? = null
        try {
            for (event in events) {
                val wait = start + event.atMs - SystemClock.uptimeMillis()
                if (wait > 0) SystemClock.sleep(wait)
                if (cancel.get()) break
                if (!send(a, plan.displayId, start, event, null)) {
                    current?.let { send(a, plan.displayId, start, it, MotionEvent.ACTION_CANCEL) }
                    return result("failed", sent, start, "系统拒绝了第 ${sent + 1} 个触摸事件")
                }
                sent++
                current = event.takeUnless { it.kind == GestureTimeline.Kind.UP }
            }
            if (cancel.get()) {
                current?.let { send(a, plan.displayId, start, it, MotionEvent.ACTION_CANCEL) }
                return result("cancelled", sent, start, "已中止并抬起手指")
            }
            return result("completed", sent, start, "")
        } finally { running.set(null) }
    }

    fun cancel() { running.get()?.set(true) }

    /** Full main-display frame from `screencap` raw output (header + RGBA), streamed through a pipe. */
    fun captureScreen(): ParcelFileDescriptor {
        val process = ProcessBuilder("/system/bin/screencap").start()
        val pipe = ParcelFileDescriptor.createReliablePipe()
        Thread {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out -> process.inputStream.use { it.copyTo(out, 64 * 1024) } }
            } catch (_: Exception) {
                runCatching { pipe[1].closeWithError("截屏失败") }
            } finally { process.destroy() }
        }.apply { isDaemon = true; name = "xiaoke-screencap" }.start()
        // Watchdog: a stuck screencap must not hold the pipe forever.
        Thread { if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly() }.apply { isDaemon = true }.start()
        return pipe[0]
    }

    private fun send(a: Access, displayId: Int, downTime: Long, event: GestureTimeline.Event, override: Int?): Boolean {
        val action = override ?: when (event.kind) {
            GestureTimeline.Kind.DOWN -> MotionEvent.ACTION_DOWN
            GestureTimeline.Kind.UP -> MotionEvent.ACTION_UP
            GestureTimeline.Kind.MOVE -> MotionEvent.ACTION_MOVE
            GestureTimeline.Kind.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
            GestureTimeline.Kind.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (event.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        val props = event.pointers.map { MotionEvent.PointerProperties().apply { id = it.id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coords = event.pointers.map { MotionEvent.PointerCoords().apply { x = it.x; y = it.y; pressure = 1f; size = 1f } }.toTypedArray()
        val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, props.size, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try {
            a.setDisplayId.invoke(motion, displayId)
            a.inject.invoke(a.manager, motion, 0) as? Boolean ?: false
        } catch (_: Exception) { false } finally { motion.recycle() }
    }

    private fun result(state: String, events: Int, start: Long, detail: String) = JSONObject().put("state", state)
        .put("events", events).put("millis", SystemClock.uptimeMillis() - start).put("detail", detail).toString()
}
