package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject

/** What each failed model attempt of a chat turn looked like, shown on the task so the owner can screenshot it. */
object ChatDiagnostics {
    /** Fallback steps of a chat turn, in order. */
    val LEVELS = listOf("原样", "同一模型再试一次", "换 flash（不带联网工具）", "不带工具")

    class Failed(override val cause: ModelFailure, val attempts: JSONArray) : IllegalStateException(cause.message, cause)

    fun attempt(e: ModelFailure, profile: JSONObject, declared: JSONArray, now: Long = System.currentTimeMillis()): JSONObject =
        JSONObject().put("at", now).put("model", profile.optString("model"))
            .put("tools", JSONArray((0 until declared.length()).map { declared.getJSONObject(it).optString("name") }))
            .put("status", e.status ?: JSONObject.NULL).put("error", e.message.orEmpty().take(200)).put("detail", e.detail.take(800))

    fun summary(attempts: JSONArray, scope: String) = JSONObject().put("scope", scope).put("attempts", JSONArray(attempts.toString()))
}
