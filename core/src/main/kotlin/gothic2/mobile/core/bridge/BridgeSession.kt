package gothic2.mobile.core.bridge

import gothic2.mobile.core.bridge.BridgeProtocol.Message
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Сеанс моста: рукопожатие, проверка версии EXE и токена, контроль «живости».
 * Любое нарушение протокола закрывает сеанс — приложение переходит на универсальную
 * панель управления, а не показывает ошибочные контекстные кнопки.
 */
class BridgeSession(
    private val expectedExeSha256: String,
    val token: String = newToken(),
    private val timeoutMs: Long = 3_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    enum class Status { AWAITING_HELLO, ACTIVE, CLOSED }

    interface Listener {
        fun onState(state: BridgeProtocol.GameState) {}
        fun onSaveDone(slot: String) {}
        fun onClosed(reason: String) {}
    }

    var status = Status.AWAITING_HELLO
        private set
    var lastState: BridgeProtocol.GameState? = null
        private set
    private var lastMessageAt = clock()
    private val listeners = mutableListOf<Listener>()

    fun addListener(l: Listener) {
        listeners += l
    }

    fun onLine(line: String) {
        if (status == Status.CLOSED) return
        val msg = try {
            BridgeProtocol.parse(line)
        } catch (e: BridgeProtocol.ProtocolException) {
            close("Нарушение протокола: ${e.message}")
            return
        }
        lastMessageAt = clock()
        when (status) {
            Status.AWAITING_HELLO -> {
                if (msg !is Message.Hello) return close("Ожидалось HELLO")
                if (msg.version != BridgeProtocol.VERSION) return close("Неподдерживаемая версия протокола ${msg.version}")
                if (msg.exeSha256 != expectedExeSha256) return close("Адаптер собран для другой версии EXE")
                if (!constantTimeEquals(msg.token, token)) return close("Неверный токен")
                status = Status.ACTIVE
            }
            Status.ACTIVE -> when (msg) {
                is Message.State -> {
                    lastState = msg.state
                    listeners.forEach { it.onState(msg.state) }
                }
                is Message.SaveDone -> listeners.forEach { it.onSaveDone(msg.slot) }
                Message.Bye -> close("Адаптер завершил работу")
                is Message.Hello -> close("Повторный HELLO")
            }
            Status.CLOSED -> Unit
        }
    }

    /** Вызывать периодически: молчание дольше [timeoutMs] считается потерей адаптера. */
    fun checkAlive() {
        if (status != Status.CLOSED && clock() - lastMessageAt > timeoutMs) close("Адаптер не отвечает")
    }

    fun close(reason: String) {
        if (status == Status.CLOSED) return
        status = Status.CLOSED
        lastState = null
        listeners.forEach { it.onClosed(reason) }
    }

    companion object {
        fun newToken(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { "%02x".format(it) }
        }

        private fun constantTimeEquals(a: String, b: String) =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
