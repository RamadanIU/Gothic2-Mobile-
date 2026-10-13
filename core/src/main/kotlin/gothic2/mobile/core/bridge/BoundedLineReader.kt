package gothic2.mobile.core.bridge

import java.io.IOException
import java.io.InputStream

/**
 * Чтение строк из сокета адаптера с жёстким ограничением длины: слишком длинная строка —
 * нарушение протокола, а не повод копить память. Разделитель — `\n`, `\r` отбрасывается.
 */
class BoundedLineReader(private val input: InputStream, private val maxBytes: Int = BridgeProtocol.MAX_LINE) {
    class LineTooLongException : IOException("Строка длиннее ${BridgeProtocol.MAX_LINE} байт")

    private val buf = ByteArray(maxBytes)

    /** @return строка без перевода строки или null в конце потока */
    fun readLine(): String? {
        var n = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (n == 0) null else String(buf, 0, n, Charsets.UTF_8)
            if (b == '\n'.code) return String(buf, 0, n, Charsets.UTF_8).trimEnd('\r')
            if (n == maxBytes) throw LineTooLongException()
            buf[n++] = b.toByte()
        }
    }
}
