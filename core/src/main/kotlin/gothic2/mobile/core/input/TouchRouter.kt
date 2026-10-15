package gothic2.mobile.core.input

import kotlin.math.abs
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

    /** Дополнительный запас к радиусу кнопки, dp: касание краем пальца засчитывается. */
    var hitSlopDp: Float = 8f

    /** Типы содержимого быстрых слотов от адаптера (номер → тип); пусто — только номера. */
    var slotKinds: Map<Int, String> = emptyMap()

    /** Вызывается при нажатии любой кнопки и при каждом принятом ударе — для тактильного отклика. */
    var onButtonPressed: (ControlSpec) -> Unit = {}

    /**
     * Игрок тянет камеру, а панель — меню или диалог: похоже, игра уже в мире
     * ([gothic2.mobile.core.bridge.ModeController.onWorldIntent]). Вызывается один раз за касание.
     */
    var onWorldIntent: () -> Unit = {}

    sealed interface Owner {
        data class Joystick(val originX: Float, val originY: Float) : Owner
        data class Camera(var lastX: Float, var lastY: Float, val startX: Float, val startY: Float) : Owner {
            var intentSent = false
        }
        data class Button(val spec: ControlSpec) : Owner

        /** Палец на главной кнопке в мире: направление от точки касания задаёт удар. */
        data class Strike(val spec: ControlSpec, val originX: Float, val originY: Float) : Owner {
            var sector: SwipeDir? = null
        }

        /** Палец на кнопке оружия: касание — достать/убрать, потянуть или подержать — колесо слотов. */
        data class Wheel(val spec: ControlSpec, val originX: Float, val originY: Float, val startedAt: Long) : Owner {
            var x = originX
            var y = originY
            var dragged = false
            val dx get() = x - originX
            val dy get() = y - originY
        }
    }

    val mode: ControlMode get() = arbiter.mode

    fun radialMenu(): RadialMenu = RadialMenu.quickSlots(RADIAL_DEAD_ZONE_DP * viewport.density, slotKinds)

    /** Колесо слотов видно: палец потянул кнопку оружия или держит её дольше [WHEEL_HOLD_MS]. */
    fun isWheelOpen(o: Owner.Wheel): Boolean = o.dragged || arbiter.now() - o.startedAt >= WHEEL_HOLD_MS

    private val pointers = HashMap<Int, Owner>()

    fun owners(): Map<Int, Owner> = pointers

    fun visibleControls(): List<ControlSpec> = layout.forMode(arbiter.mode)

    fun onPointerDown(pointerId: Int, x: Float, y: Float) {
        pointers.remove(pointerId)?.let { end(pointerId, it) }
        val mode = arbiter.mode
        val spec = hitButton(x, y)
        val owner = when {
            spec == null -> {
                val nx = viewport.toNormX(x)
                val ny = viewport.toNormY(y)
                when {
                    mode != ControlMode.LOADING && layout.joystickZone.contains(nx, ny) && !hasJoystick() -> Owner.Joystick(x, y)
                    layout.cameraZone.contains(nx, ny) && cameraAllowed(mode) -> Owner.Camera(x, y, x, y)
                    else -> null
                }
            }
            spec.command == TouchCommand.DPad -> if (hasJoystick()) null else Owner.Joystick(viewport.toPxX(spec.cx), viewport.toPxY(spec.cy))
            spec.command == TouchCommand.Weapon -> Owner.Wheel(spec, x, y, arbiter.now())
            spec.command == TouchCommand.Main && mode.isInWorld ->
                // Отклонённый жест (идёт другой удар) — палец просто держит кнопку, ничего не нажимая.
                if (arbiter.strikeBegin()) Owner.Strike(spec, x, y) else Owner.Button(spec)
            else -> Owner.Button(spec)
        } ?: return
        pointers[pointerId] = owner
        when (owner) {
            is Owner.Button -> {
                begin(pointerId, owner.spec)
                onButtonPressed(owner.spec)
            }
            is Owner.Strike -> onButtonPressed(owner.spec)
            is Owner.Wheel -> onButtonPressed(owner.spec)
            // Касание крестовины сразу нажимает стрелку под пальцем.
            is Owner.Joystick -> if (spec != null) moveJoystick(owner, x, y)
            is Owner.Camera -> Unit
        }
    }

    fun onPointerMove(pointerId: Int, x: Float, y: Float) {
        when (val owner = pointers[pointerId]) {
            is Owner.Joystick -> moveJoystick(owner, x, y)
            is Owner.Camera -> {
                if (!arbiter.mode.isInWorld && !owner.intentSent &&
                    hypot(x - owner.startX, y - owner.startY) >= WORLD_INTENT_DP * viewport.density
                ) {
                    owner.intentSent = true
                    onWorldIntent()
                }
                arbiter.onCameraDelta(x - owner.lastX, y - owner.lastY)
                owner.lastX = x
                owner.lastY = y
            }
            is Owner.Strike -> {
                val sector = sectorOf(x - owner.originX, y - owner.originY)
                if (sector != owner.sector) {
                    owner.sector = sector
                    if (arbiter.strikeAim(sector)) onButtonPressed(owner.spec)
                }
            }
            is Owner.Wheel -> {
                owner.x = x
                owner.y = y
                if (hypot(owner.dx, owner.dy) >= RADIAL_DEAD_ZONE_DP * viewport.density) owner.dragged = true
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
        val mode = arbiter.mode
        val visible = visibleControls().map { it.id }.toSet()
        pointers.entries.removeAll { (id, o) ->
            val stale = when (o) {
                is Owner.Button -> o.spec.id !in visible
                // Удар и колесо прежней панели отменяются без действия: арбитр уже сбросил удар.
                is Owner.Strike -> !mode.isInWorld
                is Owner.Wheel -> o.spec.id !in visible
                is Owner.Joystick -> mode == ControlMode.LOADING
                is Owner.Camera -> !cameraAllowed(mode)
            }
            if (stale && (o is Owner.Button || o is Owner.Joystick)) end(id, o)
            stale
        }
    }

    private fun cameraAllowed(mode: ControlMode) =
        mode.isInWorld || mode == ControlMode.MENU || mode == ControlMode.DIALOG || mode == ControlMode.TRADE

    private fun moveJoystick(owner: Owner.Joystick, x: Float, y: Float) {
        val r = layout.joystickRadiusDp * viewport.density
        var vx = (x - owner.originX) / r
        var vy = -(y - owner.originY) / r // вверх по экрану = вперёд
        val m = hypot(vx, vy)
        if (m > 1f) { vx /= m; vy /= m }
        arbiter.onJoystick(vx, vy)
    }

    /** Сектор главной кнопки по смещению пальца; null — палец в центре. */
    private fun sectorOf(dx: Float, dy: Float): SwipeDir? {
        if (hypot(dx, dy) < STRIKE_SWIPE_DP * viewport.density) return null
        return when {
            abs(dx) > abs(dy) -> if (dx < 0) SwipeDir.LEFT else SwipeDir.RIGHT
            dy < 0 -> SwipeDir.UP
            else -> SwipeDir.DOWN
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
            TouchCommand.Block -> arbiter.setBlock(true)
            // Главная кнопка на экранах навигации — «Выбрать»; в мире сюда попадает только отклонённый удар.
            TouchCommand.Main -> if (arbiter.mode.isNavigation) arbiter.tap(arbiter.mode.confirmKey)
            TouchCommand.QuickMenu -> onUiCommand(cmd)
            TouchCommand.Weapon, TouchCommand.DPad -> Unit // свои владельцы пальца
        }
    }

    private fun end(pointerId: Int, owner: Owner) {
        when (owner) {
            is Owner.Joystick -> arbiter.onJoystickReleased()
            is Owner.Camera -> Unit
            is Owner.Strike -> arbiter.strikeEnd()
            is Owner.Wheel -> if (isWheelOpen(owner)) {
                val menu = radialMenu()
                // Отпускание в центре открытого колеса — отмена.
                menu.select(owner.dx, owner.dy)?.let { i -> arbiter.tap(menu.items[i].key) }
            } else {
                arbiter.tap(GameKey.WEAPON)
            }
            is Owner.Button -> when (owner.spec.command) {
                is TouchCommand.Hold -> arbiter.onHoldEnd(holderId(pointerId))
                TouchCommand.Block -> arbiter.setBlock(false)
                else -> Unit
            }
        }
    }

    companion object {
        const val RADIAL_DEAD_ZONE_DP = 28f

        /** Сдвиг пальца по главной кнопке, после которого касание становится направленным ударом. */
        const val STRIKE_SWIPE_DP = 26f

        /** Удержание кнопки оружия, после которого открывается колесо слотов. */
        const val WHEEL_HOLD_MS = 300L

        /** Сдвиг камеры на экране меню, после которого панель возвращается к игре. */
        const val WORLD_INTENT_DP = 24f
    }
}
