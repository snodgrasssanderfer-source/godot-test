package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoEvidenceWatchedTest {
    private fun unseen(watched: JSONObject) = watched.getJSONArray("unseen").let { a -> (0 until a.length()).joinToString("|") { a.getString(it) } }
    private fun line(from: Double, to: Double) = JSONObject().put("from", from).put("to", to).put("content", "x")

    @Test fun unseenPartsAreListedNotAssumedWatched() {
        val watched = VideoEvidence.watched(JSONArray().put(line(10.0, 12.0)).put(line(300.0, 305.0)), listOf(0L, 120_000L), 900.0)
        assertEquals(10.0, watched.getDouble("subtitleFrom"), 0.0)
        assertEquals(305.0, watched.getDouble("subtitleTo"), 0.0)
        val unseen = unseen(watched)
        assertTrue(unseen.contains("00:00–00:10")); assertTrue(unseen.contains("05:05–15:00"))
        assertTrue(unseen.contains("关键帧之间")); assertTrue(unseen.contains("音频"))
        assertFalse(watched.getBoolean("audio"))
    }

    @Test fun missingSubtitlesOrFramesAreStatedAsUnseen() {
        val framesOnly = VideoEvidence.watched(JSONArray(), listOf(5_000L), 60.0)
        assertTrue(unseen(framesOnly).contains("全部台词"))
        val wordsOnly = VideoEvidence.watched(JSONArray().put(line(0.0, 60.0)), emptyList(), 60.0)
        assertTrue(unseen(wordsOnly).contains("全部画面"))
    }
}
