// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Mai-with-u/MaiBot 2a9af62cec37035d29237acbe2cf3adceb700b27 src/maisaka/turn_trigger/dynamic_gate.py.
// See third-party/MaiBot-GPL-3.0.txt. Changes: Kotlin port, millisecond clock, message-id dedupe left to the caller.
package io.deepseekharness.mobile.companion.chat

/** Keeps how often she speaks in a group close to `frequency × the replies she would make without a gate`.
 * Feed-forward from expected demand, feedback that only tightens when she has been talking more than the target. */
class DynamicReplyGate(private val windowMs: Long = 3_600_000L) {
    data class Decision(val trigger: Boolean, val probability: Double, val threshold: Double, val expected: Double, val target: Double, val actual: Int)

    private val proactive = ArrayDeque<Pair<Long, Double>>()
    private val forced = ArrayDeque<Pair<Long, Double>>()
    private val replies = ArrayDeque<Long>()
    private var openRoundStartedAt: Long? = null

    /** One virtual round (40 s) counts once, as the batching that would happen without a gate does. */
    fun recordDemand(probability: Double, now: Long) {
        val open = openRoundStartedAt
        if (open != null && now - open < VIRTUAL_ROUND_MS && proactive.isNotEmpty()) {
            proactive[proactive.lastIndex] = proactive.last().first to probability; return
        }
        openRoundStartedAt = now; proactive.addLast(now to probability)
    }
    fun recordForcedTurn(now: Long) { openRoundStartedAt = null; forced.addLast(now to FORCED_TURN_EXPECTED_REPLIES) }
    fun recordReply(now: Long) { openRoundStartedAt = null; replies.addLast(now) }

    fun evaluate(probability: Double, frequency: Double, now: Long): Decision {
        prune(now)
        val scores = proactive.map { it.second }
        val expectedProactive = scores.sum()
        val expectedForced = forced.sumOf { it.second }
        val expected = expectedProactive + expectedForced
        val f = frequency.coerceIn(0.0, 1.0)
        val target = f * expected
        val keep = when {
            f >= 1.0 -> 1.0
            expectedProactive <= 0 -> f
            else -> {
                val feedForward = (target - expectedForced) / expectedProactive
                val feedback = (1.0 + (target - replies.size) / maxOf(1.0, target)).coerceIn(0.0, 1.0)
                (feedForward * feedback).coerceIn(0.0, 1.0)
            }
        }
        val threshold = threshold(scores, expectedProactive, keep)
        return Decision(probability >= threshold, probability, threshold, expected, target, replies.size)
    }

    private fun prune(now: Long) {
        val before = now - windowMs
        while (proactive.isNotEmpty() && proactive.first().first < before) proactive.removeFirst()
        while (forced.isNotEmpty() && forced.first().first < before) forced.removeFirst()
        while (replies.isNotEmpty() && replies.first() < before) replies.removeFirst()
    }

    companion object {
        const val VIRTUAL_ROUND_MS = 40_000L
        const val FORCED_TURN_EXPECTED_REPLIES = 0.91
        const val MIN_WINDOW_SCORES = 20
        private val STATIC = listOf(0.0 to 1.0, 0.1 to 0.605, 0.2 to 0.482, 0.3 to 0.430, 0.4 to 0.376, 0.5 to 0.336,
            0.6 to 0.313, 0.7 to 0.290, 0.8 to 0.250, 0.9 to 0.206, 1.0 to 0.0)

        fun staticThreshold(keep: Double): Double {
            for ((low, high) in STATIC.zipWithNext()) if (keep <= high.first) {
                val progress = (keep - low.first) / (high.first - low.first)
                return low.second + (high.second - low.second) * progress
            }
            return 0.0
        }

        fun threshold(scores: List<Double>, expectedProactive: Double, keep: Double): Double {
            if (keep >= 1.0) return 0.0
            if (keep <= 0.0) return 1.0
            if (scores.size < MIN_WINDOW_SCORES) return staticThreshold(keep)
            val budget = keep * expectedProactive
            var accumulated = 0.0
            for (score in scores.sortedDescending()) { accumulated += score; if (accumulated >= budget) return score }
            return 0.0
        }
    }
}
