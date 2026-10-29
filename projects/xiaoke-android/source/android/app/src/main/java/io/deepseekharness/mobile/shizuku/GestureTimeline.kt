package io.deepseekharness.mobile.shizuku

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * A whole touch gesture (one or more fingers) flattened into the MotionEvent sequence Android expects:
 * DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP. Pure data so it can be checked without a device; the Shizuku
 * user service turns each [Event] into a real MotionEvent.
 *
 * Plan JSON: `{"displayId":0,"pointers":[{"path":[[x,y],[x,y,t]...],"startMs":0,"durationMs":80}]}`.
 * A point's optional `t` is ms from that finger's down; points without it are spread evenly over the stroke.
 * After the last point the finger stays put until `durationMs`, which is how a joystick is held.
 */
object GestureTimeline {
    const val MAX_POINTERS = 5
    const val MAX_TOTAL_MS = 30_000L
    const val MAX_POINTS = 64
    const val STEP_MS = 16L

    enum class Kind { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP }
    data class Pointer(val id: Int, val x: Float, val y: Float)
    data class Event(val atMs: Long, val kind: Kind, val actionIndex: Int, val pointers: List<Pointer>)
    data class Stroke(val points: List<FloatArray>, val startMs: Long, val durationMs: Long) {
        /** Position [ms] after this finger went down. */
        fun at(ms: Long): Pair<Float, Float> {
            val times = points.map { it[2] }
            if (ms <= times.first()) return points.first()[0] to points.first()[1]
            for (i in 1 until points.size) if (ms <= times[i]) {
                val a = points[i - 1]; val b = points[i]
                val f = if (b[2] <= a[2]) 1f else (ms - a[2]) / (b[2] - a[2])
                return a[0] + (b[0] - a[0]) * f to a[1] + (b[1] - a[1]) * f
            }
            return points.last()[0] to points.last()[1]
        }
        val endMs get() = startMs + durationMs
    }
    data class Plan(val displayId: Int, val strokes: List<Stroke>)

    fun parse(json: JSONObject): Plan {
        val displayId = json.optInt("displayId", 0)
        require(displayId >= 0) { "显示编号无效" }
        val list = json.getJSONArray("pointers")
        require(list.length() in 1..MAX_POINTERS) { "手指数量需在 1 到 $MAX_POINTERS 之间" }
        val strokes = (0 until list.length()).map { i ->
            val item = list.getJSONObject(i)
            val start = item.optLong("startMs", 0)
            val duration = item.getLong("durationMs")
            require(start >= 0 && duration in 1..MAX_TOTAL_MS && start + duration <= MAX_TOTAL_MS) { "手势时长超出范围" }
            val path = item.getJSONArray("path")
            require(path.length() in 1..MAX_POINTS) { "路径点数超出范围" }
            val raw = (0 until path.length()).map { path.getJSONArray(it) }
            val timed = raw.mapIndexed { index, p ->
                val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat()
                require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0) { "坐标无效" }
                val t = if (p.length() > 2) p.getDouble(2).toFloat()
                    else if (raw.size == 1) 0f else duration.toFloat() * index / (raw.size - 1)
                floatArrayOf(x, y, t.coerceIn(0f, duration.toFloat()))
            }
            for (k in 1 until timed.size) require(timed[k][2] >= timed[k - 1][2]) { "路径时间必须递增" }
            Stroke(timed, start, duration)
        }
        return Plan(displayId, strokes)
    }

    fun events(plan: Plan): List<Event> {
        val strokes = plan.strokes
        val end = strokes.maxOf { it.endMs }
        val times = sortedSetOf<Long>()
        var t = 0L
        while (t < end) { times.add(t); t += STEP_MS }
        strokes.forEach { times.add(it.startMs); times.add(it.endMs) }
        val active = sortedSetOf<Int>()
        val last = mutableMapOf<Int, Pair<Float, Float>>()
        val out = ArrayList<Event>()
        fun pointers(ids: Collection<Int>) = ids.sorted().map { id -> last.getValue(id).let { Pointer(id, round(it.first), round(it.second)) } }
        for (now in times) {
            // Moves first, so a finger lifting at `now` reports where it actually is.
            var moved = false
            for (id in active) {
                val s = strokes[id]
                val p = s.at(now - s.startMs)
                if (last[id] != p) { last[id] = p; moved = true }
            }
            if (moved) out.add(Event(now, Kind.MOVE, 0, pointers(active)))
            for (id in active.toList()) if (strokes[id].endMs == now) {
                val all = pointers(active)
                out.add(Event(now, if (active.size == 1) Kind.UP else Kind.POINTER_UP, all.indexOfFirst { it.id == id }, all))
                active.remove(id)
            }
            strokes.forEachIndexed { id, s ->
                if (s.startMs == now) {
                    last[id] = s.at(0)
                    val kind = if (active.isEmpty()) Kind.DOWN else Kind.POINTER_DOWN
                    active.add(id)
                    val all = pointers(active)
                    out.add(Event(now, kind, all.indexOfFirst { it.id == id }, all))
                }
            }
        }
        return out
    }

    private fun round(v: Float) = (v * 10).roundToInt() / 10f
}
