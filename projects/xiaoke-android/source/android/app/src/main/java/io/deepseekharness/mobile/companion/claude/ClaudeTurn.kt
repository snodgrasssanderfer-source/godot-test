package io.deepseekharness.mobile.companion.claude

import io.deepseekharness.mobile.companion.XLog
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One reply from Claude Code: a fresh `claude -p` process in stream-json mode, resumed from the conversation's own
 * session. The message goes in on stdin; text, tool use, quota and permission questions come back on stdout. stdin is
 * closed after the result so the process ends by itself.
 */
class ClaudeTurn(private val runtime: ClaudeRuntime, val id: String) {
    class Options(
        val system: String,
        val content: Any,
        val resume: String?,
        val model: String,
        val effort: String,
        /** Owner chats get Claude Code's own tools (edits and commands still need approval); groups only web search. */
        val ownerTools: Boolean,
        /** The owner switched on full access: Claude Code's edits and commands need no approval (owner chats only). */
        val fullAccess: Boolean = false,
        val cwd: String,
        val mcpPort: Int,
        val mcpToken: String,
        val timeoutMs: Long,
        /** The owner's GitHub token for push and PRs; only ever used when [ownerTools] is on. */
        val githubToken: String? = null,
    ) {
        val github get() = ownerTools && !githubToken.isNullOrBlank()
    }

    data class Outcome(val sessionId: String, val text: String, val failure: ClaudeStream.Failure?, val detail: String, val usage: JSONObject?)

    private val cancelled = AtomicBoolean(false)
    @Volatile private var secret: String? = null
    /** Whatever leaves this class (partial text, the reply, error details) has the GitHub token taken out. */
    private fun redact(text: String) = ClaudeGithub.redact(text, secret)
    @Volatile private var process: Process? = null
    fun cancel() { cancelled.set(true); process?.destroy() }
    val isCancelled get() = cancelled.get()

