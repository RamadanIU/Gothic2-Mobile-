package gothic2.mobile.core.input

import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Перестановка кнопок игроком (раздел 8 плана: «изменяемое положение», «сброс раскладки»).
 * Позиция принимается, только если кнопка остаётся в безопасной области и не перекрывает
 * другие кнопки ни в одном из режимов, где она видна; иначе кнопка остаётся на последнем
 * допустимом месте.
 */
class LayoutEditor(
    private val defaults: OverlayLayout,
    initialOverrides: Map<String, Pair<Float, Float>>,
    private val mirrored: Boolean,
) {
    private val overrides = LinkedHashMap(initialOverrides)
    private var dragging: String? = null
    private var grabDx = 0f
    private var grabDy = 0f

    /** Шаг привязки в нормированных координатах. */
    var snap: Float = 0.01f

    /** Итоговая раскладка с учётом перестановок (и зеркалирования для левши). */
    fun layout(): OverlayLayout {
        // Перестановки хранятся в координатах раскладки для правши.
        val base = defaults.withOverrides(overrides)
        return if (mirrored) base.mirrored() else base
    }

    fun overrides(): Map<String, Pair<Float, Float>> = overrides.toMap()

    val draggingId: String? get() = dragging

    fun begin(x: Float, y: Float, viewport: TouchRouter.Viewport, mode: ControlMode): Boolean {
        val l = layout()
        val hit = l.forMode(mode, adapterActive = false).minByOrNull { c ->
            hypot(x - viewport.toPxX(c.cx), y - viewport.toPxY(c.cy))
        }?.takeIf { c ->
            hypot(x - viewport.toPxX(c.cx), y - viewport.toPxY(c.cy)) <= c.radiusDp * l.buttonScale * viewport.density * 1.3f
        } ?: return false
        dragging = hit.id
        grabDx = viewport.toNormX(x) - hit.cx
        grabDy = viewport.toNormY(y) - hit.cy
        return true
    }

    /** @return true, если позиция применена */
    fun move(x: Float, y: Float, viewport: TouchRouter.Viewport): Boolean {
        val id = dragging ?: return false
        var nx = snap(viewport.toNormX(x) - grabDx)
        val ny = snap(viewport.toNormY(y) - grabDy)
        if (mirrored) nx = 1f - nx
        val spec = defaults.controls.first { it.id == id }
        val margin = { r: Float, size: Float -> r * defaults.buttonScale * viewport.density / size }
        val mx = margin(spec.radiusDp, viewport.safeWidth)
        val my = margin(spec.radiusDp, viewport.safeHeight)
        if (nx < mx || nx > 1f - mx || ny < my || ny > 1f - my) return false
        val candidate = overrides + (id to (nx to ny))
        if (overlaps(defaults.withOverrides(candidate), id, viewport)) return false
        overrides[id] = nx to ny
        return true
    }

    fun end() {
        dragging = null
    }

    fun reset() {
        overrides.clear()
        dragging = null
    }

    private fun snap(v: Float) = (v / snap).roundToInt() * snap

    private fun overlaps(layout: OverlayLayout, id: String, viewport: TouchRouter.Viewport): Boolean {
        val moved = layout.controls.first { it.id == id }
        return layout.controls.any { other ->
            other.id != id && other.modes.any { it in moved.modes } &&
                hypot(
                    viewport.toPxX(moved.cx) - viewport.toPxX(other.cx),
                    viewport.toPxY(moved.cy) - viewport.toPxY(other.cy),
                ) < (moved.radiusDp + other.radiusDp) * layout.buttonScale * viewport.density
        }
    }
}
