package gothic2.mobile.core.runtime

import java.io.ByteArrayOutputStream

/** Минимальный ustar-писатель для тестов, включая «вредные» записи. */
class TarBuilder {
    private val out = ByteArrayOutputStream()

    fun file(name: String, content: String, mode: Int = 0b110_100_100) = entry(name, '0', content.toByteArray(), mode = mode)

    fun dir(name: String) = entry(name, '5', ByteArray(0), mode = 0b111_101_101)

    fun symlink(name: String, target: String) = entry(name, '2', ByteArray(0), link = target)

    fun hardlink(name: String, target: String) = entry(name, '1', ByteArray(0), link = target)

    fun longName(name: String, content: String): TarBuilder {
        entry("././@LongLink", 'L', (name + "\u0000").toByteArray())
        return entry(name.take(99), '0', content.toByteArray())
    }

    fun pax(records: Map<String, String>, name: String, content: String): TarBuilder {
        val body = StringBuilder()
        for ((k, v) in records) {
            val rec = " $k=$v\n"
            var len = rec.toByteArray().size + 1
            while ((len.toString() + rec).toByteArray().size != len) len++
            body.append(len).append(rec)
        }
        entry("PaxHeader", 'x', body.toString().toByteArray())
        return entry(name.take(99), '0', content.toByteArray())
    }

    fun entry(name: String, type: Char, data: ByteArray, mode: Int = 0b110_100_100, link: String = ""): TarBuilder {
        val h = ByteArray(512)
        put(h, 0, name.toByteArray(), 100)
        put(h, 100, octal(mode.toLong(), 8), 8)
        put(h, 108, octal(0, 8), 8)
        put(h, 116, octal(0, 8), 8)
        put(h, 124, octal(data.size.toLong(), 12), 12)
        put(h, 136, octal(0, 12), 12)
        h[156] = type.code.toByte()
        put(h, 157, link.toByteArray(), 100)
        put(h, 257, "ustar\u0000".toByteArray(), 6)
        put(h, 263, "00".toByteArray(), 2)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        val sum = h.sumOf { it.toInt() and 0xFF }
        put(h, 148, "%06o\u0000 ".format(sum).toByteArray(), 8)
        out.write(h)
        out.write(data)
        val pad = (512 - data.size % 512) % 512
        out.write(ByteArray(pad))
        return this
    }

    fun build(): ByteArray {
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun put(h: ByteArray, off: Int, b: ByteArray, len: Int) = System.arraycopy(b, 0, h, off, minOf(b.size, len))

    private fun octal(v: Long, len: Int) = ("%0" + (len - 1) + "o").format(v).toByteArray() + byteArrayOf(0)
}
