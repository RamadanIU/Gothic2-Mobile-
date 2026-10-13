package gothic2.mobile.core.input

import gothic2.mobile.core.input.RecordingSink.Event
import gothic2.mobile.core.input.RecordingSink.Event.Down
import gothic2.mobile.core.input.RecordingSink.Event.Up
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InputArbiterTest {
    private var now = 1_000L
    private val sink = RecordingSink()
    private val timing = CombatTiming()
    private val arbiter = InputArbiter(sink, timing = timing, clock = { now })
    private val b = KeyBindings.GOTHIC2_DEFAULT

    private fun s(k: GameKey) = b.scan(k)

    private fun advance(ms: Long) {
        val end = now + ms
        while (true) {
            val d = arbiter.nextDeadline()
            if (d == null || d > end) break
            now = d
            arbiter.tick()
        }
        now = end
        arbiter.tick()
    }

    @Test
    fun `joystick forward holds forward key`() {
        arbiter.onJoystick(0f, 1f)
        assertEquals(listOf<Event>(Down(s(GameKey.FORWARD))), sink.events)
        arbiter.onJoystickReleased()
        assertEquals(Up(s(GameKey.FORWARD)), sink.events.last())
    }

    @Test
    fun `attack outside combat mode is rejected`() {
        assertFalse(arbiter.requestAttack(AttackDirection.FORWARD))
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `forward attack suspends movement and runs phases in order`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.onJoystick(0f, 1f)
        sink.clear()

        assertTrue(arbiter.requestAttack(AttackDirection.FORWARD))
        // Сначала движение снимается, «действие» ещё не нажато.
        assertEquals(listOf<Event>(Up(s(GameKey.FORWARD))), sink.events)

        advance(timing.movementReleaseMs)
        assertEquals(Down(s(GameKey.ACTION)), sink.events.last())

        advance(timing.actionLeadMs)
        assertEquals(Down(s(GameKey.FORWARD)), sink.events.last())
        assertTrue(arbiter.downScanCodes() == setOf(s(GameKey.ACTION), s(GameKey.FORWARD)))

        advance(timing.strikeHoldMs)
        assertTrue(arbiter.downScanCodes().isEmpty(), "удар отпущен полностью")

        advance(timing.recoverMs)
        // Палец всё ещё на джойстике — движение возвращается.
        assertEquals(setOf(s(GameKey.FORWARD)), arbiter.downScanCodes())
        assertFalse(arbiter.isAttacking())
    }

    @Test
    fun `side attack uses turn key, not strafe`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.requestAttack(AttackDirection.LEFT)
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.TURN_LEFT)), arbiter.downScanCodes())
    }

    @Test
    fun `attacks are not queued`() {
        arbiter.setMode(ControlMode.COMBAT)
        assertTrue(arbiter.requestAttack(AttackDirection.FORWARD))
        assertFalse(arbiter.requestAttack(AttackDirection.RIGHT))
        advance(timing.attackTotalMs + 500)
        val presses = sink.events.count { it == Down(s(GameKey.ACTION)) }
        assertEquals(1, presses, "один ввод — одно действие")
        assertNull(arbiter.nextDeadline())
    }

    @Test
    fun `block holds action and back until released and never sticks`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.onJoystick(0f, 1f)
        arbiter.setBlock(true)
        advance(timing.movementReleaseMs + timing.actionLeadMs + 5_000)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.BACK)), arbiter.downScanCodes())
        arbiter.setBlock(false)
        assertTrue(arbiter.downScanCodes().isEmpty())
        advance(timing.recoverMs)
        assertEquals(setOf(s(GameKey.FORWARD)), arbiter.downScanCodes())
    }

    @Test
    fun `block preempts running attack`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.requestAttack(AttackDirection.FORWARD)
        advance(timing.movementReleaseMs + timing.actionLeadMs + 10)
        arbiter.setBlock(true)
        assertFalse(arbiter.isAttacking())
        assertFalse(s(GameKey.FORWARD) in arbiter.downScanCodes())
    }

    @Test
    fun `mode change keeps joystick but drops button holds`() {
        arbiter.onJoystick(0f, 1f)
        arbiter.onHoldStart("p1", GameKey.JUMP)
        arbiter.setMode(ControlMode.COMBAT)
        assertEquals(setOf(s(GameKey.FORWARD)), arbiter.downScanCodes())
    }

    @Test
    fun `menu mode disables movement and camera`() {
        arbiter.onJoystick(0f, 1f)
        arbiter.setMode(ControlMode.DIALOG)
        assertTrue(arbiter.downScanCodes().isEmpty())
        sink.clear()
        arbiter.onCameraDelta(50f, 0f)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `tap is held for minimum duration`() {
        arbiter.setMode(ControlMode.MENU)
        arbiter.tap(GameKey.MENU_DOWN)
        assertTrue(s(GameKey.MENU_DOWN) in arbiter.downScanCodes())
        advance(timing.tapHoldMs - 1)
        assertTrue(s(GameKey.MENU_DOWN) in arbiter.downScanCodes())
        advance(1)
        assertTrue(arbiter.downScanCodes().isEmpty())
    }

    @Test
    fun `context action is ignored in combat so forward does not attack`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.setContextAction(true)
        arbiter.onJoystick(0f, 1f)
        assertEquals(setOf(s(GameKey.FORWARD)), arbiter.downScanCodes())
    }

    @Test
    fun `context action in world holds action with forward`() {
        arbiter.setContextAction(true)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.FORWARD)), arbiter.downScanCodes())
        arbiter.setContextAction(false)
        assertTrue(arbiter.downScanCodes().isEmpty())
    }

    @Test
    fun `releaseAll clears everything including pending attack`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.onJoystick(1f, 1f)
        arbiter.onHoldStart("x", GameKey.JUMP)
        arbiter.requestAttack(AttackDirection.FORWARD)
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        arbiter.releaseAll()
        assertTrue(arbiter.downScanCodes().isEmpty())
        advance(1_000)
        assertTrue(arbiter.downScanCodes().isEmpty(), "после сброса ничего не нажимается само")
        val downs = sink.events.filterIsInstance<Down>().map { it.scan }
        val ups = sink.events.filterIsInstance<Up>().map { it.scan }
        assertEquals(downs.sorted(), ups.sorted(), "каждое нажатие отпущено")
    }

    @Test
    fun `camera coalesces deltas and keeps fractional remainder`() {
        arbiter.camera.sensitivity = 0.5f
        arbiter.onCameraDelta(1f, 0f)
        arbiter.onCameraDelta(1f, 0f)
        arbiter.onCameraDelta(1f, 0f)
        val total = sink.events.filterIsInstance<Event.Mouse>().sumOf { it.dx }
        assertTrue(total in 1..2)
    }

    @Test
    fun `camera does not break target lock`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.setTargetLocked(true)
        arbiter.onCameraDelta(100f, 0f)
        assertTrue(sink.events.none { it is Event.Mouse })
    }
}
