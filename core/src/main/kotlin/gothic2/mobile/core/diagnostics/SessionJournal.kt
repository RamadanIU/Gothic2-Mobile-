package gothic2.mobile.core.diagnostics

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Отметка «игра запущена». Если при следующем старте отметка на месте, предыдущий сеанс
 * завершился не штатно (Android выгрузил процесс, сбой, смахивание из списка задач) —
 * приложение показывает экран восстановления вместо молчаливого запуска (раздел 17 плана).
 */
class SessionJournal(private val marker: Path, private val clock: () -> Long = System::currentTimeMillis) {

    data class Interrupted(val startedAt: Long, val lastHeartbeat: Long)

    /** Проверяет и сбрасывает отметку прошлого сеанса. Вызывать один раз при старте. */
    fun takeInterrupted(): Interrupted? {
        if (!marker.exists()) return null
        val parts = runCatching { marker.readText().trim().split(' ').map { it.toLong() } }.getOrNull()
        Files.deleteIfExists(marker)
        val start = parts?.getOrNull(0) ?: 0
        return Interrupted(start, parts?.getOrNull(1) ?: start)
    }

    fun begin() {
        Files.createDirectories(marker.parent)
        val now = clock()
        marker.writeText("$now $now")
    }

    /** Периодическая отметка «ещё жив» — помогает понять, когда именно прервался сеанс. */
    fun heartbeat() {
        if (!marker.exists()) return
        val start = runCatching { marker.readText().trim().split(' ')[0] }.getOrDefault(clock().toString())
        marker.writeText("$start ${clock()}")
    }

    /** Штатное завершение: игра вышла сама или игрок вышел через меню. */
    fun end() {
        Files.deleteIfExists(marker)
    }
}
