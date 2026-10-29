package io.deepseekharness.mobile.companion.social

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/** Downloads only the public CDN URL resolved by BilibiliReader after approval; never sends cookies. */
internal class BilibiliDownload(private val client: OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()) {
    fun cancel() = client.dispatcher.cancelAll()
    fun read(url: String, maxBytes: Int, canContinue: () -> Boolean): ByteArray {
        val uri = URI(url); val host = uri.host.orEmpty().lowercase()
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && uri.port in setOf(-1, 443) &&
            listOf("bilivideo.com", "bilivideo.cn", "hdslb.com").any { host == it || host.endsWith(".$it") }) { "下载域名不受支持" }
        require(maxBytes in 1024..16 * 1024 * 1024); check(canContinue()) { "下载已取消或来源已删除" }
        val request = Request.Builder().url(url).header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", "DSH-Companion/0.1 (Android; personal assistant)").get().build()
        return client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "公开视频下载未完成 (${response.code})" }
            val body = response.body ?: error("下载响应为空")
            require(body.contentLength() <= maxBytes) { "视频超过批准的下载上限" }
            val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
            body.byteStream().use { stream -> while (true) {
                check(canContinue()) { "下载已取消或来源已删除" }
                val count = stream.read(buffer); if (count < 0) break
                require(output.size() + count <= maxBytes) { "视频超过批准的下载上限" }; output.write(buffer, 0, count)
            } }
            val bytes = output.toByteArray()
            require(MediaFormats.mime(bytes, "video") == "video/mp4") { "平台未提供有效 MP4 容器" }; bytes
        }
    }
}