    fun run(options: Options, onText: (String) -> Unit, onTool: (String) -> Unit, onUsage: (JSONObject) -> Unit,
            onPermission: (String, JSONObject) -> Boolean): Outcome {
        // Prompt and MCP config are files: the persona alone can exceed one argv string's 128 KiB limit, and the token
        // must not show up in the process list. Both are 0600 under the turn's own directory and deleted afterwards.
        secret = options.githubToken
        val guestDir = "/root/.xiaoke/turns/$id"
        val hostDir = File(runtime.hostDirectory(), guestDir.removePrefix("/")).apply { mkdirs() }
        hostDir.setReadable(false, false); hostDir.setReadable(true, true); hostDir.setExecutable(false, false); hostDir.setExecutable(true, true)
        fun privateFile(name: String, text: String) = File(hostDir, name).apply {
            writeText(text); setReadable(false, false); setReadable(true, true)
        }
        privateFile("system.md", options.system)
        privateFile("mcp.json", mcpConfig(options.mcpPort, options.mcpToken))
        var sessionId = options.resume.orEmpty()
        var usage: JSONObject? = null
        val text = StringBuilder()
        var result: ClaudeStream.Result? = null
        val stderr = StringBuilder()
        try {
            val spec = runtime.launch(args(options, "$guestDir/system.md", "$guestDir/mcp.json"), options.cwd, options.github) { cancelled.get() }
            val started = ProcessBuilder(spec.argv).directory(runtime.hostDirectory()).redirectErrorStream(false)
                .also { it.environment().clear(); it.environment().putAll(spec.environment) }.start()
            process = started
            if (cancelled.get()) started.destroy()
            val errThread = Thread({ runCatching { started.errorStream.bufferedReader().forEachLine { synchronized(stderr) { if (stderr.length < 8000) stderr.appendLine(it) } } } }, "claude-stderr").apply { isDaemon = true; start() }
            val stdin = started.outputStream.bufferedWriter(Charsets.UTF_8)
            fun send(line: String) = synchronized(stdin) { stdin.write(line); stdin.write("\n"); stdin.flush() }
            // The wrapper takes the first line as GH_TOKEN before Claude Code starts reading (see ClaudeGithub).
            if (options.github) send(options.githubToken!!)
            send(ClaudeStream.userMessage(options.content))
            val begun = System.currentTimeMillis()
            val initAt = java.util.concurrent.atomic.AtomicLong(0L)
            val stuck = java.util.concurrent.atomic.AtomicReference("")
            XLog.i("claude turn $id started")
            // Two limits: Claude Code must report in (init) within STARTUP_MS, and the whole turn must end within timeoutMs.
            val watchdog = Thread({
                try {
                    while (!started.waitFor(1, TimeUnit.SECONDS)) {
                        val elapsed = System.currentTimeMillis() - begun
                        if (initAt.get() == 0L && elapsed > STARTUP_MS) { stuck.set("Claude Code 两分钟都没启动起来"); break }
                        if (elapsed > options.timeoutMs) { stuck.set("这一轮超过了 ${options.timeoutMs / 60_000} 分钟"); break }
                    }
                    if (stuck.get().isNotEmpty()) { XLog.w("claude turn $id: ${stuck.get()}"); started.destroyForcibly() }
                } catch (_: InterruptedException) {}
            }, "claude-watchdog").apply { isDaemon = true; start() }
            // cancel() and the watchdog end the process from another thread, which closes stdout under this read and throws
            // "read interrupted by close()". That is the stop we asked for, not a failure: fall through to the outcome below.
            try { started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (cancelled.get()) break
                    when (val event = ClaudeStream.parse(line)) {
                        is ClaudeStream.Init -> { initAt.set(System.currentTimeMillis()); if (event.sessionId.isNotBlank()) sessionId = event.sessionId; XLog.i("claude turn $id: started in ${initAt.get() - begun} ms, model ${event.model}") }
                        is ClaudeStream.TextDelta -> { text.append(event.text); onText(redact(text.toString())) }
                        is ClaudeStream.ToolUse -> { text.setLength(0); onTool(event.name) }
                        is ClaudeStream.RateLimit -> { usage = event.usage; onUsage(event.usage) }
                        is ClaudeStream.Permission -> {
                            // Answered on its own thread: a question waiting for the owner must not stop the stream being read.
                            Thread({
                                val allowed = runCatching { onPermission(event.tool, event.input) }.getOrDefault(false)
                                runCatching { send(if (allowed) ClaudeStream.allow(event.requestId, event.input) else ClaudeStream.deny(event.requestId, "主人没有允许")) }
                            }, "claude-permission").apply { isDaemon = true; start() }
                        }
                        is ClaudeStream.OtherControl -> send(ClaudeStream.unsupported(event.requestId, event.subtype))
                        // The answer is complete here. Under PRoot a leftover background child can keep stdout open long after
                        // Claude Code itself is done, so stop reading now instead of waiting for end of stream.
                        is ClaudeStream.Result -> { result = event; if (event.sessionId.isNotBlank()) sessionId = event.sessionId; break }
                        ClaudeStream.Ignored -> Unit
                    }
                }
            } } catch (e: java.io.IOException) {
                if (!cancelled.get() && stuck.get().isEmpty()) throw e
            }
            runCatching { stdin.close() }
            if (!started.waitFor(5, TimeUnit.SECONDS)) started.destroyForcibly()
            watchdog.interrupt(); errThread.join(1_000)
            XLog.i("claude turn $id ended in ${System.currentTimeMillis() - begun} ms: ${result?.let { if (it.isError) "error ${it.subtype}" else "ok" } ?: "no result"}")
            val exit = runCatching { started.exitValue() }.getOrNull()
            val err = redact(synchronized(stderr) { stderr.toString() })
            val r = result
            return when {
                cancelled.get() -> Outcome(sessionId, "", ClaudeStream.Failure.ERROR, "已取消", usage)
                stuck.get().isNotEmpty() && result == null -> Outcome(sessionId, "", ClaudeStream.Failure.ERROR, stuck.get() + "：" + err.trim().takeLast(200), usage)
                r != null && !r.isError -> Outcome(sessionId, redact(r.text.ifBlank { text.toString() }), null, "", usage)
                r != null -> Outcome(sessionId, "", ClaudeStream.classify(r.text + "\n" + r.subtype, usage), redact(r.text.ifBlank { r.subtype }).take(300), usage)
                exit == ClaudeRuntime.SIGSYS_EXIT -> Outcome(sessionId, "", ClaudeStream.Failure.ERROR, "被系统拦截了一个调用（SIGSYS），PRoot 没接住", usage)
                else -> Outcome(sessionId, "", ClaudeStream.classify(err, usage), "Claude Code 退出了（$exit）：${err.trim().takeLast(300)}", usage)
            }
        } finally {
            process = null
            hostDir.deleteRecursively()
        }
    }

    companion object {
        const val STARTUP_MS = 120_000L

        fun mcpConfig(port: Int, token: String) = JSONObject().put("mcpServers", JSONObject().put("xiaoke", JSONObject()
            .put("type", "http").put("url", "http://127.0.0.1:$port/mcp").put("headers", JSONObject().put("Authorization", "Bearer $token")))).toString()

        val FULL_ACCESS = listOf("Bash", "Edit", "MultiEdit", "Write", "NotebookEdit")
        val OWNER_ALLOWED = listOf("Read", "Grep", "Glob", "WebSearch", "WebFetch", "TodoWrite", "Task", "mcp__xiaoke")

        fun args(o: Options, systemFile: String, mcpFile: String): List<String> = buildList {
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
            addAll(listOf("--permission-prompt-tool", "stdio", "--append-system-prompt-file", systemFile))
            addAll(listOf("--mcp-config", mcpFile, "--strict-mcp-config"))
            if (o.model.isNotBlank() && o.model != "default") addAll(listOf("--model", o.model))
            if (o.effort.isNotBlank()) addAll(listOf("--effort", o.effort))
            o.resume?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--resume", it)) }
            if (o.ownerTools) addAll(listOf("--allowedTools", (OWNER_ALLOWED + if (o.fullAccess) FULL_ACCESS else emptyList()).joinToString(",")))
            else addAll(listOf("--tools", "WebSearch", "--allowedTools", "WebSearch,mcp__xiaoke"))
        }
    }
}
