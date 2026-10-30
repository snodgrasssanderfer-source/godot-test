package io.deepseekharness.mobile.companion.memory

import io.deepseekharness.mobile.companion.CompanionStore
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps a vector for every memory under the active embedder and answers "which memories mean something like this". */
class MemoryIndex(private val store: CompanionStore, private val embeddings: Embeddings) {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "xiaoke-memory-index").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    private val queryCache = object : LinkedHashMap<String, Pair<String, FloatArray>>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, FloatArray>>?) = size > 32
    }

    /** Embeds what is missing, in the background; safe to call after every save. */
    fun refresh() {
        if (!pending.compareAndSet(false, true)) return
        worker.execute {
            try {
                repeat(8) {
                    val tag = embeddings.active()?.tag ?: return@execute
                    val batch = store.unembedded(tag, 16)
                    if (batch.isEmpty()) return@execute
                    val (used, vectors) = embeddings.embed(batch.map { it.optString("text") }, query = false) ?: return@execute
                    batch.zip(vectors).forEach { (memory, vector) -> store.saveVector(memory.getString("id"), used, store.vectorHash(memory), vector) }
                }
            } catch (_: Exception) {
                // Retried on the next save or clock tick; keyword and entity matching cover the gap.
            } finally { pending.set(false) }
        }
    }

    /** Cosine similarity of [query] to each memory that has a current vector; null when no embedder is usable right now. */
    fun semantic(query: String, memories: List<JSONObject>): Map<String, Double>? {
        if (query.isBlank() || memories.isEmpty()) return null
        val key = query.take(2000)
        val cached = synchronized(queryCache) { queryCache[key] }
        val (tag, vector) = cached ?: runCatching { embeddings.embed(listOf(key), query = true) }.getOrNull()
            ?.let { (tag, list) -> tag to list.first() }?.also { synchronized(queryCache) { queryCache[key] = it } } ?: return null
        val stored = store.vectors(tag, memories)
        if (stored.size < memories.size) refresh()
        return stored.mapValues { cosine(vector, it.value) }
    }
}
