package gothic2.mobile.app.runtime

import gothic2.mobile.core.input.InputSink
import gothic2.mobile.core.runtime.LaunchSpec
import gothic2.mobile.core.runtime.RuntimeBackend
import gothic2.mobile.core.runtime.RuntimeComponents
import gothic2.mobile.core.runtime.RuntimeState

/**
 * Среда исполнения Winlator, как её видит остальная часть приложения. Подготовку и запуск
 * делают [GameLauncher] и экран игры (наследник XServerDisplayActivity), изображение рисует
 * X-сервер Winlator, а приостановку процессов при уходе в фон — сама активити Winlator.
 */
class WinlatorBackend(
    override val inputSink: InputSink,
    private val onStop: () -> Unit,
) : RuntimeBackend {
    override val name = "winlator"
    private val listeners = mutableListOf<(RuntimeState) -> Unit>()

    override fun addListener(listener: (RuntimeState) -> Unit) {
        listeners += listener
    }

    fun emit(state: RuntimeState) = listeners.forEach { it(state) }

    override fun prepare(components: RuntimeComponents) = Unit

    override fun launch(spec: LaunchSpec) = Unit

    override fun attachSurface(surface: Any, width: Int, height: Int) = Unit

    override fun detachSurface() = Unit

    override fun pause() = Unit

    override fun resume() = Unit

    override fun stop() = onStop()
}
