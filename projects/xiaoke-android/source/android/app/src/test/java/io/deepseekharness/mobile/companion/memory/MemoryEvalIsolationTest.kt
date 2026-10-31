package io.deepseekharness.mobile.companion.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.MemoryRanking
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryEvalIsolationTest {
    @Test fun `评测集私聊和私聊衍生记录不会进入外部召回候选`() {
        val corpus = JSONObject(javaClass.getResource("/memory-eval/zh_companion_memory.json")!!.readText())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "memory-eval-isolation.db"
        context.deleteDatabase(name)
        CompanionStore(context, name).use { store ->
            for (kind in listOf("message", "memory")) {
                val rows = corpus.getJSONArray(if (kind == "message") "messages" else "memories")
                for (i in 0 until rows.length()) store.put(kind, rows.getJSONObject(i))
            }
            val queries = corpus.getJSONArray("queries")
            var checked = 0
            for (i in 0 until queries.length()) {
                val query = queries.getJSONObject(i)
                val forbidden = query.getJSONArray("forbiddenIds")
                if (query.getString("scope") == CompanionPolicy.OWNER || forbidden.length() == 0) continue
                val candidates = store.memoriesFor(query.getString("scope"), query.getString("text"), 2000)
                val records = (0 until candidates.length()).map(candidates::getJSONObject)
                val blocked = (0 until forbidden.length()).map(forbidden::getString).toSet()
                assertTrue(query.getString("id"), records.none { it.getString("id") in blocked })
                val hits = MemoryRanking.relevant(records, query.getString("text"), semantic = records.associate { it.getString("id") to 1.0 })
                assertTrue(query.getString("id"), hits.none { it.getString("id") in blocked })
                checked++
            }
            assertTrue("评测集必须含外部请求私聊记忆的案例", checked > 0)
        }
        context.deleteDatabase(name)
    }
}
