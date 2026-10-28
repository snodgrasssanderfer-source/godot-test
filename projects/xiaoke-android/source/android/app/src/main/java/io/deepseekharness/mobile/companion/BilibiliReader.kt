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
    fun video(videoId: String): JSONObject {
        require(Regex("BV[0-9A-Za-z]{10}").matches(videoId))
        val metadata = read("https://api.bilibili.com/x/web-interface/view?bvid=$videoId")
        check(metadata.optInt("code") == 0) { "视频资料不可读取，可能需要登录或已失效" }
        val data = metadata.getJSONObject("data")
        val output = JSONObject().put("videoId", videoId).put("title", data.optString("title"))
            .put("durationSeconds", data.optLong("duration")).put("cid", data.optLong("cid"))
            .put("description", data.optString("desc")).put("meaning", "标题和简介仅用于定位，不是观看证据")
        try {
            val nav = read("https://api.bilibili.com/x/web-interface/nav").optJSONObject("data")?.optJSONObject("wbi_img") ?: error("没有公开 WBI 信息")
            fun key(name: String) = URI(nav.getString(name)).path.substringAfterLast('/').substringBefore('.')
            val query = BilibiliWbi.sign(mapOf("bvid" to videoId, "cid" to data.getLong("cid").toString()), key("img_url"), key("sub_url"), System.currentTimeMillis() / 1000)
            val subtitles = read("https://api.bilibili.com/x/player/wbi/v2?$query")
            check(subtitles.optInt("code") == 0) { "字幕需要额外权限或暂不可用" }
            val list = subtitles.getJSONObject("data").optJSONObject("subtitle")?.optJSONArray("subtitles") ?: JSONArray()
            val selected = (0 until list.length()).map(list::getJSONObject).sortedBy { if (it.optString("lan").contains("zh")) 0 else 1 }.firstOrNull()
            if (selected != null) {
                val url = selected.getString("subtitle_url").let { if (it.startsWith("//")) "https:$it" else it }
                output.put("subtitle", read(url).getJSONArray("body")).put("subtitleLanguage", selected.optString("lan"))
            } else output.put("subtitleStatus", "没有可用字幕，请使用画面采样或导入视频")
        } catch (_: Exception) { output.put("subtitleStatus", "字幕未取得；可使用安卓画面采样或导入本地视频") }
        return output
    }
}
