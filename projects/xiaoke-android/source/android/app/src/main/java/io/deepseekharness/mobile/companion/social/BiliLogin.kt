// QR login and cookie renewal after iseenope/bilibili-mcp-server 20eedd09862aea2c160163007f45221c544651b2
// src/api/login.ts and src/api/cookie.ts (MIT): qrcode/generate → qrcode/poll; cookie/info → RSA-OAEP(SHA-256)
// correspond path → refresh_csrf → cookie/refresh → confirm/refresh with the old refresh_token.
package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.BilibiliReader
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

internal object BiliLogin {
    private const val PASSPORT = "https://passport.bilibili.com"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDLgd2OAkcGVtoE3ThUREbio0EgUc/prcajMKXvkCKFCWhJYJcLkcM2DKKcSeFpD/j6Boy538YXnR6VhcuUJOhH2x71nzPjfdTcqMz7djHum0qSZA0AyCBDABUqCrfNgCiJ00Ra7GmRj+YCK1NJEuewlb40JNrRuoEUXpabUzGB8QIDAQAB"
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private val keep = setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "buvid3", "buvid4", "sid")

    data class Session(val cookie: String, val refreshToken: String)

    private fun request(url: String, cookie: String? = null) = Request.Builder().url(url)
        .header("User-Agent", BilibiliReader.USER_AGENT).header("Referer", "https://www.bilibili.com").also { b -> cookie?.let { b.header("Cookie", it) } }

    private fun json(response: Response): JSONObject = JSONObject(response.body?.string().orEmpty().take(1024 * 1024))

    /** Returns the URL to encode as a QR code and the key to poll with. */
    fun start(): Pair<String, String> = client.newCall(request("$PASSPORT/x/passport-login/web/qrcode/generate").build()).execute().use { r ->
        val data = json(r).optJSONObject("data") ?: error("没拿到登录二维码")
        data.getString("url") to data.getString("qrcode_key")
    }

    /** One poll: "waiting", "scanned", "expired", or a finished session. */
    fun poll(key: String): Pair<String, Session?> {
        require(Regex("[0-9a-fA-F]{16,64}").matches(key))
        return client.newCall(request("$PASSPORT/x/passport-login/web/qrcode/poll?qrcode_key=$key").build()).execute().use { r ->
            val data = json(r).optJSONObject("data") ?: return@use "waiting" to null
            when (data.optInt("code", -1)) {
                0 -> {
                    // Cookies arrive as Set-Cookie headers; the redirect URL carries them too. Take whichever is present.
                    val fields = linkedMapOf<String, String>()
                    r.headers("Set-Cookie").forEach { header -> header.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 }?.let { fields[it[0].trim()] = it[1].trim() } }
                    runCatching { URI(data.optString("url")).rawQuery.orEmpty().split('&').forEach { part ->
                        part.split('=', limit = 2).takeIf { it.size == 2 && it[0] !in fields }?.let { fields[it[0]] = java.net.URLDecoder.decode(it[1], "UTF-8") }
                    } }
                    data.optJSONObject("cookie_info")?.optJSONArray("cookies")?.let { a ->
                        for (i in 0 until a.length()) a.getJSONObject(i).let { c -> fields.putIfAbsent(c.getString("name"), c.getString("value")) }
                    }
                    val cookie = fields.filterKeys { it in keep }.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    check("SESSDATA=" in cookie && "bili_jct=" in cookie) { "扫码成功但没拿到登录信息，请重试" }
                    "confirmed" to Session(cookie, data.optString("refresh_token"))
                }
                86090 -> "scanned" to null
                86038 -> "expired" to null
                else -> "waiting" to null
            }
        }
    }

    fun correspondPath(timestampMs: Long): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        // Node's publicEncrypt with oaepHash sha256 uses SHA-256 for both OAEP and MGF1; Java's default MGF1 would be SHA-1.
        val cipher = Cipher.getInstance("RSA/ECB/OAEPPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        return cipher.doFinal("refresh_$timestampMs".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun field(cookie: String, name: String) = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("$name=") }?.substringAfter('=').orEmpty()

    /** Renews the cookie when B站 says it should be; returns the new session, or null when nothing needed doing. */
    fun refreshIfNeeded(current: Session): Session? {
        val csrf = field(current.cookie, "bili_jct")
        val info = client.newCall(request("$PASSPORT/x/passport-login/web/cookie/info?csrf=$csrf", current.cookie).build()).execute().use(::json)
        check(info.optInt("code", -1) == 0) { "登录已失效，请重新扫码" }
        val data = info.getJSONObject("data")
        if (!data.optBoolean("refresh")) return null
        check(current.refreshToken.isNotBlank()) { "需要重新扫码登录才能自动续期" }
        val html = client.newCall(request("https://www.bilibili.com/correspond/1/${correspondPath(data.getLong("timestamp"))}", current.cookie).build()).execute().use { it.body?.string().orEmpty() }
        val refreshCsrf = Regex("<div\\s+id=\"1-name\">([^<]+)</div>").find(html)?.groupValues?.get(1)?.trim() ?: error("续期页面没有给出校验码")
        val body = FormBody.Builder().add("csrf", csrf).add("refresh_csrf", refreshCsrf).add("source", "main_web")
            .add("refresh_token", current.refreshToken).build()
        val (fresh, token) = client.newCall(request("$PASSPORT/x/passport-login/web/cookie/refresh", current.cookie).header("Origin", "https://www.bilibili.com").post(body).build()).execute().use { r ->
            val result = json(r)
            check(result.optInt("code", -1) == 0) { "续期失败：${result.optString("message").take(80)}" }
            val updated = current.cookie.split(';').map { it.trim() }.filter { it.isNotEmpty() }.associate { it.substringBefore('=') to it.substringAfter('=') }.toMutableMap()
            r.headers("Set-Cookie").forEach { header -> header.substringBefore(';').split('=', limit = 2).takeIf { it.size == 2 && it[0].trim() in keep }?.let { updated[it[0].trim()] = it[1].trim() } }
            updated.entries.joinToString("; ") { "${it.key}=${it.value}" } to result.optJSONObject("data")?.optString("refresh_token").orEmpty()
        }
        // Confirm with the new csrf and the OLD refresh token; this retires the old session.
        runCatching {
            client.newCall(request("$PASSPORT/x/passport-login/web/confirm/refresh", fresh).header("Origin", "https://www.bilibili.com")
                .post(FormBody.Builder().add("csrf", field(fresh, "bili_jct")).add("refresh_token", current.refreshToken).build()).build()).execute().close()
        }
        return Session(fresh, token.ifBlank { current.refreshToken })
    }
}
