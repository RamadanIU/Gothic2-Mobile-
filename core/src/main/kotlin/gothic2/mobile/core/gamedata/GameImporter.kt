package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.FileOps
import gothic2.mobile.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Копирует установленную игру во внутреннее хранилище приложения.
 *
 * Импорт идёт во временный каталог и становится видимым только после полной копии и
 * повторной проверки EXE (атомарное переименование). Прерванный импорт не оставляет
 * «полуустановленную» игру. Каталоги сохранений не копируются поверх существующих
 * сохранений приложения — их импорт выполняет [gothic2.mobile.core.saves.SaveManager].
 */
class GameImporter(private val target: Path) {

    fun interface Progress {
        fun onProgress(copiedBytes: Long, totalBytes: Long, currentFile: String)
    }

    class Cancelled : Exception("Импорт отменён")

    fun import(
        source: GameTree,
        build: SupportedBuild,
        expectedExeSha256: String,
        progress: Progress = Progress { _, _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        val savesPrefixes = build.allSavesDirs().map { it.lowercase().trimEnd('/') + "/" }
        val files = source.walkFiles()
            .filterNot { (p, _) -> savesPrefixes.any { p.lowercase().startsWith(it) } }
            .toList()
        for ((p, _) in files) require(FileOps.isSafeRelative(p)) { "Недопустимый путь: $p" }
        val total = files.sumOf { it.second }

        recoverInterrupted(build)
        val parent = target.toAbsolutePath().parent
        Files.createDirectories(parent)
        val staging = stagingDir()
        FileOps.deleteTree(staging)
        Files.createDirectories(staging)
        try {
            var copied = 0L
            for ((rel, size) in files) {
                if (isCancelled()) throw Cancelled()
                val dst = staging.resolve(rel)
                Files.createDirectories(dst.parent)
                source.open(rel).use { Files.copy(it, dst) }
                copied += size
                progress.onProgress(copied, total, rel)
            }
            val exe = PathGameTree(staging).resolve(build.exePath)
                ?: error("После копирования не найден ${build.exePath}")
            check(Hashing.sha256(staging.resolve(exe)) == expectedExeSha256) { "EXE изменился при копировании" }
            Files.createDirectories(staging.resolve(build.savesDir))

            val old = oldDir()
            FileOps.deleteTree(old)
            if (target.exists()) FileOps.moveAtomic(target, old)
            val newSaves = staging.resolve(build.savesDir)
            val oldSaves = if (old.exists()) PathGameTree(old).resolve(build.savesDir)?.let(old::resolve) else null
            try {
                // Сохранения пользователя переносятся в новую установку, а не копируются заново.
                if (oldSaves != null) {
                    FileOps.deleteTree(newSaves)
                    FileOps.moveAtomic(oldSaves, newSaves)
                }
                FileOps.moveAtomic(staging, target)
            } catch (e: Exception) {
                // Откат: сохранения и прежняя установка возвращаются на место.
                if (!target.exists()) {
                    if (oldSaves != null && !oldSaves.exists() && newSaves.exists()) FileOps.moveAtomic(newSaves, oldSaves)
                    if (old.exists()) FileOps.moveAtomic(old, target)
                }
                throw e
            }
            FileOps.deleteTree(old)
        } catch (e: Exception) {
            FileOps.deleteTree(staging)
            throw e
        }
    }

    /**
     * Восстановление после принудительного завершения процесса посреди замены установки.
     * Вызывать при старте приложения и перед импортом: гарантирует, что сохранения
     * пользователя не останутся во временных каталогах, которые затем удаляются.
     */
    fun recoverInterrupted(build: SupportedBuild) {
        val old = oldDir()
        val staging = stagingDir()
        if (!target.exists() && old.exists()) {
            val stagingSaves = staging.resolve(build.savesDir)
            if (PathGameTree(old).resolve(build.savesDir) == null && stagingSaves.exists()) {
                FileOps.moveAtomic(stagingSaves, old.resolve(build.savesDir))
            }
            FileOps.moveAtomic(old, target)
        }
        if (old.exists() && PathGameTree(old).resolve(build.savesDir) != null) {
            // Не удаляем каталог, в котором ещё лежат сохранения, — оставляем для ручного разбора.
            FileOps.moveAtomic(old, old.resolveSibling("${old.fileName}-${System.currentTimeMillis()}"))
        }
        FileOps.deleteTree(old)
    }

    private fun stagingDir(): Path = target.toAbsolutePath().resolveSibling(".${target.fileName}.importing")

    private fun oldDir(): Path = target.toAbsolutePath().resolveSibling(".${target.fileName}.old")
}
