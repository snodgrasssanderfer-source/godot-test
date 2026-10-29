package io.deepseekharness.mobile.companion.devicev2

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import io.deepseekharness.mobile.shizuku.ShizukuRuntime
import io.deepseekharness.mobile.virtualscreen.VirtualScreenPreferences
import io.deepseekharness.mobile.virtualscreen.VirtualScreenService
import io.deepseekharness.mobile.virtualscreen.VirtualScreenStart
import io.deepseekharness.mobile.virtualscreen.putVirtualScreenStart
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Shizuku input and screen: whole gestures injected by the shell-uid user service, raw `screencap` frames. */
object ShizukuInput {
    private val sink = object : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
        override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
    }
    @Volatile private var runtime: ShizukuRuntime? = null

    fun ready(): Boolean = runCatching {
        Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun runtime(context: Context): ShizukuRuntime = runtime ?: synchronized(this) {
        runtime ?: RuntimeHost.acquire(context.applicationContext, sink).terminals.shizuku.also { runtime = it }
    }

    /** Called when control ends, so the shared runtime can wind down if nothing else uses it. */
    fun release() = synchronized(this) {
        if (runtime != null) { runtime = null; runCatching { RuntimeHost.detachPluginSink(sink) } }
    }

    fun inject(context: Context, plan: JSONObject): JSONObject = runtime(context).injectGesture(plan.toString())
    fun cancel() { runtime?.cancelGesture() }

    /** Whole main display, in screen pixels. */
    fun screen(context: Context): Bitmap {
        val raw = runtime(context).captureScreen()
        check(raw.size > 16) { "截屏为空" }
        val head = ByteBuffer.wrap(raw, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
        val w = head.int; val h = head.int
        check(w in 1..8192 && h in 1..8192) { "截屏格式无法识别" }
        val header = raw.size - w * h * 4
        check(header == 12 || header == 16) { "截屏格式无法识别" }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            copyPixelsFromBuffer(ByteBuffer.wrap(raw, header, w * h * 4))
        }
    }

    fun jpeg(bitmap: Bitmap): ByteArray {
        val scale = 960.0 / maxOf(bitmap.width, bitmap.height).coerceAtLeast(960)
        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 75, out)
        if (small !== bitmap) small.recycle()
        return out.toByteArray()
    }

    fun gray(bitmap: Bitmap, originX: Int = 0, originY: Int = 0, pixelScale: Float = 1f): FrameMatcher.Gray {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val g = FrameMatcher.fromArgb(px, bitmap.width, bitmap.height)
        return FrameMatcher.Gray(g.w, g.h, g.px, g.scale * pixelScale, originX, originY)
    }
}

/**
 * Background play: the game runs on DSH's virtual display, so the owner keeps using the phone while she plays.
 * Input goes to that display through the same Shizuku injector; frames come from the virtual display itself.
 */
object BackgroundScreen {
    data class Session(val sessionId: String, val displayId: Int, val width: Int, val height: Int)

    fun session(pkg: String): Session? {
        val state = runCatching { VirtualScreenService.current?.state() }.getOrNull() ?: return null
        if (!state.optBoolean("active") || state.optString("packageName") != pkg) return null
        val id = state.optInt("displayId"); val sessionId = state.optString("sessionId")
        if (id <= 0 || sessionId.isBlank()) return null
        return Session(sessionId, id, state.optInt("width"), state.optInt("height"))
    }

    fun start(context: Context, pkg: String): Session {
        session(pkg)?.let { return it }
        check(VirtualScreenService.current == null) { "副屏正在显示别的应用，先在副屏里关掉它" }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString() ?: error("无法打开目标应用")
        val settings = VirtualScreenPreferences.read(context)
        val metrics = context.resources.displayMetrics
        val request = VirtualScreenPreferences.parseStart(JSONObject().put("packageName", pkg))
        val spec = VirtualScreenStart.resolve(request, settings, metrics.widthPixels, metrics.heightPixels, metrics.density)
        ContextCompat.startForegroundService(context, Intent(context, VirtualScreenService::class.java)
            .putExtra("component", launch).putVirtualScreenStart(spec, request.adaptive ?: settings.adaptive, request.orientation ?: settings.orientation))
        val deadline = SystemClock.elapsedRealtime() + 12_000
        while (SystemClock.elapsedRealtime() < deadline) {
            session(pkg)?.let { return it }
            Thread.sleep(300)
        }
        error("副屏没能启动；请确认 Shizuku 已授权")
    }

    fun frame(session: Session): Bitmap {
        val service = VirtualScreenService.current ?: error("副屏已关闭")
        val png = service.screenshot(session.sessionId)
        return BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("副屏画面无法解码")
    }

    fun stop() { runCatching { VirtualScreenService.current?.requestStop() } }
}
