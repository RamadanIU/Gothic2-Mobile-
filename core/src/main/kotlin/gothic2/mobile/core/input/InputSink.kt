package gothic2.mobile.core.input

/**
 * Конечная точка ввода: то, что действительно доставляет события в Gothic под Wine
 * (например, X-сервер среды класса Winlator). Реализация обязана сохранять порядок вызовов.
 */
interface InputSink {
    fun keyDown(scanCode: Int)
    fun keyUp(scanCode: Int)

    /** Относительное перемещение мыши в «пикселях» игры. */
    fun mouseMove(dx: Int, dy: Int)
}

/** Записывающий приёмник для тестов и диагностики. */
class RecordingSink : InputSink {
    sealed interface Event {
        data class Down(val scan: Int) : Event
        data class Up(val scan: Int) : Event
        data class Mouse(val dx: Int, val dy: Int) : Event
    }

    val events = mutableListOf<Event>()

    override fun keyDown(scanCode: Int) {
        events += Event.Down(scanCode)
    }

    override fun keyUp(scanCode: Int) {
        events += Event.Up(scanCode)
    }

    override fun mouseMove(dx: Int, dy: Int) {
        events += Event.Mouse(dx, dy)
    }

    fun clear() = events.clear()
}
