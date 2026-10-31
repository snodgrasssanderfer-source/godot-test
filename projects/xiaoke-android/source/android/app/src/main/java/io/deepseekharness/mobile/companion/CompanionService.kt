package io.deepseekharness.mobile.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.deepseekharness.mobile.MainActivity

class CompanionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val sweep = object : Runnable {
        override fun run() {
            val engine = CompanionEngine.get(this@CompanionService)
            if (!engine.store.settings().optBoolean("backgroundEnabled") && !engine.store.pendingWork() && engine.activeTask == null && !engine.store.settings().optBoolean("oneBotEnabled") && nativeOperations.get() == 0) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
            }
            handler.postDelayed(this, 10000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("companion-status", "助手运行状态", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(this, 810, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 811, Intent(this, CompanionService::class.java).setAction("pause"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "companion-status").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("小克助手").setContentText("任务与已授权会话处理中；可随时暂停")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
        // A sticky restart after the process was killed may be refused foreground status; never crash-loop on it.
        try { ServiceCompat.startForeground(this, 810, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) }
        catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        val engine = CompanionEngine.get(this)
        // This resident service is what keeps the process, and so the module host's unlock listener for proactive sharing, alive.
        engine.modules
        CompanionOverlayService.restoreIfWanted(this)
        if (intent?.action == io.deepseekharness.mobile.companion.claude.ClaudePermissions.ACTION)
            io.deepseekharness.mobile.companion.claude.ClaudePermissions.answer(intent.getStringExtra("id").orEmpty(), intent.getBooleanExtra("allow", false))
        if (intent?.action == "pause") {
            engine.store.saveSettings(engine.store.settings().put("paused", true))
            engine.activeTask?.let { engine.cancel(it, "主人在通知栏点了暂停") }; engine.cancelClaudeTurns("主人在通知栏点了暂停")
            OneBotBridge.get(this).stop()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (engine.store.settings().optBoolean("oneBotEnabled") && !engine.store.settings().optBoolean("paused")) OneBotBridge.get(this).ensureStarted()
        handler.removeCallbacks(sweep); handler.postDelayed(sweep, 10000)
        return if (engine.store.settings().optBoolean("backgroundEnabled")) START_STICKY else START_NOT_STICKY
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); OneBotBridge.get(this).stop(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        private val nativeOperations = java.util.concurrent.atomic.AtomicInteger(0)
        fun beginOperation(context: Context) {
            nativeOperations.incrementAndGet()
            try { start(context) } catch (e: Exception) { endOperation(); throw e }
        }
        fun endOperation() { nativeOperations.updateAndGet { (it - 1).coerceAtLeast(0) } }
        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, CompanionService::class.java)) }
            catch (_: IllegalStateException) { error("系统限制了后台启动，请回到助手界面再次开始") }
        }
    }
}
