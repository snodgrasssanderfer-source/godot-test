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
        val cwd: String,
        val mcpPort: Int,
        val mcpToken: String,
        val timeoutMs: Long,
    )

    data class Outcome(val sessionId: String, val text: String, val failure: ClaudeStream.Failure?, val detail: String, val usage: JSONObject?)

    private val cancelled = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    fun cancel() { cancelled.set(true); process?.destroy() }
    val isCancelled get() = cancelled.get()

    fun run(options: Options, onText: (String) -> Unit, onTool: (String) -> Unit, onUsage: (JSONObject) -> Unit,
            onPermission: (String, JSONObject) -> Boolean): Outcome {
        // Prompt and MCP config are files: the persona alone can exceed one argv string's 128 KiB limit, and the token
        // must not show up in the process list. Both are 0600 under the turn's own directory and deleted afterwards.
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
            val spec = runtime.launch(args(options, "$guestDir/system.md", "$guestDir/mcp.json"), options.cwd) { cancelled.get() }
            val started = ProcessBuilder(spec.argv).directory(runtime.hostDirectory()).redirectErrorStream(false)
                .also { it.environment().clear(); it.environment().putAll(spec.environment) }.start()
            process = started
            if (cancelled.get()) started.destroy()
            val errThread = Thread({ runCatching { started.errorStream.bufferedReader().forEachLine { synchronized(stderr) { if (stderr.length < 8000) stderr.appendLine(it) } } } }, "claude-stderr").apply { isDaemon = true; start() }
            val stdin = started.outputStream.bufferedWriter(Charsets.UTF_8)
            fun send(line: String) = synchronized(stdin) { stdin.write(line); stdin.write("\n"); stdin.flush() }
            send(ClaudeStream.userMessage(options.content))
            val watchdog = Thread({
                try { if (!started.waitFor(options.timeoutMs, TimeUnit.MILLISECONDS)) { XLog.w("claude turn timed out"); started.destroy() } } catch (_: InterruptedException) {}
            }, "claude-watchdog").apply { isDaemon = true; start() }
            started.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (cancelled.get()) break
                    when (val event = ClaudeStream.parse(line)) {
                        is ClaudeStream.Init -> if (event.sessionId.isNotBlank()) sessionId = event.sessionId
                        is ClaudeStream.TextDelta -> { text.append(event.text); onText(text.toString()) }
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
                        is ClaudeStream.Result -> { result = event; if (event.sessionId.isNotBlank()) sessionId = event.sessionId; runCatching { stdin.close() } }
                        ClaudeStream.Ignored -> Unit
                    }
                }
            }
            runCatching { stdin.close() }
            if (!started.waitFor(10, TimeUnit.SECONDS)) started.destroyForcibly()
            watchdog.interrupt(); errThread.join(1_000)
            val exit = runCatching { started.exitValue() }.getOrNull()
            val err = synchronized(stderr) { stderr.toString() }
            val r = result
            return when {
                cancelled.get() -> Outcome(sessionId, "", ClaudeStream.Failure.ERROR, "已取消", usage)
                r != null && !r.isError -> Outcome(sessionId, r.text.ifBlank { text.toString() }, null, "", usage)
                r != null -> Outcome(sessionId, "", ClaudeStream.classify(r.text + "\n" + r.subtype, usage), r.text.ifBlank { r.subtype }.take(300), usage)
                exit == ClaudeRuntime.SIGSYS_EXIT -> Outcome(sessionId, "", ClaudeStream.Failure.ERROR, "被系统拦截了一个调用（SIGSYS），PRoot 没接住", usage)
                else -> Outcome(sessionId, "", ClaudeStream.classify(err, usage), "Claude Code 退出了（$exit）：${err.trim().takeLast(300)}", usage)
            }
        } finally {
            process = null
            hostDir.deleteRecursively()
        }
    }

    companion object {
        fun mcpConfig(port: Int, token: String) = JSONObject().put("mcpServers", JSONObject().put("xiaoke", JSONObject()
            .put("type", "http").put("url", "http://127.0.0.1:$port/mcp").put("headers", JSONObject().put("Authorization", "Bearer $token")))).toString()

        val OWNER_ALLOWED = listOf("Read", "Grep", "Glob", "WebSearch", "WebFetch", "TodoWrite", "Task", "mcp__xiaoke")

        fun args(o: Options, systemFile: String, mcpFile: String): List<String> = buildList {
            addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose", "--include-partial-messages"))
            addAll(listOf("--permission-prompt-tool", "stdio", "--append-system-prompt-file", systemFile))
            addAll(listOf("--mcp-config", mcpFile, "--strict-mcp-config"))
            if (o.model.isNotBlank() && o.model != "default") addAll(listOf("--model", o.model))
            if (o.effort.isNotBlank()) addAll(listOf("--effort", o.effort))
            o.resume?.takeIf { it.isNotBlank() }?.let { addAll(listOf("--resume", it)) }
            if (o.ownerTools) addAll(listOf("--allowedTools", OWNER_ALLOWED.joinToString(",")))
            else addAll(listOf("--tools", "WebSearch", "--allowedTools", "WebSearch,mcp__xiaoke"))
        }
    }
}
