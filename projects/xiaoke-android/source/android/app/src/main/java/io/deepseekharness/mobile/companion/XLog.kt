package io.deepseekharness.mobile.companion

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Companion log: logcat under one tag ("Xiaoke"), plus a small rolling file the owner can export from 我的 → 导出诊断日志.
 * Secrets are scrubbed on the way in and again on export.
 */
object XLog {
    const val TAG = "Xiaoke"
    private const val MAX_BYTES = 256 * 1024L
    @Volatile private var file: File? = null
    private val clock = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

    fun init(context: Context) { if (file == null) file = File(context.applicationContext.filesDir, "xiaoke-diagnostics.log") }

    fun i(message: String) { android.util.Log.i(TAG, message); append("I", message) }
    fun w(message: String, error: Throwable? = null) {
        val line = if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message
        android.util.Log.w(TAG, line); append("W", line)
    }
    fun e(message: String, error: Throwable? = null) {
        val line = if (error != null) "$message (${error.javaClass.simpleName}: ${error.message})" else message
        android.util.Log.e(TAG, line); append("E", line)
    }

    @Synchronized private fun append(level: String, message: String) = runCatching {
        val target = file ?: return@runCatching
        if (target.length() > MAX_BYTES) { val old = File(target.path + ".1"); old.delete(); target.renameTo(old) }
        target.appendText("${LocalDateTime.now().format(clock)} $level ${scrub(message).take(2000)}\n")
    }

    /** Both log files, oldest first. */
    @Synchronized fun read(): String {
        val target = file ?: return ""
        return listOf(File(target.path + ".1"), target).filter { it.isFile }.joinToString("") { it.readText() }
    }

    private val secrets = listOf(
        Regex("sk-[A-Za-z0-9_-]{12,}"), Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}"),
        Regex("(?i)(\"?(?:token|key|password|secret|cookie|sessdata|access_token|refresh_token)\"?\\s*[:=]\\s*\"?)[^\"\\s,;}]{4,}"),
        Regex("(?i)(x-goog-api-key[:=]\\s*)\\S+"), Regex("\\b[A-Fa-f0-9]{40,}\\b"),
    )

    /** Keys, tokens, cookies and long hex secrets become "***"; the label before them stays so the line still reads. */
    fun scrub(text: String): String = secrets.fold(text) { acc, pattern ->
        pattern.replace(acc) { m -> if (m.groupValues.size > 1 && m.groupValues[1].isNotEmpty()) m.groupValues[1] + "***" else "***" }
    }
}
