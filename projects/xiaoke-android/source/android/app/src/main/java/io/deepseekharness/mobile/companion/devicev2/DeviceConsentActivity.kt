package io.deepseekharness.mobile.companion.devicev2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat

/** Local visible permission entry. Neither projection tokens nor grants are saved across recreation. */
class DeviceConsentActivity : Activity() {
    private var ticket = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ticket = intent.getStringExtra("ticket").orEmpty()
        if (state != null) { fail("permission_interrupted", "权限流程被重建，请重新开始"); return }
        if (DeviceAudioSession.pending(ticket) == null) { finish(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 91)
        else projectOrStart()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 91) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) projectOrStart()
        else fail("permission_denied", "未获得录音权限，没有开始采集")
    }
    private fun projectOrStart() {
        val config = DeviceAudioSession.pending(ticket) ?: run { finish(); return }
        if (!config.playback) { startCapture(null); return }
        if (Build.VERSION.SDK_INT < 29) { fail("unsupported", "内播采集需要 Android 10 以上"); return }
        @Suppress("DEPRECATION")
        startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 92)
    }
    @Deprecated("Platform activity callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 92) return
        if (resultCode == RESULT_OK && data != null) startCapture(data)
        else fail("projection_denied", "未获得投影同意，没有开始采集")
    }
    private fun startCapture(projection: Intent?) {
        if (DeviceAudioSession.consume(ticket) == null) { finish(); return }
        try {
            ContextCompat.startForegroundService(this, Intent(this, DeviceAudioService::class.java)
                .putExtra("ticket", ticket).putExtra("projection", projection))
        } catch (_: Exception) { DeviceAudioSession.ended(ticket, "error", "系统拒绝启动录音服务，请在前台重新开始") }
        finish()
    }
    private fun fail(state: String, message: String) { DeviceAudioSession.ended(ticket, state, message); finish() }
}
