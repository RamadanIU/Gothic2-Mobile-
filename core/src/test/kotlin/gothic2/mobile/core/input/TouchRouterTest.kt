package gothic2.mobile.core.input

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TouchRouterTest {
    private var now = 0L
    private val sink = RecordingSink()
    private val arbiter = InputArbiter(sink, clock = { now })
    private val viewport = TouchRouter.Viewport(0f, 0f, 2200f, 1000f, density = 2.75f)
    private val uiCommands = mutableListOf<TouchCommand>()
    private val router = TouchRouter(arbiter, OverlayLayout.gothicDefault(), viewport) { uiCommands += it }
    private val b = KeyBindings.GOTHIC2_DEFAULT
    private val timing = CombatTiming()

    private fun px(spec: String): Pair<Float, Float> {
        val c = router.layout.controls.first { it.id == spec }
        return viewport.toPxX(c.cx) to viewport.toPxY(c.cy)
    }

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

    private fun downs() = sink.events.filterIsInstance<RecordingSink.Event.Down>().map { it.scan }

    @Test
    fun `joystick finger stays joystick when crossing into camera zone`() {
        router.onPointerDown(1, 300f, 700f)
        router.onPointerMove(1, 1500f, 700f)
        assertIs<TouchRouter.Owner.Joystick>(router.owners()[1])
        assertTrue(b.scan(GameKey.STRAFE_RIGHT) in arbiter.downScanCodes())
        assertTrue(sink.events.none { it is RecordingSink.Event.Mouse })
    }

    @Test
    fun `button finger does not turn into camera`() {
        val (x, y) = px("jump")
        router.onPointerDown(2, x, y)
        router.onPointerMove(2, x - 400f, y - 300f)
        assertTrue(sink.events.none { it is RecordingSink.Event.Mouse })
        assertTrue(b.scan(GameKey.JUMP) in arbiter.downScanCodes())
        router.onPointerUp(2)
        assertTrue(arbiter.downScanCodes().isEmpty())
    }

    @Test
    fun `move, camera and main button work simultaneously`() {
        router.onPointerDown(1, 300f, 700f)
        router.onPointerMove(1, 300f, 400f)
        router.onPointerDown(2, 1300f, 500f)
        router.onPointerMove(2, 1350f, 500f)
        val (x, y) = px("main")
        router.onPointerDown(3, x, y)
        advance(timing.movementReleaseMs)
        assertTrue(b.scan(GameKey.ACTION) in arbiter.downScanCodes())
        assertFalse(b.scan(GameKey.FORWARD) in arbiter.downScanCodes(), "движение снято на время удара")
        assertTrue(sink.events.any { it is RecordingSink.Event.Mouse })
        router.onPointerUp(3)
        advance(timing.actionLeadMs)
        assertTrue(b.scan(GameKey.FORWARD) in arbiter.downScanCodes(), "удар вперёд по отпусканию")
    }

    @Test
    fun `swipe on main button strikes to the side`() {
        arbiter.setMode(ControlMode.COMBAT)
        val (x, y) = px("main")
        router.onPointerDown(1, x, y)
        router.onPointerMove(1, x - 150f, y + 10f)
        advance(timing.movementReleaseMs + timing.actionLeadMs)
        assertEquals(setOf(b.scan(GameKey.ACTION), b.scan(GameKey.TURN_LEFT)), arbiter.downScanCodes())
        router.onPointerUp(1)
        advance(1_000)
        assertTrue(arbiter.downScanCodes().isEmpty())
        assertFalse(b.scan(GameKey.FORWARD) in downs(), "свайп — один удар, без удара вперёд")
    }

    @Test
    fun `main button confirms on menu screens`() {
        val (x, y) = px("main")
        arbiter.setMode(ControlMode.MENU)
        router.onPointerDown(1, x, y)
        assertEquals(setOf(b.scan(GameKey.CONFIRM)), arbiter.downScanCodes())
        router.onPointerUp(1)
        advance(1_000)
        arbiter.setMode(ControlMode.INVENTORY)
        router.onPointerDown(2, x, y)
        assertEquals(setOf(b.scan(GameKey.ACTION)), arbiter.downScanCodes(), "в инвентаре — использовать")
    }

    @Test
    fun `weapon tap draws, drag picks a slot, hold in center cancels`() {
        val (x, y) = px("weapon")
        router.onPointerDown(1, x, y)
        now += 100
        router.onPointerUp(1)
        assertEquals(setOf(b.scan(GameKey.WEAPON)), arbiter.downScanCodes())
        advance(1_000)

        router.onPointerDown(2, x, y)
        router.onPointerMove(2, x, y - 300f)
        assertTrue(arbiter.downScanCodes().isEmpty(), "до отпускания ничего не нажато")
        router.onPointerUp(2)
        assertEquals(setOf(b.scan(GameKey.SLOT_1)), arbiter.downScanCodes())
        advance(1_000)

        router.onPointerDown(3, x, y)
        now += TouchRouter.WHEEL_HOLD_MS
        assertTrue(router.isWheelOpen(router.owners()[3] as TouchRouter.Owner.Wheel))
        router.onPointerUp(3)
        assertTrue(arbiter.downScanCodes().isEmpty(), "колесо открыто и отпущено в центре — отмена")
    }

    @Test
    fun `dpad press holds the arrow under the finger`() {
        arbiter.setMode(ControlMode.MENU)
        val (x, y) = px("dpad")
        router.onPointerDown(1, x, y + 40f * viewport.density)
        assertEquals(setOf(b.scan(GameKey.MENU_DOWN)), arbiter.downScanCodes())
        router.onPointerUp(1)
        assertTrue(arbiter.downScanCodes().isEmpty())
    }

    @Test
    fun `dragging camera on a menu screen asks to return to the world`() {
        var intents = 0
        router.onWorldIntent = {
            intents++
            arbiter.setMode(ControlMode.WORLD)
            router.onModeChanged()
        }
        arbiter.setMode(ControlMode.MENU)
        router.onPointerDown(1, 1300f, 500f)
        router.onPointerMove(1, 1310f, 500f)
        assertEquals(0, intents, "мелкое движение — не намерение")
        router.onPointerMove(1, 1400f, 500f)
        router.onPointerMove(1, 1500f, 500f)
        assertEquals(1, intents)
        assertTrue(sink.events.any { it is RecordingSink.Event.Mouse }, "камера уже работает")
    }

    @Test
    fun `quick menu goes to the app`() {
        val (x, y) = px("more")
        router.onPointerDown(1, x, y)
        assertEquals(listOf<TouchCommand>(TouchCommand.QuickMenu), uiCommands)
    }

    @Test
    fun `cancel releases everything`() {
        router.onPointerDown(1, 300f, 700f)
        router.onPointerMove(1, 300f, 400f)
        val (x, y) = px("jump")
        router.onPointerDown(2, x, y)
        router.onCancel()
        assertTrue(arbiter.downScanCodes().isEmpty())
        assertTrue(router.owners().isEmpty())
    }

    @Test
    fun `few buttons on screen`() {
        val layout = OverlayLayout.gothicDefault()
        assertEquals(6, layout.forMode(ControlMode.WORLD).size)
        assertEquals(7, layout.forMode(ControlMode.COMBAT).size)
        assertEquals(4, layout.forMode(ControlMode.MENU).size)
        // Esc и главная кнопка — на одних местах во всех режимах.
        for (mode in OverlayLayout.WORLD_ALL + OverlayLayout.NAV) {
            assertTrue(layout.forMode(mode).any { it.id == "escape" } && layout.forMode(mode).any { it.id == "main" })
        }
    }

    @Test
    fun `visible buttons never overlap in any mode, both hands`() {
        // Узкий 20:9 телефон с высокой плотностью — худший случай для стартовой раскладки.
        for (layout in listOf(OverlayLayout.gothicDefault(), OverlayLayout.gothicDefault().mirrored())) {
            for (mode in ControlMode.entries) {
                val visible = layout.forMode(mode)
                for (i in visible.indices) for (j in i + 1 until visible.size) {
                    val a = visible[i]
                    val c = visible[j]
                    val d = hypot(viewport.toPxX(a.cx) - viewport.toPxX(c.cx), viewport.toPxY(a.cy) - viewport.toPxY(c.cy))
                    val min = (a.radiusDp + c.radiusDp) * viewport.density
                    assertTrue(d >= min, "$mode: ${a.id} и ${c.id} перекрываются")
                }
            }
        }
    }
}
