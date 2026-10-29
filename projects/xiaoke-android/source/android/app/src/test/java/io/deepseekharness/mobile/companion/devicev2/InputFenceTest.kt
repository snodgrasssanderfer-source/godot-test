package io.deepseekharness.mobile.companion.devicev2

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InputFenceTest {
    @Test fun `停止撤销旧授权且不能跨会话复活队列`() {
        var now = 100L
        val fence = InputFence { now }
        val first = fence.start("example.game", 30)
        fence.enqueue("tap-1", "point", first.sessionId, first.epoch)
        fence.stop("owner_stop")
        assertFalse(fence.dispatch("tap-1") { fail("停止后不应触达设备"); true })
        assertTrue(fence.status()["releasePending"] as Boolean)
        assertThrows(IllegalStateException::class.java) { fence.start("example.game", 30) }
        fence.finish("tap-1", "cancelled")
        val second = fence.start("example.game", 30)
        assertNotEquals(first.sessionId, second.sessionId)
        assertThrows(IllegalStateException::class.java) { fence.enqueue("late", "point", first.sessionId, first.epoch) }
        now += 30001
        assertNull(fence.current())
        assertEquals("expired", fence.status()["reason"])
    }
    @Test fun `重复点击返回同一收据且修改载荷被拒绝`() {
        val fence = InputFence { 0L }; val lease = fence.start("example.game", 30)
        val dispatched = AtomicInteger()
        fence.enqueue("one", "digest", lease.sessionId, lease.epoch)
        assertTrue(fence.dispatch("one") { dispatched.incrementAndGet(); true })
        fence.finish("one", "completed")
        assertEquals("completed", fence.enqueue("one", "digest", lease.sessionId, lease.epoch).state)
        assertFalse(fence.dispatch("one") { dispatched.incrementAndGet(); true })
        assertEquals(1, dispatched.get())
        assertThrows(IllegalStateException::class.java) { fence.enqueue("one", "changed", lease.sessionId, lease.epoch) }
    }
    @Test fun `派发与急停并发后旧代际无法继续下一段`() {
        val fence = InputFence { 0L }; val lease = fence.start("example.game", 30)
        fence.enqueue("one", "digest", lease.sessionId, lease.epoch)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val stopped = CountDownLatch(1)
        val dispatchThread = Thread { fence.dispatch("one") { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)); true } }
        dispatchThread.start(); assertTrue(entered.await(2, TimeUnit.SECONDS))
        val stopThread = Thread { fence.stop("emergency"); stopped.countDown() }; stopThread.start()
        release.countDown(); assertTrue(stopped.await(2, TimeUnit.SECONDS)); dispatchThread.join(); stopThread.join()
        assertFalse(fence.dispatch("one") { fail("旧段不得继续"); true })
        fence.finish("one", "completed")
        assertEquals("stopped", fence.receipt("one")!!.state)
    }
    @Test fun `系统未确认释放时不能重新获得控制`() {
        val fence = InputFence { 0L }; val lease = fence.start("example.game", 30)
        fence.enqueue("one", "digest", lease.sessionId, lease.epoch); fence.stop("timeout")
        fence.finish("one", "outcome_unknown")
        assertTrue(fence.status()["releasePending"] as Boolean)
        assertThrows(IllegalStateException::class.java) { fence.start("example.game", 30) }
    }
    @Test fun `浮球静止计满五秒且取消拖动多指不误触发`() {
        var now = 0L; val hold = FiveSecondHold({ now }, 8f)
        hold.down(10f, 10f); now = 4999; assertFalse(hold.fire()); now = 5000; assertTrue(hold.fire()); assertFalse(hold.fire())
        hold.down(10f, 10f); hold.move(20f, 10f, 1); now += 5000; assertFalse(hold.fire())
        hold.down(10f, 10f); hold.move(10f, 10f, 2); now += 5000; assertFalse(hold.fire())
        hold.down(10f, 10f); hold.cancel(); now += 5000; assertFalse(hold.fire())
    }
}
