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
}
