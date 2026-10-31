package io.deepseekharness.mobile.companion.claude

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class ClaudeInstallerTest {
    @get:Rule val tmp = TemporaryFolder()
    private val manifest = javaClass.getResource("/claude/manifest-2.1.308.json")!!.readBytes()
    private val signature = javaClass.getResource("/claude/manifest-2.1.308.json.sig")!!.readText()
    private val routes = mutableMapOf<String, ByteArray>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    /** Minimal HTTP/1.1 file server: GET path → 200 body, else 404; one request per connection. */
    @Before fun start() {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                    while (reader.readLine()?.isNotEmpty() == true) Unit
                    val body = routes[path]
                    val out = s.getOutputStream()
                    if (body == null) out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    else { out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); out.write(body) }
                    out.flush()
                }
            }
        }
    }
    @After fun stop() = server.close()
    private val base get() = "http://127.0.0.1:${server.localPort}"

    @Test fun `官方清单的签名能验过，改一个字节就验不过`() {
        assertTrue(ClaudeSignature.verify(manifest, signature))
        val tampered = manifest.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertFalse(ClaudeSignature.verify(tampered, signature))
        assertFalse(ClaudeSignature.verify(manifest, "not a signature"))
    }

    @Test fun `签名验过的清单给出 linux-arm64 的校验值`() {
        routes["/2.1.308/manifest.json"] = manifest; routes["/2.1.308/manifest.json.sig"] = signature.toByteArray()
        val release = ClaudeInstaller(tmp.root, base).release("2.1.308")
        assertEquals("fdd22e6cbb734d580f5664c48df4294eeec850218ff07f186f9af30426396ee1", release.sha256)
        assertEquals(292_085_944L, release.size)
    }

    @Test fun `清单被改过就不装`() {
        routes["/2.1.308/manifest.json"] = String(manifest).replace("fdd22e6c", "00000000").toByteArray()
        routes["/2.1.308/manifest.json.sig"] = signature.toByteArray()
        try { ClaudeInstaller(tmp.root, base).release("2.1.308"); fail() } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("签名")) }
    }

    @Test fun `下载的程序和清单的 sha256 不一致就删掉，不会变成当前版本`() {
        // A signed manifest can't be faked here, so the server sends a short wrong file: it must never become current.
        routes["/2.1.308/manifest.json"] = manifest; routes["/2.1.308/manifest.json.sig"] = signature.toByteArray()
        routes["/2.1.308/linux-arm64/claude"] = ByteArray(1 shl 16)
        val installer = ClaudeInstaller(tmp.root, base, retryDelayMs = 1)
        try { installer.install("2.1.308"); fail() } catch (e: IllegalStateException) { assertTrue(e.message, e.message!!.contains("下载")) }
        assertNull(installer.current())
        assertFalse(installer.binary("2.1.308").exists())
    }

    @Test fun `渠道只认 stable 和 latest，版本号格式要对`() {
        routes["/stable"] = "2.1.308\n".toByteArray(); routes["/latest"] = "<html>".toByteArray()
        assertEquals("2.1.308", ClaudeInstaller(tmp.root, base).channelVersion("stable"))
        try { ClaudeInstaller(tmp.root, base).channelVersion("latest"); fail() } catch (_: IllegalStateException) {}
        try { ClaudeInstaller(tmp.root, base).channelVersion("../x"); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun `登录状态只读 claude auth status 的 JSON`() {
        val s = ClaudeRuntime.parseAuthStatus("warn: x\n{\"loggedIn\":true,\"authMethod\":\"claude.ai\",\"apiProvider\":\"firstParty\"}\n")!!
        assertTrue(s.loggedIn); assertEquals("claude.ai", s.method)
        assertNull(ClaudeRuntime.parseAuthStatus("not logged in"))
        val pretty = ClaudeRuntime.parseAuthStatus("{\n  \"loggedIn\": true,\n  \"authMethod\": \"claude.ai\",\n  \"subscriptionType\": \"max\"\n}\n")!!
        assertTrue(pretty.loggedIn); assertEquals("max", pretty.note)
    }
}
