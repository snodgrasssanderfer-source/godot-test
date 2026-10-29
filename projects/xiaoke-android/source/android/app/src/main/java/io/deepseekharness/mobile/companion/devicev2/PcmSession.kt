package io.deepseekharness.mobile.companion.devicev2

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/** Bounded, separated tracks. Nothing is written to disk; a new session erases the old clip. */
class PcmSession(private val now: () -> Long) {
    data class Track(val samples: Int, val nonZero: Boolean, val firstAtMs: Long, val lastAtMs: Long, val sequence: Long)
    private data class Buffer(val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), var nonZero: Boolean = false,
        var first: Long = 0, var last: Long = 0, var sequence: Long = 0)
    private val tracks = linkedMapOf<String, Buffer>()
    var id: String = ""; private set
    private var recording = false
    private var limit = 0
    private var deadline = 0L
    private var expiresAt = 0L
    @Synchronized fun start(sources: Set<String>, seconds: Int): String {
        require(sources.isNotEmpty() && sources.all { it in setOf("mic", "playback") }); require(seconds in 1..30)
        clear(); id = UUID.randomUUID().toString(); recording = true; limit = seconds * 16_000 * 2
        deadline = now() + seconds * 1000L; expiresAt = deadline + 5 * 60_000
        sources.forEach { tracks[it] = Buffer() }
        return id
    }
    @Synchronized fun append(session: String, source: String, pcm: ByteArray, count: Int): Boolean {
        if (!recording || session != id || now() >= deadline) return false
        val b = tracks[source] ?: return false
        require(count in 0..pcm.size && count % 2 == 0)
        val length = minOf(count, limit - b.bytes.size())
        if (length <= 0) return false
        if (b.bytes.size() == 0) b.first = now()
        b.bytes.write(pcm, 0, length); b.last = now(); b.sequence++
        for (i in 0 until length) if (pcm[i].toInt() != 0) { b.nonZero = true; break }
        return length == count
    }
    @Synchronized fun stop(session: String) { check(session == id) { "录音会话已经更换" }; recording = false }
    @Synchronized fun metadata(): Map<String, Track> {
        expire()
        return tracks.mapValues { (_, b) -> Track(b.bytes.size() / 2, b.nonZero, b.first, b.last, b.sequence) }
    }
    @Synchronized fun wav(session: String, source: String): ByteArray {
        expire(); check(session == id) { "录音已过期或会话已经更换" }; check(!recording) { "请先结束录音" }
        val pcm = tracks[source]?.bytes?.toByteArray() ?: error("此音轨没有采集")
        check(pcm.isNotEmpty()) { "没有收到音频样本" }
        return ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size); put(pcm)
        }.array()
    }
    @Synchronized fun clear() { tracks.values.forEach { it.bytes.reset() }; tracks.clear(); id = ""; recording = false; expiresAt = 0 }
    private fun expire() { if (expiresAt != 0L && now() >= expiresAt) clear() }
}
