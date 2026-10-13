package gothic2.mobile.core.input

import gothic2.mobile.core.input.RecordingSink.Event.Down
import gothic2.mobile.core.input.RecordingSink.Event.Up
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyStateTrackerTest {
    private val sink = RecordingSink()
    private val tracker = KeyStateTracker(sink)

    @Test
    fun `release by one source keeps key held by another`() {
        tracker.set("a", setOf(10))
        tracker.set("b", setOf(10))
        tracker.release("a")
        assertTrue(tracker.isDown(10))
        assertEquals(listOf<RecordingSink.Event>(Down(10)), sink.events)
        tracker.release("b")
        assertEquals(listOf<RecordingSink.Event>(Down(10), Up(10)), sink.events)
    }

    @Test
    fun `releases are emitted before presses in a batch`() {
        tracker.set("move", setOf(0xC8))
        sink.clear()
        tracker.apply(mapOf("move" to emptySet(), "combat" to setOf(0x1D)))
        assertEquals(listOf<RecordingSink.Event>(Up(0xC8), Down(0x1D)), sink.events)
    }

    @Test
    fun `moving key between sources does not flicker`() {
        tracker.set("a", setOf(5))
        sink.clear()
        tracker.apply(mapOf("a" to emptySet(), "b" to setOf(5)))
        assertTrue(sink.events.isEmpty())
        assertTrue(tracker.isDown(5))
    }

    @Test
    fun `releaseAll lifts everything once`() {
        tracker.set("a", setOf(1, 2))
        tracker.set("b", setOf(2, 3))
        sink.clear()
        tracker.releaseAll()
        assertEquals(listOf<RecordingSink.Event>(Up(1), Up(2), Up(3)), sink.events)
        assertTrue(tracker.downKeys().isEmpty())
    }
}
