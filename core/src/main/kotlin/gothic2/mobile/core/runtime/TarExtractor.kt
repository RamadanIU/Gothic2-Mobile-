package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.FileOps
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Распаковка tar (ustar, GNU long names, PAX) для компонентов среды исполнения.
 *
 * Архив считается недоверенным: запрещены абсолютные пути, `..`, запись через ранее
 * созданные символические ссылки и ссылки, указывающие за пределы каталога установки.
 * Биты исполнения сохраняются — Wine и Box64 без них не запустятся.
 */
class TarExtractor(private val limits: Limits = Limits()) {

    data class Limits(
        val maxEntries: Int = 200_000,
        val maxTotalBytes: Long = 4L * 1024 * 1024 * 1024,
    )

    class UnsafeArchiveException(message: String) : IOException(message)

    data class Stats(val files: Int, val directories: Int, val symlinks: Int, val bytes: Long)

    fun extract(input: InputStream, root: Path, onFile: (String, Long) -> Unit = { _, _ -> }): Stats {
        Files.createDirectories(root)
        val realRoot = root.toRealPath()
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var files = 0
        var dirs = 0
        var links = 0
        var total = 0L
        var entries = 0

        while (true) {
            if (!readFully(input, header)) break
            if (header.all { it == 0.toByte() }) break // конец архива (два нулевых блока)
            verifyChecksum(header)
            val type = header[156].toInt().toChar()
            val size = pax["size"]?.toLongOrNull() ?: parseNumber(header, 124, 12)
            if (size < 0) throw UnsafeArchiveException("Отрицательный размер записи")

            when (type) {
                'L' -> { longName = readString(input, size); continue }
                'K' -> { longLink = readString(input, size); continue }
                'x' -> { pax = parsePax(readString(input, size)); continue }
                'g' -> { skip(input, padded(size)); continue }
            }

            val rawName = pax["path"] ?: longName ?: ustarName(header)
            val rawLink = pax["linkpath"] ?: longLink ?: cString(header, 157, 100)
            val mode = parseNumber(header, 100, 8).toInt()
            longName = null
            longLink = null
            pax = emptyMap()

            if (++entries > limits.maxEntries) throw UnsafeArchiveException("Слишком много записей")
            val rel = normalize(rawName)
            if (rel == null) { // запись «./» — корень
                skip(input, padded(size))
                continue
            }
            val target = root.resolve(rel)
            ensureParentInside(target, realRoot)

            when (type) {
                '5' -> {
                    if (Files.isSymbolicLink(target)) throw UnsafeArchiveException("Каталог поверх ссылки: $rel")
                    Files.createDirectories(target)
                    dirs++
                    skip(input, padded(size))
                }
                '2' -> {
                    checkLinkTarget(rel, rawLink)
                    Files.deleteIfExists(target)
                    Files.createSymbolicLink(target, Paths.get(rawLink))
                    links++
                    skip(input, padded(size))
                }
                '1' -> {
                    val src = normalize(rawLink) ?: throw UnsafeArchiveException("Жёсткая ссылка на корень: $rel")
                    val srcPath = root.resolve(src)
                    ensureParentInside(srcPath, realRoot)
                    if (!Files.isRegularFile(srcPath, LinkOption.NOFOLLOW_LINKS)) {
                        throw UnsafeArchiveException("Жёсткая ссылка на отсутствующий файл: $rel → $src")
                    }
                    Files.copy(srcPath, target, StandardCopyOption.REPLACE_EXISTING)
                    files++
                    skip(input, padded(size))
                }
                '0', '\u0000', '7' -> {
                    total += size
                    if (total > limits.maxTotalBytes) throw UnsafeArchiveException("Архив превышает допустимый размер")
                    if (Files.isSymbolicLink(target)) Files.delete(target)
                    Files.newOutputStream(target).use { out -> copyExactly(input, out, size) }
                    skip(input, padded(size) - size)
                    applyMode(target, mode)
                    files++
                    onFile(rel, size)
                }
                else -> skip(input, padded(size)) // устройства, FIFO и прочее не нужны
            }
        }
        return Stats(files, dirs, links, total)
    }

    // ---- безопасность ------------------------------------------------------------------

    private fun normalize(name: String): String? {
        val trimmed = name.removePrefix("./").trimEnd('/')
        if (trimmed.isEmpty() || trimmed == ".") return null
        if (!FileOps.isSafeRelative(trimmed)) throw UnsafeArchiveException("Недопустимый путь: $name")
        return trimmed
    }

    /** Родитель должен реально (после разрешения ссылок) находиться внутри корня. */
    private fun ensureParentInside(target: Path, realRoot: Path) {
        val parent = target.parent
        Files.createDirectories(parent)
        if (!parent.toRealPath().startsWith(realRoot)) {
            throw UnsafeArchiveException("Запись за пределы каталога через ссылку: $target")
        }
    }

