package gothic2.mobile.core.diagnostics

import gothic2.mobile.core.input.InputSink

/**
 * Обёртка над [InputSink], измеряющая задержку «касание → доставка события в среду».
 * UI-поток вызывает [beginEvent] с временем аппаратного события перед передачей его в арбитр
 * и [endEvent] после. Каждое вызванное этим событием нажатие/движение даёт один замер;
 * события, порождённые таймером арбитра (фазы удара), не учитываются.
 */
class TimedInputSink(
    private val delegate: InputSink,
    private val clock: () -> Long,
    val keys: LatencyStats = LatencyStats(),
    val mouse: LatencyStats = LatencyStats(),
) : InputSink {
    private var eventTime: Long? = null

    fun beginEvent(hardwareEventTimeMs: Long) {
        eventTime = hardwareEventTimeMs
    }

    fun endEvent() {
        eventTime = null
    }

    override fun keyDown(scanCode: Int) {
        delegate.keyDown(scanCode)
        eventTime?.let { keys.record(clock() - it) }
    }

    override fun keyUp(scanCode: Int) {
        delegate.keyUp(scanCode)
        eventTime?.let { keys.record(clock() - it) }
    }

    override fun mouseMove(dx: Int, dy: Int) {
        delegate.mouseMove(dx, dy)
        eventTime?.let { mouse.record(clock() - it) }
    }
}
