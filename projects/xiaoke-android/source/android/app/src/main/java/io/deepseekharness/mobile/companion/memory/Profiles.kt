package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Short running profiles of the owner, group members, games and works, rewritten as conversations add to them.
 * Each lives in the scope it was learned in: the owner's only in the owner's private chats, a member's in that
 * group. Profiles keep their latest source messages, so deleting those messages removes the profile with them.
 */
class Profiles(private val store: CompanionStore) {
    val types = setOf("owner", "member", "game", "work", "other")

    fun id(scope: String, key: String) = "profile-" + CompanionPolicy.digest(scope + "\u0000" + key).take(24)

    fun key(type: String, name: String, actorId: String) = when (type) {
        "owner" -> "owner"
        "member" -> "member:" + CompanionPolicy.digest(actorId).take(24)
        else -> "$type:" + name.trim().lowercase().take(40)
    }

    fun upsert(scope: String, type: String, name: String, summary: String, actorId: String, sourceIds: List<String>): JSONObject? {
        require(type in types)
        // The owner's profile only ever forms in the owner's own private chats.
        if (type == "owner" && !store.ownerPrivate(scope)) return null
        if (type == "member" && actorId.isBlank()) return null
        val refs = sourceIds.mapNotNull { id -> store.get("message", id)?.takeIf { it.optString("scope") == scope }?.let { JSONObject().put("kind", "message").put("id", id).put("scope", scope) } }
        if (refs.isEmpty()) return null
        val key = key(type, name, actorId)
        // One owner profile, whichever private chat (app or the owner's QQ) it was learned in.
        val home = if (type == "owner") CompanionPolicy.OWNER else scope
        val old = store.get("profile", id(home, key))?.optJSONArray("sourceRefs")?.let { a -> (0 until a.length()).map(a::getJSONObject) }.orEmpty()
        return store.put("profile", JSONObject().put("id", id(home, key)).put("scope", home).put("key", key).put("type", type)
            .put("name", name.trim().take(40)).put("summary", summary.trim().take(400)).put("sourceRefs", JSONArray((old + refs).distinctBy { it.optString("id") }.takeLast(5))))
    }

    fun inScope(scope: String): List<JSONObject> = store.list("profile", scope, 500).let { a -> (0 until a.length()).map(a::getJSONObject) }

    /** What she can draw on in [scope]: its own profiles, plus (in the owner's local chat) group profiles the owner mentions. */
    fun relevant(scope: String, text: String, sharesOwner: Boolean): List<JSONObject> {
        val own = inScope(scope)
        val extra = mutableListOf<JSONObject>()
        if (scope == CompanionPolicy.OWNER) {
            val all = store.list("profile", null, 2000)
            for (i in 0 until all.length()) all.getJSONObject(i).let { p ->
                if (p.optString("scope").startsWith("qq:group:") && p.optString("name").length >= 2 && text.contains(p.optString("name"))) extra.add(p)
            }
        } else if (sharesOwner) extra.addAll(inScope(CompanionPolicy.OWNER).filter { it.optString("type") == "owner" })
        val mentioned = own.filter { it.optString("type") == "owner" || (it.optString("name").length >= 2 && text.contains(it.optString("name"))) }
        val recent = own.sortedByDescending { it.optLong("updatedAt") }.take(6)
        return (mentioned + extra + recent).distinctBy { it.optString("id") }.take(10)
    }

    companion object {
        fun describe(profiles: List<JSONObject>): String = profiles.joinToString("\n") { p ->
            val label = when (p.optString("type")) { "owner" -> "主人"; "member" -> "群友"; "game" -> "游戏"; "work" -> "作品"; else -> "其他" }
            "【$label·${p.optString("name")}】${p.optString("summary")}"
        }
    }
}