    /** Цель ссылки — относительная и не выходит за корень установки. */
    private fun checkLinkTarget(rel: String, link: String) {
        if (link.isEmpty() || link.startsWith("/") || link.contains('\u0000')) {
            throw UnsafeArchiveException("Недопустимая символическая ссылка: $rel → $link")
        }
        var depth = rel.count { it == '/' } // глубина каталога, содержащего ссылку
        for (part in link.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (--depth < 0) throw UnsafeArchiveException("Ссылка за пределы каталога: $rel → $link")
                else -> depth++
            }
        }
    }

    private fun applyMode(path: Path, mode: Int) {
        val perms = HashSet<PosixFilePermission>()
        val bits = listOf(
            0b100_000_000 to PosixFilePermission.OWNER_READ, 0b010_000_000 to PosixFilePermission.OWNER_WRITE,
            0b001_000_000 to PosixFilePermission.OWNER_EXECUTE, 0b000_100_000 to PosixFilePermission.GROUP_READ,
            0b000_010_000 to PosixFilePermission.GROUP_WRITE, 0b000_001_000 to PosixFilePermission.GROUP_EXECUTE,
            0b000_000_100 to PosixFilePermission.OTHERS_READ, 0b000_000_010 to PosixFilePermission.OTHERS_WRITE,
            0b000_000_001 to PosixFilePermission.OTHERS_EXECUTE,
        )
        for ((bit, p) in bits) if (mode and bit != 0) perms += p
        perms += PosixFilePermission.OWNER_READ
        perms += PosixFilePermission.OWNER_WRITE
        runCatching { Files.setPosixFilePermissions(path, perms) }
            .onFailure { if (mode and 0b001_001_001 != 0) path.toFile().setExecutable(true, false) }
    }

    // ---- формат ------------------------------------------------------------------------

    private fun verifyChecksum(h: ByteArray) {
        val stored = parseNumber(h, 148, 8)
        var sum = 0L
        for (i in h.indices) sum += if (i in 148 until 156) 0x20 else (h[i].toInt() and 0xFF)
        if (sum != stored) throw IOException("Повреждённый заголовок tar")
    }

    private fun ustarName(h: ByteArray): String {
        val name = cString(h, 0, 100)
        val magic = cString(h, 257, 6)
        val prefix = if (magic.startsWith("ustar")) cString(h, 345, 155) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    private fun cString(h: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && h[end] != 0.toByte()) end++
        return String(h, off, end - off, Charsets.UTF_8)
    }

    /** Восьмеричное число или base-256 (старший бит первого байта) для больших файлов. */
    private fun parseNumber(h: ByteArray, off: Int, len: Int): Long {
        if (h[off].toInt() and 0x80 != 0) {
            var v = (h[off].toLong() and 0x7F)
            for (i in off + 1 until off + len) v = (v shl 8) or (h[i].toLong() and 0xFF)
            return v
        }
        val s = cString(h, off, len).trim()
        return if (s.isEmpty()) 0 else s.toLong(8)
    }

    private fun parsePax(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        val bytes = text.toByteArray(Charsets.UTF_8)
        while (i < bytes.size) {
            val sp = (i until bytes.size).firstOrNull { bytes[it] == ' '.code.toByte() } ?: break
            val len = String(bytes, i, sp - i, Charsets.UTF_8).toIntOrNull() ?: break
            if (len <= 0 || i + len > bytes.size) break
            val record = String(bytes, sp + 1, i + len - sp - 2, Charsets.UTF_8) // без '\n'
            val eq = record.indexOf('=')
            if (eq > 0) out[record.substring(0, eq)] = record.substring(eq + 1)
            i += len
        }
        return out
    }

    private fun readString(input: InputStream, size: Long): String {
        if (size > MAX_META) throw UnsafeArchiveException("Слишком длинная служебная запись")
        val buf = ByteArray(size.toInt())
        if (!readFully(input, buf)) throw EOFException()
        skip(input, padded(size) - size)
        return String(buf, Charsets.UTF_8).trimEnd('\u0000')
    }

    private fun copyExactly(input: InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw EOFException("Архив обрывается")
            out.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(BLOCK)
        while (left > 0) {
            val r = input.read(buf, 0, minOf(left, BLOCK.toLong()).toInt())
            if (r < 0) throw EOFException("Архив обрывается")
            left -= r
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) {
                if (off == 0) return false
                throw EOFException("Архив обрывается")
            }
            off += n
        }
        return true
    }

    private fun padded(size: Long) = (size + BLOCK - 1) / BLOCK * BLOCK

    private companion object {
        const val BLOCK = 512
        const val MAX_META = 1L shl 20
    }
}

