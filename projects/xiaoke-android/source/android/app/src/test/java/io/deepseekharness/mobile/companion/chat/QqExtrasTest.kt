package io.deepseekharness.mobile.companion.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class QqExtrasTest {
    @Test fun `过门槛回复，差一点点个表情，差得远不理`() {
        assertEquals("reply", QqExtras.tier(0.9, 0.7, trigger = true, roll = 0.99))
        assertEquals("react", QqExtras.tier(0.5, 0.7, trigger = false, roll = 0.1))
        assertEquals("ignore", QqExtras.tier(0.5, 0.7, trigger = false, roll = 0.9)) // not every near miss gets a reaction
        assertEquals("ignore", QqExtras.tier(0.3, 0.7, trigger = false, roll = 0.0))
    }

    @Test fun `表情跟着内容走`() {
        assertEquals(QqExtras.LAUGH, QqExtras.reaction("哈哈哈哈笑死", false))
        assertEquals(QqExtras.PARTY, QqExtras.reaction("十连出金了！", false))
        assertEquals(QqExtras.CRY, QqExtras.reaction("今天又加班，好累", false))
        assertEquals(QqExtras.THINK, QqExtras.reaction("这个怎么打", false))
        assertEquals(QqExtras.EYES, QqExtras.reaction("", true))
        assertEquals(QqExtras.THUMBS_UP, QqExtras.reaction("刚把房间收拾完", false))
    }
}
