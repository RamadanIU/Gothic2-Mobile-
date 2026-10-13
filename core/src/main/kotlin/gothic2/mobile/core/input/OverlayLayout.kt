package gothic2.mobile.core.input

/** Что делает сенсорный элемент. */
sealed interface TouchCommand {
    /** Клавиша удерживается, пока палец на кнопке. */
    data class Hold(val key: GameKey) : TouchCommand

    /** Одиночное нажатие с минимальной длительностью. */
    data class Tap(val key: GameKey) : TouchCommand

    data class Attack(val direction: AttackDirection) : TouchCommand

    data object Block : TouchCommand

    /** Говорить / подобрать / использовать. */
    data object ContextAction : TouchCommand

    /** Ручная смена панели (уровень 1, без игрового адаптера). */
    data class SwitchMode(val mode: ControlMode) : TouchCommand

    /** Раскрывающееся меню редких действий и быстрых слотов — обрабатывает приложение. */
    data object QuickMenu : TouchCommand
}

/** Прямоугольник в нормированных координатах безопасной области (0..1). */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun mirrored() = NRect(1f - right, top, 1f - left, bottom)
}

/**
 * Кнопка оверлея. Центр — в нормированных координатах безопасной области,
 * радиус — в dp (не меньше 24 dp, т.е. зона касания ≥ 48 dp).
 */
data class ControlSpec(
    val id: String,
    val label: String,
    val cx: Float,
    val cy: Float,
    val radiusDp: Float,
    val modes: Set<ControlMode>,
    val command: TouchCommand,
    /** Видна только без игрового адаптера (ручное переключение режимов). */
    val manualOnly: Boolean = false,
) {
    init {
        require(radiusDp >= MIN_RADIUS_DP) { "Кнопка $id меньше минимальной зоны касания 48 dp" }
        require(cx in 0f..1f && cy in 0f..1f) { "Кнопка $id вне безопасной области" }
    }

    fun mirrored() = copy(cx = 1f - cx)

    companion object {
        const val MIN_RADIUS_DP = 24f
    }
}

