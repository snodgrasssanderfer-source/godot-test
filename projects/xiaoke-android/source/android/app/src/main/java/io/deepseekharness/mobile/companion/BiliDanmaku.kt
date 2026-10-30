// Protobuf layout from iseenope/bilibili-mcp-server 20eedd09862aea2c160163007f45221c544651b2 src/api/danmaku.ts (MIT):
// DmSegMobileReply.elems = 1 (repeated DanmakuElem); DanmakuElem.progress = 2 (ms), content = 7.
package io.deepseekharness.mobile.companion

/** Reads the web danmaku segment (`/x/v2/dm/web/seg.so`, six minutes per segment) without a protobuf library. */
object BiliDanmaku {
    const val SEGMENT_MS = 360_000L
    data class Line(val progressMs: Long, val content: String)

    fun segments(startSeconds: Double, endSeconds: Double): IntRange =
        ((startSeconds * 1000).toLong() / SEGMENT_MS + 1).toInt()..((endSeconds * 1000).toLong().coerceAtLeast(1) / SEGMENT_MS + 1).toInt()

    fun parse(bytes: ByteArray): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        while (i < bytes.size) {
            val (tag, n) = varint(bytes, i); if (n == 0) break
            i += n
            val field = (tag ushr 3).toInt(); val wire = (tag and 7).toInt()
            when (wire) {
                0 -> { val (_, m) = varint(bytes, i); if (m == 0) break; i += m }
                2 -> {
                    val (len, m) = varint(bytes, i); if (m == 0 || len < 0 || i + m + len > bytes.size) break
                    i += m
                    if (field == 1) element(bytes, i, i + len.toInt())?.let(out::add)
                    i += len.toInt()
                }
                1 -> i += 8
                5 -> i += 4
                else -> break
            }
        }
        return out
    }

    private fun element(bytes: ByteArray, from: Int, to: Int): Line? {
        var i = from; var progress = 0L; var content = ""
        while (i < to) {
            val (tag, n) = varint(bytes, i); if (n == 0) return null
            i += n
            val field = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> { val (v, m) = varint(bytes, i); if (m == 0) return null; if (field == 2) progress = v; i += m }
                2 -> {
                    val (len, m) = varint(bytes, i); if (m == 0 || i + m + len > to) return null
                    i += m
                    if (field == 7) content = String(bytes, i, len.toInt(), Charsets.UTF_8)
                    i += len.toInt()
                }
                1 -> i += 8
                5 -> i += 4
                else -> return null
            }
        }
        return if (content.isBlank()) null else Line(progress, content)
    }

    private fun varint(bytes: ByteArray, start: Int): Pair<Long, Int> {
        var value = 0L; var shift = 0; var i = start
        while (i < bytes.size && shift < 64) {
            val b = bytes[i].toInt() and 0xff
            value = value or ((b and 0x7f).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return value to (i - start)
            shift += 7
        }
        return 0L to 0
    }

    /** Lines inside [start, end), duplicates folded ("哈哈哈" ×40 is one line with a count), at most [limit]. */
    fun window(lines: List<Line>, startSeconds: Double, endSeconds: Double, limit: Int = 200): List<Pair<Line, Int>> {
        val inside = lines.filter { it.progressMs >= startSeconds * 1000 && it.progressMs < endSeconds * 1000 }.sortedBy { it.progressMs }
        val grouped = inside.groupBy { it.content.trim() }.map { (_, same) -> same.first() to same.size }
        return grouped.sortedByDescending { it.second }.take(limit).sortedBy { it.first.progressMs }
    }
}
