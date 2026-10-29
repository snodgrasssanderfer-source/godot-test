// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Mai-with-u/MaiBot 6bcb2e068cd98793a132cb8cc4ebf3cc00cb8fc3
// src/maisaka/turn_trigger/reply_likelihood.py. See third-party/MaiBot-GPL-3.0.txt.
// Changes: Kotlin/Android port, caller-configurable threshold and hard rate limits.
package io.deepseekharness.mobile.companion

import kotlin.math.exp
import kotlin.math.ln

object ReplyLikelihood {
    fun estimate(texts: List<String>, mentioned: Boolean, recentSelfRatio: Double,
                 recentMessages: Int, secondsSinceReply: Double): Double {
        val stripped = texts.map(String::trim).filter(String::isNotEmpty)
        val prefixes = listOf("[表情包", "[图片", "[消息类型]", "[CQ:image", "[语音", "[文件]", "[卡片")
        val atOther = !mentioned && stripped.any { '@' in it }
        val question = stripped.any { '?' in it || '？' in it }
        val placeholderOnly = stripped.isNotEmpty() && stripped.all { text -> prefixes.any(text::startsWith) }
        fun flag(value: Boolean) = if (value) 1.0 else 0.0
        val logit = -1.2005 + 1.5852 * flag(mentioned) - 1.1157 * flag(atOther) +
            0.4807 * flag(question) - 1.0281 * flag(placeholderOnly) +
            2.6525 * recentSelfRatio.coerceIn(0.0, 1.0) - 0.0367 * recentMessages.coerceAtLeast(0) +
            0.0571 * ln(1.0 + secondsSinceReply.coerceAtLeast(0.0)) + 0.1012 * texts.size.coerceIn(0, 20)
        return 1.0 / (1.0 + exp(-logit))
    }
    fun shouldReply(mode: String, mentioned: Boolean, score: Double, threshold: Double,
                    secondsSinceReply: Long, cooldownSeconds: Int, usedThisHour: Int, hourlyLimit: Int): Boolean {
        if (mode == "quiet" || usedThisHour >= hourlyLimit || secondsSinceReply < cooldownSeconds) return false
        return when (mode) { "mention" -> mentioned; "natural" -> mentioned || score >= threshold; else -> false }
    }
}
