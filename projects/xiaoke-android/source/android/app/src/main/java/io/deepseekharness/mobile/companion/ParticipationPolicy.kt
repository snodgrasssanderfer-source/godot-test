package io.deepseekharness.mobile.companion

import org.json.JSONObject

/** Deterministic gates around the reused MaiBot likelihood calculation. */
object ParticipationPolicy {
    fun mayReceive(c: JSONObject) = c.optBoolean("captureEnabled", false)
    fun nightAllows(c: JSONObject, settings: JSONObject, mentioned: Boolean, senderId: String, hour: Int): Boolean {
        if (!c.optString("scope").startsWith("qq:group:") || !c.optBoolean("nightQuiet", true) || hour !in 0..7) return true
        return mentioned && c.optString("route") == "onebot" && c.optString("account") == settings.optString("oneBotAccount") &&
            senderId.isNotBlank() && senderId == settings.optString("ownerQqId")
    }
    fun isOwnerMute(event: JSONObject, settings: JSONObject) = settings.optBoolean("allowOwnerQqMute") &&
        event.optString("targetType") == "private" && event.optString("account") == settings.optString("oneBotAccount") &&
        settings.optString("ownerQqId").isNotBlank() && event.optString("senderId") == settings.optString("ownerQqId") && event.optString("text").trim() == "#群静音"
}
