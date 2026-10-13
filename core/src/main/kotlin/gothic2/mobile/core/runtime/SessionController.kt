package gothic2.mobile.core.runtime

import gothic2.mobile.core.input.InputArbiter

/**
 * Реакция на события жизненного цикла Android (раздел 17 плана).
 * Порядок при потере фокуса: снять весь ввод → пауза → аудио → зафиксировать состояние.
 * `Esc` как универсальная пауза НЕ используется: в диалоге/меню он делает другое.
 */
class SessionController(
    private val arbiter: InputArbiter,
    private val backend: RuntimeBackend,
    private val onAudioSuspend: () -> Unit = {},
    private val onAudioResume: () -> Unit = {},
) {
    var focused: Boolean = true
        private set

    fun onFocusLost() {
        if (!focused) return
        focused = false
        arbiter.releaseAll()
        backend.pause()
        onAudioSuspend()
    }

    fun onFocusGained() {
        if (focused) return
        focused = true
        // Старые события ввода уже сброшены в onFocusLost; камера не доигрывает накопленное.
        arbiter.camera.discard()
        onAudioResume()
        backend.resume()
    }
}
