package gothic2.mobile.app.storage

import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.io.path.appendText
import kotlin.io.path.readText

/**
 * Журнал диагностики для воспроизведения проблем. Пишет только технические события
 * (состояние среды, ошибки, переходы режимов) — без путей пользовательских файлов вне
 * приложения и без содержимого сохранений. Ротация по размеру.
 */
class Diagnostics(private val dir: Path, private val maxBytes: Long = 1_000_000) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "diagnostics").apply { isDaemon = true } }
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    val current: Path get() = dir.resolve("session.log")
    val previous: Path get() = dir.resolve("session.1.log")

    fun log(tag: String, message: String) {
        val line = "${format.format(Date())} [$tag] ${message.replace('\n', ' ')}\n"
        executor.execute {
            runCatching {
                if (Files.exists(current) && Files.size(current) > maxBytes) {
                    Files.deleteIfExists(previous)
                    Files.move(current, previous)
                }
                current.appendText(line)
            }
        }
    }

    /** Вывод Wine и Box64 последнего запуска игры (ошибки, причины падения). */
    val wineLog: Path get() = dir.resolve("wine.log")
    private var wineBytes = 0L

    /** Новый запуск — новый журнал Wine: прошлый не нужен, а размер ограничен. */
    fun beginWineLog() = executor.execute {
        wineBytes = 0
        runCatching { Files.deleteIfExists(wineLog) }
    }

    fun wine(line: String) = executor.execute {
        if (wineBytes > maxWineBytes) return@execute
        val text = if (wineBytes + line.length >= maxWineBytes) "… журнал Wine обрезан\n" else line + "\n"
        wineBytes += text.length
        runCatching { wineLog.appendText(text) }
    }

    /** Склеенный текст журнала для экспорта. */
    fun snapshot(): String = buildString {
        for (p in listOf(previous, current)) if (Files.exists(p)) append(p.readText())
        if (Files.exists(wineLog)) append("\n===== Wine / Box64 (последний запуск) =====\n").append(wineLog.readText())
    }

    private val maxWineBytes get() = maxBytes * 2
}
