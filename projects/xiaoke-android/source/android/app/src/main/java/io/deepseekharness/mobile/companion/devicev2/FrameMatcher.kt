package io.deepseekharness.mobile.companion.devicev2

import kotlin.math.sqrt

/**
 * Her fast eyes: cheap checks that need no model call. Frames are reduced to grayscale at a fixed width,
 * a 64-bit difference hash says "is this the same screen", and normalized cross-correlation finds a saved
 * landmark (a button, an icon) in a new frame. Coarse-to-fine keeps a search well under 100 ms on a phone.
 */
object FrameMatcher {
    const val WIDTH = 320
    const val SAME_SCREEN = 12
    const val FOUND = 0.80

    /** Grayscale image; [scale] maps its pixels back to screen pixels (screen = gray * scale + origin). */
    class Gray(val w: Int, val h: Int, val px: IntArray, val scale: Float = 1f, val originX: Int = 0, val originY: Int = 0) {
        operator fun get(x: Int, y: Int) = px[y * w + x]
    }
    data class Match(val x: Int, val y: Int, val score: Double)

    /** ARGB pixels of a screen region starting at (originX, originY), reduced to [WIDTH] wide. */
    fun fromArgb(argb: IntArray, w: Int, h: Int, originX: Int = 0, originY: Int = 0, width: Int = WIDTH): Gray {
        val tw = minOf(width, w); val scale = w.toFloat() / tw
        val th = maxOf(1, (h / scale).toInt())
        val out = IntArray(tw * th)
        for (y in 0 until th) for (x in 0 until tw) {
            // Box average over the source cell; plain sampling flickers on game UI with thin lines.
            val x0 = (x * scale).toInt(); val x1 = maxOf(x0 + 1, ((x + 1) * scale).toInt()).coerceAtMost(w)
            val y0 = (y * scale).toInt(); val y1 = maxOf(y0 + 1, ((y + 1) * scale).toInt()).coerceAtMost(h)
            var sum = 0L; var n = 0
            var yy = y0
            while (yy < y1) { var xx = x0; while (xx < x1) { val c = argb[yy * w + xx]; sum += luma(c); n++; xx += 1 }; yy += 1 }
            out[y * tw + x] = (sum / maxOf(1, n)).toInt()
        }
        return Gray(tw, th, out, scale, originX, originY)
    }

    private fun luma(c: Int) = ((c shr 16 and 255) * 299 + (c shr 8 and 255) * 587 + (c and 255) * 114) / 1000

    fun resize(g: Gray, w: Int, h: Int): Gray {
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = x * g.w / w; val x1 = maxOf(x0 + 1, (x + 1) * g.w / w)
            val y0 = y * g.h / h; val y1 = maxOf(y0 + 1, (y + 1) * g.h / h)
            var sum = 0; var n = 0
            for (yy in y0 until y1) for (xx in x0 until x1) { sum += g[xx, yy]; n++ }
            out[y * w + x] = sum / n
        }
        return Gray(w, h, out, g.scale * g.w / w, g.originX, g.originY)
    }

    /** Difference hash: is the left pixel brighter than the right, over a 9x8 thumbnail. */
    fun fingerprint(g: Gray): Long {
        val t = resize(g, 9, 8)
        var bits = 0L
        for (y in 0 until 8) for (x in 0 until 8) bits = (bits shl 1) or if (t[x, y] > t[x + 1, y]) 1L else 0L
        return bits
    }

    fun distance(a: Long, b: Long) = java.lang.Long.bitCount(a xor b)

    fun crop(g: Gray, x: Int, y: Int, w: Int, h: Int): Gray {
        require(x >= 0 && y >= 0 && w > 0 && h > 0 && x + w <= g.w && y + h <= g.h) { "区域超出画面" }
        val out = IntArray(w * h)
        for (yy in 0 until h) System.arraycopy(g.px, (y + yy) * g.w + x, out, yy * w, w)
        return Gray(w, h, out, g.scale)
    }

    /** Best place of [template] in [frame], both at the same reduced scale; score is NCC in -1..1. */
    fun find(frame: Gray, template: Gray): Match? {
        if (template.w > frame.w || template.h > frame.h || template.w < 8 || template.h < 8) return null
        val factor = when { template.w >= 32 && template.h >= 32 -> 4; template.w >= 16 && template.h >= 16 -> 2; else -> 1 }
        if (factor == 1) return scan(frame, template, 0, 0, frame.w - template.w, frame.h - template.h).maxByOrNull { it.score }
        val small = resize(frame, frame.w / factor, frame.h / factor)
        val tmpl = resize(template, template.w / factor, template.h / factor)
        if (tmpl.w > small.w || tmpl.h > small.h) return null
        val radius = factor * 2
        return scan(small, tmpl, 0, 0, small.w - tmpl.w, small.h - tmpl.h).sortedByDescending { it.score }.take(3).mapNotNull { c ->
            val cx = c.x * factor; val cy = c.y * factor
            scan(frame, template, maxOf(0, cx - radius), maxOf(0, cy - radius),
                minOf(frame.w - template.w, cx + radius), minOf(frame.h - template.h, cy + radius)).maxByOrNull { it.score }
        }.maxByOrNull { it.score }
    }

    /** NCC at every offset in the box; returns one best per 4x4 cell so the top few are spread out. */
    private fun scan(f: Gray, t: Gray, x0: Int, y0: Int, x1: Int, y1: Int): List<Match> {
        val n = t.w * t.h
        var tSum = 0.0; var tSq = 0.0
        for (v in t.px) { tSum += v; tSq += v.toDouble() * v }
        val tMean = tSum / n; val tVar = tSq - tSum * tMean
        val cells = HashMap<Long, Match>()
        for (y in y0..y1) for (x in x0..x1) {
            var fSum = 0.0; var fSq = 0.0; var cross = 0.0
            for (ty in 0 until t.h) {
                val row = (y + ty) * f.w + x; val trow = ty * t.w
                for (tx in 0 until t.w) {
                    val a = f.px[row + tx].toDouble(); val b = t.px[trow + tx]
                    fSum += a; fSq += a * a; cross += a * b
                }
            }
            val fVar = fSq - fSum * fSum / n
            val score = if (fVar <= 1e-6 || tVar <= 1e-6) (if (fVar <= 1e-6 && tVar <= 1e-6 && kotlin.math.abs(fSum / n - tMean) < 8) 1.0 else 0.0)
                else (cross - fSum * tMean) / sqrt(fVar * tVar)
            val key = (y / 4).toLong() shl 32 or (x / 4).toLong()
            if ((cells[key]?.score ?: -2.0) < score) cells[key] = Match(x, y, score)
        }
        return cells.values.toList()
    }

    /** Screen pixel of the centre of a match found in [frame]. */
    fun screenCenter(frame: Gray, m: Match, template: Gray): Pair<Int, Int> =
        (frame.originX + (m.x + template.w / 2f) * frame.scale).toInt() to (frame.originY + (m.y + template.h / 2f) * frame.scale).toInt()

    fun encode(g: Gray): String = java.util.Base64.getEncoder().encodeToString(ByteArray(g.px.size) { g.px[it].toByte() })
    fun decode(w: Int, h: Int, data: String): Gray {
        val bytes = java.util.Base64.getDecoder().decode(data)
        require(bytes.size == w * h)
        return Gray(w, h, IntArray(bytes.size) { bytes[it].toInt() and 255 })
    }
}
