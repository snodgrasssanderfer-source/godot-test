package io.deepseekharness.mobile.companion.claude

import android.content.Context
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.XLog
import io.deepseekharness.mobile.runtime.NativePty
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Install, login and status for the 我的 → Claude page and onboarding. One of each runs at a time. */
object ClaudeSetup {
    private val installing = AtomicBoolean(false)
    private val cancelInstall = AtomicBoolean(false)
    @Volatile private var install = JSONObject().put("phase", "idle")
    @Volatile private var auth: ClaudeRuntime.AuthStatus? = null
    @Volatile private var authCheckedAt = 0L

    fun status(context: Context, refreshAuth: Boolean = false): JSONObject {
        val runtime = ClaudeRuntime(context)
        val engine = CompanionEngine.get(context)
        val version = runCatching { runtime.version() }.getOrNull()
        if (version != null && (refreshAuth || System.currentTimeMillis() - authCheckedAt > 10 * 60_000L) && !installing.get()) {
            auth = runtime.authStatus(); authCheckedAt = System.currentTimeMillis()
            if (auth?.loggedIn == true && ClaudeChat.outage(engine.store)?.optString("reason") == ClaudeStream.Failure.LOGIN.name) ClaudeChat.clearOutage(engine.store)
        }
        return JSONObject().put("runtimeReady", runtime.runtimeReady()).put("version", version ?: JSONObject.NULL)
            .put("install", install).put("login", ClaudeLogin.state())
            .put("loggedIn", auth?.loggedIn == true).put("authMethod", auth?.method.orEmpty()).put("authNote", auth?.note.orEmpty())
            .put("settings", ClaudeChat.settings(engine.store)).put("usage", engine.store.lifeState("claudeUsage").apply { remove("id"); remove("scope") })
            .put("outage", ClaudeChat.outage(engine.store) ?: JSONObject.NULL)
            .put("permissions", ClaudePermissions.list()).put("workspace", ClaudeWorkspace.state(runtime))
    }

    fun install(context: Context, channel: String = "stable"): JSONObject {
        check(installing.compareAndSet(false, true)) { "正在安装" }
        cancelInstall.set(false)
        install = JSONObject().put("phase", "checking")
        Thread({
            // A 290 MB download: the foreground service keeps the process alive until it is done.
            runCatching { io.deepseekharness.mobile.companion.CompanionService.beginOperation(context) }
            try {
                val runtime = ClaudeRuntime(context)
                check(runtime.runtimeReady()) { "先装好 Ubuntu 运行环境（引导第一步）" }
                val version = runtime.installer.install(channel, progress = { done, total ->
                    install = JSONObject().put("phase", "downloading").put("done", done).put("total", total)
                }, cancelled = { cancelInstall.get() })
                install = JSONObject().put("phase", "verifying").put("version", version)
                val r = runtime.run(listOf("--version"), 60)
                check(r.code == 0 && version in r.output) { "装好了但运行不起来：${r.output.trim().takeLast(300).ifBlank { "退出码 ${r.code}" }}" }
                install = JSONObject().put("phase", "done").put("version", version)
                XLog.i("claude code $version installed and runs")
            } catch (e: Exception) {
                install = JSONObject().put("phase", "failed").put("error", (e.message ?: "安装失败").take(300))
                XLog.w("claude install failed", e)
            } finally { installing.set(false); io.deepseekharness.mobile.companion.CompanionService.endOperation() }
        }, "claude-install").apply { isDaemon = true }.start()
        return install
    }

    fun cancelInstall() { cancelInstall.set(true) }
}

/**
 * `claude auth login` in a pseudo-terminal. The app only moves bytes: what Claude Code prints is shown to the owner
 * (with the sign-in link made tappable), and what the owner types (the code from the browser) is written back. Nothing
 * here parses, keeps or forwards credentials; Claude Code stores them itself inside the rootfs.
 */
object ClaudeLogin {
    private class Session(val pid: Int, val fd: Int) { val output = StringBuilder(); @Volatile var exit: Int? = null }
    @Volatile private var session: Session? = null
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>]|\r")

    fun state(): JSONObject {
        val s = session ?: return JSONObject().put("running", false)
        val text = synchronized(s.output) { s.output.toString() }
        val url = Regex("https://[^\\s\"'<>]+").findAll(text).map { it.value }.lastOrNull { "oauth" in it || "claude.ai" in it || "anthropic.com" in it }
        return JSONObject().put("running", s.exit == null).put("exit", s.exit ?: JSONObject.NULL).put("output", text.takeLast(4000)).put("url", url ?: JSONObject.NULL)
    }

    @Synchronized fun start(context: Context, method: String = "claudeai"): JSONObject {
        session?.takeIf { it.exit == null }?.let { return state() }
        require(method in setOf("claudeai", "console")) { "登录方式无效" }
        val spec = ClaudeRuntime(context).launch(listOf("auth", "login", "--$method"))
        // Wide enough that the sign-in link is never wrapped across lines.
        val handles = NativePty.spawn(spec.argv.toTypedArray(), spec.environment.map { (k, v) -> "$k=$v" }.toTypedArray(), 400, 40)
        val s = Session(handles[0].toInt(), handles[1].toInt())
        session = s
        Thread({
            val buffer = ByteArray(8192)
            try {
                while (true) {
                    val n = NativePty.read(s.fd, buffer); if (n <= 0) break
                    val chunk = ansi.replace(String(buffer, 0, n, Charsets.UTF_8), "")
                    synchronized(s.output) { s.output.append(chunk); if (s.output.length > 16000) s.output.delete(0, s.output.length - 16000) }
                }
                s.exit = NativePty.waitFor(s.pid)
            } catch (_: Throwable) { s.exit = s.exit ?: 255 }
            finally {
                runCatching { NativePty.close(s.fd) }
                if (s.exit == 0) runCatching { ClaudeSetup.status(context, refreshAuth = true) }
            }
        }, "claude-login").apply { isDaemon = true }.start()
        return state()
    }

    fun input(text: String) {
        val s = session?.takeIf { it.exit == null } ?: error("登录没有在进行")
        require(text.length <= 4000)
        val bytes = (text + "\r").toByteArray(Charsets.UTF_8)
        var at = 0
        while (at < bytes.size) { val n = NativePty.write(s.fd, bytes.copyOfRange(at, bytes.size)); if (n <= 0) break; at += n }
    }

    fun stop() { session?.takeIf { it.exit == null }?.let { runCatching { NativePty.signal(it.pid, 9) } } }

    fun logout(context: Context): JSONObject {
        val r = ClaudeRuntime(context).run(listOf("auth", "logout"), 30)
        ClaudeSetup.status(context, refreshAuth = true)
        return JSONObject().put("ok", r.code == 0)
    }
}

/** The code workspace inside the rootfs. Implemented in step 3; here only its state. */
object ClaudeWorkspace {
    fun state(runtime: ClaudeRuntime): JSONObject {
        val dir = File(runtime.hostDirectory(), ClaudeRuntime.WORKSPACE_DIR.removePrefix("/"))
        return JSONObject().put("path", ClaudeRuntime.WORKSPACE_DIR).put("exists", dir.isDirectory)
    }
}
