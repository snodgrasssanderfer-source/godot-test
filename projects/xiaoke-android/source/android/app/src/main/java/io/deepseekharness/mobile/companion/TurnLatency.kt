package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** Monotonic, content-free stage timings for one turn, saved on the task. */
class TurnLatency(private val clock: () -> Long = System::nanoTime) {
    private val started = clock()
    private var finished: Long? = null
    private val values = JSONObject()
    private val requests = JSONArray()
    private val tools = JSONArray()

    fun now() = clock()
    private fun ms(from: Long, until: Long = clock()) = ((until - from).coerceAtLeast(0) / 1_000_000.0)
    @Synchronized fun running() { if (!values.has("queueMs")) values.put("queueMs", ms(started)) }
    @Synchronized fun duration(name: String, since: Long) { values.put(name, ms(since)) }
    fun <T> measure(name: String, action: () -> T): T {
        val since = clock()
        try { return action() } finally { synchronized(this) { values.put(name, values.optDouble(name, 0.0) + ms(since)) } }
    }
    @Synchronized fun estimate(tokens: Int) { values.put("estimatedPromptTokens", tokens) }
    @Synchronized fun visible() { if (!values.has("firstTextMs")) values.put("firstTextMs", ms(started)) }
    @Synchronized fun request(purpose: String, timing: JSONObject, usage: JSONObject, outcome: String) {
        val row = JSONObject().put("purpose", purpose).put("outcome", outcome).put("timing", JSONObject(timing.toString()))
        for (key in listOf("promptTokenCount", "cachedContentTokenCount", "candidatesTokenCount", "thoughtsTokenCount", "totalTokenCount"))
            if (usage.has(key)) row.put(key, usage.optLong(key))
        requests.put(row)
    }
    @Synchronized fun tool(name: String, since: Long, completed: Boolean) {
        tools.put(JSONObject().put("name", name).put("elapsedMs", ms(since)).put("completed", completed))
    }
    @Synchronized fun finish() { if (finished == null) finished = clock() }
    @Synchronized fun snapshot(): JSONObject = JSONObject(values.toString()).put("version", 1)
        .put("elapsedMs", ms(started, finished ?: clock()))
        .put("requests", JSONArray(requests.toString())).put("tools", JSONArray(tools.toString()))
}
