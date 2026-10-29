package io.deepseekharness.mobile.companion.devicev2

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmSessionTest {
    @Test fun `麦克风内播独立且停止后迟到样本不会污染已完成片段`() {
        var now = 1L; val audio = PcmSession { now }
        val id = audio.start(setOf("mic", "playback"), 1)
        audio.append(id, "mic", byteArrayOf(1, 2, 3, 4), 4)
        audio.append(id, "playback", byteArrayOf(0, 0), 2)
        assertEquals(2, audio.metadata()["mic"]!!.samples)
        assertFalse(audio.metadata()["playback"]!!.nonZero)
        assertThrows(IllegalStateException::class.java) { audio.wav(id, "mic") }
        audio.stop(id); now++
        assertFalse(audio.append(id, "mic", byteArrayOf(5, 6), 2))
        val wave = audio.wav(id, "mic")
        assertEquals("RIFF", String(wave.copyOfRange(0, 4)))
        assertEquals(4, ByteBuffer.wrap(wave, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), wave.copyOfRange(44, wave.size))
        assertArrayEquals(byteArrayOf(0, 0), audio.wav(id, "playback").copyOfRange(44, 46))
    }
    @Test fun `重开会话拒绝旧数据且缓存有容量与时间上限`() {
        var now = 0L; val audio = PcmSession { now }
        val old = audio.start(setOf("mic"), 1)
        val current = audio.start(setOf("playback"), 1)
        assertFalse(audio.append(old, "mic", byteArrayOf(1, 2), 2))
        assertFalse(audio.append(current, "playback", ByteArray(64000), 64000))
        assertEquals(16000, audio.metadata()["playback"]!!.samples)
        audio.stop(current)
        assertThrows(IllegalStateException::class.java) { audio.stop(old) }
        now = 301001
        assertTrue(audio.metadata().isEmpty())
        assertThrows(IllegalStateException::class.java) { audio.wav(current, "playback") }
    }
    @Test fun `截止时间后无新音频且拒绝空片段`() {
        var now = 0L; val audio = PcmSession { now }
        val id = audio.start(setOf("mic"), 1)
        now = 1000; assertFalse(audio.append(id, "mic", byteArrayOf(1, 2), 2))
        audio.stop(id)
        assertThrows(IllegalStateException::class.java) { audio.wav(id, "mic") }
    }
}
