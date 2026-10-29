// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Mai-with-u/MaiBot 2a9af62cec37035d29237acbe2cf3adceb700b27 src/chat/debounce/controller.py.
// See third-party/MaiBot-GPL-3.0.txt. Changes: Kotlin port, monotonic milliseconds, no asyncio event.
package io.deepseekharness.mobile.companion.chat

/** Waits for someone to finish a run of short messages before she answers. One instance per conversation. */
class MessageDebouncer {
    enum class Mode(val quiet: Long, val active: Long, val sameAuthor: Long, val otherAuthor: Long) {
        FAST(1_000, 3_000, 1_000, 500), NATURAL(2_000, 5_000, 2_000, 500), STEADY(4_000, 7_000, 4_000, 2_000)
    }

    private var first: Long? = null
    private var lastArrival: Long? = null
    private var lastAuthor: String? = null
    private var urgent: Long? = null
    /** When the current batch is due, or null when nothing is waiting. */
    var deadline: Long? = null; private set

    /** Records one inbound message and returns the batch deadline (monotonic ms). */
    fun observe(author: String, bareImage: Boolean, pureEmoji: Boolean, mentioned: Boolean, now: Long, mode: Mode = Mode.NATURAL): Long {
        val previousArrival = lastArrival
        val previousAuthor = lastAuthor
        val arrival = maxOf(now, previousArrival ?: now)
        lastArrival = arrival; lastAuthor = author
        val newBatch = first == null
        if (newBatch) first = arrival
        if (mentioned) urgent = minOf(urgent ?: Long.MAX_VALUE, now + MENTION_LIMIT)
        val current = deadline
        // A batch that is already due (her reply is in progress) is not stretched by later messages.
        if (current == null || current > now) {
            var limit = first!! + BATCH_LIMIT
            urgent?.let { limit = minOf(limit, it) }
            var candidate = if (newBatch) {
                val active = previousArrival != null && arrival - previousArrival <= QUIET_THRESHOLD
                var wait = if (active) mode.active else mode.quiet
                if (bareImage) wait = maxOf(wait, BARE_IMAGE_MIN_WAIT)
                arrival + wait
            } else arrival + if (author == previousAuthor) mode.sameAuthor else mode.otherAuthor
            if (pureEmoji && !newBatch && current != null) candidate = minOf(candidate, current)
            deadline = minOf(candidate, limit)
        }
        return deadline!!
    }

    /** The batch went to her; keep only the last arrival and author for the next decision. */
    fun consume() { first = null; deadline = null; urgent = null }

    companion object {
        const val QUIET_THRESHOLD = 30_000L
        const val BARE_IMAGE_MIN_WAIT = 4_000L
        const val BATCH_LIMIT = 8_000L
        const val MENTION_LIMIT = 1_000L
    }
}
