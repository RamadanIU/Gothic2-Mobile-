package gothic2.mobile.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadialAndEditorTest {
    private var now = 0L
    private val sink = RecordingSink()
    private val arbiter = InputArbiter(sink, clock = { now })
    private val viewport = TouchRouter.Viewport(0f, 0f, 2200f, 1000f, density = 2.75f)
    private val router = TouchRouter(arbiter, OverlayLayout.gothicDefault(), viewport)
    private val b = KeyBindings.GOTHIC2_DEFAULT

    private fun center(id: String): Pair<Float, Float> {
        val c = router.layout.controls.first { it.id == id }
        return viewport.toPxX(c.cx) to viewport.toPxY(c.cy)
    }

    @Test
    fun `radial sectors start at top and go clockwise`() {
        val menu = RadialMenu.quickSlots(deadZonePx = 50f)
        assertNull(menu.select(10f, 10f))
        assertEquals(0, menu.select(0f, -200f))   // вверх → слот 1
        assertEquals(1, menu.select(120f, -165f)) // 36° по часовой → слот 2
        assertEquals(5, menu.select(0f, 200f))    // вниз → слот 6
        assertEquals(9, menu.select(-120f, -165f))
    }

    @Test
    fun `adapter slot kinds appear in labels`() {
        val menu = RadialMenu.quickSlots(50f, mapOf(3 to "RUNE"))
        assertEquals("3 · руна", menu.items[2].label)
        assertEquals("4", menu.items[3].label)
    }

    @Test
    fun `swipe on slots button selects slot and last slot repeats it`() {
        val (x, y) = center("slots")
        router.onPointerDown(1, x, y)
        router.onPointerMove(1, x, y - 300f)
        assertTrue(arbiter.downScanCodes().isEmpty(), "до отпускания ничего не нажато")
        router.onPointerUp(1)
        assertTrue(b.scan(GameKey.SLOT_1) in arbiter.downScanCodes())
        assertEquals(GameKey.SLOT_1, router.lastSlot)

        now += 1_000
        arbiter.tick()
        val (lx, ly) = center("last_slot")
        router.onPointerDown(2, lx, ly)
        assertTrue(b.scan(GameKey.SLOT_1) in arbiter.downScanCodes())
    }

    @Test
    fun `mode change while radial is open cancels without selecting`() {
        val (x, y) = center("slots")
        router.onPointerDown(1, x, y)
        router.onPointerMove(1, x, y - 300f)
        arbiter.setMode(ControlMode.DIALOG)
        router.onModeChanged()
        router.onPointerUp(1)
        assertTrue(arbiter.downScanCodes().isEmpty())
        assertNull(router.lastSlot)
    }

    @Test
    fun `release in dead zone cancels radial`() {
        val (x, y) = center("slots")
        router.onPointerDown(1, x, y)
        router.onPointerMove(1, x + 5f, y)
        router.onPointerUp(1)
        assertTrue(arbiter.downScanCodes().isEmpty())
        assertNull(router.lastSlot)
    }

    @Test
    fun `editor moves button, rejects overlap and out of bounds, resets`() {
        val editor = LayoutEditor(OverlayLayout.gothicDefault(), emptyMap(), mirrored = false)
        val jump = OverlayLayout.gothicDefault().controls.first { it.id == "jump" }
        val sx = viewport.toPxX(jump.cx)
        val sy = viewport.toPxY(jump.cy)
        assertTrue(editor.begin(sx, sy, viewport, ControlMode.WORLD))
        assertTrue(editor.move(sx - 100f, sy, viewport))
        val moved = editor.layout().controls.first { it.id == "jump" }
        assertEquals(jump.cx - 100f / 2200f, moved.cx, 0.011f)

        // Наезд на кнопку «Действие» отклоняется — кнопка остаётся на прежнем месте.
        val ctx = OverlayLayout.gothicDefault().controls.first { it.id == "context" }
        assertFalse(editor.move(viewport.toPxX(ctx.cx), viewport.toPxY(ctx.cy), viewport))
        assertEquals(moved, editor.layout().controls.first { it.id == "jump" })

        assertFalse(editor.move(-50f, sy, viewport), "за пределы безопасной области нельзя")
        editor.end()
        assertEquals(setOf("jump"), editor.overrides().keys)
        editor.reset()
        assertTrue(editor.overrides().isEmpty())
    }

    @Test
    fun `editor stores right-handed coordinates for left-handed layout`() {
        val defaults = OverlayLayout.gothicDefault()
        val editor = LayoutEditor(defaults, emptyMap(), mirrored = true)
        val jump = editor.layout().controls.first { it.id == "jump" } // зеркальная позиция на экране
        val sx = viewport.toPxX(jump.cx)
        val sy = viewport.toPxY(jump.cy)
        assertTrue(editor.begin(sx, sy, viewport, ControlMode.WORLD))
        assertTrue(editor.move(sx + 110f, sy, viewport))
        val stored = editor.overrides().getValue("jump").first
        val original = defaults.controls.first { it.id == "jump" }.cx
        assertTrue(stored < original, "сдвиг вправо у левши = сдвиг влево у правши")
    }
}
