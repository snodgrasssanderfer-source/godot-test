package io.deepseekharness.mobile.companion.claude

import io.deepseekharness.mobile.companion.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * MCP over Streamable HTTP on 127.0.0.1 only. PRoot shares the host network, so Claude Code inside the rootfs reaches
 * it as http://127.0.0.1:<port>/mcp. Every turn registers its own bearer token, bound to one conversation, and revokes
 * it when the turn ends; a request without a live token gets 401.
 */
object XiaokeMcpServer {
    private val grants = ConcurrentHashMap<String, XiaokeMcp.Grant>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "xiaoke-mcp").apply { isDaemon = true } }
    private val random = SecureRandom()
    @Volatile private var server: ServerSocket? = null

    @Synchronized fun port(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = socket
        Thread({ acceptLoop(socket) }, "xiaoke-mcp-accept").apply { isDaemon = true }.start()
        return socket.localPort
    }

    fun register(grant: XiaokeMcp.Grant): String {
        val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        grants[token] = grant
        return token
    }
    fun revoke(token: String) { grants.remove(token) }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            pool.execute { client.use { runCatching { serve(it) }.onFailure { e -> XLog.w("mcp request failed", e) } } }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = line(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) { val h = line(input) ?: return; if (h.isEmpty()) break; h.indexOf(':').takeIf { it > 0 }?.let { headers[h.substring(0, it).trim().lowercase()] = h.substring(it + 1).trim() } }
        val (method, path) = requestLine.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "").substringBefore('?') }
        val out = client.getOutputStream()
        fun respond(status: String, body: String? = null) {
            val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            out.write(("HTTP/1.1 $status\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n" +
                (if (body != null) "Content-Type: application/json\r\n" else "") + "\r\n").toByteArray(Charsets.ISO_8859_1))
            out.write(bytes); out.flush()
        }
        // A browser page on the phone must not be able to drive this (DNS rebinding): only no Origin or a loopback one.
        headers["origin"]?.let { origin -> if (!Regex("^https?://(127\\.0\\.0\\.1|localhost)(:\\d+)?$").matches(origin)) return respond("403 Forbidden") }
        if (path != "/mcp") return respond("404 Not Found")
        val grant = headers["authorization"]?.removePrefix("Bearer ")?.trim()?.let(::grantFor) ?: return respond("401 Unauthorized")
        when (method) {
            "POST" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: return respond("411 Length Required")
                if (length !in 1..(4 shl 20)) return respond("413 Payload Too Large")
                val body = String(read(input, length), Charsets.UTF_8).trim()
                val responses = if (body.startsWith("[")) JSONArray(body).let { a -> (0 until a.length()).mapNotNull { XiaokeMcp.handle(a.getJSONObject(it), grant) } }
                    else listOfNotNull(XiaokeMcp.handle(JSONObject(body), grant))
                when {
                    responses.isEmpty() -> respond("202 Accepted")
                    body.startsWith("[") -> respond("200 OK", JSONArray(responses).toString())
                    else -> respond("200 OK", responses.single().toString())
                }
            }
            "DELETE" -> respond("200 OK")
            else -> respond("405 Method Not Allowed")
        }
    }

    private fun grantFor(token: String): XiaokeMcp.Grant? {
        val wanted = token.toByteArray()
        return grants.entries.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), wanted) }?.value
    }

    private fun line(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (b == '\n'.code) return buffer.toString("ISO-8859-1").trimEnd('\r')
            if (buffer.size() > 16 * 1024) return null
            buffer.write(b)
        }
    }

    private fun read(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length); var at = 0
        while (at < length) { val n = input.read(data, at, length - at); if (n < 0) break; at += n }
        return data.copyOf(at)
    }
}
