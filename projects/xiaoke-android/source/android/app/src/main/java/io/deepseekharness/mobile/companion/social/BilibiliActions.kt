// Protocol adaptation: iseenope/bilibili-mcp-server @ 20eedd09862aea2c160163007f45221c544651b2,
// src/api/bilibili.ts, MIT; see third-party/bilibili-mcp-MIT.txt. No write retries or risk-control bypass.
package io.deepseekharness.mobile.companion.social

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class BiliCredential(val cookie: String, val csrf: String) {
    companion object {
        fun parse(raw: String): BiliCredential {
            require(raw.length in 1..12000 && raw.none { it.code < 32 || it.code > 126 }) { "B站 Cookie 格式无效" }
            val allowed = setOf("SESSDATA", "bili_jct", "DedeUserID", "buvid3", "buvid4")
            val fields = raw.split(';').map(String::trim).filter(String::isNotEmpty).associate { part ->
                require('=' in part); part.substringBefore('=').trim() to part.substringAfter('=').trim()
            }.filterKeys { it in allowed }
            val csrf = fields["bili_jct"].orEmpty()
            require(Regex("[a-fA-F0-9]{32}").matches(csrf) && !fields["SESSDATA"].isNullOrBlank()) { "需要有效 SESSDATA 与 bili_jct" }
            require(fields.values.all { it.length in 1..4096 && it.none(Char::isWhitespace) }) { "B站 Cookie 字段无效" }
            return BiliCredential(fields.entries.joinToString("; ") { "${it.key}=${it.value}" }, csrf)
        }
    }
}

/** Always validates the logged-in account immediately before a write, with a single POST attempt. */
internal class BilibiliActions(
    private val credential: () -> String,
    private val allowed: () -> Boolean,
    private val client: OkHttpClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
) {
    fun cancel() = client.dispatcher.cancelAll()
    private fun response(request: Request): JSONObject = client.newCall(request).execute().use { result ->
        check(result.isSuccessful) { "B站接口未完成 (${result.code})" }
        val body = result.body?.source() ?: error("B站响应为空")
        require(!body.request(2L * 1024 * 1024 + 1)) { "B站响应过大" }
        JSONObject(body.readUtf8())
    }
    private fun builder(path: String, cookie: String) = Request.Builder().url("https://api.bilibili.com$path")
        .header("Cookie", cookie).header("Referer", "https://www.bilibili.com/")
        .header("User-Agent", io.deepseekharness.mobile.companion.BilibiliReader.USER_AGENT)
    fun verify(raw: String): JSONObject {
        check(allowed()) { "联网已关闭或助手已暂停" }
        val credentials = BiliCredential.parse(raw)
        val result = response(builder("/x/web-interface/nav", credentials.cookie).get().build())
        check(result.optInt("code", -1) == 0 && result.optJSONObject("data")?.optBoolean("isLogin") == true) { "B站登录资料已失效，请在本机重新配置" }
        val data = result.getJSONObject("data")
        return JSONObject().put("account", SocialRules.numeric(data.get("mid").toString())).put("name", data.optString("uname").take(160))
    }
    fun execute(account: String, method: String, params: JSONObject): JSONObject {
        if (!allowed()) return rejected(-1, "联网已关闭或助手已暂停，未提交")
        val credentials = BiliCredential.parse(credential())
        val verified = try { verify(credentials.cookie) } catch (_: Exception) { return rejected(-101, "账号身份核对未完成，没有提交写入；请检查连接或登录资料") }
        if (verified.getString("account") != account) return rejected(-101, "B站账号已变化，请重新规划目标")
        val fields = linkedMapOf("csrf" to credentials.csrf)
        val path = when (method) {
            "bili.like" -> { fields["bvid"] = videoId(params.getString("videoId")); fields["thumbs_up"] = "1"; "/x/v2/view/like" }
            "bili.follow" -> { fields["fid"] = SocialRules.numeric(params.getString("target")); fields["act"] = "1"; fields["re_src"] = "14"; "/x/relation/modify" }
            "bili.comment" -> {
                fields["oid"] = SocialRules.numeric(params.getString("aid")); fields["type"] = "1"; fields["plat"] = "1"
                fields["message"] = SocialRules.bounded(params.getString("content"), 4000); "/x/v2/reply/add"
            }
            "bili.reply" -> {
                fields["oid"] = SocialRules.numeric(params.getString("oid")); fields["type"] = params.optInt("type", 1).toString(); fields["plat"] = "1"
                fields["root"] = SocialRules.numeric(params.getString("root")); fields["parent"] = SocialRules.numeric(params.getString("parent"))
                fields["message"] = SocialRules.bounded(params.getString("content"), 4000); "/x/v2/reply/add"
            }
            else -> error("不支持的B站写入操作")
        }
        if (!allowed()) return rejected(-1, "联网已关闭或助手已暂停，未提交")
        val body = FormBody.Builder().also { form -> fields.forEach { (key, value) -> form.add(key, value) } }.build()
        val result = response(builder(path, credentials.cookie).post(body).build())
        val code = result.optInt("code", -1)
        if (code != 0) return rejected(code, "B站未接受操作，请查看权限、登录状态或平台限制；不会自动重发")
        val data = result.optJSONObject("data") ?: JSONObject()
        val ack = JSONObject()
        if (method == "bili.comment" || method == "bili.reply") {
            val rpid = data.opt("rpid")?.toString() ?: data.optJSONObject("reply")?.opt("rpid")?.toString().orEmpty()
            if (rpid.isNotBlank()) ack.put("message_id", rpid)
        }
        return JSONObject().put("status", "ok").put("retcode", 0).put("data", ack)
    }
    private fun rejected(code: Int, reason: String) = JSONObject().put("status", "failed").put("retcode", code).put("message", reason)
    companion object {
        fun videoId(value: String): String { require(Regex("BV[0-9A-Za-z]{10}").matches(value)) { "请使用完整 BV 视频编号" }; return value }
    }
}
