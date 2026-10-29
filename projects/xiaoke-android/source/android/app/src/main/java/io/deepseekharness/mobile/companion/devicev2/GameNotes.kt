package io.deepseekharness.mobile.companion.devicev2

import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * What she knows about each game: her notes (also kept as memories the owner can read and edit),
 * landmarks for her fast eyes, and routes she has seen work. One record per game, owner scope only.
 */
class GameNotes(private val store: CompanionStore) {
    private fun key(pkg: String) = "game-" + CompanionPolicy.digest(pkg).take(24)
    fun read(pkg: String): JSONObject = store.lifeState(key(pkg)).put("packageName", pkg)
    private fun update(pkg: String, change: (JSONObject) -> Unit): JSONObject = store.lifeTransaction {
        val state = read(pkg); change(state); store.saveLifeState(key(pkg), state)
    }

    fun addNote(pkg: String, text: String, memoryId: String) = update(pkg) { state ->
        val notes = state.optJSONArray("notes") ?: JSONArray()
        notes.put(JSONObject().put("text", text.take(300)).put("at", System.currentTimeMillis()).put("memoryId", memoryId))
        state.put("notes", tail(notes, 40))
    }

    fun saveLandmark(pkg: String, name: String, template: FrameMatcher.Gray) = update(pkg) { state ->
        val all = state.optJSONObject("landmarks") ?: JSONObject()
        if (!all.has(name)) check(all.length() < 30) { "这个游戏的标记已经存满了，先删掉不用的" }
        all.put(name, JSONObject().put("w", template.w).put("h", template.h).put("data", FrameMatcher.encode(template)).put("at", System.currentTimeMillis()))
        state.put("landmarks", all)
    }

    fun landmark(pkg: String, name: String): FrameMatcher.Gray? = read(pkg).optJSONObject("landmarks")?.optJSONObject(name)?.let {
        FrameMatcher.decode(it.getInt("w"), it.getInt("h"), it.getString("data"))
    }

    fun saveRoute(pkg: String, name: String, steps: JSONArray) = update(pkg) { state ->
        val all = state.optJSONObject("routes") ?: JSONObject()
        if (!all.has(name)) check(all.length() < 20) { "这个游戏的路线已经存满了，先删掉不用的" }
        all.put(name, JSONObject().put("steps", steps).put("at", System.currentTimeMillis()))
        state.put("routes", all)
    }

    fun route(pkg: String, name: String): JSONArray? = read(pkg).optJSONObject("routes")?.optJSONObject(name)?.optJSONArray("steps")

    fun forget(pkg: String, kind: String, name: String) = update(pkg) { state -> state.optJSONObject(kind)?.remove(name) }

    /** For her prompt while she plays this game. */
    fun describe(pkg: String): String {
        val state = read(pkg)
        val notes = state.optJSONArray("notes")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("text") } }.orEmpty().takeLast(12)
        val landmarks = state.optJSONObject("landmarks")?.keys()?.asSequence()?.toList().orEmpty()
        val routes = state.optJSONObject("routes")?.let { r -> r.keys().asSequence().map { "$it(${r.getJSONObject(it).optJSONArray("steps")?.length() ?: 0}步)" }.toList() }.orEmpty()
        return listOfNotNull(
            notes.takeIf { it.isNotEmpty() }?.joinToString("；", "你对这个游戏的经验：") ,
            landmarks.takeIf { it.isNotEmpty() }?.joinToString("、", "存过的画面标记：") ,
            routes.takeIf { it.isNotEmpty() }?.joinToString("、", "走通过的路线：") ,
        ).joinToString("\n")
    }

    private fun tail(a: JSONArray, max: Int) = JSONArray((maxOf(0, a.length() - max) until a.length()).map { a.get(it) })
}
