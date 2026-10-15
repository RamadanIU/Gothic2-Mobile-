package gothic2.mobile.core.input

/** Режим управления: определяет, какие панели видны и какие сигналы допустимы. */
enum class ControlMode {
    WORLD, COMBAT, RANGED, MAGIC, DIALOG, INVENTORY, TRADE, MENU, LOADING;

    /** Режимы, в которых джойстик двигает героя, а правая область крутит камеру. */
    val isInWorld: Boolean get() = this == WORLD || this == COMBAT || this == RANGED || this == MAGIC

    /** Экраны со списками (меню, диалог, инвентарь, торговля): джойстик работает как стрелки. */
    val isNavigation: Boolean get() = this == DIALOG || this == INVENTORY || this == TRADE || this == MENU

    /** Режимы с направленными ударами и блоком. */
    val allowsMelee: Boolean get() = this == COMBAT

    /**
     * Клавиша кнопки «Выбрать» на экранах навигации: в меню игры — Enter, в диалоге, инвентаре
     * и торговле — «действие» (Ctrl): использовать предмет, купить/продать, выбрать ответ.
     */
    val confirmKey: GameKey get() = if (this == MENU) GameKey.CONFIRM else GameKey.ACTION

    /** Слой панели: мир, экраны навигации или загрузка. Внутри слоя набор кнопок почти не меняется. */
    val layer: Int get() = when {
        isInWorld -> 0
        isNavigation -> 1
        else -> 2
    }
}

enum class AttackDirection { FORWARD, LEFT, RIGHT }

/** Направление движения пальца по главной кнопке; null — палец в центре. */
enum class SwipeDir { UP, DOWN, LEFT, RIGHT }
