package io.deepseekharness.mobile.companion.devicev2

import android.os.SystemClock
import org.json.JSONObject
import java.util.UUID

/** A single capture owner independent of the conversation and gesture lifetimes. */
object DeviceAudioSession {
    data class Config(val microphone: Boolean, val playback: Boolean, val seconds: Int, val packageName: String)
    private val pcm = PcmSession(SystemClock::elapsedRealtime)
    private var ticket = ""
    private var config: Config? = null
    private var phase = "idle"
    private var detail = ""
    private var stopService: (() -> Unit)? = null
    private var lastPublish = 0L
    @Volatile var onChanged: (() -> Unit)? = null
    @Synchronized fun prepare(value: Config): String {
        check(phase !in setOf("awaiting_permission", "starting", "recording", "stopping")) { "录音已开启或正在等待权限" }
        require(value.microphone || value.playback); require(value.seconds in 1..30)
        ticket = UUID.randomUUID().toString(); config = value; phase = "awaiting_permission"; detail = "等待系统同意"
        pcm.clear(); onChanged?.invoke(); return ticket
    }
    @Synchronized fun pending(id: String): Config? = if (id == ticket && phase == "awaiting_permission") config else null
    @Synchronized fun starting(id: String): Config? = if (id == ticket && phase == "starting") config else null
    @Synchronized fun consume(id: String): Config? = pending(id)?.also { phase = "starting"; onChanged?.invoke() }
    @Synchronized fun started(id: String, stop: () -> Unit): String {
        check(id == ticket && phase == "starting") { "录音请求已取消" }
        val c = config!!
        val session = pcm.start(buildSet { if (c.microphone) add("mic"); if (c.playback) add("playback") }, c.seconds)
        stopService = stop; phase = "recording"; detail = "正在本机分轨采集；尚未交给模型"
        onChanged?.invoke(); return session
    }
    @Synchronized fun isRecording(id: String) = id == ticket && phase == "recording"
    @Synchronized fun append(session: String, source: String, buffer: ByteArray, size: Int): Boolean {
        val result = pcm.append(session, source, buffer, size)
        if (SystemClock.elapsedRealtime() - lastPublish >= 500) { lastPublish = SystemClock.elapsedRealtime(); onChanged?.invoke() }
        return result
    }
    @Synchronized fun ended(id: String, status: String, message: String) {
        if (id != ticket) return
        if (pcm.id.isNotEmpty()) pcm.stop(pcm.id)
        phase = status; detail = message; stopService = null; onChanged?.invoke()
    }
    fun stop(session: String = "") {
        val action: (() -> Unit)?
        synchronized(this) {
            check(session.isEmpty() || session == pcm.id) { "不能停止另一个录音会话" }
            action = stopService
            if (pcm.id.isNotEmpty()) pcm.stop(pcm.id)
            phase = if (action == null) "stopped" else "stopping"; detail = "正在结束采集"; stopService = null
            if (action == null) ticket = "" // Fences an open permission dialog / pending service start.
        }
        action?.invoke(); onChanged?.invoke()
    }
    @Synchronized fun read(session: String, source: String) = pcm.wav(session, source)
    @Synchronized fun state(): JSONObject {
        val tracks = JSONObject()
        pcm.metadata().forEach { (name, m) -> tracks.put(name, JSONObject().put("samples", m.samples).put("hasSignal", m.nonZero)
            .put("firstAtMs", m.firstAtMs).put("lastAtMs", m.lastAtMs).put("sampleRate", 16000).put("channels", 1)
            .put("signalStatus", if (m.nonZero) "signal_received" else "silence_or_capture_disallowed")) }
        return JSONObject().put("state", phase).put("jobId", ticket).put("captureSession", pcm.id).put("detail", detail)
            .put("mode", "bounded_capture").put("tracks", tracks).put("fullDuplexVerified", false)
            .put("microphoneBusy", phase == "recording" && config?.microphone == true)
            .put("mayContainAssistantEcho", config?.microphone == true)
    }
}
