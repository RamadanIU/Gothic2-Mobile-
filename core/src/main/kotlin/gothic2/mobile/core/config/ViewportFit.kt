package gothic2.mobile.core.config

import kotlin.math.roundToInt

/**
 * Где на экране телефона выводить картинку игры (раздел 15 плана).
 * Картинка не растягивается: при несовпадении пропорций (4:3 на 20:9) остаются поля.
 * Поля можно использовать под элементы управления.
 */
object ViewportFit {
    data class Rect(val left: Int, val top: Int, val width: Int, val height: Int) {
        val right get() = left + width
        val bottom get() = top + height
    }

    data class Result(val game: Rect, val leftBar: Int, val rightBar: Int, val topBar: Int, val bottomBar: Int)

    fun fit(screenW: Int, screenH: Int, gameW: Int, gameH: Int): Result {
        require(screenW > 0 && screenH > 0 && gameW > 0 && gameH > 0)
        val screenAspect = screenW.toDouble() / screenH
        val gameAspect = gameW.toDouble() / gameH
        return if (screenAspect >= gameAspect) {
            val w = (screenH * gameAspect).roundToInt().coerceAtMost(screenW)
            val left = (screenW - w) / 2
            Result(Rect(left, 0, w, screenH), left, screenW - w - left, 0, 0)
        } else {
            val h = (screenW / gameAspect).roundToInt().coerceAtMost(screenH)
            val top = (screenH - h) / 2
            Result(Rect(0, top, screenW, h), 0, 0, top, screenH - h - top)
        }
    }
}
