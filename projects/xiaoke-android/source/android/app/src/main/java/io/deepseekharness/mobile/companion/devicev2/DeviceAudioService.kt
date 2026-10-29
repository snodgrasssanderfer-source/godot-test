package io.deepseekharness.mobile.companion.devicev2

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.ServiceCompat
import io.deepseekharness.mobile.companion.CompanionPresenceHost
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Native PCM capture, two independent tracks, no network, camera or persistent media files. */
class DeviceAudioService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2)
    private val records = CopyOnWriteArrayList<AudioRecord>()
    private val closed = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var ticket = ""
    private var session = ""
    private var ownsMicrophone = false
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { finish("projection_revoked", "投影已被系统或主人撤销，采集已结束") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { DeviceAudioSession.stop(); return START_NOT_STICKY }
        if (ticket.isNotEmpty()) return START_NOT_STICKY
        ticket = intent?.getStringExtra("ticket").orEmpty()
        val config = DeviceAudioSession.starting(ticket) ?: run { stopSelf(); return START_NOT_STICKY }
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("device-audio", "小克音频采集", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 862, Intent(this, DeviceAudioService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val notice = Notification.Builder(this, "device-audio").setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("小克 · 限时分轨收音").setContentText("麦克风与内播分别保存于临时内存；尚未上传")
                .setOngoing(true).addAction(Notification.Action.Builder(null, "停止收音", stop).build()).build()
            var types = 0
            if (config.microphone && Build.VERSION.SDK_INT >= 30) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (config.playback && Build.VERSION.SDK_INT >= 29) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            ServiceCompat.startForeground(this, 861, notice, types)
            if (config.playback) {
                check(Build.VERSION.SDK_INT >= 29)
                @Suppress("DEPRECATION") val token = intent?.getParcelableExtra<Intent>("projection") ?: error("missing_projection")
                projection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK, token)
                projection!!.registerCallback(projectionCallback, main)
            }
            if (config.microphone) { ownsMicrophone = true; CompanionPresenceHost.externalMicrophone(true) }
            val format = AudioFormat.Builder().setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
            val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minimum > 0)
            val bufferSize = maxOf(minimum * 2, 6400)
            val sources = mutableListOf<Pair<String, AudioRecord>>()
            fun create(source: String, builder: AudioRecord.Builder) {
                val record = builder.setAudioFormat(format).setBufferSizeInBytes(bufferSize).build()
                records.add(record); check(record.state == AudioRecord.STATE_INITIALIZED)
                sources.add(source to record)
            }
            if (config.microphone) create("mic", AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION))
            if (config.playback && Build.VERSION.SDK_INT >= 29) {
                val uid = packageManager.getApplicationInfo(config.packageName, 0).uid
                require(uid != applicationInfo.uid)
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!).addMatchingUid(uid)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
                create("playback", AudioRecord.Builder().setAudioPlaybackCaptureConfig(capture))
            }
            sources.forEach { (_, record) -> record.startRecording(); check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
            session = DeviceAudioSession.started(ticket) { main.post { finish("stopped", "已停止收音，临时音轨最多保留五分钟") } }
            sources.forEach { (source, record) -> worker.execute {
                val buffer = ByteArray(3200)
                try {
                    while (!closed.get() && DeviceAudioSession.isRecording(ticket)) {
                        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (closed.get()) break
                        check(count > 0) { "audio_read_$count" }
                        if (!DeviceAudioSession.append(session, source, buffer, count - count % 2)) break
                    }
                } catch (_: Exception) { if (!closed.get()) main.post { finish("error", "$source 音轨采集被系统中断") } }
            } }
            main.postDelayed({ finish("completed", "限时采集完成；静音不代表已取得受保护音轨") }, config.seconds * 1000L)
        } catch (_: Exception) { finish("error", "音频启动失败，权限、系统占用或投影限制可能阻止采集") }
        return START_NOT_STICKY
    }
    private fun finish(state: String, message: String) {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacksAndMessages(null)
        records.forEach { runCatching { it.stop() } }
        worker.shutdown()
        // Releasing after read threads exit avoids releasing an AudioRecord under a blocking read.
        Thread {
            worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
            records.forEach { runCatching { it.release() } }; records.clear()
        }.start()
        projection?.let { it.unregisterCallback(projectionCallback); runCatching { it.stop() } }; projection = null
        if (ownsMicrophone) { ownsMicrophone = false; CompanionPresenceHost.externalMicrophone(false) }
        DeviceAudioSession.ended(ticket, state, message)
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() { finish("stopped", "录音服务已结束"); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
