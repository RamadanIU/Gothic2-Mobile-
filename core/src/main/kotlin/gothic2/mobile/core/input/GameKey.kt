package gothic2.mobile.core.input

/**
 * Логические игровые действия Gothic II. Приложение оперирует только ими;
 * конкретные клавиши задаются в [KeyBindings].
 */
enum class GameKey {
    FORWARD, BACK, TURN_LEFT, TURN_RIGHT, STRAFE_LEFT, STRAFE_RIGHT,
    ACTION, JUMP, WEAPON, SPEED_MODIFIER, SNEAK, FIRST_PERSON,
    INVENTORY, CHARACTER, LOG, MAP,
    MENU_UP, MENU_DOWN, MENU_LEFT, MENU_RIGHT, CONFIRM, ESCAPE,
    SLOT_1, SLOT_2, SLOT_3, SLOT_4, SLOT_5, SLOT_6, SLOT_7, SLOT_8, SLOT_9, SLOT_10,
    QUICKSAVE, QUICKLOAD,
}

/** Скан-коды DirectInput (DIK_*): Gothic читает клавиатуру через DirectInput. */
object ScanCode {
    const val ESCAPE = 0x01
    const val DIGIT_1 = 0x02 // 1..9 = 0x02..0x0A, 0 = 0x0B
    const val DIGIT_0 = 0x0B
    const val TAB = 0x0F
    const val RETURN = 0x1C
    const val LCONTROL = 0x1D
    const val LSHIFT = 0x2A
    const val B = 0x30
    const val F = 0x21
    const val L = 0x26
    const val X = 0x2D
    const val M = 0x32
    const val LMENU = 0x38 // левый Alt
    const val SPACE = 0x39
    const val F5 = 0x3F
    const val F9 = 0x43
    const val UP = 0xC8
    const val LEFT = 0xCB
    const val RIGHT = 0xCD
    const val DOWN = 0xD0
    const val NEXT = 0xD1 // Page Down
    const val DELETE = 0xD3
}

/**
 * Соответствие логических действий скан-кодам.
 *
 * Значения по умолчанию сверены с разделом [KEYS] `System/Gothic.ini` закреплённой
 * GOG-сборки (отчёт от 2026-10-14). В INI у действия две клавиши (по 2 байта, младший
 * байт первым: `keyUp=c8001100` — «вверх» и W); здесь берётся та, что не требует мыши.
 * Взгляда назад в Gothic II нет: `End` — это блок (keyParade), `Num0` — «осмотреться»
 * (keyLook). Быстрые сохранение и загрузка (F5/F9) работают только при
 * `useQuickSaveKeys=1` — его выставляет [gothic2.mobile.core.config.GothicIniWriter].
 */
data class KeyBindings(val map: Map<GameKey, Int>) {
    init {
        val missing = GameKey.entries.filter { it !in map }
        require(missing.isEmpty()) { "Нет привязки для: $missing" }
    }

    fun scan(key: GameKey): Int = map.getValue(key)

    fun with(key: GameKey, scanCode: Int): KeyBindings = KeyBindings(map + (key to scanCode))

    companion object {
        val GOTHIC2_DEFAULT = KeyBindings(
            buildMap {
                put(GameKey.FORWARD, ScanCode.UP)
                put(GameKey.BACK, ScanCode.DOWN)
                put(GameKey.TURN_LEFT, ScanCode.LEFT)
                put(GameKey.TURN_RIGHT, ScanCode.RIGHT)
                put(GameKey.STRAFE_LEFT, ScanCode.DELETE)
                put(GameKey.STRAFE_RIGHT, ScanCode.NEXT)
                put(GameKey.ACTION, ScanCode.LCONTROL)
                put(GameKey.JUMP, ScanCode.LMENU)
                put(GameKey.WEAPON, ScanCode.SPACE)
                put(GameKey.SPEED_MODIFIER, ScanCode.LSHIFT)
                put(GameKey.SNEAK, ScanCode.X)
                put(GameKey.FIRST_PERSON, ScanCode.F)
                put(GameKey.INVENTORY, ScanCode.TAB)
                put(GameKey.CHARACTER, ScanCode.B)
                put(GameKey.LOG, ScanCode.L)
                put(GameKey.MAP, ScanCode.M)
                put(GameKey.MENU_UP, ScanCode.UP)
                put(GameKey.MENU_DOWN, ScanCode.DOWN)
                put(GameKey.MENU_LEFT, ScanCode.LEFT)
                put(GameKey.MENU_RIGHT, ScanCode.RIGHT)
                put(GameKey.CONFIRM, ScanCode.RETURN)
                put(GameKey.ESCAPE, ScanCode.ESCAPE)
                val slots = listOf(
                    GameKey.SLOT_1, GameKey.SLOT_2, GameKey.SLOT_3, GameKey.SLOT_4, GameKey.SLOT_5,
                    GameKey.SLOT_6, GameKey.SLOT_7, GameKey.SLOT_8, GameKey.SLOT_9,
                )
                slots.forEachIndexed { i, k -> put(k, ScanCode.DIGIT_1 + i) }
                put(GameKey.SLOT_10, ScanCode.DIGIT_0)
                put(GameKey.QUICKSAVE, ScanCode.F5)
                put(GameKey.QUICKLOAD, ScanCode.F9)
            },
        )
    }
}
