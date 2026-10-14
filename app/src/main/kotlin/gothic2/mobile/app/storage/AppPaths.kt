package gothic2.mobile.app.storage

import android.content.Context
import java.nio.file.Files
import java.nio.file.Path

/**
 * Раскладка данных приложения — в постоянном внутреннем хранилище (не в кэше), чтобы
 * обновление приложения и смена графического профиля их не трогали. [gameRoot] — копия игры,
 * если её переносили в приложение; обычно игра запускается из папки игрока
 * (GothicApplication.gameRoot).
 */
class AppPaths(context: Context) {
    private val files: Path = context.filesDir.toPath()
    private val cache: Path = context.cacheDir.toPath()

    val gameRoot: Path = files.resolve("game")
    val backups: Path = files.resolve("save-backups")
    val runtime: Path = files.resolve("runtime")
    val components: Path = runtime.resolve("components")
    val bridgeSocket: Path = files.resolve("bridge/adapter.sock")
    val sessionMarker: Path = files.resolve("session.marker")
    val wineprefix: Path = runtime.resolve("prefix")
    val logs: Path = files.resolve("logs")
    val shaderCache: Path = cache.resolve("shaders")

    init {
        listOf(backups, runtime, logs, shaderCache).forEach { Files.createDirectories(it) }
    }
}
