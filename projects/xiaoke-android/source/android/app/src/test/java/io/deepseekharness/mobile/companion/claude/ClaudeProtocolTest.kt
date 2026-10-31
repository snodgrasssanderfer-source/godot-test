package io.deepseekharness.mobile.companion.claude

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeProtocolTest {
    @Test fun `权限请求、额度、结果都能从输出里认出来`() {
        val ask = ClaudeStream.parse("""{"type":"control_request","request_id":"r1","request":{"subtype":"can_use_tool","tool_name":"Bash","input":{"command":"git status"}}}""")
        ask as ClaudeStream.Permission
        assertEquals("r1", ask.requestId); assertEquals("Bash", ask.tool)
        assertEquals("运行命令：git status", ClaudeStream.describe(ask.tool, ask.input))
        val allow = JSONObject(ClaudeStream.allow("r1", ask.input))
        assertEquals("allow", allow.getJSONObject("response").getJSONObject("response").getString("behavior"))
        assertEquals("r1", allow.getJSONObject("response").getString("request_id"))

        val limit = ClaudeStream.parse("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","rateLimitType":"five_hour","resetsAt":1900000000,"utilization":0.91,"unifiedWindows":{"seven_day":{"utilization":0.4,"resetsAt":1900500000}}}}""")
        limit as ClaudeStream.RateLimit
        val merged = ClaudeStream.mergeUsage(JSONObject(), limit.usage)
        assertEquals(0.91, merged.getJSONObject("fiveHour").getDouble("utilization"), 1e-9)
        assertEquals(0.4, merged.getJSONObject("sevenDay").getDouble("utilization"), 1e-9)

        val result = ClaudeStream.parse("""{"type":"result","subtype":"success","is_error":false,"result":"好呀","session_id":"s-1"}""") as ClaudeStream.Result
        assertFalse(result.isError); assertEquals("好呀", result.text)
        assertEquals(ClaudeStream.Failure.LOGIN, ClaudeStream.classify("Not logged in · Please run /login"))
        assertEquals(ClaudeStream.Failure.LIMIT, ClaudeStream.classify("Claude AI usage limit reached|1900000000"))
        assertEquals(ClaudeStream.Failure.LIMIT, ClaudeStream.classify("", JSONObject().put("status", "rejected")))
        assertEquals(ClaudeStream.Failure.ERROR, ClaudeStream.classify("socket hang up"))
    }

    @Test fun `群里只有网页搜索和小克的能力，主人私聊才有改文件和命令（仍要主人点允许）`() {
        fun opts(owner: Boolean) = ClaudeTurn.Options("s", "hi", "sess", "default", "high", owner, cwd = "/root/workspace", mcpPort = 1234, mcpToken = "t", timeoutMs = 1000)
        val group = ClaudeTurn.args(opts(false), "/x/system.md", "/x/mcp.json")
        assertEquals("WebSearch", group[group.indexOf("--tools") + 1])
        assertTrue("--permission-prompt-tool" in group && "--strict-mcp-config" in group)
        assertEquals("sess", group[group.indexOf("--resume") + 1])
        assertFalse(group.contains("--model")) // "default" leaves the choice to Claude Code
        val owner = ClaudeTurn.args(opts(true), "/x/system.md", "/x/mcp.json")
        assertFalse(owner.contains("--tools"))
        val allowed = owner[owner.indexOf("--allowedTools") + 1].split(",")
        assertFalse("Bash" in allowed || "Edit" in allowed || "Write" in allowed)
        // The token goes in the 0600 config file, never on the command line.
        assertFalse(owner.any { "Bearer" in it } || group.any { "Bearer" in it })
        assertTrue(ClaudeTurn.mcpConfig(1234, "tok").contains("Bearer tok"))
        // Full access lets the owner's chats edit and run commands without asking; a group gets nothing more.
        fun full(owner: Boolean) = ClaudeTurn.Options("s", "hi", null, "default", "high", owner, true, "/root/workspace", 1234, "t", 1000)
        val ownerFull = ClaudeTurn.args(full(true), "/x/system.md", "/x/mcp.json")
        assertTrue(ownerFull[ownerFull.indexOf("--allowedTools") + 1].split(",").containsAll(listOf("Bash", "Edit", "Write")))
        val groupFull = ClaudeTurn.args(full(false), "/x/system.md", "/x/mcp.json")
        assertEquals("WebSearch", groupFull[groupFull.indexOf("--tools") + 1])
        assertFalse(groupFull.any { "Bash" in it })
    }

    private val declarations = JSONArray().put(JSONObject().put("name", "recall").put("description", "搜记忆")
        .put("parameters", JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("query", JSONObject().put("type", "STRING"))).put("required", JSONArray().put("query"))))

    @Test fun `MCP 只列出并执行这个会话自己的工具`() {
        val calls = mutableListOf<String>()
        val grant = XiaokeMcp.Grant("qq:group:x", declarations) { name, args -> calls += name; JSONObject().put("ok", args.optString("query")) }
        val listed = XiaokeMcp.handle(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list"), grant)!!
        val schema = listed.getJSONObject("result").getJSONArray("tools").getJSONObject(0).getJSONObject("inputSchema")
        assertEquals("object", schema.getString("type")); assertEquals("string", schema.getJSONObject("properties").getJSONObject("query").getString("type"))
        val ok = XiaokeMcp.handle(JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call")
            .put("params", JSONObject().put("name", "recall").put("arguments", JSONObject().put("query", "猫"))), grant)!!
        assertTrue(ok.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text").contains("猫"))
        val denied = XiaokeMcp.handle(JSONObject().put("jsonrpc", "2.0").put("id", 3).put("method", "tools/call")
            .put("params", JSONObject().put("name", "device_action").put("arguments", JSONObject())), grant)!!
        assertTrue(denied.has("error")); assertEquals(listOf("recall"), calls)
        assertNull(XiaokeMcp.handle(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), grant))
    }

    @Test fun `本机 MCP 服务没有这一轮的令牌就拒绝`() {
        val token = XiaokeMcpServer.register(XiaokeMcp.Grant("owner", declarations) { _, _ -> JSONObject() })
        val port = XiaokeMcpServer.port()
        // Raw HTTP: HttpURLConnection silently drops a hand-set Origin header.
        fun post(auth: String?, origin: String? = null): Int = java.net.Socket("127.0.0.1", port).use { socket ->
            val body = """{"jsonrpc":"2.0","id":1,"method":"ping"}"""
            socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                (auth?.let { "Authorization: $it\r\n" } ?: "") + (origin?.let { "Origin: $it\r\n" } ?: "") + "\r\n" + body).toByteArray())
            socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
        }
        assertEquals(401, post(null))
        assertEquals(401, post("Bearer nope"))
        assertEquals(403, post("Bearer $token", "https://evil.example"))
        assertEquals(200, post("Bearer $token"))
        XiaokeMcpServer.revoke(token)
        assertEquals(401, post("Bearer $token"))
    }

    @Test fun `Claude 只收到上次回复之后的新消息，包括 Gemini 替它回的`() {
        val messages = listOf("m1" to "早", "m2" to "早呀（Gemini）", "m3" to "在吗").map { (id, text) -> JSONObject().put("id", id).put("text", text).put("role", "user").put("createdAt", 0L) }
        val since = ClaudeChat.transcript(messages, "m1", 10_000) { "X" }
        assertFalse("早\n" in since + "\n" && since.startsWith("[") && since.contains("：早\n"))
        assertTrue(since.contains("早呀（Gemini）") && since.contains("在吗"))
        assertEquals(3, ClaudeChat.transcript(messages, null, 10_000) { "X" }.lines().size)
        assertEquals(1, ClaudeChat.transcript(messages, null, 5) { "X" }.lines().size) // budget keeps the newest
    }

    @Test fun `登录链接被终端折行时能拼回完整的一条`() {
        val url = "https://claude.ai/oauth/authorize?code=true&client_id=abc&response_type=code&redirect_uri=https%3A%2F%2Fconsole.anthropic.com%2Foauth%2Fcode%2Fcallback&scope=user%3Ainference&code_challenge=" + "x".repeat(300) + "&state=yz"
        val wrapped = url.chunked(ClaudeLogin.COLUMNS).joinToString("\n")
        val printed = "Browser didn't open? Use the url below to sign in:\n\n$wrapped\n\nPaste code here if prompted >"
        assertEquals(url, ClaudeLogin.signInUrl(printed))
        assertEquals(url, ClaudeLogin.signInUrl("  $url  \n"))
        assertNull(ClaudeLogin.signInUrl("Opening browser…"))
    }
}
