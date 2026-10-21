package gothic2.mobile.app.storage

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Журнал диагностики для воспроизведения проблем. Пишет только технические события
 * (состояние среды, ошибки, переходы режимов) — без путей пользовательских файлов вне
 * приложения и без содержимого сохранений.
 *
 * Журнал пишется всегда и сразу: каждая строка — прямая запись в файл (без очереди и буфера в
 * процессе), поэтому при вылете в файле остаётся всё до последней строки. Длинный вывод Wine не
 * обрезается, а прокручивается: начало запуска ([RollingLog.keepHead]) и последние мегабайты —
 * то, что было перед вылетом.
 *
 * Файлы лежат в [dir] — папке, которую видит игрок (см. LogLocation); при сбое записи журнал
 * переходит в [fallback] (внутреннее хранилище), чтобы не потерять ни строки.
 */
class Diagnostics(dir: Path, private val fallback: Path? = null, private val maxBytes: Long = 1_000_000) {
    @Volatile
    var dir: Path = dir
        private set

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val session = RollingLog("session", maxBytes, keepHead = false)
    private val wineOut = RollingLog("wine", maxBytes * 2, keepHead = true)

    init {
        runCatching { Files.createDirectories(dir) }
    }

    val current: Path get() = session.file()
    val previous: Path get() = dir.resolve("session.1.log")

    @Synchronized
    fun log(tag: String, message: String) {
        write(session, "${format.format(Date())} [$tag] ${message.replace('\n', ' ')}\n")
    }

    /** То же, что [log]: запись и так синхронная. Оставлено для обработчика падений. */
    fun logNow(tag: String, message: String) = log(tag, message)

    /** Сбросить записанное на накопитель — перед завершением процесса. */
    @Synchronized
    fun flush() {
        session.sync()
        wineOut.sync()
    }

    /** Новый запуск игры — новый журнал Wine; журнал прошлого запуска сохраняется рядом (wine-prev). */
    @Synchronized
    fun beginWineLog() {
        wineOut.close()
        runCatching {
            for ((from, to) in wineOut.names().zip(wineOut.names(PREVIOUS_WINE))) {
                Files.deleteIfExists(dir.resolve(to))
                if (Files.exists(dir.resolve(from))) Files.move(dir.resolve(from), dir.resolve(to))
            }
        }
    }

    /** Строка вывода Wine и Box64. */
    @Synchronized
    fun wine(line: String) {
        write(wineOut, line + "\n")
    }

    /**
     * Перенести журналы в другую папку и писать дальше туда (игрок дал разрешение «Файлы» —
     * журнал переезжает в видимую папку). Файлы из старой папки переносятся, если новее.
     */
    @Synchronized
    fun relocate(target: Path) {
        if (target == dir) return
        session.close()
        wineOut.close()
        runCatching {
            Files.createDirectories(target)
            migrate(dir, target)
            dir = target
        }
    }

    /** Склеенный текст журнала для экспорта. */
    @Synchronized
    fun snapshot(): String = buildString {
        flush()
        for (name in listOf("session.1.log", "session.log")) appendFile(name)
        appendSection("Wine / Box64 (предыдущий запуск)", wineOut.names(PREVIOUS_WINE))
        appendSection("Wine / Box64 (последний запуск)", wineOut.names())
        appendSection("Системный журнал (прошлый процесс, конец)", listOf(LogcatCapture.PREVIOUS), tail = LOGCAT_TAIL)
        appendSection("Системный журнал (текущий процесс, конец)", listOf(LogcatCapture.CURRENT), tail = LOGCAT_TAIL)
    }

    /** Файл с журналом для отправки (через «Поделиться»); вызывать не из UI-потока. */
    fun exportToFile(target: Path): Path {
        Files.createDirectories(target.parent)
        target.writeText(snapshot().ifEmpty { "(журнал пуст)\n" })
        return target
    }

    private fun StringBuilder.appendSection(title: String, names: List<String>, tail: Int = Int.MAX_VALUE) {
        if (names.none { Files.exists(dir.resolve(it)) }) return
        append("\n===== ").append(title).append(" =====\n")
        for (name in names) appendFile(name, tail)
    }

    private fun StringBuilder.appendFile(name: String, tail: Int = Int.MAX_VALUE) {
        val file = dir.resolve(name).toFile()
        if (!file.isFile) return
        runCatching {
            val skip = (file.length() - tail).coerceAtLeast(0)
            if (skip > 0) append("… начало файла пропущено (").append(skip / 1024).append(" КБ)\n")
            file.inputStream().use { input ->
                input.skip(skip)
                append(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }

    /** Запись строки; если папка недоступна (отозвали разрешение, извлекли карту) — запасная папка. */
    private fun write(log: RollingLog, text: String) {
        try {
            log.append(text)
        } catch (e: IOException) {
            val spare = fallback ?: return
            if (spare == dir) return
            session.close()
            wineOut.close()
            dir = spare
            runCatching {
                Files.createDirectories(spare)
                session.append("${format.format(Date())} [log] запись в папку журнала не удалась ($e), журнал продолжается здесь\n")
                log.append(text)
            }
        }
    }

    /**
     * Файл журнала, открытый на дописывание. Запись без буфера: строка сразу уходит в ядро и
     * переживает падение процесса. При переполнении текущий файл становится `.1` (или `.head`,
     * если начало ещё не сохранено), и запись продолжается в новый.
     */
    private inner class RollingLog(private val base: String, private val limit: Long, private val keepHead: Boolean) {
        private var out: FileOutputStream? = null
        private var size = 0L

        fun file(): Path = dir.resolve("$base.log")

        /** Имена файлов от старых к новым: начало, предыдущая часть, текущая. */
        fun names(prefix: String = base): List<String> =
            listOfNotNull("$prefix.head.log".takeIf { keepHead }, "$prefix.1.log", "$prefix.log")

        fun append(text: String) {
            val bytes = text.toByteArray()
            val stream = out ?: open()
            if (size > 0 && size + bytes.size > limit) {
                rotate()
                return append(text)
            }
            stream.write(bytes)
            size += bytes.size
        }

        fun sync() {
            runCatching { out?.fd?.sync() }
        }

        fun close() {
            runCatching { out?.close() }
            out = null
        }

        private fun open(): FileOutputStream {
            val f: File = file().toFile()
            return FileOutputStream(f, true).also {
                out = it
                size = f.length()
            }
        }

        private fun rotate() {
            close()
            val head = dir.resolve("$base.head.log")
            if (keepHead && !Files.exists(head)) {
                Files.move(file(), head)
            } else {
                Files.move(file(), dir.resolve("$base.1.log"), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        private const val PREVIOUS_WINE = "wine-prev"
        private const val LOGCAT_TAIL = 400_000

        /** Перенести файлы журнала из [from] в [to]; более старые, чем уже лежащие в [to], удаляются. */
        fun migrate(from: Path, to: Path) {
            if (from == to || !Files.isDirectory(from)) return
            for (file in from.listDirectoryEntries().filter { it.isRegularFile() }) {
                runCatching {
                    val target = to.resolve(file.name)
                    if (!Files.exists(target) || Files.getLastModifiedTime(file) > Files.getLastModifiedTime(target)) {
                        moveFile(file, target)
                    } else {
                        Files.delete(file)
                    }
                }
            }
        }

        /** Перенос между файловыми системами (внутренняя память → общая) — копированием. */
        private fun moveFile(from: Path, to: Path) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: IOException) {
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
                Files.delete(from)
            }
        }
    }
}
