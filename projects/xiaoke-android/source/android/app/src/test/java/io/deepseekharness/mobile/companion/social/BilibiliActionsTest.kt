package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BilibiliActionsTest {
    private val cookie = "SESSDATA=synthetic-session; bili_jct=${"1".repeat(32)}"
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    private fun client(handler: (Request) -> String) = OkHttpClient.Builder().retryOnConnectionFailure(false).addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(handler(chain.request()).toResponseBody()).build()
    }.build()
    private val nav = "{\"code\":0,\"data\":{\"isLogin\":true,\"mid\":10001,\"uname\":\"synthetic\"}}"
    @Test fun protocolBindsVerifiedAccountTargetAndContentWithoutBlindRetry() {
        val posted = mutableListOf<Request>()
        val api = BilibiliActions({ cookie }, { true }, client { request ->
            if (request.method == "GET") nav else { posted.add(request); "{\"code\":0,\"data\":{\"rpid\":9001}}" }
        })
        val target = JSONObject().put("videoId", "BV1234567890").put("target", "20002").put("aid", "30003").put("content", "synthetic exact comment")
        api.execute("10001", "bili.like", target); api.execute("10001", "bili.follow", target)
        assertEquals("9001", api.execute("10001", "bili.comment", target).getJSONObject("data").getString("message_id"))
        assertEquals(listOf("/x/v2/view/like", "/x/relation/modify", "/x/v2/reply/add"), posted.map { it.url.encodedPath })
        val fields = posted.map { request -> (request.body as FormBody).let { form -> (0 until form.size).associate { form.name(it) to form.value(it) } } }
        assertEquals("1", fields[0]["thumbs_up"]); assertEquals("20002", fields[1]["fid"])
        assertEquals("30003", fields[2]["oid"]); assertEquals("synthetic exact comment", fields[2]["message"])
        assertEquals("1".repeat(32), fields[2]["csrf"])
        assertEquals("failed", api.execute("99999", "bili.follow", target).getString("status")); assertEquals(3, posted.size)
        var writes = 0
        val failing = BilibiliActions({ cookie }, { true }, client { request -> if (request.method == "GET") nav else { writes++; throw IOException("lost acknowledgement") } })
        assertThrows(IOException::class.java) { failing.execute("10001", "bili.like", target) }; assertEquals(1, writes)
    }
    @Test fun publicDownloadDoesNotSendCredentialsAndEnforcesActualBytesAndDomain() {
        var requests = 0
        val client = client { request ->
            requests++; assertNull(request.header("Cookie")); "xxxxftypisom" + "x".repeat(1100)
        }
        val downloader = BilibiliDownload(client)
        assertThrows(IllegalArgumentException::class.java) { downloader.read("https://video.bilivideo.com/fixture", 1024) { true } }
        assertEquals(1112, downloader.read("https://video.bilivideo.com/fixture", 2048) { true }.size)
        assertThrows(IllegalArgumentException::class.java) { downloader.read("https://video.bilivideo.com.evil.test/fixture", 2048) { true } }
        assertEquals(2, requests)
    }
    private class Host : HostPorts {
        val effects = mutableListOf<String>(); val notices = mutableListOf<String>()
        override fun emit(topic: String, payload: JSONObject) { if (topic == "social.reminder") notices.add(payload.getString("sourceId")) }
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal): JSONObject { effects.add(effect.getString("type")); return JSONObject().put("allowed", true) }
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit): String = error("no paid request during recovery")
        override fun cancelModel(requestId: String) {}
    }
    @Test fun autonomousLikesAndFollowsRemainSeparateFromExactCommentDownloadApproval() {
        val ledger = SocialLedger(); val host = Host(); var writes = 0
        ledger.change { root ->
            rows(root, "bilibili").put("account", "10001")
            rows(root, "biliResources").put("resource", JSONObject().put("id", "resource").put("sourceId", "video-source")
                .put("videoId", "BV1234567890").put("aid", "30003").put("cid", 40004).put("upMid", "20002").put("title", "synthetic video"))
        }
        val runtime = SocialRuntime(ledger, host, SocialTransport { _, _, _ -> writes++; JSONObject().put("status", "ok").put("retcode", 0) },
            accountState = { JSONObject().put("connected", true) })
        runtime.execute("social.configure", JSONObject().put("autonomousDailyPost", true), owner)
        runtime.execute("social.configure", JSONObject().put("autonomousBiliLikes", true).put("autonomousBiliFollowing", true), owner)
        assertTrue(runtime.snapshot(owner).getJSONObject("service").getJSONObject("config").getBoolean("autonomousDailyPost"))
        fun plan(kind: String) = runtime.execute("social.plan", JSONObject().put("kind", "bili_$kind").put("account", "10001")
            .put("target", if (kind == "follow") "20002" else "BV1234567890").put("content", "synthetic proposal").put("idempotencyKey", kind)
            .put("payload", JSONObject().put("resourceId", "resource")), owner)
        val like = plan("like"); assertEquals(like.getString("digest"), plan("like").getString("digest"))
        assertEquals("ready", like.getString("state")); assertEquals("ready", plan("follow").getString("state"))
        val comment = plan("comment"); val download = plan("download")
        assertEquals("pending", comment.getString("state")); assertEquals("pending", download.getString("state"))
        runtime.dispatchAction(comment.getString("id")); assertEquals(0, writes)
        runtime.dispatchAction(like.getString("id")); assertEquals(listOf("like"), host.effects); assertEquals(1, writes)
    }
    @Test fun restartedReminderIsDeliveredOnceWhileInterruptedPaidWorkWaits() {
        val ledger = SocialLedger(); val first = Host(); val runtime = SocialRuntime(ledger, first, SocialTransport { _, _, _ -> error("no send") }, clock = { 1000 })
        runtime.execute("jobs.create", JSONObject().put("kind", "reminder").put("text", "synthetic reminder").put("idempotencyKey", "reminder").put("dueAt", 1000), owner)
        runtime.execute("jobs.create", JSONObject().put("kind", "summary").put("text", "synthetic task").put("idempotencyKey", "summary"), owner)
        ledger.change { root -> values(rows(root, "jobs")).forEach { it.put("state", "running") } }
        val recovered = Host(); val second = SocialRuntime(SocialLedger(ledger.exportEncryptedInput()), recovered, SocialTransport { _, _, _ -> error("no send") }, clock = { 2000 })
        second.tick(); second.tick(); assertEquals(1, recovered.notices.size)
        assertEquals(setOf("done", "interrupted"), objects(second.snapshot(owner).getJSONArray("scheduled")).map { it.getString("state") }.toSet())
    }
}
