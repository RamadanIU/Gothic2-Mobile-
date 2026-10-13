package gothic2.mobile.core.input

import kotlin.math.hypot

/**
 * Распределяет пальцы между джойстиком, камерой и кнопками.
 *
 * Палец принадлежит элементу, на котором начал касание, до самого отпускания:
 * палец с джойстика остаётся джойстиком, палец с кнопки не превращается в камеру,
 * даже если выйдет за её границы. Поддерживаются одновременные движение, камера и действие.
 *
 * Координаты — в пикселях вида; [Viewport] описывает безопасную область и плотность.
 */
class TouchRouter(
    private val arbiter: InputArbiter,
    var layout: OverlayLayout,
    var viewport: Viewport,
    private val onUiCommand: (TouchCommand) -> Unit = {},
) {
    data class Viewport(
        val safeLeft: Float,
        val safeTop: Float,
        val safeWidth: Float,
        val safeHeight: Float,
        val density: Float,
    ) {
        fun toNormX(px: Float) = (px - safeLeft) / safeWidth
        fun toNormY(py: Float) = (py - safeTop) / safeHeight
        fun toPxX(nx: Float) = safeLeft + nx * safeWidth
        fun toPxY(ny: Float) = safeTop + ny * safeHeight
    }

    /** true — работает игровой адаптер, ручные кнопки режимов скрыты. */
    var adapterActive: Boolean = false

    /** Дополнительный запас к радиусу кнопки, dp: касание краем пальца засчитывается. */
    var hitSlopDp: Float = 8f

    /** Типы содержимого быстрых слотов от адаптера (номер → тип); пусто — только номера. */
    var slotKinds: Map<Int, String> = emptyMap()

    /** Последний выбранный быстрый слот — для кнопки «Посл.». */
    var lastSlot: GameKey? = null
        private set

    /** Вызывается при нажатии любой кнопки — для тактильного отклика. */
    var onButtonPressed: (ControlSpec) -> Unit = {}

    sealed interface Owner {
        data class Joystick(val originX: Float, val originY: Float) : Owner
        data class Camera(var lastX: Float, var lastY: Float) : Owner
        data class Button(val spec: ControlSpec) : Owner
        data class Radial(val spec: ControlSpec, val originX: Float, val originY: Float, var x: Float, var y: Float) : Owner {
            val dx get() = x - originX
            val dy get() = y - originY
        }
    }

    fun radialMenu(): RadialMenu = RadialMenu.quickSlots(RADIAL_DEAD_ZONE_DP * viewport.density, slotKinds)

    private val pointers = HashMap<Int, Owner>()

    fun owners(): Map<Int, Owner> = pointers

    fun visibleControls(): List<ControlSpec> = layout.forMode(arbiter.mode, adapterActive)

    fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        pointers.remove(pointerId)?.let { end(pointerId, it) }
        val owner = hitButton(x, y)?.let { spec ->
            if (spec.command == TouchCommand.QuickSlots) Owner.Radial(spec, x, y, x, y) else Owner.Button(spec)
        }
            ?: run {
                val nx = viewport.toNormX(x)
                val ny = viewport.toNormY(y)
                when {
                    arbiter.mode.isInWorld && layout.joystickZone.contains(nx, ny) && !hasJoystick() ->
                        Owner.Joystick(x, y)
                    arbiter.mode.isInWorld && layout.cameraZone.contains(nx, ny) -> Owner.Camera(x, y)
                    else -> null
                }
            } ?: return
        pointers[pointerId] = owner
        when (owner) {
            is Owner.Button -> {
                begin(pointerId, owner.spec)
                onButtonPressed(owner.spec)
            }
            is Owner.Radial -> onButtonPressed(owner.spec)
            else -> Unit
        }
    }

    fun onPointerMove(pointerId: Int, x: Float, y: Float) {
        when (val owner = pointers[pointerId]) {
            is Owner.Joystick -> {
                val r = layout.joystickRadiusDp * viewport.density
                var vx = (x - owner.originX) / r
                var vy = -(y - owner.originY) / r // вверх по экрану = вперёд
                val m = hypot(vx, vy)
                if (m > 1f) { vx /= m; vy /= m }
                arbiter.onJoystick(vx, vy)
            }
            is Owner.Camera -> {
                arbiter.onCameraDelta(x - owner.lastX, y - owner.lastY)
                owner.lastX = x
                owner.lastY = y
            }
            is Owner.Radial -> {
                owner.x = x
                owner.y = y
            }
            is Owner.Button, null -> Unit
        }
    }

    fun onPointerUp(pointerId: Int) {
        pointers.remove(pointerId)?.let { end(pointerId, it) }
    }

    /** ACTION_CANCEL / потеря фокуса: снимается абсолютно всё. */
    fun onCancel() {
        pointers.clear()
        arbiter.releaseAll()
    }

    /** Смена режима: пальцы на кнопках прежней панели отвязываются, джойстик остаётся. */
    fun onModeChanged() {
        val visible = visibleControls().map { it.id }.toSet()
        pointers.entries.removeAll { (id, o) ->
            val stale = o is Owner.Button && o.spec.id !in visible ||
                o is Owner.Radial && o.spec.id !in visible ||
                !arbiter.mode.isInWorld && (o is Owner.Joystick || o is Owner.Camera)
            // Радиальное меню при смене панели отменяется без выбора слота.
            if (stale && o !is Owner.Radial) end(id, o)
            stale
        }
    }

    private fun hasJoystick() = pointers.values.any { it is Owner.Joystick }

    private fun hitButton(x: Float, y: Float): ControlSpec? {
        val d = viewport.density
        return visibleControls()
            .map { it to hypot(x - viewport.toPxX(it.cx), y - viewport.toPxY(it.cy)) }
            .filter { (spec, dist) -> dist <= (spec.radiusDp * layout.buttonScale + hitSlopDp) * d }
            .minByOrNull { it.second }
            ?.first
    }

    private fun holderId(pointerId: Int) = "touch:$pointerId"

    private fun begin(pointerId: Int, spec: ControlSpec) {
        when (val cmd = spec.command) {
            is TouchCommand.Hold -> arbiter.onHoldStart(holderId(pointerId), cmd.key)
            is TouchCommand.Tap -> arbiter.tap(cmd.key)
            is TouchCommand.Attack -> arbiter.requestAttack(cmd.direction)
            TouchCommand.Block -> arbiter.setBlock(true)
            TouchCommand.ContextAction -> arbiter.setContextAction(true)
            TouchCommand.LastSlot -> lastSlot?.let(arbiter::tap)
            TouchCommand.QuickSlots -> Unit // обрабатывается как Owner.Radial
            is TouchCommand.SwitchMode, TouchCommand.QuickMenu -> onUiCommand(cmd)
        }
    }

    private fun end(pointerId: Int, owner: Owner) {
        when (owner) {
            is Owner.Joystick -> arbiter.onJoystickReleased()
            is Owner.Camera -> Unit
            is Owner.Radial -> {
                val menu = radialMenu()
                menu.select(owner.dx, owner.dy)?.let { i ->
                    val key = menu.items[i].key
                    lastSlot = key
                    arbiter.tap(key)
                }
            }
            is Owner.Button -> when (owner.spec.command) {
                is TouchCommand.Hold -> arbiter.onHoldEnd(holderId(pointerId))
                TouchCommand.Block -> arbiter.setBlock(false)
                TouchCommand.ContextAction -> arbiter.setContextAction(false)
                else -> Unit
            }
        }
    }

    private companion object {
        const val RADIAL_DEAD_ZONE_DP = 28f
    }
}
