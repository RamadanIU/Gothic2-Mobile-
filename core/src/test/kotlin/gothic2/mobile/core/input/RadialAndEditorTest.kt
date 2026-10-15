package gothic2.mobile.core.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadialAndEditorTest {
    private val viewport = TouchRouter.Viewport(0f, 0f, 2200f, 1000f, density = 2.75f)

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
        assertEquals("Меч", menu.items[0].label, "слот 1 в Gothic II — оружие ближнего боя")
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

        // Наезд на главную кнопку отклоняется — кнопка остаётся на прежнем месте.
        val ctx = OverlayLayout.gothicDefault().controls.first { it.id == "main" }
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
