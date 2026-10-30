package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class BiliReadingTest {
    private fun varint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (v >= 0x80) { out.write(((v and 0x7f) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }
    private fun bytesField(out: ByteArrayOutputStream, field: Int, bytes: ByteArray) { varint(out, (field shl 3 or 2).toLong()); varint(out, bytes.size.toLong()); out.write(bytes) }
    private fun intField(out: ByteArrayOutputStream, field: Int, value: Long) { varint(out, (field shl 3).toLong()); varint(out, value) }

    private fun elem(id: Long, progressMs: Long, content: String): ByteArray = ByteArrayOutputStream().also { e ->
        intField(e, 1, id); intField(e, 2, progressMs); intField(e, 3, 1); intField(e, 5, 16777215)
        bytesField(e, 6, "abcd1234".toByteArray()); bytesField(e, 7, content.toByteArray()); intField(e, 8, 1_700_000_000)
        bytesField(e, 12, id.toString().toByteArray())
    }.toByteArray()

    @Test fun `弹幕分段按 protobuf 读出时间和内容，重复的合并计数`() {
        val segment = ByteArrayOutputStream().also { s ->
            bytesField(s, 1, elem(9_000_000_000_001, 12_500, "前方高能"))
            bytesField(s, 1, elem(2, 13_000, "哈哈哈哈"))
            bytesField(s, 1, elem(3, 14_000, "哈哈哈哈"))
            bytesField(s, 1, elem(4, 700_000, "后面的"))
            bytesField(s, 2, "unknown outer field".toByteArray())
        }.toByteArray()
        val lines = BiliDanmaku.parse(segment)
        assertEquals(listOf(12_500L to "前方高能", 13_000L to "哈哈哈哈", 14_000L to "哈哈哈哈", 700_000L to "后面的"), lines.map { it.progressMs to it.content })
        val window = BiliDanmaku.window(lines, 0.0, 600.0)
        assertEquals(listOf("前方高能" to 1, "哈哈哈哈" to 2), window.map { it.first.content to it.second })
        assertEquals(emptyList<BiliDanmaku.Line>(), BiliDanmaku.parse(byteArrayOf(0x0a, 0x7f, 0x01))) // truncated input: nothing, no crash
    }

    @Test fun `弹幕分段编号按六分钟一段`() {
        assertEquals(1..2, BiliDanmaku.segments(0.0, 600.0))
        assertEquals(2..2, BiliDanmaku.segments(400.0, 700.0))
    }

    @Test fun `续期用的 CorrespondPath 是 1024 位 RSA-OAEP 密文`() {
        val path = io.deepseekharness.mobile.companion.social.BiliLogin.correspondPath(1_700_000_000_000)
        assertEquals(256, path.length)
        assertTrue(path.all { it in "0123456789abcdef" })
    }
}
