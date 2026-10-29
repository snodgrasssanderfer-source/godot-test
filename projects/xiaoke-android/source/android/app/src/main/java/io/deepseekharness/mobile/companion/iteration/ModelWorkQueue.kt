package io.deepseekharness.mobile.companion.iteration

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** One provider lease, FIFO within a lane. Owner work goes ahead of queued activity. */
class ModelWorkQueue : AutoCloseable {
    private val sequence = AtomicLong()
    private val queued = ConcurrentHashMap<String, Work>()
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(), { task -> Thread(task, "companion-model").apply { isDaemon = true } })

    private inner class Work(val id: String, val owner: Boolean, val order: Long, val body: () -> Unit) : Runnable, Comparable<Work> {
        override fun compareTo(other: Work): Int = compareValues(if (owner) 0 else 1, if (other.owner) 0 else 1)
            .takeIf { it != 0 } ?: order.compareTo(other.order)
        override fun run() {
            if (queued.remove(id, this)) body()
        }
    }

    fun submit(id: String, owner: Boolean, body: () -> Unit) {
        val work = Work(id, owner, sequence.getAndIncrement(), body)
        check(queued.putIfAbsent(id, work) == null) { "模型任务已在队列中" }
        try { executor.execute(work) }
        catch (e: RuntimeException) { queued.remove(id, work); throw e }
    }

    /** True means it never reached the provider; false requires checking the active lease. */
    fun cancelQueued(id: String): Boolean {
        val work = queued.remove(id) ?: return false
        executor.remove(work)
        return true
    }
    fun pending() = queued.size
    fun hasOwnerWaiting() = queued.values.any { it.owner }
    override fun close() { queued.clear(); executor.shutdownNow() }
}
