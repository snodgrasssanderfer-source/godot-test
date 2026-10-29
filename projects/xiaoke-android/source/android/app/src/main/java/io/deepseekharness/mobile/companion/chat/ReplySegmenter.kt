// SPDX-License-Identifier: AGPL-3.0-only
// Mechanism adapted from AstrBotDevs/AstrBot abf683071952375ec36baa4cad6b6f3d9c9bb03f
// astrbot/core/pipeline/result_decorate/stage.py and respond/stage.py (segmented reply, log interval). See third-party/AstrBot-AGPL-3.0.txt.
// Changes: Kotlin; longer replies are split (short ones stay whole) and tiny pieces are merged so a chat never turns into a drip.
package io.deepseekharness.mobile.companion.chat

import kotlin.math.ln
import kotlin.random.Random

/** How a longer QQ reply is sent as a few messages, with typing-like pauses between them. */
object ReplySegmenter {
    private val sentence = Regex(".*?[。？！?!~…]+|.+$", setOf(RegexOption.DOT_MATCHES_ALL))
    const val SPLIT_FROM = 40
    const val MAX_SEGMENTS = 4
    const val MIN_SEGMENT = 8

    fun split(text: String): List<String> {
        val body = text.trim()
        if (body.length < SPLIT_FROM || body.contains("```")) return listOf(body)
        // Paragraphs first; within a paragraph, sentence ends.
        val pieces = body.split(Regex("\n\\s*\n")).flatMap { para ->
            sentence.findAll(para.trim()).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        }
        val merged = mutableListOf<String>()
        for (piece in pieces) {
            if (merged.isNotEmpty() && (merged.last().length < MIN_SEGMENT || piece.length < MIN_SEGMENT)) merged[merged.lastIndex] = merged.last() + piece
            else merged.add(piece)
        }
        // At most four messages: fold the tail into the last one.
        while (merged.size > MAX_SEGMENTS) {
            val shortest = (0 until merged.size - 1).minByOrNull { merged[it].length + merged[it + 1].length }!!
            merged[shortest] = merged[shortest] + merged.removeAt(shortest + 1)
        }
        return merged.ifEmpty { listOf(body) }
    }

    /** log_2.6(characters + 1) seconds plus a little jitter, as AstrBot's "log" interval. */
    fun pauseMs(segment: String, random: Random = Random.Default): Long {
        val count = segment.count { it.isLetterOrDigit() }
        val base = ln(count + 1.0) / ln(2.6)
        return ((base + random.nextDouble(0.0, 0.5)) * 1000).toLong().coerceIn(600, 6_000)
    }
}
