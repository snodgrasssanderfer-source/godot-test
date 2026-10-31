package io.deepseekharness.mobile.companion.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.deepseekharness.mobile.companion.CompanionPolicy
import io.deepseekharness.mobile.companion.CompanionStore
import io.deepseekharness.mobile.companion.MemoryRanking
import io.deepseekharness.mobile.companion.life.LifeMemoryRepository
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class MemoryRepositoryEvolutionTest {
    private lateinit var store: CompanionStore
    private lateinit var repo: LifeMemoryRepository
    @Before fun open() {
        store = CompanionStore(ApplicationProvider.getApplicationContext<Context>(), "memory-evolution-${System.nanoTime()}.db")
        repo = LifeMemoryRepository(store)
    }
    @After fun close() { store.close() }
    private fun save(id: String, holder: String, at: Long) = repo.upsertMemory(CompanionPolicy.OWNER, JSONObject()
        .put("id", id).put("text", "虚构人物对作品结尾的评价 $id").put("kind", "opinion").put("holder", holder)
        .put("factKey", "paper-star-ending").put("occurredAt", at).put("validFrom", at))

    @Test fun `同一话题的主人和她各有当前观点`() {
        save("owner", "owner", 1000); save("her", "her", 2000)
        assertFalse(store.get("memory", "owner")!!.getBoolean("superseded"))
        assertFalse(store.get("memory", "her")!!.getBoolean("superseded"))
    }

    @Test fun `补录中间观点能重建连续演化时间段`() {
        save("first", "her", 1000); save("last", "her", 3000); save("middle", "her", 2000)
        val first = store.get("memory", "first")!!; val middle = store.get("memory", "middle")!!
        assertEquals(2000L, first.getLong("validUntil")); assertEquals("middle", first.getString("endedBy"))
        assertEquals(3000L, middle.getLong("validUntil")); assertEquals("last", middle.getString("endedBy"))
        assertFalse(store.get("memory", "last")!!.getBoolean("superseded"))
    }

    @Test fun `未来才生效的变更不会提前使旧观点过期`() {
        val now = System.currentTimeMillis()
        save("current", "her", 1000)
        repo.upsertMemory(CompanionPolicy.OWNER, JSONObject().put("id", "future").put("text", "明天开始改变看法")
            .put("kind", "opinion").put("holder", "her").put("factKey", "paper-star-ending")
            .put("occurredAt", now).put("validFrom", now + 86_400_000))
        val current = store.get("memory", "current")!!
        assertEquals(now + 86_400_000, current.getLong("validUntil"))
        assertFalse(MemoryRanking.past(current, now))
        assertTrue(MemoryRanking.past(current, now + 86_400_001))
    }

    @Test fun `存入没有持有人的观点时不补造归属`() {
        val saved = repo.upsertMemory(CompanionPolicy.OWNER, JSONObject().put("text", "有人觉得配乐比剧情好").put("kind", "opinion"))
        assertEquals("", saved.getString("holder"))
    }
}
