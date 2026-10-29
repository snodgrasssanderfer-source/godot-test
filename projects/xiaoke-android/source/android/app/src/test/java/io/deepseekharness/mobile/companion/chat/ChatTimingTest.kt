package io.deepseekharness.mobile.companion.chat

import org.junit.Assert.*
import org.junit.Test

class ChatTimingTest {
    @Test fun debounceWaitsForTheRunToFinishButNeverLongerThanTheCaps() {
        val d = MessageDebouncer()
        // Quiet chat: a new batch waits 2 s (natural); the same person typing on extends it by 2 s each time.
        assertEquals(2_000L, d.observe("a", false, false, false, 0))
        assertEquals(3_500L, d.observe("a", false, false, false, 1_500))
        // Someone else chimes in: only 0.5 s more.
        assertEquals(3_000L, d.observe("b", false, false, false, 2_500))
        // A message after the batch was due does not reopen it; her reply is already on its way.
        assertEquals(3_000L, d.observe("a", false, false, false, 6_000))
        d.consume()
        // A long run is capped at 8 s from its first message.
        val run = MessageDebouncer()
        var last = 0L
        for (t in 0L..7_500L step 1_500) last = run.observe("a", false, false, false, 40_000 + t)
        assertEquals(48_000L, last)
        run.consume()
        // Active chat (last message < 30 s ago) opens with 5 s; an @ answers within 1 s.
        assertEquals(50_000L + 1_000, run.observe("c", false, false, true, 50_000))
    }

    @Test fun dynamicGateLetsEverythingThroughAtFullFrequencyAndTightensWhenSheTalksTooMuch() {
        val gate = DynamicReplyGate()
        assertTrue(gate.evaluate(0.05, 1.0, 0).trigger)
        var t = 0L
        repeat(10) { gate.recordDemand(0.5, t); gate.recordReply(t); t += 60_000 }
        // She replied 10 times against an expected 5: at half frequency a middling message stays quiet.
        assertFalse(gate.evaluate(0.5, 0.5, t).trigger)
        // A very likely reply still gets through when she has been quiet.
        val quiet = DynamicReplyGate(); var q = 0L
        repeat(10) { quiet.recordDemand(0.5, q); q += 60_000 }
        assertTrue(quiet.evaluate(0.9, 0.5, q).trigger)
    }

    @Test fun onlyLongerQqRepliesAreSplitAndIntoAFewNaturalPieces() {
        assertEquals(listOf("好呀。"), ReplySegmenter.split("好呀。"))
        val long = "我顺着去找了两个视频看。第二个视频后半段没字幕，我只核对了前四分钟。看下来还是单线闭环更耐看！你呢？要不要一起看看第一个？"
        val parts = ReplySegmenter.split(long)
        assertTrue(parts.size in 2..ReplySegmenter.MAX_SEGMENTS)
        assertEquals(long, parts.joinToString(""))
        assertEquals(1, ReplySegmenter.split("看这段：\n```kotlin\nval a = 1\n```\n就是这样运行的，没有别的问题了吧应该").size)
        assertTrue(ReplySegmenter.pauseMs("短") in 600..6_000)
    }
}
