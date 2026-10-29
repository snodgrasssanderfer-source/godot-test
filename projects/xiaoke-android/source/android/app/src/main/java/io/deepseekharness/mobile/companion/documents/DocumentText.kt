package io.deepseekharness.mobile.companion.documents

import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Reuses the pinned Apache Commons Compress parser; never extracts untrusted archive paths. */
object DocumentText {
    const val MAX_TEXT = 100000
    const val MAX_BYTES = 4 * 1024 * 1024
    private val textExtensions = setOf("txt", "md", "csv", "json", "yaml", "yml", "xml", "log", "srt", "vtt")
    fun supportedText(name: String) = name.substringAfterLast('.', "").lowercase() in textExtensions
    fun bounded(input: InputStream, maximum: Int = MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            check(!Thread.currentThread().isInterrupted) { "文件读取已取消" }
            val count = input.read(buffer); if (count < 0) break
            require(out.size() + count <= maximum) { "资料超过本次读取上限" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
    fun decode(bytes: ByteArray): String {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = try { decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
        catch (_: Exception) { error("此文本不是有效 UTF-8，请先转换编码；没有将乱码交给模型") }
        require(!text.contains('\u0000')) { "检测到二进制内容，不作为文本读取" }
        return text
    }
    fun archive(input: InputStream): JSONObject {
        val listing = JSONArray(); val text = StringBuilder(); var expanded = 0
        ZipArchiveInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextZipEntry ?: break
                require(listing.length() < 100) { "压缩包条目超过 100；请拆分后读取" }
                val name = entry.name.replace('\\', '/')
                require(name.length <= 300 && !name.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(name) && name.split('/').none { it == ".." }) { "压缩包含不安全路径，已停止" }
                require(!entry.isUnixSymlink) { "不读取压缩包符号链接" }
                require(zip.canReadEntryData(entry)) { "此压缩包含加密或不支持的条目" }
                val metadata = JSONObject().put("name", name).put("directory", entry.isDirectory)
                listing.put(metadata)
                if (entry.isDirectory) continue
                val bytes = bounded(zip, (16 * 1024 * 1024 - expanded).coerceAtMost(MAX_BYTES))
                expanded += bytes.size
                metadata.put("bytes", bytes.size)
                if (supportedText(name)) {
                    val body = decode(bytes)
                    val remaining = (MAX_TEXT - text.length).coerceAtLeast(0)
                    if (remaining > 0) {
                        text.append("\n文件：").append(name).append('\n').append(body.take(remaining))
                        metadata.put("read", true).put("truncated", body.length > remaining)
                    } else metadata.put("read", false).put("reason", "已到本次文本上限")
                } else metadata.put("read", false).put("reason", "仅列目录，未理解此文件内容")
            }
        }
        return JSONObject().put("entries", listing).put("text", text.take(MAX_TEXT)).put("expandedBytes", expanded)
    }
}
