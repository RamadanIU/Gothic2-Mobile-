package gothic2.mobile.app.bridge

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import gothic2.mobile.app.storage.Diagnostics
import gothic2.mobile.core.bridge.BoundedLineReader
import gothic2.mobile.core.bridge.BridgeProtocol
import gothic2.mobile.core.bridge.BridgeSession
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Локальный сокет для игрового адаптера (раздел 12 плана).
 *
 * Сокет — файл в приватном каталоге приложения (FILESYSTEM namespace): в отличие от
 * абстрактного пространства имён, к нему не может подключиться другое приложение.
 * Сетевой порт не открывается. Каждое подключение получает свой [BridgeSession];
 * все события сеанса обрабатываются в главном потоке.
 */
class BridgeServer(
    private val socketFile: File,
    private val expectedExeSha256: String,
    val token: String,
    private val diagnostics: Diagnostics,
    private val configure: (BridgeSession) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "bridge-write").apply { isDaemon = true } }
    private var server: LocalServerSocket? = null
    private var bound: LocalSocket? = null
    @Volatile private var client: LocalSocket? = null
    private var session: BridgeSession? = null
    @Volatile private var running = false

    private val heartbeat = object : Runnable {
        override fun run() {
            session?.checkAlive()
            send(BridgeProtocol.Command.PING)
            main.postDelayed(this, HEARTBEAT_MS)
        }
    }

    val socketPath: String get() = socketFile.absolutePath

    fun start() {
        socketFile.parentFile?.mkdirs()
        socketFile.delete()
        val s = LocalSocket(LocalSocket.SOCKET_STREAM)
        s.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
        bound = s
        server = LocalServerSocket(s.fileDescriptor)
        running = true
        Thread(::acceptLoop, "bridge-accept").apply { isDaemon = true }.start()
        main.postDelayed(heartbeat, HEARTBEAT_MS)
    }

    fun send(command: BridgeProtocol.Command) {
        val c = client ?: return
        if (session?.status != BridgeSession.Status.ACTIVE) return
        writer.execute {
            runCatching { c.outputStream.write((BridgeProtocol.encode(command) + "\n").toByteArray()) }
        }
    }

    fun stop() {
        running = false
        main.removeCallbacks(heartbeat)
        runCatching { client?.close() }
        runCatching { server?.close() }
        runCatching { bound?.close() }
        writer.shutdown()
        socketFile.delete()
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server?.accept() ?: return
            } catch (e: IOException) {
                if (running) diagnostics.log("bridge", "accept failed: $e")
                return
            }
            // Одновременно обслуживается один адаптер; новое подключение вытесняет старое.
            runCatching { client?.close() }
            client = socket
            val s = BridgeSession(expectedExeSha256, token)
            s.addListener(object : BridgeSession.Listener {
                override fun onClosed(reason: String) {
                    diagnostics.log("bridge", "closed: $reason")
                    runCatching { socket.close() }
                }
            })
            // Очередь главного потока упорядочена: настройка сеанса выполнится раньше,
            // чем первая прочитанная строка, которую readLoop тоже передаёт через main.
            main.post {
                session?.close("Новое подключение")
                configure(s)
                session = s
            }
            Thread({ readLoop(socket, s) }, "bridge-read").apply { isDaemon = true }.start()
        }
    }

    private fun readLoop(socket: LocalSocket, s: BridgeSession) {
        try {
            val reader = BoundedLineReader(socket.inputStream)
            while (true) {
                val line = reader.readLine() ?: break
                main.post { s.onLine(line) }
            }
            main.post { s.close("Адаптер отключился") }
        } catch (e: IOException) {
            main.post { s.close("Ошибка чтения: ${e.message}") }
        }
    }

    private companion object {
        const val HEARTBEAT_MS = 1_000L
    }
}
