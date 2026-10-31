package io.deepseekharness.mobile.companion.claude

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Installs the official, unmodified Claude Code (linux-arm64, glibc) into DSH's Ubuntu rootfs, where PRoot runs it.
 *
 * Trust: the release manifest from downloads.claude.ai must carry Anthropic's PGP signature ([ClaudeSignature]);
 * the binary, from the same official host, must match the manifest's sha256 for linux-arm64. Nothing is patched.
 * Mechanism after greyoak111/cccodroid android/assets/scripts/install-claude.mjs (Apache-2.0); no code copied, and only
 * the official host is used (no npm mirror, no third-party manifest mirror).
 */
class ClaudeInstaller(
    private val rootfs: File,
    private val base: String = OFFICIAL,
    client: OkHttpClient? = null,
    private val retryDelayMs: Long = 2_000L,
) {
    data class Release(val version: String, val sha256: String, val size: Long)

    private val http = (client?.newBuilder() ?: OkHttpClient.Builder()).connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(true).followSslRedirects(false).build()

    /** /root/.xiaoke/claude inside the rootfs; each version in its own directory. */
    val home get() = File(rootfs, "root/.xiaoke/claude")
    fun binary(version: String) = File(home, "$version/claude")
    fun guestBinary(version: String) = "/root/.xiaoke/claude/$version/claude"

    /** The installed, verified version in use, or null. */
    fun current(): String? = File(home, "current").takeIf { it.isFile }?.readText()?.trim()
        ?.takeIf { isVersion(it) && File(home, "$it/.verified").isFile && binary(it).isFile }

    fun channelVersion(channel: String): String {
        require(channel in setOf("stable", "latest")) { "渠道只有 stable 和 latest" }
        return text("$base/$channel").trim().takeIf(::isVersion) ?: error("官方没有给出有效的版本号")
    }

    /** The signed manifest's entry for linux-arm64. An unsigned or badly signed manifest is refused. */
    fun release(version: String): Release {
        require(isVersion(version)) { "版本号不对" }
        val manifest = bytes("$base/$version/manifest.json", 1 shl 20)
        val signature = text("$base/$version/manifest.json.sig")
        check(ClaudeSignature.verify(manifest, signature)) { "官方清单的签名验不过（指纹 ${ClaudeSignature.FINGERPRINT}），不装" }
        val json = JSONObject(String(manifest, Charsets.UTF_8))
        check(json.optString("version") == version) { "清单里的版本和要装的不一致" }
        val entry = json.getJSONObject("platforms").getJSONObject(PLATFORM)
        val sum = entry.getString("checksum").lowercase()
        check(Regex("[a-f0-9]{64}").matches(sum)) { "清单里的校验值格式不对" }
        return Release(version, sum, entry.optLong("size"))
    }

    /**
     * Downloads (resuming a partial file), checks the sha256 against the signed manifest, marks it executable and makes
     * it current. [progress] gets (bytes so far, total).
     */
    fun install(channel: String = "stable", progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }): String {
        check(rootfs.isDirectory) { "先装好 Ubuntu 运行环境" }
        val version = if (isVersion(channel)) channel else channelVersion(channel)
        if (current() == version) return version
        val release = release(version)
        val dir = File(home, version).apply { mkdirs() }
        val target = binary(version)
        val part = File(dir, "claude.part")
        if (!(target.isFile && sha256(target) == release.sha256)) {
            target.delete()
            download("$base/$version/$PLATFORM/claude", part, release.size, progress, cancelled)
            val got = sha256(part)
            if (got != release.sha256) { part.delete(); error("下载的文件和官方清单不符（期望 ${release.sha256.take(12)}…，实得 ${got.take(12)}…），已删掉") }
            check(part.renameTo(target)) { "无法放到运行环境里" }
        }
        check(target.setExecutable(true, false) && target.setReadable(true, false)) { "无法设置可执行权限" }
        File(dir, ".verified").writeText(release.sha256 + "\n")
        File(home, "current").writeText(version + "\n")
        // Older versions are removed once the new one is in place; they are 280+ MB each.
        home.listFiles()?.filter { it.isDirectory && it.name != version && isVersion(it.name) }?.forEach { it.deleteRecursively() }
        return version
    }

    private fun download(url: String, part: File, size: Long, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        var attempts = 0
        while (true) {
            val have = if (part.isFile) part.length() else 0L
            if (size > 0 && have == size) return
            if (size > 0 && have > size) part.delete()
            try {
                val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
                http.newCall(request).execute().use { response ->
                    check(response.code == 200 || response.code == 206) { "下载失败（HTTP ${response.code}）" }
                    val resumed = response.code == 206
                    val body = response.body ?: error("下载内容为空")
                    RandomAccessFile(part, "rw").use { out ->
                        if (resumed) out.seek(have) else out.setLength(0)
                        var written = if (resumed) have else 0L
                        val buffer = ByteArray(1 shl 16)
                        body.byteStream().use { input ->
                            while (true) {
                                check(!cancelled()) { "已取消，下次接着下" }
                                val n = input.read(buffer); if (n < 0) break
                                out.write(buffer, 0, n); written += n
                                if (size > 0) check(written <= size) { "下载的文件比清单里写的大" }
                                progress(written, size)
                            }
                        }
                    }
                }
                if (size <= 0 || part.length() == size) return
                error("连接提前断开（${part.length()}/$size）")
            } catch (e: Exception) {
                if (cancelled() || e.message?.contains("已取消") == true) throw e
                if (++attempts >= 5) throw IllegalStateException("下载反复中断：${e.message}", e)
                Thread.sleep(retryDelayMs * attempts)
            }
        }
    }

    private fun text(url: String) = String(bytes(url, 64 * 1024), Charsets.UTF_8)

    private fun bytes(url: String, limit: Int): ByteArray = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
        check(response.isSuccessful) { "官方地址打不开（HTTP ${response.code}）：${url.substringAfter("claude-code-releases/")}" }
        val source = response.body?.source() ?: error("内容为空")
        check(!source.request(limit + 1L)) { "内容太大" }
        source.readByteArray()
    }

    companion object {
        const val OFFICIAL = "https://downloads.claude.ai/claude-code-releases"
        const val PLATFORM = "linux-arm64"
        fun isVersion(v: String) = Regex("\\d{1,6}\\.\\d{1,6}\\.\\d{1,6}(-[0-9A-Za-z.]{1,40})?").matches(v)
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(1 shl 20); while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
