package gothic2.mobile.core.input

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Радиальное меню быстрых слотов (раздел 11 плана). Выбор — направлением движения пальца
 * от точки касания; отпускание в центральной зоне — отмена. Сектор 0 смотрит вверх,
 * дальше по часовой стрелке.
 */
class RadialMenu(val items: List<Item>, private val deadZonePx: Float) {
    data class Item(val key: GameKey, val label: String)

    init {
        require(items.size in 2..12) { "В радиальном меню 2..12 пунктов" }
    }

    /** @param dx,dy смещение пальца в пикселях экрана (y вниз) */
    fun select(dx: Float, dy: Float): Int? {
        if (hypot(dx, dy) < deadZonePx) return null
        // Угол от направления «вверх» по часовой стрелке, 0..2π.
        var a = atan2(dx.toDouble(), -dy.toDouble())
        if (a < 0) a += 2 * PI
        val sector = 2 * PI / items.size
        return (((a + sector / 2) / sector).toInt()) % items.size
    }

    /** Центр сектора [index] в радианах от «вверх» по часовой. */
    fun angleOf(index: Int): Double = 2 * PI / items.size * index

    companion object {
        private val SLOTS = listOf(
            GameKey.SLOT_1, GameKey.SLOT_2, GameKey.SLOT_3, GameKey.SLOT_4, GameKey.SLOT_5,
            GameKey.SLOT_6, GameKey.SLOT_7, GameKey.SLOT_8, GameKey.SLOT_9, GameKey.SLOT_10,
        )

        fun slotKey(number: Int): GameKey = SLOTS[number - 1]

        /**
         * Слоты 1..10. Без адаптера подписи — только номера; с адаптером — тип содержимого,
         * который сообщила игра (иконки и названия рун без адаптера достоверно не получить).
         */
        fun quickSlots(deadZonePx: Float, kinds: Map<Int, String> = emptyMap()) = RadialMenu(
            SLOTS.mapIndexed { i, k ->
                val n = i + 1
                Item(k, kinds[n]?.let { "$n · ${kindLabel(it)}" } ?: n.toString())
            },
            deadZonePx,
        )

        private fun kindLabel(kind: String) = when (kind.uppercase()) {
            "MELEE" -> "оружие"
            "RANGED" -> "лук"
            "RUNE" -> "руна"
            "SCROLL" -> "свиток"
            else -> kind.lowercase()
        }
    }
}
