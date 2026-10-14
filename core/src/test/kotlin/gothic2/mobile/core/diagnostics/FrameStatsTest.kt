package gothic2.mobile.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameStatsTest {
    @Test
    fun `counts hitches and pauses separately from smooth frames`() {
        val s = FrameStats(hitchMs = 100, pauseMs = 1000)
        var t = 0L
        s.onFrame(1, t)
        repeat(98) { t += 16; s.onFrame(1, t) }
        t += 250; s.onFrame(1, t) // фриз
        t += 5000; s.onFrame(1, t) // загрузка — пауза, не фриз
        t += 16; s.onFrame(1, t)
        val sum = s.summaryAndReset()!!
        assertEquals(100, sum.frames)
        assertEquals(1, sum.hitches)
        assertEquals(1, sum.pauses)
        assertEquals(16, sum.p50)
        assertEquals(250, sum.max)
        assertEquals(100 * 1000.0 / (99 * 16 + 250), sum.fps, 0.001)
    }

    @Test
    fun `picks the busiest window and resets between periods`() {
        val s = FrameStats()
        for (i in 0..10) s.onFrame(7, i * 20L)
        s.onFrame(3, 0)
        s.onFrame(3, 500)
        val sum = s.summaryAndReset()!!
        assertEquals(10, sum.frames)
        assertEquals(20, sum.max)
        // Следующий период начинается с последнего кадра, а не с нуля.
        s.onFrame(7, 230)
        assertEquals(30, s.summaryAndReset()!!.max)
        assertNull(s.summaryAndReset())
    }
}
