package gothic2.mobile.core.input

/** Что делает сенсорный элемент. */
sealed interface TouchCommand {
    /** Клавиша удерживается, пока палец на кнопке. */
    data class Hold(val key: GameKey) : TouchCommand

    /** Одиночное нажатие с минимальной длительностью. */
    data class Tap(val key: GameKey) : TouchCommand

    /**
     * Главная кнопка — одна на все случаи. В мире: касание — действие, удар, выстрел или
     * заклинание (смотря что в руках); свайп влево/вправо — боковой удар; вниз и держать — блок;
     * вверх и держать — удар/заряд; удержание без движения — прицел, отпускание — выстрел.
     * На экранах меню, диалога и инвентаря — «Выбрать» ([ControlMode.confirmKey]).
     */
    data object Main : TouchCommand

    data object Block : TouchCommand

    /** Оружие: касание — достать/убрать, потянуть — колесо слотов (оружие, лук, руны). */
    data object Weapon : TouchCommand

    /** Крестовина экранов навигации: стрелки, пока палец держит направление. */
    data object DPad : TouchCommand

    /** Панель редких действий — обрабатывает приложение. */
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
    /** Подпись по режимам: одна и та же кнопка говорит, что сделает сейчас. */
    val labels: Map<ControlMode, String> = emptyMap(),
) {
    init {
        require(radiusDp >= MIN_RADIUS_DP) { "Кнопка $id меньше минимальной зоны касания 48 dp" }
        require(cx in 0f..1f && cy in 0f..1f) { "Кнопка $id вне безопасной области" }
    }

    fun labelFor(mode: ControlMode): String = labels[mode] ?: label

    fun mirrored() = copy(cx = 1f - cx)

    companion object {
        const val MIN_RADIUS_DP = 24f
    }
}

data class OverlayLayout(
    val controls: List<ControlSpec>,
    /** Зона, где касание создаёт плавающий джойстик (в меню — стрелки). */
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

    fun forMode(mode: ControlMode): List<ControlSpec> = controls.filter { mode in it.modes }

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
        val WORLD_ALL = setOf(ControlMode.WORLD, ControlMode.COMBAT, ControlMode.RANGED, ControlMode.MAGIC)
        val NAV = setOf(ControlMode.DIALOG, ControlMode.INVENTORY, ControlMode.TRADE, ControlMode.MENU)

        /**
         * Раскладка для правши (раздел 8 плана). Кнопок мало, режимы игрок не переключает:
         *  - слева — плавающий джойстик (лёгкое отклонение — шаг, дальше — бег), в меню — стрелки;
         *  - справа — обзор пальцем и одна главная кнопка под большим пальцем, рядом прыжок
         *    и оружие с колесом слотов, в бою — ещё блок;
         *  - сверху — «Сумка», «Ещё» (карта, журнал, сохранения…) и одна кнопка Esc:
         *    «Меню» в игре, «Назад» на любом экране.
         * Главная кнопка и Esc стоят на одних и тех же местах во всех режимах.
         */
        fun gothicDefault(): OverlayLayout {
            val c = mutableListOf<ControlSpec>()
            val everywhere = WORLD_ALL + NAV + ControlMode.LOADING

            // Верх: редкие экраны и Esc.
            c += ControlSpec("inventory", "Сумка", 0.06f, 0.08f, 26f, WORLD_ALL + ControlMode.INVENTORY, TouchCommand.Tap(GameKey.INVENTORY))
            c += ControlSpec("more", "Ещё", 0.84f, 0.08f, 26f, WORLD_ALL + NAV, TouchCommand.QuickMenu)
            c += ControlSpec(
                "escape", "Назад", 0.94f, 0.08f, 26f, everywhere, TouchCommand.Tap(GameKey.ESCAPE),
                labels = WORLD_ALL.associateWith { "Меню" } + (ControlMode.LOADING to "Пропуск"),
            )

            // Главная кнопка — под большим пальцем; подпись говорит, что она сделает сейчас.
            c += ControlSpec(
                "main", "Действие", 0.87f, 0.72f, 44f, WORLD_ALL + NAV, TouchCommand.Main,
                labels = mapOf(
                    ControlMode.COMBAT to "Удар",
                    ControlMode.RANGED to "Выстрел",
                    ControlMode.MAGIC to "Магия",
                    ControlMode.MENU to "Выбрать",
                    ControlMode.DIALOG to "Выбрать",
                    ControlMode.INVENTORY to "Применить",
                    ControlMode.TRADE to "Обмен",
                ),
            )
            c += ControlSpec("jump", "Прыжок", 0.73f, 0.87f, 30f, WORLD_ALL, TouchCommand.Hold(GameKey.JUMP))
            c += ControlSpec("weapon", "Оружие", 0.72f, 0.58f, 32f, WORLD_ALL, TouchCommand.Weapon)
            c += ControlSpec("block", "Блок", 0.93f, 0.42f, 34f, setOf(ControlMode.COMBAT), TouchCommand.Block)

            // Экраны навигации: крестовина на месте джойстика.
            c += ControlSpec("dpad", "", 0.15f, 0.66f, 56f, NAV, TouchCommand.DPad)

            return OverlayLayout(
                controls = c,
                joystickZone = NRect(0f, 0.18f, 0.45f, 1f),
                cameraZone = NRect(0.45f, 0.15f, 1f, 1f),
            )
        }
    }
}
