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
    fun `leaving the world keeps joystick but drops button holds`() {
        arbiter.onJoystick(0f, 1f)
        arbiter.onHoldStart("p1", GameKey.JUMP)
        arbiter.setMode(ControlMode.DIALOG)
        // Джойстик в диалоге — стрелка «вверх» (та же клавиша, что «вперёд»), прыжок снят.
        assertEquals(setOf(s(GameKey.MENU_UP)), arbiter.downScanCodes())
    }

    @Test
    fun `drawing a weapon keeps the panel holds`() {
        arbiter.onHoldStart("p1", GameKey.JUMP)
        arbiter.setMode(ControlMode.COMBAT)
        assertEquals(setOf(s(GameKey.JUMP)), arbiter.downScanCodes())
    }

    @Test
    fun `navigation joystick holds one arrow and camera is off`() {
        arbiter.setMode(ControlMode.INVENTORY)
        arbiter.onJoystick(0.6f, 0.5f) // по диагонали — только преобладающая ось
        assertEquals(setOf(s(GameKey.MENU_RIGHT)), arbiter.downScanCodes())
        arbiter.onJoystick(0.5f, 0.55f) // чуть перевесила вертикаль — стрелка не прыгает
        assertEquals(setOf(s(GameKey.MENU_RIGHT)), arbiter.downScanCodes())
        arbiter.onJoystick(0.1f, -0.8f)
        assertEquals(setOf(s(GameKey.MENU_DOWN)), arbiter.downScanCodes())
        arbiter.onJoystickReleased()
        assertTrue(arbiter.downScanCodes().isEmpty())
        sink.clear()
        arbiter.onCameraDelta(50f, 0f)
        assertTrue(sink.events.isEmpty())
    }

    @Test
    fun `tap survives the mode change it causes`() {
        arbiter.onKeyTapped = { if (it == GameKey.INVENTORY) arbiter.setMode(ControlMode.INVENTORY) }
        arbiter.tap(GameKey.INVENTORY)
        assertEquals(ControlMode.INVENTORY, arbiter.mode)
        assertTrue(s(GameKey.INVENTORY) in arbiter.downScanCodes(), "Tab не обрывается сменой панели")
        advance(timing.tapHoldMs)
        assertTrue(arbiter.downScanCodes().isEmpty())
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
    fun `main button tap holds action then strikes forward on release`() {
        arbiter.onJoystick(0f, 1f)
        sink.clear()
        assertTrue(arbiter.strikeBegin())
        assertEquals(listOf<Event>(Up(s(GameKey.FORWARD))), sink.events, "движение снято")
        advance(timing.movementReleaseMs)
        assertEquals(setOf(s(GameKey.ACTION)), arbiter.downScanCodes())
        advance(30)
        arbiter.strikeEnd()
        // «Вперёд» не раньше, чем через actionLeadMs после «действия».
        assertEquals(setOf(s(GameKey.ACTION)), arbiter.downScanCodes())
        advance(timing.actionLeadMs - 30)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.FORWARD)), arbiter.downScanCodes())
        advance(timing.strikeHoldMs)
        assertTrue(arbiter.downScanCodes().isEmpty())
        advance(timing.recoverMs)
        assertEquals(setOf(s(GameKey.FORWARD)), arbiter.downScanCodes(), "джойстик снова ведёт")
        assertFalse(arbiter.isStriking())
        assertNull(arbiter.nextDeadline())
    }

    @Test
    fun `holding main button aims and release fires`() {
        arbiter.setMode(ControlMode.RANGED)
        arbiter.strikeBegin()
        advance(2_000)
        assertEquals(setOf(s(GameKey.ACTION)), arbiter.downScanCodes(), "прицел, пока палец держит")
        arbiter.strikeEnd()
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.FORWARD)), arbiter.downScanCodes(), "выстрел сразу по отпусканию")
        advance(timing.strikeHoldMs + timing.recoverMs)
        assertTrue(arbiter.downScanCodes().isEmpty())
    }

    @Test
    fun `swipes strike sideways once and down blocks while held`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.strikeBegin()
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        assertTrue(arbiter.strikeAim(SwipeDir.LEFT))
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.TURN_LEFT)), arbiter.downScanCodes())
        assertFalse(arbiter.strikeAim(SwipeDir.RIGHT), "второй удар во время первого — без очереди")
        advance(timing.strikeHoldMs)
        assertEquals(setOf(s(GameKey.ACTION)), arbiter.downScanCodes())
        // Серия одним пальцем: следующее направление после окончания удара.
        assertTrue(arbiter.strikeAim(SwipeDir.DOWN))
        assertTrue(arbiter.isBlocking())
        advance(3_000)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.BACK)), arbiter.downScanCodes())
        arbiter.strikeEnd()
        advance(timing.strikeHoldMs + timing.recoverMs)
        assertTrue(arbiter.downScanCodes().isEmpty())
        val forward = sink.events.count { it == Down(s(GameKey.FORWARD)) }
        assertEquals(0, forward, "после свайпов отпускание не бьёт вперёд")
    }

    @Test
    fun `very short swipe still holds the strike long enough`() {
        arbiter.strikeBegin()
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        arbiter.strikeAim(SwipeDir.UP)
        advance(10)
        arbiter.strikeAim(null)
        assertTrue(s(GameKey.FORWARD) in arbiter.downScanCodes())
        advance(timing.strikeHoldMs - 10)
        assertFalse(s(GameKey.FORWARD) in arbiter.downScanCodes())
    }

    @Test
    fun `interaction without forward when the build wants only action`() {
        arbiter.interactUsesForward = false
        arbiter.strikeBegin()
        arbiter.strikeEnd()
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        assertEquals(setOf(s(GameKey.ACTION)), arbiter.downScanCodes())
        advance(1_000)
        assertTrue(sink.events.none { it == Down(s(GameKey.FORWARD)) })
    }

    @Test
    fun `strike is rejected in menus and dropped when a menu opens`() {
        arbiter.setMode(ControlMode.MENU)
        assertFalse(arbiter.strikeBegin())
        arbiter.setMode(ControlMode.WORLD)
        assertTrue(arbiter.strikeBegin())
        advance(timing.movementReleaseMs)
        arbiter.setMode(ControlMode.DIALOG)
        assertTrue(arbiter.downScanCodes().isEmpty())
        assertFalse(arbiter.isStriking())
    }

    @Test
    fun `block button cancels the main button gesture`() {
        arbiter.setMode(ControlMode.COMBAT)
        arbiter.strikeBegin()
        advance(timing.movementReleaseMs)
        arbiter.setBlock(true)
        assertFalse(arbiter.isStriking())
        arbiter.strikeEnd() // палец с главной кнопки — уже ничего не делает
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        assertEquals(setOf(s(GameKey.ACTION), s(GameKey.BACK)), arbiter.downScanCodes())
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
