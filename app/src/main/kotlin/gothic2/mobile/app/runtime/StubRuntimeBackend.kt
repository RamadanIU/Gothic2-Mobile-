package gothic2.mobile.app.runtime

import android.os.Handler
import android.os.Looper
import gothic2.mobile.app.storage.Diagnostics
import gothic2.mobile.core.input.InputSink
import gothic2.mobile.core.runtime.LaunchSpec
import gothic2.mobile.core.runtime.RuntimeBackend
import gothic2.mobile.core.runtime.RuntimeComponents
import gothic2.mobile.core.runtime.RuntimeState

/**
 * Заглушка среды исполнения до подключения Wine/Box64 (этап 2, docs/ROADMAP.md).
 * Позволяет на реальном телефоне проверять оверлей, арбитр ввода, жизненный цикл и
 * сохранения: все события ввода пишутся в журнал диагностики и в [onInputEcho].
 */
class StubRuntimeBackend(private val diagnostics: Diagnostics) : RuntimeBackend {
    override val name = "stub"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<(RuntimeState) -> Unit>()
    var onInputEcho: ((String) -> Unit)? = null

    private fun emit(s: RuntimeState) {
        diagnostics.log("runtime", "state $s")
        main.post { listeners.forEach { it(s) } }
    }

    override fun addListener(listener: (RuntimeState) -> Unit) {
        listeners += listener
    }

    override fun prepare(components: RuntimeComponents) {
        val unpinned = components.unpinned().map { it.id }
        diagnostics.log("runtime", "stub prepare; unpinned components: $unpinned")
        emit(RuntimeState.Preparing("stub", 1f))
    }

    override fun launch(spec: LaunchSpec) {
        diagnostics.log("runtime", "stub launch ${spec.windowsExePath} env=${spec.env.keys}")
        emit(RuntimeState.Running)
    }

    override fun attachSurface(surface: Any, width: Int, height: Int) {
        diagnostics.log("runtime", "surface ${width}x$height")
    }

    override fun detachSurface() = diagnostics.log("runtime", "surface detached")

    override fun pause() = emit(RuntimeState.Paused)

    override fun resume() = emit(RuntimeState.Running)

    override fun stop() = emit(RuntimeState.Exited(0))

    override val inputSink: InputSink = object : InputSink {
        override fun keyDown(scanCode: Int) = echo("down 0x%02X".format(scanCode))
        override fun keyUp(scanCode: Int) = echo("up   0x%02X".format(scanCode))
        override fun mouseMove(dx: Int, dy: Int) = echo("mouse $dx,$dy")
    }

    private fun echo(s: String) {
        diagnostics.log("input", s)
        onInputEcho?.invoke(s)
    }
}
