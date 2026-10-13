package gothic2.mobile.core.runtime

/** Состояние среды исполнения, как его видит приложение. */
sealed interface RuntimeState {
    data object Idle : RuntimeState
    data class Preparing(val step: String, val progress: Float?) : RuntimeState
    data object Starting : RuntimeState
    data object Running : RuntimeState
    data object Paused : RuntimeState
    data class Exited(val exitCode: Int) : RuntimeState
    data class Failed(val reason: FailureReason, val details: String) : RuntimeState
}

enum class FailureReason {
    COMPONENTS_MISSING, COMPONENTS_CORRUPTED, PREFIX_SETUP_FAILED,
    GAME_NOT_IMPORTED, GPU_UNSUPPORTED, PROCESS_CRASHED, UNKNOWN,
}

/**
 * Граница между приложением и средой исполнения (Wine + Box64 + графика).
 *
 * Реализация на основе среды класса Winlator живёт в модуле приложения и подключается
 * на этапе 2 (docs/ROADMAP.md). Приложение не зависит от её внутренностей:
 * ввод доставляется через [inputSink], вывод — на поверхность, переданную в [attachSurface].
 */
interface RuntimeBackend {
    val name: String

    fun addListener(listener: (RuntimeState) -> Unit)

    /** Распаковка/проверка компонентов и префикса. Идемпотентна. */
    fun prepare(components: RuntimeComponents)

    fun launch(spec: LaunchSpec)

    /** Поверхность вывода (Android Surface), тип скрыт, чтобы core не зависел от Android. */
    fun attachSurface(surface: Any, width: Int, height: Int)

    fun detachSurface()

    /** Мягкая пауза процесса при потере фокуса (если поддерживается). */
    fun pause()

    fun resume()

    fun stop()

    val inputSink: gothic2.mobile.core.input.InputSink
}
