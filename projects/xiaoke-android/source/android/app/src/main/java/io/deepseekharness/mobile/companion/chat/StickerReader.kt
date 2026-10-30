package io.deepseekharness.mobile.companion.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import io.deepseekharness.mobile.companion.CompanionEngine
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.OneBotProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Stickers become a few words ("[表情包：猫猫震惊]") instead of an image in every chat request. Each distinct sticker is
 * looked at once (activity budget) and the words are remembered by the hash of its bytes, so a group's favourite
 * stickers cost nothing after the first time. When she cannot look, QQ's own summary text is kept.
 */
object StickerReader {
    private const val KEY = "stickerNotes"
    private const val KEEP = 600
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()

    /** Replaces each sticker placeholder in [text]. [mayLook] is the conversation's image switch. */
    fun describe(engine: CompanionEngine, scope: String, text: String, attachments: JSONArray, mayLook: Boolean): String {
        var out = text
        for (i in 0 until attachments.length()) {
            val sticker = attachments.optJSONObject(i)?.takeIf { it.optString("kind") == "sticker" } ?: continue
            val placeholder = sticker.optString("placeholder").takeIf { it.isNotBlank() } ?: continue
            val words = if (mayLook) runCatching { look(engine, scope, sticker.optString("url")) }.getOrNull() else null
            val summary = sticker.optString("summary").takeIf { it.isNotBlank() && it != "动画表情" }
            sticker.put("status", if (words != null) "described" else "summary_only")
            out = out.replace(placeholder, " [表情包${(words ?: summary)?.let { "：$it" }.orEmpty()}]")
        }
        return out
    }

    private fun look(engine: CompanionEngine, scope: String, url: String): String? {
        val safe = stickerUrl(url) ?: return null
        val bytes = client.newCall(Request.Builder().url(safe).build()).execute().use { response ->
            check(response.isSuccessful)
            val source = response.body?.source() ?: error("empty")
            check(!source.request(4L * 1024 * 1024 + 1)); source.readByteArray()
        }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        cached(engine.store, hash)?.let { return it }
        if (engine.modules.activityStopped()) return null
        val jpeg = jpeg(bytes) ?: return null
        val done = CountDownLatch(1)
        var answer: String? = null
        engine.enqueueModuleRequest(JSONObject().put("scope", scope).put("lane", "activity").put("purpose", "sticker").put("maxOutputTokens", 200)
            .put("parts", JSONArray().put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))))
            .put("prompt", "这是聊天里有人发的一张表情包。用不超过 20 个字说出它画的是什么、想表达的情绪或梗，只输出这一句。")) { event ->
            when (event.optString("type")) {
                "completed" -> { answer = event.optString("text").lines().firstOrNull { it.isNotBlank() }?.trim()?.take(40); done.countDown() }
                "failed", "cancelled" -> done.countDown()
            }
        }
        // The message waits a little for her to look; if the queue is busy it goes on with QQ's summary.
        if (!done.await(15, TimeUnit.SECONDS)) return null
        return answer?.takeIf { it.isNotBlank() }?.also { remember(engine.store, hash, it) }
    }

    fun cached(store: CompanionStore, hash: String): String? = store.lifeState(KEY).optJSONObject(hash)?.optString("text")?.takeIf { it.isNotBlank() }

    @Synchronized fun remember(store: CompanionStore, hash: String, text: String, now: Long = System.currentTimeMillis()) {
        val all = store.lifeState(KEY).put(hash, JSONObject().put("text", text).put("at", now))
        if (all.length() > KEEP) {
            val oldest = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("at") ?: 0L }.take(all.length() - KEEP)
            oldest.forEach { all.remove(it) }
        }
        store.saveLifeState(KEY, all)
    }

    /** QQ image CDN, plus the market-sticker host. */
    fun stickerUrl(raw: String): String? = OneBotProtocol.imageUrl(raw) ?: runCatching {
        val u = java.net.URI(raw)
        if (u.host?.lowercase() == "gxh.vip.qq.com" && u.scheme in setOf("http", "https") && u.userInfo == null && u.port in setOf(-1, 443) && u.fragment == null)
            "https:" + raw.substringAfter(':') else null
    }.getOrNull()

    /** First frame, at most 512 px, as JPEG: a GIF or WebP sticker reads fine from one frame. */
    private fun jpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..8000 || bounds.outHeight !in 1..8000) return null
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
        }) ?: return null
        val factor = 512.0 / maxOf(decoded.width, decoded.height).coerceAtLeast(512)
        val small = Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt().coerceAtLeast(1), (decoded.height * factor).toInt().coerceAtLeast(1), true)
        return try { ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
        finally { if (small !== decoded) small.recycle(); decoded.recycle() }
    }
}
