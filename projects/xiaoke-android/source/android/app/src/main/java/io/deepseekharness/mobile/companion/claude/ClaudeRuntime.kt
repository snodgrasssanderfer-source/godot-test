package io.deepseekharness.mobile.companion.claude

import android.content.Context
import io.deepseekharness.mobile.runtime.ProcessProbe
import io.deepseekharness.mobile.runtime.RuntimeLaunchResolver
import io.deepseekharness.mobile.runtime.RuntimeLaunchSpec
import io.deepseekharness.mobile.runtime.RuntimeStore
import org.json.JSONObject
import java.io.File

/**
 * Runs the installed Claude Code inside DSH's Ubuntu (PRoot). The launch goes through DSH's own resolver without
 * credentials: `env -i` gives a clean environment, so no API key or token from the app ever reaches Claude Code, and
 * Claude Code only ever uses the subscription login the owner made with `claude auth login` (kept in the rootfs at
 * /root/.claude, which the app never reads).
 */
class ClaudeRuntime(context: Context) {
    private val app = context.applicationContext
    val store = RuntimeStore(app)
    val installer get() = ClaudeInstaller(store.currentRoot)
    private val resolver by lazy { RuntimeLaunchResolver(app, store, includeCredentials = false) }

    fun runtimeReady() = store.currentRoot.isDirectory && File(store.currentRoot, "bin/bash").exists()
    fun version(): String? = if (runtimeReady()) installer.current() else null

    /** A PRoot launch of `claude <args>` in [cwd] (guest path). */
    fun launch(args: List<String>, cwd: String = HOME_DIR, cancelled: () -> Boolean = { false }): RuntimeLaunchSpec {
        val version = version() ?: error("Claude Code 还没装好")
        return resolver.launch(command(installer.guestBinary(version), args, cwd), externalCancellation = cancelled)
    }

    /** A PRoot launch of a plain shell command (git clone and the like) in [cwd]. */
    fun shell(script: String, cwd: String = HOME_DIR): RuntimeLaunchSpec =
        resolver.launch(listOf("/bin/bash", "--noprofile", "--norc", "-c", "cd \"\$1\" || exit 3; shift; $script", "xiaoke", cwd))

    fun hostDirectory() = store.currentRoot

    data class Result(val code: Int?, val output: String, val timedOut: Boolean)

    fun run(args: List<String>, timeoutSeconds: Long = 30, cwd: String = HOME_DIR): Result {
        val r = ProcessProbe.run(launch(args, cwd), store.currentRoot, timeoutSeconds, outputLimit = 64 * 1024)
        r.startError?.let { throw IllegalStateException("启动不了：${it.message}", it) }
        return Result(r.exitCode, r.output, r.timedOut)
    }

    fun runShell(script: String, timeoutSeconds: Long, cwd: String = HOME_DIR): Result {
        val r = ProcessProbe.run(shell(script, cwd), store.currentRoot, timeoutSeconds, outputLimit = 64 * 1024)
        r.startError?.let { throw IllegalStateException("启动不了：${it.message}", it) }
        return Result(r.exitCode, r.output, r.timedOut)
    }

    /** `claude auth status --json`: whether she is logged in and with what. Never touches the credential files. */
    fun authStatus(): AuthStatus = runCatching {
        val r = run(listOf("auth", "status", "--json"), 30)
        parseAuthStatus(r.output) ?: AuthStatus(false, "", r.output.takeLast(200).trim())
    }.getOrElse { AuthStatus(false, "", it.message ?: "查不到登录状态") }

    data class AuthStatus(val loggedIn: Boolean, val method: String, val note: String = "")

    companion object {
        const val HOME_DIR = "/root"
        const val WORKSPACE_DIR = "/root/workspace"
        /** Exit 128+31: the process was killed by SIGSYS, i.e. Android's seccomp rejected a syscall PRoot did not catch. */
        const val SIGSYS_EXIT = 159

        /**
         * The guest command. Auto-update is off because updates must come through [ClaudeInstaller]'s signature and
         * checksum checks; the binary itself is run as published.
         */
        fun command(binary: String, args: List<String>, cwd: String): List<String> = buildList {
            add("/bin/bash"); add("--noprofile"); add("--norc"); add("-c")
            add("cd \"\$1\" 2>/dev/null || cd /root; shift; exec \"\$@\"")
            add("xiaoke"); add(cwd)
            add("/usr/bin/env"); addAll(ENVIRONMENT); add(binary); addAll(args)
        }

        val ENVIRONMENT = listOf(
            "DISABLE_AUTOUPDATER=1",
            "CLAUDE_CODE_DISABLE_TERMINAL_TITLE=1",
            "NO_COLOR=1",
        )

        fun parseAuthStatus(output: String): AuthStatus? {
            val line = output.lineSequence().map(String::trim).lastOrNull { it.startsWith("{") && it.endsWith("}") } ?: return null
            val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
            return AuthStatus(json.optBoolean("loggedIn"), json.optString("authMethod"), json.optString("subscriptionType"))
        }
    }
}
