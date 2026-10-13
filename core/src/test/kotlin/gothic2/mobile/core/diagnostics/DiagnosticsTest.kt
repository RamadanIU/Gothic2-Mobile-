package gothic2.mobile.core.diagnostics

import gothic2.mobile.core.bridge.BoundedLineReader
import gothic2.mobile.core.config.ViewportFit
import gothic2.mobile.core.input.RecordingSink
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DiagnosticsTest {
    @Test
    fun `latency percentiles over sliding window`() {
        val s = LatencyStats(capacity = 100)
        for (i in 1..200) s.record(i.toLong())
        val sum = s.summary()!!
        assertEquals(100, sum.count)
        assertEquals(150, sum.p50)
        assertEquals(195, sum.p95)
        assertEquals(200, sum.max)
        assertEquals(200, s.total)
    }

    @Test
    fun `timed sink measures only input-driven events`() {
        var now = 100L
        val sink = TimedInputSink(RecordingSink(), clock = { now })
        sink.beginEvent(90)
        sink.keyDown(1)
        sink.mouseMove(1, 1)
        sink.endEvent()
        sink.keyUp(1) // от таймера арбитра — не учитывается
        assertEquals(1, sink.keys.summary()!!.count)
        assertEquals(10, sink.keys.summary()!!.p50)
        assertEquals(1, sink.mouse.summary()!!.count)
    }

    @Test
    fun `session journal detects unclean exit once`() {
        var now = 1_000L
        val j = SessionJournal(Files.createTempDirectory("g2j").resolve("session.marker"), clock = { now })
        assertNull(j.takeInterrupted())
        j.begin()
        now = 5_000
        j.heartbeat()
        val i = assertNotNull(SessionJournal(Files.createTempDirectory("x").resolve("m")).takeInterrupted() ?: j.takeInterrupted())
        assertEquals(1_000, i.startedAt)
        assertEquals(5_000, i.lastHeartbeat)
        assertNull(j.takeInterrupted(), "отметка сбрасывается")
        j.begin()
        j.end()
        assertNull(j.takeInterrupted())
    }

    @Test
    fun `viewport fit pillarboxes 4-3 on wide phone and letterboxes on tall`() {
        val wide = ViewportFit.fit(2400, 1080, 800, 600)
        assertEquals(1440, wide.game.width)
        assertEquals(480, wide.leftBar)
        assertEquals(480, wide.rightBar)
        val exact = ViewportFit.fit(1920, 1080, 960, 540)
        assertEquals(0, exact.leftBar)
        assertEquals(1920, exact.game.width)
        val tall = ViewportFit.fit(1000, 1000, 1280, 720)
        assertEquals(563, tall.game.height)
        assertEquals(tall.topBar + tall.bottomBar + tall.game.height, 1000)
    }

    @Test
    fun `bounded line reader splits lines and rejects oversized`() {
        val r = BoundedLineReader(ByteArrayInputStream("a\r\nbc\nlast".toByteArray()), maxBytes = 8)
        assertEquals("a", r.readLine())
        assertEquals("bc", r.readLine())
        assertEquals("last", r.readLine())
        assertNull(r.readLine())
        val big = BoundedLineReader(ByteArrayInputStream("x".repeat(20).toByteArray()), maxBytes = 8)
        assertFailsWith<BoundedLineReader.LineTooLongException> { big.readLine() }
    }
}
