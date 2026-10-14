package gothic2.mobile.app.runtime

import android.os.SystemClock
import android.system.OsConstants
import com.winlator.core.ProcessHelper
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

    companion object {
        /** Экран игры открыт: процессы Wine принадлежат ему, трогать их нельзя. */
        @Volatile
        var sessionActive = false

        /**
         * Завершить все процессы Wine этого приложения (wineserver, игра, PulseAudio). Winlator при
         * выходе перезапускает приложение целиком; мы остаёмся в процессе, поэтому без этого процессы
         * прошлой игры живут дальше, и новый запуск Gothic натыкается на них (Union: «Can not create
         * Event object»).
         */
        fun killWineProcesses(): Int {
            val self = android.os.Process.myPid()
            fun children() = ProcessHelper.getChildProcesses().filter { it.pid != self && it.state != ProcessHelper.PState.ZOMBIE }
            val initial = children()
            if (initial.isEmpty()) return 0
            // Сначала SIGTERM: wineserver по нему сохраняет реестр (иначе настройки, которые игра
            // записала при первом запуске, пропадут), затем SIGKILL тем, кто не завершился.
            initial.forEach { android.os.Process.sendSignal(it.pid, OsConstants.SIGTERM) }
            val deadline = SystemClock.uptimeMillis() + TERM_WAIT_MS
            while (children().isNotEmpty() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            children().forEach { ProcessHelper.killProcess(it.pid) }
            return initial.size
        }

        private const val TERM_WAIT_MS = 3000L
    }
}
