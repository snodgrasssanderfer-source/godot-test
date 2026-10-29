package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Time coverage is explicit; samples do not imply continuous watching. */
object VideoEvidence {
    fun publicVideoUrl(raw: String): String? = runCatching {
        val u = java.net.URI(raw); val host = u.host?.lowercase().orEmpty()
        val allowed = listOf("bilivideo.com", "bilivideo.cn", "hdslb.com").any { host == it || host.endsWith(".$it") }
        if (allowed && u.scheme == "https" && u.userInfo == null && u.fragment == null && u.port in setOf(-1, 443)) raw else null
    }.getOrNull()
    fun range(startSeconds: Double, endSeconds: Double, durationSeconds: Double): Pair<Double, Double> {
        require(startSeconds.isFinite() && endSeconds.isFinite() && durationSeconds.isFinite() && durationSeconds > 0) { "视频时长或范围无效" }
        val end = if (endSeconds <= 0) durationSeconds else endSeconds
        require(startSeconds >= 0 && startSeconds < end && end <= durationSeconds + 0.1) { "时段必须位于视频时长内，结束时间晚于开始时间" }
        return startSeconds to end.coerceAtMost(durationSeconds)
    }
    fun subtitles(body: JSONArray, start: Double, end: Double): JSONArray = JSONArray().also { out ->
        for (i in 0 until body.length()) {
            val line = body.getJSONObject(i)
            if (line.optDouble("to") > start && line.optDouble("from") < end) out.put(JSONObject()
                .put("from", line.optDouble("from")).put("to", line.optDouble("to")).put("content", line.optString("content").take(4000)))
        }
    }
    fun samples(start: Double, end: Double, count: Int = 3): List<Long> {
        require(start >= 0 && end > start && count in 3..12)
        // Keep the last sample within the media timeline rather than precisely at EOF.
        val last = (end - 0.05).coerceAtLeast(start)
        return (0 until count).map { ((start + (last - start) * it / (count - 1)) * 1000).toLong() }
    }
    fun time(seconds: Double): String = "%02d:%02d".format(seconds.toInt() / 60, seconds.toInt() % 60)

    /** What she actually took in. Subtitles cover words only, keyframes are single instants; everything else is listed as unseen. */
    fun watched(lines: JSONArray, keyframesMs: List<Long>, durationSeconds: Double): JSONObject {
        val unseen = JSONArray()
        val result = JSONObject().put("durationSeconds", durationSeconds).put("subtitleLines", lines.length())
            .put("keyframesMs", JSONArray(keyframesMs)).put("audio", false)
        val words = if (lines.length() == 0) { unseen.put("全部台词与字幕"); "没有字幕" } else {
            val from = lines.getJSONObject(0).optDouble("from"); val to = lines.getJSONObject(lines.length() - 1).optDouble("to")
            result.put("subtitleFrom", from).put("subtitleTo", to)
            if (from > 0.5) unseen.put("台词 00:00–${time(from)}")
            if (to < durationSeconds - 0.5) unseen.put("台词 ${time(to)}–${time(durationSeconds)}")
            "字幕 ${time(from)}–${time(to)}（${lines.length()} 行，只代表台词和字幕文字）"
        }
        val sights = if (keyframesMs.isEmpty()) { unseen.put("全部画面"); "没有画面" } else {
            unseen.put("关键帧之间的画面与动作")
            "关键帧 ${keyframesMs.joinToString("、") { time(it / 1000.0) }}（单帧，非连续画面）"
        }
        unseen.put("全部音频（未取音轨）")
        return result.put("unseen", unseen).put("text", "$words；$sights；视频总长 ${time(durationSeconds)}；未看：" +
            (0 until unseen.length()).joinToString("、") { unseen.getString(it) })
    }
}
