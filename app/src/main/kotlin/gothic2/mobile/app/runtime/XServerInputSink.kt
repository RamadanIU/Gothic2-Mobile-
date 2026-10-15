package gothic2.mobile.app.runtime

import com.winlator.xserver.XKeycode
import com.winlator.xserver.XServer
import gothic2.mobile.core.input.InputSink

/**
 * Доставка ввода в X-сервер Winlator: Wine передаёт его Gothic как DirectInput.
 * Скан-коды DirectInput (DIK_*) переводятся в коды клавиш X: у основных клавиш код X на 8 больше
 * (DIK совпадает со скан-кодом set 1, а он — с кодом evdev), у расширенных — по таблице.
 */
class XServerInputSink(private val xServer: XServer) : InputSink {
    override fun keyDown(scanCode: Int) {
        keycode(scanCode)?.let { xServer.injectKeyPress(it) }
    }

    override fun keyUp(scanCode: Int) {
        keycode(scanCode)?.let { xServer.injectKeyRelease(it) }
    }

    override fun mouseMove(dx: Int, dy: Int) {
        if (dx != 0 || dy != 0) xServer.injectPointerMoveDelta(dx, dy)
    }

    companion object {
        private val byId: Map<Int, XKeycode> = XKeycode.entries.associateBy { it.id.toInt() }

        /** Расширенные скан-коды (с префиксом E0) → код X. */
        private val EXTENDED = mapOf(
            0x9C to 104, // Enter на цифровом блоке
            0x9D to 105, // правый Ctrl
            0xB5 to 106, // / на цифровом блоке
            0xB8 to 108, // правый Alt
            0xC7 to 110, // Home
            0xC8 to 111, // стрелка вверх
            0xC9 to 112, // Page Up
            0xCB to 113, // стрелка влево
            0xCD to 114, // стрелка вправо
            0xCF to 115, // End
            0xD0 to 116, // стрелка вниз
            0xD1 to 117, // Page Down
            0xD2 to 118, // Insert
            0xD3 to 119, // Delete
        )

        fun xKeycodeId(scanCode: Int): Int? = when {
            scanCode in 0x01..0x58 -> scanCode + 8
            else -> EXTENDED[scanCode]
        }

        fun keycode(scanCode: Int): XKeycode? = xKeycodeId(scanCode)?.let { byId[it] }
    }
}
