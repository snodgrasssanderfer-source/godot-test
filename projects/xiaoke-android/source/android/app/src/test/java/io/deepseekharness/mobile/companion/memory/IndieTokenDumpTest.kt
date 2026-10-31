package io.deepseekharness.mobile.companion.memory

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * Token ids for the independent memory set, cut by the phone's own WordPiece (queries with its retrieval prefix), for
 * src/test/memory-indie/embed.py to turn into cosine scores with the bundled ONNX model.
 */
class IndieTokenDumpTest {
    @Test fun dump() {
        val vocab = File("src/main/assets/embedding/vocab.txt").takeIf { it.isFile } ?: return // not checked out on every machine
        val set = JSONObject(File("src/test/resources/memory-indie/indie_memory.json").readText())
        val wp = WordPiece(vocab.readLines())
        fun ids(text: String) = JSONArray(wp.encode(text, 256).toList())
        val m = set.getJSONArray("memories"); val q = set.getJSONArray("queries")
        File("build/indie-tokens.json").writeText(JSONObject()
            .put("memories", JSONObject().also { o -> for (i in 0 until m.length()) m.getJSONObject(i).let { o.put(it.getString("id"), ids(it.getString("text"))) } })
            .put("queries", JSONObject().also { o -> for (i in 0 until q.length()) q.getJSONObject(i).let { o.put(it.getString("id"), ids(LocalEmbedder.QUERY_PREFIX + it.getString("query"))) } })
            .toString())
    }
}
