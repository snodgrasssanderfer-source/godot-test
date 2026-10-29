package io.deepseekharness.mobile.companion.devicev2

import io.deepseekharness.mobile.shizuku.GestureTimeline
import io.deepseekharness.mobile.shizuku.GestureTimeline.Kind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class GameControlTest {
    private val screen = intArrayOf(0, 0, 1080, 2400)

    private fun timeline(kind: String, data: JSONObject, maxMs: Long = 10_000) =
        GestureTimeline.events(GestureTimeline.parse(GameInputController.plan(kind, data, screen, maxMs)))

    @Test fun `点击是一次按下一次抬起`() {
        val events = timeline("tap", JSONObject().put("x", 100).put("y", 200))
        assertEquals(listOf(Kind.DOWN, Kind.UP), events.map { it.kind })
        assertEquals(70L, events.last().atMs)
    }

    @Test fun `摇杆按住拖到位后停住直到抬起`() {
        val events = timeline("hold", JSONObject().put("x", 200).put("y", 1800).put("toX", 300).put("toY", 1700).put("moveMs", 160).put("durationMs", 3000))
        assertEquals(Kind.DOWN, events.first().kind)
        assertEquals(Kind.UP, events.last().kind)
        assertEquals(3000L, events.last().atMs)
        val moves = events.filter { it.kind == Kind.MOVE }
        assertTrue(moves.isNotEmpty() && moves.all { it.atMs <= 160 })
        assertEquals(300f, events.last().pointers.single().x)
    }

    @Test fun `两指操作按顺序产生多指事件`() {
        val pointers = JSONArray()
            .put(JSONObject().put("x", 200).put("y", 1800).put("toX", 260).put("toY", 1800).put("durationMs", 1000))
            .put(JSONObject().put("x", 900).put("y", 1800).put("startMs", 300).put("durationMs", 200))
        val events = timeline("multi", JSONObject().put("pointers", pointers))
        val kinds = events.map { it.kind }
        assertEquals(Kind.DOWN, kinds.first())
        assertEquals(1, kinds.count { it == Kind.POINTER_DOWN })
        assertEquals(1, kinds.count { it == Kind.POINTER_UP })
        assertEquals(Kind.UP, kinds.last())
        val down = events.first { it.kind == Kind.POINTER_DOWN }
        assertEquals(300L, down.atMs)
        assertEquals(2, down.pointers.size)
        assertEquals(1, down.pointers[down.actionIndex].id)
        val up = events.first { it.kind == Kind.POINTER_UP }
        assertEquals(500L, up.atMs)
        assertEquals(1, up.pointers[up.actionIndex].id)
        // Every event while two fingers are down carries both.
        assertTrue(events.filter { it.atMs in 301..499 && it.kind == Kind.MOVE }.all { it.pointers.size == 2 })
    }

    @Test fun `坐标出界和超长按住被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { GameInputController.plan("tap", JSONObject().put("x", 1080).put("y", 10), screen, 10_000) }
        assertThrows(IllegalArgumentException::class.java) { GameInputController.plan("hold", JSONObject().put("x", 10).put("y", 10).put("durationMs", 5000), screen, 1500) }
        val six = JSONArray().apply { repeat(6) { put(JSONObject().put("x", 10).put("y", 10)) } }
        assertThrows(IllegalArgumentException::class.java) { GameInputController.plan("multi", JSONObject().put("pointers", six), screen, 10_000) }
    }

    private fun noise(w: Int, h: Int, seed: Int): FrameMatcher.Gray {
        val r = Random(seed)
        // Smooth blobs rather than white noise, closer to a game screen.
        val coarse = IntArray((w / 8 + 2) * (h / 8 + 2)) { r.nextInt(256) }
        val cw = w / 8 + 2
        return FrameMatcher.Gray(w, h, IntArray(w * h) { i -> val x = i % w; val y = i / w; (coarse[(y / 8) * cw + x / 8] + r.nextInt(12)).coerceAtMost(255) })
    }

    @Test fun `模板能在画面里找回原位置`() {
        val frame = noise(320, 700, 7)
        for ((x, y, size) in listOf(Triple(40, 500, 48), Triple(250, 90, 24), Triple(5, 5, 10))) {
            val template = FrameMatcher.crop(frame, x, y, size, size)
            val match = FrameMatcher.find(frame, template)!!
            assertEquals(x, match.x); assertEquals(y, match.y)
            assertTrue(match.score > 0.95)
        }
        val elsewhere = FrameMatcher.crop(noise(320, 700, 99), 100, 100, 40, 40)
        assertTrue(FrameMatcher.find(frame, elsewhere)!!.score < FrameMatcher.FOUND)
    }

    @Test fun `同一画面指纹相近，换了画面指纹相远`() {
        val a = noise(320, 700, 1)
        val shaded = FrameMatcher.Gray(a.w, a.h, IntArray(a.px.size) { (a.px[it] * 0.9 + 8).toInt() })
        val other = noise(320, 700, 2)
        assertTrue(FrameMatcher.distance(FrameMatcher.fingerprint(a), FrameMatcher.fingerprint(shaded)) <= FrameMatcher.SAME_SCREEN)
        assertTrue(FrameMatcher.distance(FrameMatcher.fingerprint(a), FrameMatcher.fingerprint(other)) > FrameMatcher.SAME_SCREEN)
    }

    @Test fun `缩小后的匹配位置换算回屏幕坐标`() {
        val argb = IntArray(1280 * 720) { i -> val x = i % 1280; val y = i / 1280; val v = ((x / 40 + y / 40) % 2) * 200 + (x * 7 + y * 3) % 50; (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val gray = FrameMatcher.fromArgb(argb, 1280, 720, originX = 100, originY = 50)
        assertEquals(FrameMatcher.WIDTH, gray.w)
        assertEquals(4f, gray.scale)
        val (x, y) = FrameMatcher.screenCenter(gray, FrameMatcher.Match(10, 20, 1.0), FrameMatcher.Gray(8, 8, IntArray(64)))
        assertEquals(100 + 14 * 4, x); assertEquals(50 + 24 * 4, y)
    }
}
