package io.deepseekharness.mobile.companion.devicev2

import java.util.UUID

/** Process-local authority. Reboot/process death always revokes control. No observation/chat state here. */
class InputFence(private val now: () -> Long) {
    data class Lease(val sessionId: String, val epoch: Long, val packageName: String, val expiresAtMs: Long)
    data class Receipt(val requestId: String, val digest: String, val lease: Lease, var state: String = "queued", var detail: String = "")
    private var epoch = 0L
    private var lease: Lease? = null
    private var reason = "not_authorized"
    private var active: String? = null
    private val receipts = linkedMapOf<String, Receipt>()

    @Synchronized fun start(packageName: String, seconds: Int, expectedEpoch: Long? = null): Lease {
        check(expectedEpoch == null || expectedEpoch == epoch) { "授权界面已过期，请重新确认恢复" }
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        require(seconds in 10..300)
        check(active == null) { "上一个触点尚未释放" }
        stop("new_authorization")
        return Lease(UUID.randomUUID().toString(), ++epoch, packageName, now() + seconds * 1000L)
            .also { lease = it; reason = ""; receipts.clear() }
    }
    @Synchronized fun current(): Lease? {
        if (lease?.let { now() >= it.expiresAtMs } == true) stop("expired")
        return lease
    }
    @Synchronized fun stop(cause: String): Long {
        epoch++; lease = null
        reason = cause
        receipts.values.filter { it.state == "queued" || it.state == "dispatched" }.forEach { it.state = "stopped"; it.detail = cause }
        return epoch
    }
    @Synchronized fun enqueue(id: String, digest: String, sessionId: String, inputEpoch: Long): Receipt {
        require(id.length in 1..128)
        receipts[id]?.let { check(it.digest == digest) { "同一请求编号不能更改动作" }; return it.copy() }
        val token = current() ?: error("输入已停止，请由主人重新授权")
        check(token.sessionId == sessionId && token.epoch == inputEpoch) { "旧控制授权已失效" }
        check(active == null) { "上一步仍在执行或释放触点" }
        check(receipts.size < 256) { "本次动作已达上限，请重新授权" }
        return Receipt(id, digest, token).also { receipts[id] = it; active = id }.copy()
    }
    /** The native submission is non-blocking; stop and dispatch share this short critical section. */
    @Synchronized fun dispatch(id: String, submit: () -> Boolean): Boolean {
        val item = receipts[id] ?: return false
        if (!isLive(item.lease) || active != id || item.state !in setOf("queued", "dispatched")) return false
        if (!submit()) { item.state = "refused"; item.detail = "系统拒绝手势"; return false }
        item.state = "dispatched"
        return true
    }
    @Synchronized fun isLive(token: Lease): Boolean = current() == token
    @Synchronized fun finish(id: String, state: String, detail: String = ""): Receipt? {
        val item = receipts[id] ?: return null
        if (item.state != "stopped" || state == "outcome_unknown") { item.state = state; item.detail = detail }
        if (active == id && state != "outcome_unknown") active = null
        return item.copy()
    }
    @Synchronized fun receipt(id: String) = receipts[id]?.copy()
    @Synchronized fun status(): Map<String, Any> {
        val token = current()
        return mapOf("state" to if (token == null) "stopped" else "authorized", "inputEpoch" to epoch,
            "sessionId" to (token?.sessionId ?: ""), "packageName" to (token?.packageName ?: ""),
            "expiresAtMs" to (token?.expiresAtMs ?: 0), "reason" to reason,
            "releasePending" to (active != null), "activeRequestId" to (active ?: ""))
    }
}

/** A stationary five-second hold must fire without any MOVE event. UI supplies a timer. */
class FiveSecondHold(private val now: () -> Long, private val slop: Float) {
    private var startedAt: Long? = null
    private var x = 0f; private var y = 0f
    private var fired = false
    fun down(x: Float, y: Float) { this.x = x; this.y = y; startedAt = now(); fired = false }
    fun move(x: Float, y: Float, pointers: Int) {
        if (pointers != 1 || kotlin.math.abs(x - this.x) > slop || kotlin.math.abs(y - this.y) > slop) cancel()
    }
    fun cancel() { startedAt = null }
    fun fire(): Boolean {
        val began = startedAt ?: return false
        if (fired || now() - began < 5_000L) return false
        fired = true; startedAt = null; return true
    }
}

/** Partition a bounded gesture without a long remainder segment delaying an emergency release. */
internal fun gestureSegments(durationMs: Long): List<Long> {
    require(durationMs in 1..1500)
    val count = ((durationMs + 79) / 80).toInt()
    return (0 until count).map { index -> durationMs * (index + 1) / count - durationMs * index / count }
}