data class OverlayLayout(
    val controls: List<ControlSpec>,
    /** Зона, где касание создаёт плавающий джойстик. */
    val joystickZone: NRect,
    /** Свободная зона поворота камеры (кнопки имеют приоритет). */
    val cameraZone: NRect,
    /** Радиус полного отклонения джойстика, dp. */
    val joystickRadiusDp: Float = 64f,
    val buttonScale: Float = 1f,
    val opacity: Float = 0.6f,
) {
    init {
        require(controls.map { it.id }.toSet().size == controls.size) { "Повторяющиеся id кнопок" }
        require(buttonScale in 0.5f..2.5f && opacity in 0.1f..1f)
    }

    fun forMode(mode: ControlMode, adapterActive: Boolean): List<ControlSpec> =
        controls.filter { mode in it.modes && !(adapterActive && it.manualOnly) }

    /** Раскладка для левшей: зеркальное отражение по горизонтали. */
    fun mirrored() = copy(
        controls = controls.map { it.mirrored() },
        joystickZone = joystickZone.mirrored(),
        cameraZone = cameraZone.mirrored(),
    )

    fun withOverrides(overrides: Map<String, Pair<Float, Float>>): OverlayLayout = copy(
        controls = controls.map { c ->
            overrides[c.id]?.let { (x, y) -> c.copy(cx = x.coerceIn(0f, 1f), cy = y.coerceIn(0f, 1f)) } ?: c
        },
    )

    companion object {
        private val WORLD_ALL = setOf(ControlMode.WORLD, ControlMode.COMBAT, ControlMode.RANGED, ControlMode.MAGIC)
        private val NAV = setOf(ControlMode.DIALOG, ControlMode.INVENTORY, ControlMode.TRADE, ControlMode.MENU)

        /**
         * Стартовая раскладка для правши: слева джойстик, справа камера и действия,
         * сверху по краям — инвентарь, журнал, меню. Позиции — отправная точка
         * для проверки на реальных экранах.
         */
        fun gothicDefault(): OverlayLayout {
            val c = mutableListOf<ControlSpec>()
            val world = setOf(ControlMode.WORLD)
            val combat = setOf(ControlMode.COMBAT)
            val ranged = setOf(ControlMode.RANGED, ControlMode.MAGIC)

            // Верхние края: редкие экраны.
            c += ControlSpec("inventory", "Инвентарь", 0.06f, 0.08f, 26f, WORLD_ALL, TouchCommand.Tap(GameKey.INVENTORY))
            c += ControlSpec("log", "Журнал", 0.16f, 0.08f, 26f, world, TouchCommand.Tap(GameKey.LOG))
            c += ControlSpec("menu", "Меню", 0.94f, 0.08f, 26f, WORLD_ALL + NAV, TouchCommand.Tap(GameKey.ESCAPE))
            c += ControlSpec("quick", "Ещё", 0.84f, 0.08f, 26f, WORLD_ALL, TouchCommand.QuickMenu)

            // Мир.
            c += ControlSpec("context", "Действие", 0.88f, 0.72f, 40f, world, TouchCommand.ContextAction)
            c += ControlSpec("jump", "Прыжок", 0.76f, 0.86f, 32f, world + combat, TouchCommand.Hold(GameKey.JUMP))
            c += ControlSpec("weapon", "Оружие", 0.94f, 0.32f, 30f, WORLD_ALL, TouchCommand.Tap(GameKey.WEAPON))

            // Ближний бой: ромб «вперёд / влево / вправо / блок».
            c += ControlSpec("atk_fwd", "Удар", 0.86f, 0.52f, 34f, combat, TouchCommand.Attack(AttackDirection.FORWARD))
            c += ControlSpec("atk_left", "Слева", 0.78f, 0.66f, 32f, combat, TouchCommand.Attack(AttackDirection.LEFT))
            c += ControlSpec("atk_right", "Справа", 0.94f, 0.66f, 32f, combat, TouchCommand.Attack(AttackDirection.RIGHT))
            c += ControlSpec("block", "Блок", 0.86f, 0.80f, 34f, combat, TouchCommand.Block)

            // Лук, арбалет, магия: удержание действия + выстрел/применение (проверить на сборке).
            c += ControlSpec("aim", "Цель", 0.80f, 0.74f, 36f, ranged, TouchCommand.Hold(GameKey.ACTION))
            c += ControlSpec("fire", "Пуск", 0.92f, 0.70f, 34f, ranged, TouchCommand.Hold(GameKey.FORWARD))

            // Ручное переключение панелей (только без адаптера).
            c += ControlSpec("to_combat", "Бой", 0.70f, 0.08f, 26f, world, TouchCommand.SwitchMode(ControlMode.COMBAT), manualOnly = true)
            c += ControlSpec("to_world", "Мир", 0.70f, 0.08f, 26f, combat + ranged, TouchCommand.SwitchMode(ControlMode.WORLD), manualOnly = true)
            c += ControlSpec("to_ranged", "Лук/Магия", 0.60f, 0.08f, 26f, combat, TouchCommand.SwitchMode(ControlMode.RANGED), manualOnly = true)
            c += ControlSpec("to_nav", "Навигация", 0.26f, 0.08f, 26f, world, TouchCommand.SwitchMode(ControlMode.MENU), manualOnly = true)
            c += ControlSpec("nav_to_world", "К игре", 0.06f, 0.08f, 26f, NAV, TouchCommand.SwitchMode(ControlMode.WORLD), manualOnly = true)

            // Навигация по меню, диалогам, инвентарю и торговле.
            c += ControlSpec("nav_up", "▲", 0.14f, 0.52f, 32f, NAV, TouchCommand.Tap(GameKey.MENU_UP))
            c += ControlSpec("nav_down", "▼", 0.14f, 0.80f, 32f, NAV, TouchCommand.Tap(GameKey.MENU_DOWN))
            c += ControlSpec("nav_left", "◀", 0.06f, 0.66f, 30f, setOf(ControlMode.INVENTORY, ControlMode.TRADE, ControlMode.MENU), TouchCommand.Tap(GameKey.MENU_LEFT))
            c += ControlSpec("nav_right", "▶", 0.22f, 0.66f, 30f, setOf(ControlMode.INVENTORY, ControlMode.TRADE, ControlMode.MENU), TouchCommand.Tap(GameKey.MENU_RIGHT))
            c += ControlSpec("confirm", "Выбрать", 0.88f, 0.72f, 38f, setOf(ControlMode.DIALOG, ControlMode.MENU), TouchCommand.Tap(GameKey.CONFIRM))
            c += ControlSpec("use_item", "Использовать", 0.88f, 0.72f, 38f, setOf(ControlMode.INVENTORY, ControlMode.TRADE), TouchCommand.Tap(GameKey.ACTION))

            return OverlayLayout(
                controls = c,
                joystickZone = NRect(0f, 0.25f, 0.45f, 1f),
                cameraZone = NRect(0.45f, 0.15f, 1f, 1f),
            )
        }
    }
}
