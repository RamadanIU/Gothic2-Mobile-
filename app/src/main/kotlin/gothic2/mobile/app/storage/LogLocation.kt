package gothic2.mobile.app.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat
import gothic2.mobile.app.BuildConfig
import java.nio.file.Files
import java.nio.file.Path

/**
 * Где лежат журналы. Игрок должен находить их сам, без экспорта, поэтому в порядке предпочтения:
 * 1. «Загрузки/Gothic2Mobile/logs» — видна в любом диспетчере файлов; нужно разрешение «Файлы»
 *    (его спрашивают один раз при первом запуске, оно же нужно для игры из папки игрока);
 * 2. Android/data/<пакет>/files/logs — без разрешения, видна с компьютера по USB;
 * 3. внутреннее хранилище приложения — последний запасной вариант.
 */
object LogLocation {
    /** Отдельная папка для debug-сборки: она ставится рядом с release и не должна путать журналы. */
    private val folder = if (BuildConfig.DEBUG) "logs-debug" else "logs"

    fun hasStoragePermission(context: Context): Boolean = STORAGE_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Папки-кандидаты в порядке предпочтения (доступные сейчас). */
    fun candidates(context: Context, internal: Path): List<Path> = buildList {
        if (hasStoragePermission(context)) {
            @Suppress("DEPRECATION")
            add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath().resolve("Gothic2Mobile/$folder"))
        }
        context.getExternalFilesDir(null)?.let { add(it.toPath().resolve("logs")) }
        add(internal)
    }

    /** Первая папка, в которую действительно получается писать. */
    fun choose(context: Context, internal: Path): Path =
        candidates(context, internal).firstOrNull { writable(it) } ?: internal

    /** Все известные места журналов — чтобы перенести старые файлы в выбранную папку. */
    fun all(context: Context, internal: Path): List<Path> = buildList {
        @Suppress("DEPRECATION")
        add(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toPath().resolve("Gothic2Mobile/$folder"))
        context.getExternalFilesDir(null)?.let { add(it.toPath().resolve("logs")) }
        add(internal)
    }

    private fun writable(dir: Path): Boolean = runCatching {
        Files.createDirectories(dir)
        val probe = dir.resolve(".probe")
        Files.write(probe, byteArrayOf(1))
        Files.delete(probe)
    }.isSuccess

    val STORAGE_PERMISSIONS = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}
