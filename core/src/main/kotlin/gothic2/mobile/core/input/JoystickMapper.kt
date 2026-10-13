package gothic2.mobile.core.input

import kotlin.math.hypot

/**
 * Преобразует отклонение виртуального джойстика в дискретные клавиши движения.
 *
 * Игра получает клавиши, поэтому скорости дискретны (ходьба/бег), а не аналоговые.
 * Каждая ось и переключатель ходьбы/бега имеют разные пороги включения и выключения,
 * чтобы режим не «дрожал» на границе.
 */
class JoystickMapper(private val config: Config = Config()) {
    data class Config(
        val deadZone: Float = 0.18f,
        val axisOn: Float = 0.38f,
        val axisOff: Float = 0.28f,
        val runOn: Float = 0.75f,
        val runOff: Float = 0.60f,
        /** true — ходьба требует удерживать модификатор скорости (проверить на сборке). */
        val walkUsesModifier: Boolean = true,
    ) {
        init {
            require(axisOff < axisOn && runOff < runOn && deadZone < axisOn) { "Неверные пороги джойстика" }
        }
    }

    data class Output(val keys: Set<GameKey>, val running: Boolean)

    private var forward = false
    private var back = false
    private var left = false
    private var right = false
    private var running = true

    /**
     * @param x вправо положительно, −1..1
     * @param y вперёд (вверх по экрану) положительно, −1..1
     */
    fun map(x: Float, y: Float): Output {
        val magnitude = hypot(x, y).coerceAtMost(1f)
        if (magnitude < config.deadZone) {
            reset()
            return Output(emptySet(), running = true)
        }
        forward = axis(y, forward)
        back = axis(-y, back)
        right = axis(x, right)
        left = axis(-x, left)
        running = if (running) magnitude >= config.runOff else magnitude >= config.runOn

        val keys = buildSet {
            if (forward) add(GameKey.FORWARD)
            if (back) add(GameKey.BACK)
            if (left) add(GameKey.STRAFE_LEFT)
            if (right) add(GameKey.STRAFE_RIGHT)
            if (isNotEmpty() && !running && config.walkUsesModifier) add(GameKey.SPEED_MODIFIER)
        }
        return Output(keys, running)
    }

    fun reset() {
        forward = false; back = false; left = false; right = false
        running = true
    }

    private fun axis(value: Float, active: Boolean): Boolean =
        if (active) value > config.axisOff else value > config.axisOn
}
