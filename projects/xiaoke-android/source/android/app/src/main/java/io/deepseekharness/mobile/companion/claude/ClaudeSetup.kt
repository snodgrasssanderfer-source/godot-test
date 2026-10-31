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
        if (version != null && (refreshAuth || System.currentTimeMillis() - authCheckedAt > 10 * 60_000L) && !installing.get())
            recordAuth(context, runtime.authStatus())
        return JSONObject().put("runtimeReady", runtime.runtimeReady()).put("version", version ?: JSONObject.NULL)
            .put("install", install).put("login", ClaudeLogin.state())
            .put("loggedIn", auth?.loggedIn == true).put("authMethod", auth?.method.orEmpty()).put("authNote", auth?.note.orEmpty())
            .put("settings", ClaudeChat.settings(engine.store)).put("usage", engine.store.lifeState("claudeUsage").apply { remove("id"); remove("scope") })
            .put("outage", ClaudeChat.outage(engine.store) ?: JSONObject.NULL)
            .put("permissions", ClaudePermissions.list()).put("workspace", ClaudeWorkspace.state(runtime))
            .put("githubConfigured", ClaudeGithub.configured(context))
    }

    /** A fresh `claude auth status`; logging in again ends a login outage at once. */
    fun recordAuth(context: Context, status: ClaudeRuntime.AuthStatus) {
        auth = status; authCheckedAt = System.currentTimeMillis()
        val store = CompanionEngine.get(context).store
        if (status.loggedIn && ClaudeChat.outage(store)?.optString("reason") == ClaudeStream.Failure.LOGIN.name) ClaudeChat.clearOutage(store)
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
    private class Session(val pid: Int, val fd: Int) {
        val output = StringBuilder()
        /** Set only after the login result has been checked, so the page never shows a stale "not logged in". */
        @Volatile var exit: Int? = null
        @Volatile var checking = false
    }
    @Volatile private var session: Session? = null
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>]|\r")

    fun state(): JSONObject {
        val s = session ?: return JSONObject().put("running", false)
        val text = synchronized(s.output) { s.output.toString() }
        val url = signInUrl(text)
        return JSONObject().put("running", s.exit == null).put("checking", s.checking).put("exit", s.exit ?: JSONObject.NULL).put("output", text.takeLast(4000)).put("url", url ?: JSONObject.NULL)
    }

    @Synchronized fun start(context: Context, method: String = "claudeai"): JSONObject {
        session?.takeIf { it.exit == null }?.let { return state() }
        require(method in setOf("claudeai", "console")) { "登录方式无效" }
        val spec = ClaudeRuntime(context).launch(listOf("auth", "login", "--$method"))
        // As wide as the native PTY allows; a longer link still wraps, and signInUrl joins it back.
        val handles = NativePty.spawn(spec.argv.toTypedArray(), spec.environment.map { (k, v) -> "$k=$v" }.toTypedArray(), COLUMNS, 40)
        val s = Session(handles[0].toInt(), handles[1].toInt())
        session = s
        Thread({
            val buffer = ByteArray(8192)
            var code = 255
            try {
                while (true) {
                    val n = NativePty.read(s.fd, buffer); if (n <= 0) break
                    val chunk = ansi.replace(String(buffer, 0, n, Charsets.UTF_8), "")
                    synchronized(s.output) { s.output.append(chunk); if (s.output.length > 16000) s.output.delete(0, s.output.length - 16000) }
                }
                code = NativePty.waitFor(s.pid)
            } catch (_: Throwable) {}
            finally {
                runCatching { NativePty.close(s.fd) }
                s.checking = true
                val result = runCatching { ClaudeRuntime(context).authStatus() }.getOrNull()
                if (result != null) ClaudeSetup.recordAuth(context, result)
                XLog.i("claude auth login exited $code, logged in: ${result?.loggedIn}")
                s.checking = false
                s.exit = code
            }
        }, "claude-login").apply { isDaemon = true }.start()
        return state()
    }

    const val COLUMNS = 300

    /**
     * The sign-in link Claude Code printed. A link longer than the terminal is wrapped (by the terminal or by Claude
     * Code's own layout), so lines right after it that are nothing but URL characters are joined back on.
     */
    fun signInUrl(text: String): String? {
        val lines = text.split('\n')
        val urlChars = Regex("[A-Za-z0-9%._~:/?#\\[\\]@!$&'()*+,;=-]+")
        var found: String? = null
        for ((i, line) in lines.withIndex()) {
            val start = line.indexOf("https://").takeIf { it >= 0 } ?: continue
            val piece = StringBuilder(line.substring(start).trim())
            var next = i + 1
            while (urlChars.matches(piece) && next < lines.size && urlChars.matches(lines[next].trim())) { piece.append(lines[next].trim()); next++ }
            val url = Regex("https://[^\\s\"'<>]+").find(piece)?.value ?: continue
            if ("oauth" in url || "claude.ai" in url || "anthropic.com" in url || "claude.com" in url) found = url
        }
        return found
    }

    fun input(text: String) {
        val s = session?.takeIf { it.exit == null } ?: error("登录没有在进行")
        require(text.length <= 4000)
        // The code and Enter go separately: arriving in one chunk, the terminal UI takes it all as a paste and never submits.
        write(s, text.toByteArray(Charsets.UTF_8))
        Thread.sleep(300)
        write(s, byteArrayOf('\r'.code.toByte()))
    }

    private fun write(s: Session, bytes: ByteArray) {
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

/**
 * The code workspace: this project's public repo cloned inside the rootfs, where Claude reads, fixes and commits on a
 * branch of its own. Pushing needs the owner's own git credentials (set up in the DSH terminal); the app stores none.
 * Signing and releases stay in the cloud: no keystore ever comes to the phone.
 */
object ClaudeWorkspace {
    const val REPO = "https://github.com/snodgrasssanderfer-source/godot-test.git"
    const val CLONE = "${ClaudeRuntime.WORKSPACE_DIR}/godot-test"
    const val PROJECT = "$CLONE/projects/xiaoke-android"
    private val running = AtomicBoolean(false)
    @Volatile private var job = JSONObject().put("phase", "idle")

    fun projectReady(runtime: ClaudeRuntime) = File(runtime.hostDirectory(), PROJECT.removePrefix("/")).isDirectory

    fun state(runtime: ClaudeRuntime): JSONObject {
        val cloned = File(runtime.hostDirectory(), "${CLONE.removePrefix("/")}/.git").isDirectory
        val out = JSONObject().put("path", PROJECT).put("exists", cloned).put("repo", REPO).put("job", job)
        if (cloned) runCatching {
            // Reads the branch straight from .git: no process start just to show a status line.
            val head = File(runtime.hostDirectory(), "${CLONE.removePrefix("/")}/.git/HEAD").readText().trim()
            out.put("branch", head.removePrefix("ref: refs/heads/"))
        }
        return out
    }

    /** Clone on first use, fetch afterwards. Commits she makes stay on her branches; nothing here touches them. */
    fun prepare(context: Context): JSONObject {
        check(running.compareAndSet(false, true)) { "工作区正在准备" }
        job = JSONObject().put("phase", "running")
        Thread({
            runCatching { io.deepseekharness.mobile.companion.CompanionService.beginOperation(context) }
            try {
                val runtime = ClaudeRuntime(context)
                check(runtime.runtimeReady()) { "先装好 Ubuntu 运行环境" }
                val script = listOf(
                    "set -e", "mkdir -p ${ClaudeRuntime.WORKSPACE_DIR}",
                    "git config --global user.name >/dev/null || git config --global user.name 'Xiaoke'",
                    "git config --global user.email >/dev/null || git config --global user.email 'xiaoke@localhost'",
                    "if [ -d $CLONE/.git ]; then git -C $CLONE fetch --depth 50 origin main && echo fetched",
                    "else git clone --depth 50 $REPO $CLONE && echo cloned; fi",
                ).joinToString("\n")
                // With the owner's token the fetch also works if the repo turns private (token on stdin only).
                val r = runtime.runShell(script, 900, githubToken = ClaudeGithub.token(context))
                check(r.code == 0 && !r.timedOut) { "没准备好：${r.output.trim().takeLast(300).ifBlank { "退出码 ${r.code}" }}" }
                job = JSONObject().put("phase", "done").put("detail", r.output.trim().lines().lastOrNull().orEmpty())
            } catch (e: Exception) {
                job = JSONObject().put("phase", "failed").put("error", (e.message ?: "失败").take(300))
                XLog.w("workspace prepare failed", e)
            } finally { running.set(false); io.deepseekharness.mobile.companion.CompanionService.endOperation() }
        }, "claude-workspace").apply { isDaemon = true }.start()
        return job
    }
}
