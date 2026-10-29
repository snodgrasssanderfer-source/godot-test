package io.deepseekharness.mobile.companion.social

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun interface SyncTransport { fun exchange(request: JSONObject): JSONObject }

class HttpsSyncTransport(endpoint: String, private val token: () -> String) : SyncTransport {
    private val url = validatedEndpoint(endpoint) + "/v1/sync"
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build()
    fun cancel() { client.dispatcher.cancelAll() }
    override fun exchange(request: JSONObject): JSONObject {
        val call = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")
            .post(request.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(call).execute().use { response ->
            check(response.isSuccessful) { "同步端拒绝请求 (${response.code})" }
            val body = response.body?.source() ?: error("同步响应为空")
            check(!body.request(8L * 1024 * 1024 + 1)) { "同步批次过大" }
            JSONObject(body.readUtf8())
        }
    }
    companion object {
        fun validatedEndpoint(value: String): String {
            val uri = URI(value)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "同步仅支持无凭据参数的 HTTPS 地址" }
            return value.trimEnd('/')
        }
    }
}

/** Pull deletion barrier before upload. Only durable exact acknowledgements clear an outbox item. */
class SocialSync(private val runtime: SocialRuntime, private val cipher: SyncCipher, private val transport: SyncTransport, private val enabled: () -> Boolean = { true }) {
    private val running = AtomicBoolean(false)
    fun run(): JSONObject {
        check(running.compareAndSet(false, true)) { "同步已经在执行" }
        try {
            check(enabled()) { "同步未启用" }
            // An empty first exchange prevents stale offline content being reintroduced before deletion.
            val pull = request(false)
            apply(transport.exchange(pull), pull)
            check(enabled()) { "同步已停用" }
            val sent = request(true)
            apply(transport.exchange(sent), sent)
            return runtime.ledger.change { root ->
                val sync = rows(root, "sync"); sync.put("lastAt", System.currentTimeMillis()).put("status", "已完成本批同步"); sync.remove("error")
                copy(sync)
            }
        } catch (_: Exception) {
            runtime.ledger.change { root -> rows(root, "sync").put("status", "待同步").put("error", "同步未完成，已保留待传事件；主人手机陪聊不受此队列影响") }
            throw IllegalStateException("同步未完成，待传事件已保留")
        } finally { running.set(false) }
    }
    private fun request(upload: Boolean): JSONObject = runtime.ledger.change { root ->
        val config = rows(root, "sync")
        val device = config.optString("deviceId").ifBlank { id().also { config.put("deviceId", it) } }
        val envelopes = JSONArray()
        if (upload) values(rows(root, "events")).filter { it.optBoolean("pendingSync") && !SocialRules.blocked(root, it) }.take(16).forEach { event ->
            val eventId = event.getString("id")
            var sealed = rows(root, "sealed").optJSONObject(eventId)
            if (sealed == null) {
                val payload = copy(event); payload.remove("pendingSync")
                sealed = cipher.seal(payload); rows(root, "sealed").put(eventId, sealed)
            }
            envelopes.put(sealed)
        }
        JSONObject().put("version", 1).put("device", device).put("vault", config.getString("vault"))
            .put("cursor", config.optLong("cursor")).put("generation", config.optLong("generation"))
            .put("events", envelopes).put("deletions", JSONArray(values(rows(root, "tombstones")).filter { !it.optBoolean("acked") }.take(1000).map { it.getString("id") }))
    }
    private fun apply(response: JSONObject, sent: JSONObject? = null) {
        require(response.getInt("version") == 1 && response.getLong("generation") >= 0 && response.getLong("cursor") >= 0)
        val removed = strings(response.getJSONArray("deletions")); require(removed.size <= 10000)
        runtime.deleteHashes(removed, true)
        // Authentication failures leave the cursor and outbox untouched; deletion fences remain installed.
        val incoming = objects(response.getJSONArray("events")).map(cipher::open)
        val acks = strings(response.getJSONArray("ackIds"))
        val sentIds = objects(sent?.optJSONArray("events") ?: JSONArray()).map { it.getString("id") }.toSet()
        require(acks.all { it in sentIds }) { "同步确认不属于本次提交" }
        val acknowledgedDeletes = strings(response.getJSONArray("ackDeletions"))
        val sentDeletes = strings(sent?.optJSONArray("deletions")).toSet()
        require(acknowledgedDeletes.all { it in sentDeletes }) { "删除确认不属于本次提交" }
        runtime.ledger.change { root ->
            val sync = rows(root, "sync")
            require(response.getLong("generation") >= sync.optLong("generation") && response.getLong("cursor") >= sync.optLong("cursor")) { "同步游标回退" }
            incoming.forEach { event ->
                if (!SocialRules.blocked(root, event) && !rows(root, "events").has(event.getString("id"))) {
                    event.put("pendingSync", false); rows(root, "events").put(event.getString("id"), event)
                }
            }
            values(rows(root, "events")).filter { hash(it.getString("id")) in acks }.forEach { it.put("pendingSync", false) }
            acknowledgedDeletes.forEach { key -> rows(root, "tombstones").optJSONObject(key)?.put("acked", true) }
            sync.put("cursor", response.getLong("cursor")).put("generation", response.getLong("generation"))
        }
    }
}
