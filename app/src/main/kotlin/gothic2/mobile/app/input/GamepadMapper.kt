package gothic2.mobile.app.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import gothic2.mobile.core.input.AttackDirection
import gothic2.mobile.core.input.ControlMode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.InputArbiter

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
        if (!arbiter.mode.isInWorld) {
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
        val combat = arbiter.mode == ControlMode.COMBAT
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> when {
                !arbiter.mode.isInWorld -> if (down) arbiter.tap(GameKey.CONFIRM)
                combat -> if (down) arbiter.requestAttack(AttackDirection.FORWARD)
                else -> arbiter.setContextAction(down)
            }
            KeyEvent.KEYCODE_BUTTON_B -> if (!arbiter.mode.isInWorld) { if (down) arbiter.tap(GameKey.ESCAPE) } else hold(id, GameKey.JUMP, down)
            KeyEvent.KEYCODE_BUTTON_X -> if (down) arbiter.tap(GameKey.WEAPON)
            KeyEvent.KEYCODE_BUTTON_Y -> if (down) arbiter.tap(GameKey.INVENTORY)
            KeyEvent.KEYCODE_BUTTON_L1 -> if (combat) { if (down) arbiter.requestAttack(AttackDirection.LEFT) } else hold(id, GameKey.ACTION, down)
            KeyEvent.KEYCODE_BUTTON_R1 -> if (combat) { if (down) arbiter.requestAttack(AttackDirection.RIGHT) } else hold(id, GameKey.FORWARD, down)
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> arbiter.setBlock(down)
            KeyEvent.KEYCODE_BUTTON_START -> if (down) arbiter.tap(GameKey.ESCAPE)
            KeyEvent.KEYCODE_BUTTON_SELECT -> if (down) arbiter.tap(GameKey.LOG)
            else -> return false
        }
        return true
    }

    private fun hold(id: String, key: GameKey, down: Boolean) {
        if (down) arbiter.onHoldStart(id, key) else arbiter.onHoldEnd(id)
    }

    private fun dead(v: Float) = if (kotlin.math.abs(v) < 0.12f) 0f else v
}
