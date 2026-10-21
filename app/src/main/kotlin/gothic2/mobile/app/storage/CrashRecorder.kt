package gothic2.mobile.app.storage

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.nio.file.Files

/**
 * Почему завершился прошлый процесс приложения. Если вылетает всё приложение (а не только игра),
 * в журнале Wine причины нет: процесс X-сервера и рендерера — наш. Android хранит причину сам
 * (ApplicationExitInfo: падение Java или нативного кода, нехватка памяти, сигнал) — пишем её
 * в журнал при следующем запуске. Плюс перехват необработанных исключений Java.
 */
object CrashRecorder {
    private const val KEY_LAST_EXIT = "last_logged_exit_timestamp"
    private val CRASH_REASONS = setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR)

    /**
     * [onCrashExit] — прошлый процесс упал (Java, нативный код, ANR): лаунчер должен показать экран
     * сбоя, а не запускать игру сразу — иначе ошибка при старте игры даёт бесконечный цикл вылетов.
     */
    fun install(context: Context, diagnostics: Diagnostics, onCrashExit: () -> Unit) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Синхронно: после обработчика процесс завершается и очередь журнала не допишется.
            runCatching { diagnostics.logNow("crash", "uncaught in ${thread.name}: ${error.stackTraceToString().take(6000)}") }
            runCatching { diagnostics.flush() }
            previous?.uncaughtException(thread, error)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) logPreviousExits(context, diagnostics, onCrashExit)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun logPreviousExits(context: Context, diagnostics: Diagnostics, onCrashExit: () -> Unit) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val lastLogged = prefs.getLong(KEY_LAST_EXIT, 0)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val exits = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, 5) }.getOrNull().orEmpty()
            .filter { it.timestamp > lastLogged }
            .sortedBy { it.timestamp }
        for (e in exits) {
            diagnostics.log(
                "exit",
                "prev process: reason=${reasonName(e.reason)} status=${e.status} importance=${e.importance} " +
                    "pss=${e.pss}kB rss=${e.rss}kB desc=${e.description}",
            )
            // Для ANR Android отдаёт стеки всех потоков текстом — сохраняем рядом с журналом.
            if (e.reason == ApplicationExitInfo.REASON_ANR) saveTrace(e, diagnostics)
            if (e.reason in CRASH_REASONS) onCrashExit()
        }
        exits.maxOfOrNull { it.timestamp }?.let { ts -> prefs.edit { putLong(KEY_LAST_EXIT, ts) } }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun saveTrace(e: ApplicationExitInfo, diagnostics: Diagnostics) {
        runCatching {
            val text = e.traceInputStream?.use { it.readBytes() } ?: return
            val file = diagnostics.dir.resolve("anr-trace.txt")
            Files.write(file, text)
            diagnostics.log("exit", "ANR trace saved: ${file.fileName}")
        }.onFailure { diagnostics.log("exit", "ANR trace failed: $it") }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "UNKNOWN($reason)"
    }
}
