package gothic2.mobile.core.input

import kotlin.math.roundToInt

/**
 * Перевод движения пальца в относительное движение мыши.
 *
 * Без сглаживания и без очереди: дельты, пришедшие между двумя отправками, суммируются
 * в одну, дробный остаток переносится, чтобы медленное движение не терялось.
 */
class CameraMapper(var sensitivity: Float = 1.0f, var invertY: Boolean = false) {
    private var accX = 0f
    private var accY = 0f

    fun add(dxPx: Float, dyPx: Float) {
        accX += dxPx * sensitivity
        accY += dyPx * sensitivity * (if (invertY) -1f else 1f)
    }

    /** Возвращает целую часть накопленного смещения или null, если двигать нечего. */
    fun drain(): Pair<Int, Int>? {
        val ix = accX.roundToInt()
        val iy = accY.roundToInt()
        accX -= ix
        accY -= iy
        return if (ix == 0 && iy == 0) null else ix to iy
    }

    /** Сброс накопленного (смена режима, потеря фокуса): старые движения не доигрываются. */
    fun discard() {
        accX = 0f
        accY = 0f
    }
}
