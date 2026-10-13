package gothic2.mobile.core.input

/** Режим управления: определяет, какие панели видны и какие сигналы допустимы. */
enum class ControlMode {
    WORLD, COMBAT, RANGED, MAGIC, DIALOG, INVENTORY, TRADE, MENU, LOADING;

    /** Режимы, в которых джойстик двигает героя, а правая область крутит камеру. */
    val isInWorld: Boolean get() = this == WORLD || this == COMBAT || this == RANGED || this == MAGIC

    /** Режимы с направленными ударами и блоком. */
    val allowsMelee: Boolean get() = this == COMBAT
}

enum class AttackDirection { FORWARD, LEFT, RIGHT }
