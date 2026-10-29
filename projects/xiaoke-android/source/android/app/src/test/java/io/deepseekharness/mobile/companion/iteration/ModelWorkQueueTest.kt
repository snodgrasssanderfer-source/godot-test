package io.deepseekharness.mobile.companion.iteration

import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelWorkQueueTest {
    @Test fun ownerGoesAheadOfPendingActivityAndQueuedCancellationDoesNotRun() {
        val queue = ModelWorkQueue()
        val started = CountDownLatch(1); val release = CountDownLatch(1); val done = CountDownLatch(3)
        val order = Collections.synchronizedList(mutableListOf<String>())
        try {
            queue.submit("active", false) { started.countDown(); release.await(3, TimeUnit.SECONDS); order.add("active"); done.countDown() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            queue.submit("background", false) { order.add("background"); done.countDown() }
            queue.submit("cancelled", true) { order.add("cancelled") }
            queue.submit("owner", true) { order.add("owner"); done.countDown() }
            assertTrue(queue.cancelQueued("cancelled"))
            release.countDown(); assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals(listOf("active", "owner", "background"), order.toList())
            assertFalse(queue.cancelQueued("active"))
        } finally { release.countDown(); queue.close() }
    }
}
