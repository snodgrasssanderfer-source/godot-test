package io.deepseekharness.mobile.companion

import android.media.MediaDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Bounded HTTPS range reader for Android's decoder; never stores the full video or cookies. */
class PublicVideoDataSource(url: String, private val allowed: () -> Boolean) : MediaDataSource() {
    private val url = VideoEvidence.publicVideoUrl(url) ?: error("公开视频地址不在支持的内容域名范围")
    private val client = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75)
    private var totalBytes = 0L
    private var requests = 0
    private var closed = false
    private var length = -1L
    private var cacheAt = -1L
    private var cache = ByteArray(0)

    @Synchronized private fun fetch(position: Long, requested: Int): ByteArray {
        check(!closed && allowed() && System.nanoTime() < deadline) { "视频观察已停止或超时" }
        check(requests++ < 160 && totalBytes < 48L * 1024 * 1024) { "片段读取达到本次流量上限，请缩短时段或导入本地视频" }
        val size = minOf(requested.coerceIn(1, 256 * 1024), (48L * 1024 * 1024 - totalBytes).toInt())
        val end = if (length >= 0) minOf(position + size - 1, length - 1) else position + size - 1
        val request = Request.Builder().url(url).header("Range", "bytes=$position-$end")
            .header("Accept-Encoding", "identity").header("Referer", "https://www.bilibili.com/")
            .header("User-Agent", "Xiaoke-Companion/0.2 Android").build()
        client.newCall(request).execute().use { response ->
            check(response.code == 206) { "内容服务不支持受限分段读取，或需要额外访问权限" }
            val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty()) ?: error("视频范围响应不完整")
            check(range.groupValues[1].toLong() == position && range.groupValues[2].toLong() <= end)
            length = range.groupValues[3].toLong(); check(length > 0)
            val source = response.body?.source() ?: error("视频数据为空")
            check(!source.request(size.toLong() + 1)) { "视频分段超过请求上限" }
            val bytes = source.readByteArray()
            check(bytes.size.toLong() == range.groupValues[2].toLong() - position + 1) { "视频分段被截断" }
            totalBytes += bytes.size
            return bytes
        }
    }
    @Synchronized override fun getSize(): Long { if (length < 0) fetch(0, 1); return length }
    @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (position < 0 || offset < 0 || size < 0 || offset > buffer.size - size) throw IOException("Invalid media range")
        if (position >= getSize()) return -1
        if (closed || !allowed() || System.nanoTime() >= deadline) throw IOException("Observation stopped")
        if (position !in cacheAt until cacheAt + cache.size) { cacheAt = position; cache = fetch(position, 256 * 1024) }
        val from = (position - cacheAt).toInt()
        val count = minOf(size, cache.size - from)
        cache.copyInto(buffer, offset, from, from + count)
        return count
    }
    @Synchronized override fun close() { closed = true; cache = ByteArray(0); client.dispatcher.cancelAll(); client.connectionPool.evictAll() }
    fun bytesRead() = totalBytes
}
