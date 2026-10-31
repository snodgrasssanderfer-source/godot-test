package io.deepseekharness.mobile.companion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TurnLatencyTest {
    @Test fun `确认收到与模型首字分开且只记录第一次`() {
        var nanos = 0L
        val timing = TurnLatency { nanos }
        nanos = 20_000_000; timing.running(); timing.visible(true)
        nanos = 3_000_000_000; timing.visible()
        nanos = 4_000_000_000; timing.visible(); timing.finish()
        nanos = 9_000_000_000
        val result = timing.snapshot()
        assertEquals(20.0, result.getDouble("acknowledgementMs"), 0.0)
        assertEquals(20.0, result.getDouble("firstVisibleTextMs"), 0.0)
        assertEquals(3000.0, result.getDouble("firstModelTextMs"), 0.0)
        assertEquals(4000.0, result.getDouble("elapsedMs"), 0.0)
    }

    @Test fun `用量只记录数值白名单并防止外部修改快照`() {
        val timing = TurnLatency { 0L }
        timing.request("chat", JSONObject().put("firstTextMs", 12), JSONObject()
            .put("promptTokenCount", 17).put("text", "虚构私有资料不进计时"), "completed")
        val first = timing.snapshot()
        first.getJSONArray("requests").getJSONObject(0).getJSONObject("timing").put("firstTextMs", 99)
        assertFalse(timing.snapshot().toString().contains("虚构私有资料"))
        assertEquals(12, timing.snapshot().getJSONArray("requests").getJSONObject(0).getJSONObject("timing").getInt("firstTextMs"))
    }
}
