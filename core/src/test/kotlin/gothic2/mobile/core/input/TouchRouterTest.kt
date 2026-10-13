package gothic2.mobile.core.input

import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun px(spec: String): Pair<Float, Float> {
        val c = router.layout.controls.first { it.id == spec }
        return viewport.toPxX(c.cx) to viewport.toPxY(c.cy)
    }

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
    fun `move, camera and action work simultaneously`() {
        router.onPointerDown(1, 300f, 700f)
        router.onPointerMove(1, 300f, 400f)
        router.onPointerDown(2, 1300f, 500f)
        router.onPointerMove(2, 1350f, 500f)
        val (x, y) = px("context")
        router.onPointerDown(3, x, y)
        assertTrue(b.scan(GameKey.FORWARD) in arbiter.downScanCodes())
        assertTrue(b.scan(GameKey.ACTION) in arbiter.downScanCodes())
        assertTrue(sink.events.any { it is RecordingSink.Event.Mouse })
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
    fun `manual mode switch goes to app and is hidden with adapter`() {
        val (x, y) = px("to_combat")
        router.onPointerDown(1, x, y)
        assertEquals(listOf<TouchCommand>(TouchCommand.SwitchMode(ControlMode.COMBAT)), uiCommands)
        router.adapterActive = true
        assertTrue(router.visibleControls().none { it.manualOnly })
    }

    @Test
    fun `visible buttons never overlap in any mode, both hands`() {
        // Узкий 20:9 телефон с высокой плотностью — худший случай для стартовой раскладки.
        for (layout in listOf(OverlayLayout.gothicDefault(), OverlayLayout.gothicDefault().mirrored())) {
            for (mode in ControlMode.entries) for (adapter in listOf(false, true)) {
                val visible = layout.forMode(mode, adapter)
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
