package gothic2.mobile.app.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Системный журнал Android (logcat) нашего процесса — в файл, в реальном времени. Его пишет
 * отдельный процесс `logcat -f`, поэтому запись не зависит от потоков приложения и продолжается до
 * самого падения. Туда попадают и причины вылета, которых нет в журнале Wine: падение нативного
 * кода (графический драйвер, X-сервер, звук) с обратной трассировкой, исключения Java, ANR.
 * Приложение видит в logcat только свои строки — разрешение не нужно.
 *
 * При старте процесс logcat сначала выгружает весь буфер — в том числе строки прошлого процесса
 * перед его гибелью; файл прошлого процесса сохраняется как [PREVIOUS].
 */
object LogcatCapture {
    const val CURRENT = "logcat.log"
    const val PREVIOUS = "logcat-prev.log"

    /** Размер одного файла, КБ, и число файлов прокрутки (logcat.log.1 … .N). */
    private const val ROTATE_KB = 2048
    private const val ROTATE_COUNT = 2

    private var process: Process? = null

    /** Файлы прошлого процесса — в logcat-prev*, текущий процесс начинает с пустого файла. */
    fun keepPrevious(dir: Path) {
        runCatching {
            for (i in 0..ROTATE_COUNT) Files.deleteIfExists(dir.resolve(PREVIOUS + suffix(i)))
            for (i in 0..ROTATE_COUNT) {
                val from = dir.resolve(CURRENT + suffix(i))
                if (Files.exists(from)) Files.move(from, dir.resolve(PREVIOUS + suffix(i)), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /**
     * Запустить запись в [dir]. [fromNow] — без выгрузки старого буфера (при переезде журнала в другую
     * папку: старые строки уже в перенесённом файле).
     */
    @Synchronized
    fun start(dir: Path, fromNow: Boolean): Result<Unit> = runCatching {
        stop()
        val command = mutableListOf(
            "logcat", "-v", "threadtime", "-b", "main,system,crash",
            "-f", dir.resolve(CURRENT).toString(), "-r", ROTATE_KB.toString(), "-n", ROTATE_COUNT.toString(),
        )
        if (fromNow) command += listOf("-T", "1")
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir.toFile(), "logcat-errors.txt")))
            .start()
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    private fun suffix(i: Int) = if (i == 0) "" else ".$i"
}
