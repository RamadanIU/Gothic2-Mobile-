package gothic2.mobile.app.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import gothic2.mobile.core.input.AttackDirection
import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter
import gothic2.mobile.core.input.SwipeDir

/**
 * Геймпад через тот же [InputArbiter], что и сенсорное управление (раздел 21).
 * Gothic не понимает XInput — всё преобразуется в поддерживаемые игрой действия.
 */
class GamepadMapper(private val arbiter: InputArbiter) {
    /** Скорость камеры правого стика, пикселей мыши за кадр при полном отклонении. */
    var stickCameraSpeed = 18f
    private var rightX = 0f
    private var rightY = 0f

    val hasStickInput: Boolean get() = rightX != 0f || rightY != 0f

    fun onMotion(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || e.action != MotionEvent.ACTION_MOVE) return false
        val lx = dead(e.getAxisValue(MotionEvent.AXIS_X))
        val ly = dead(e.getAxisValue(MotionEvent.AXIS_Y))
        arbiter.onJoystick(lx, -ly)
        rightX = dead(e.getAxisValue(MotionEvent.AXIS_Z))
        rightY = dead(e.getAxisValue(MotionEvent.AXIS_RZ))
        val hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        // Крестовина на экранах меню — одиночные шаги; левый стик там тоже работает как стрелки.
        if (arbiter.mode.isNavigation) {
            when {
                hatY < -0.5f -> arbiter.tap(GameKey.MENU_UP)
                hatY > 0.5f -> arbiter.tap(GameKey.MENU_DOWN)
                hatX < -0.5f -> arbiter.tap(GameKey.MENU_LEFT)
                hatX > 0.5f -> arbiter.tap(GameKey.MENU_RIGHT)
            }
        }
        return true
    }

    /** Вызывается раз в кадр, пока правый стик отклонён. */
    fun onFrame() {
        if (hasStickInput) arbiter.onCameraDelta(rightX * stickCameraSpeed, rightY * stickCameraSpeed)
    }

    fun onKey(e: KeyEvent): Boolean {
        if (!KeyEvent.isGamepadButton(e.keyCode)) return false
        if (e.repeatCount > 0) return true
        val down = e.action == KeyEvent.ACTION_DOWN
        val id = "pad:${e.keyCode}"
        when (e.keyCode) {
            // A — та же главная кнопка, что на экране: в мире действие/удар/выстрел (держать —
            // прицел, отпустить — выстрел), на экранах меню — «Выбрать».
            KeyEvent.KEYCODE_BUTTON_A -> when {
                arbiter.mode.isNavigation -> if (down) arbiter.tap(arbiter.mode.confirmKey)
                down -> arbiter.strikeBegin()
                else -> arbiter.strikeEnd()
            }
            KeyEvent.KEYCODE_BUTTON_B -> if (!arbiter.mode.isInWorld) { if (down) arbiter.tap(GameKey.ESCAPE) } else hold(id, GameKey.JUMP, down)
            KeyEvent.KEYCODE_BUTTON_X -> if (down) arbiter.tap(GameKey.WEAPON)
            KeyEvent.KEYCODE_BUTTON_Y -> if (down) arbiter.tap(GameKey.INVENTORY)
            KeyEvent.KEYCODE_BUTTON_L1 -> side(id, AttackDirection.LEFT, down)
            KeyEvent.KEYCODE_BUTTON_R1 -> side(id, AttackDirection.RIGHT, down)
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> arbiter.setBlock(down)
            KeyEvent.KEYCODE_BUTTON_START -> if (down) arbiter.tap(GameKey.ESCAPE)
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (down) arbiter.tap(GameKey.LOG)
            else -> return false
        }
        return true
    }

    /**
     * Боковой удар: при зажатой A — направление жеста главной кнопки, в бою без A — отдельный
     * удар, вне боя — удержание «действия» (L1) или «вперёд» (R1) для стрельбы вручную.
     */
    private fun side(id: String, dir: AttackDirection, down: Boolean) {
        if (!down) {
            arbiter.onHoldEnd(id)
            if (arbiter.isStriking()) arbiter.strikeAim(null)
            return
        }
        when {
            arbiter.isStriking() -> arbiter.strikeAim(if (dir == AttackDirection.LEFT) SwipeDir.LEFT else SwipeDir.RIGHT)
            arbiter.mode == ControlMode.COMBAT -> arbiter.requestAttack(dir)
            else -> arbiter.onHoldStart(id, if (dir == AttackDirection.LEFT) GameKey.ACTION else GameKey.FORWARD)
        }
    }

    private fun hold(id: String, key: GameKey, down: Boolean) {
        if (down) arbiter.onHoldStart(id, key) else arbiter.onHoldEnd(id)
    }

    private fun dead(v: Float) = if (kotlin.math.abs(v) < 0.12f) 0f else v
}
