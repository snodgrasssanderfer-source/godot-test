package io.deepseekharness.mobile.companion

import org.json.JSONObject

/** Explicitly unsaved screen material. No files, database or backup access. */
class TemporaryObservations(private val now: () -> Long = System::currentTimeMillis) {
    private val values = linkedMapOf<String, JSONObject>()
    private fun prune() { values.entries.removeAll { it.value.optLong("expiresAt") <= now() } }
    @Synchronized fun put(value: JSONObject): JSONObject {
        prune()
        val copy = JSONObject(value.toString()).put("temporary", true)
        if (!copy.has("expiresAt")) copy.put("expiresAt", now() + 10 * 60000)
        require(copy.toString().toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "临时画面超过内存预算" }
        values[copy.getString("id")] = copy
        while (values.size > 6) values.remove(values.keys.first())
        return JSONObject(copy.toString())
    }
    @Synchronized fun get(id: String): JSONObject? { prune(); return values[id]?.let { JSONObject(it.toString()) } }
    @Synchronized fun list(scope: String?): List<JSONObject> { prune(); return values.values.filter { scope == null || it.optString("scope") == scope }.map { JSONObject(it.toString()) } }
    @Synchronized fun remove(id: String) { values.remove(id) }
    @Synchronized fun clear() { values.clear() }
}
