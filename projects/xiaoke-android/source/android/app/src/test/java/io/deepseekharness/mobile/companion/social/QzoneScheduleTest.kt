package io.deepseekharness.mobile.companion.social

import io.deepseekharness.mobile.companion.iteration.CommandPrincipal
import io.deepseekharness.mobile.companion.iteration.HostPorts
import io.deepseekharness.mobile.companion.iteration.ModuleEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

class QzoneScheduleTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun at(hour: Int, minute: Int = 0) = LocalDate.of(2026, 10, 30).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    private val owner = CommandPrincipal("owner", "owner_ui", "owner:local", true)
    private val her = CommandPrincipal("companion", "background", "owner:local", false)
    private class Host : HostPorts {
        override fun emit(topic: String, payload: JSONObject) {}
        override fun authorizeEffect(effect: JSONObject, principal: CommandPrincipal) = JSONObject().put("allowed", true)
        override fun cancelModel(requestId: String) {}
        override fun requestModel(request: JSONObject, onEvent: (JSONObject) -> Unit) = "r"
    }

    @Test fun `说说的发布时段：草稿后一到四小时内，最晚十点半，太晚就挪到第二天上午`() {
        repeat(50) { seed ->
            val t = QzoneLife.publishTime(at(15), zone, Random(seed))
            assertTrue(t in at(16)..at(19))
            val late = QzoneLife.publishTime(at(22), zone, Random(seed))
            assertTrue(late in at(10) + 86_400_000L..at(12) + 86_400_000L)
        }
    }

    @Test fun `批准时已过时段就再随机晚一会儿发，不在点批准的那一刻`() {
        assertEquals(at(20), QzoneLife.afterApproval(at(20), at(18)))
        repeat(50) { seed ->
            val t = QzoneLife.afterApproval(at(18), at(21), Random(seed))
            assertTrue(t in at(21, 10)..at(22))
        }
    }

    @Test fun `她起草的说说先待审，批准后等到时段才发`() {
        var now = at(15)
        val sent = mutableListOf<String>()
        val ledger = SocialLedger()
        val rt = SocialRuntime(ledger, Host(), SocialTransport { _, method, _ -> sent.add(method); JSONObject().put("status", "ok").put("retcode", 0).put("data", JSONObject().put("tid", "t1")) },
            { now }, { JSONObject().put("connected", true).put("account", "10001") })
        rt.onEvent(ModuleEvent("connectivity.changed", "owner:local", "net", JSONObject().put("online", true)))
        // Without a shareable basis she cannot draft anything public.
        assertThrows(IllegalStateException::class.java) { rt.execute("social.plan", post("x", "nothing"), her) }
        rt.onEvent(ModuleEvent("observation.summary", "qq:group:fixture", "basis", JSONObject().put("summary", "群里在聊天气").put("shareable", true)))
        val draft = rt.execute("social.plan", post("basis", "today").put("notBefore", at(17)), her)
        assertEquals("pending", draft.getString("state"))
        rt.execute("approvals.approveBatch", JSONObject().put("items", JSONArray().put(JSONObject().put("id", draft.getString("id")).put("digest", draft.getString("digest")))), owner)
        rt.tickEffects(); assertEquals(emptyList<String>(), sent)
        now = at(17, 1); rt.tickEffects()
        assertEquals(listOf("send_qzone_msg"), sent)
    }

    @Test fun `空间评论的回复只能指向已读取的评论`() {
        val ledger = SocialLedger()
        ledger.change { rows(it, "qzone").put("ref", JSONObject().put("id", "ref").put("tid", "t9").put("ownerUin", "10001").put("commentId", "c1").put("commentUin", "20002")) }
        val rt = SocialRuntime(ledger, Host(), SocialTransport { _, _, _ -> JSONObject() }, { 1000L }, { JSONObject().put("connected", true) })
        val plan = JSONObject().put("kind", "qzone_reply").put("account", "10001").put("content", "谢谢").put("idempotencyKey", "k").put("payload", JSONObject().put("refId", "ref"))
        assertThrows(IllegalArgumentException::class.java) { rt.execute("social.plan", JSONObject(plan.toString()).put("target", "other"), owner) }
        val ok = rt.execute("social.plan", JSONObject(plan.toString()).put("target", "t9"), owner)
        assertEquals("c1", ok.getJSONObject("payload").getString("commentId"))
    }

    private fun post(source: String, key: String) = JSONObject().put("kind", "post").put("account", "10001").put("target", "10001")
        .put("content", "今天的云好看").put("idempotencyKey", key).put("sourceIds", JSONArray().put(source)).put("payload", JSONObject())
}
