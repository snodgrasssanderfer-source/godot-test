// Public content endpoint/signing adaptation of iseenope/bilibili-mcp-server
// 20eedd09862aea2c160163007f45221c544651b2, src/api/{bilibili,wbi}.ts, MIT.
// No login, cookies, account writes, WBI-disable fallback or risk-control bypass.
package io.deepseekharness.mobile.companion

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object BilibiliWbi {
    private val table = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    fun sign(params: Map<String, String>, imageKey: String, subKey: String, timestamp: Long): String {
        val raw = imageKey + subKey
        require(raw.length >= 64)
        val mixin = table.take(32).map { raw[it] }.joinToString("")
        fun encode(value: String) = URLEncoder.encode(value.filterNot { it in "!'()*" }, "UTF-8").replace("+", "%20")
        val query = (params + ("wts" to timestamp.toString())).toSortedMap().entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
        val signature = MessageDigest.getInstance("MD5").digest((query + mixin).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return "$query&w_rid=$signature"
    }
}

class BilibiliReader {
    private var keys: Pair<String, String>? = null
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun read(url: String): JSONObject {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && (uri.port == -1 || uri.port == 443))
        require(uri.host == "api.bilibili.com" || uri.host.endsWith(".hdslb.com") || uri.host.endsWith(".bilibili.com"))
        val request = Request.Builder().url(url).header("Referer", "https://www.bilibili.com/").header("User-Agent", "DSH-Companion/0.1 (Android; personal assistant)").build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "B站内容接口暂不可用（HTTP ${response.code}），没有进行绕过或重复发布" }
            val source = response.body?.source() ?: error("内容响应为空")
            require(!source.request(2L * 1024 * 1024 + 1)) { "内容响应过大" }
            val text = source.readByteArray()
            return JSONObject(String(text, Charsets.UTF_8))
        }
    }
    private fun signed(path: String, params: Map<String, String>): JSONObject {
        val k = keys ?: read("https://api.bilibili.com/x/web-interface/nav").optJSONObject("data")?.optJSONObject("wbi_img")?.let { image ->
            fun key(name: String) = URI(image.getString(name)).path.substringAfterLast('/').substringBefore('.')
            (key("img_url") to key("sub_url")).also { keys = it }
        } ?: error("未取得公开签名资料")
        val query = BilibiliWbi.sign(params, k.first, k.second, System.currentTimeMillis() / 1000)
        return read("https://api.bilibili.com$path?$query")
    }
    /** Port of biliApi.playUrl: standard GET/WBI; low-bandwidth AVC, no cookie or bypass fallback. */
    fun publicVideoSource(info: JSONObject): String {
        val result = signed("/x/player/playurl", mapOf("bvid" to info.getString("videoId"), "cid" to info.getLong("cid").toString(),
            "qn" to "16", "fnval" to "16", "fourk" to "0", "platform" to "html5", "high_quality" to "0"))
        check(result.optInt("code", -1) == 0) { "公开视频流不可读取；需要登录或受限时请使用本地文件" }
        val data = result.getJSONObject("data")
        val video = data.optJSONObject("dash")?.optJSONArray("video")
        val url = if (video != null) (0 until video.length()).map(video::getJSONObject)
            .filter { it.optString("codecs").startsWith("avc") }.minByOrNull { it.optLong("bandwidth", Long.MAX_VALUE) }
            ?.let { it.optString("baseUrl", it.optString("base_url")) }
        else data.optJSONArray("durl")?.takeIf { it.length() == 1 }?.getJSONObject(0)?.optString("url")
        return VideoEvidence.publicVideoUrl(url.orEmpty()) ?: error("没有支持的公开 AVC/MP4 视频流；可使用本地视频取帧")
    }
    /** A real single-file public stream, selected only after exact-target download approval. */
    fun publicDownloadSource(info: JSONObject): JSONObject {
        val result = signed("/x/player/playurl", mapOf("bvid" to info.getString("videoId"), "cid" to info.getLong("cid").toString(),
            "qn" to "16", "fnval" to "0", "fourk" to "0", "platform" to "html5", "high_quality" to "0"))
        check(result.optInt("code", -1) == 0) { "平台未提供公开下载流" }
        val data = result.getJSONObject("data")
        check(!data.optBoolean("is_drm") && data.optInt("drm_tech_type", 0) == 0) { "受保护视频不支持下载" }
        val parts = data.optJSONArray("durl")
        check(parts != null && parts.length() == 1) { "平台未提供单文件 MP4；本版本不拼接 DASH 或分段视频" }
        val part = parts.getJSONObject(0)
        val url = VideoEvidence.publicVideoUrl(part.optString("url")) ?: error("下载地址不在公开视频域名范围")
        return JSONObject().put("url", url).put("size", part.optLong("size", -1)).put("quality", data.optInt("quality", 16))
    }
    fun video(videoId: String, startSeconds: Double = 0.0, endSeconds: Double = 0.0): JSONObject {
        require(Regex("BV[0-9A-Za-z]{10}").matches(videoId))
        val metadata = read("https://api.bilibili.com/x/web-interface/view?bvid=$videoId")
        check(metadata.optInt("code") == 0) { "视频资料不可读取，可能需要登录或已失效" }
        val data = metadata.getJSONObject("data")
        val firstPage = data.optJSONArray("pages")?.optJSONObject(0)
        val duration = firstPage?.optDouble("duration", data.getDouble("duration")) ?: data.getDouble("duration")
        val cid = firstPage?.optLong("cid", data.getLong("cid")) ?: data.getLong("cid")
        val (start, end) = VideoEvidence.range(startSeconds, endSeconds, duration)
        val output = JSONObject().put("videoId", videoId).put("title", data.optString("title"))
            .put("aid", data.get("aid").toString()).put("upMid", data.getJSONObject("owner").get("mid").toString()).put("upName", data.getJSONObject("owner").optString("name"))
            .put("durationSeconds", duration).put("cid", cid).put("page", 1).put("partTitle", firstPage?.optString("part").orEmpty())
            .put("description", data.optString("desc")).put("meaning", "标题和简介仅用于定位，不是观看证据")
            .put("rangeStart", start).put("rangeEnd", end)
        try {
            val subtitles = signed("/x/player/wbi/v2", mapOf("bvid" to videoId, "cid" to cid.toString()))
            check(subtitles.optInt("code") == 0) { "字幕需要额外权限或暂不可用" }
            val list = subtitles.getJSONObject("data").optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
            val selected = (0 until list.length()).map(list::getJSONObject).sortedBy { if (it.optString("lan").contains("zh")) 0 else 1 }.firstOrNull()
            if (selected != null) {
                val url = selected.getString("subtitle_url").let { if (it.startsWith("//")) "https:$it" else it }
                val lines = VideoEvidence.subtitles(read(url).getJSONArray("body"), start, end)
                if (lines.length() > 0) output.put("subtitle", lines).put("subtitleLanguage", selected.optString("lan"))
                else output.put("subtitleStatus", "所选时段没有字幕行，请用关键帧或导入片段")
            } else output.put("subtitleStatus", "没有可用字幕，请使用画面采样或导入视频")
        } catch (_: Exception) { output.put("subtitleStatus", "字幕未取得；可使用安卓画面采样或导入本地视频") }
        return output
    }
}
