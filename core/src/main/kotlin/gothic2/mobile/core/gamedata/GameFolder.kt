package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.FileOps
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText

/**
 * Игра, которая запускается прямо из папки игрока на накопителе, без копии в приложение
 * ([GameImporter] — путь с копией). Папку игрок выбирает сам; приложение в ней пишет то же,
 * что и Gothic на ПК: `Gothic.ini`, сохранения, а на время игры в классику — переименованные
 * архивы аддона ([VariantLayout]).
 */
object GameFolder {
    /** Итог переноса сохранений: скопировано слотов и оставлено (такой слот в папке уже был). */
    data class SavesMigration(val copied: Int, val kept: Int)

    /**
     * Проверить, что в папку можно писать (без этого Gothic не сохранит игру), и создать
     * каталоги сохранений всех вариантов — Gothic сама их не создаёт (у классики — `Saves_GII`).
     *
     * @return созданные каталоги (относительные пути)
     */
    fun prepare(root: Path, build: SupportedBuild): List<String> {
        val probe = root.resolve(PROBE)
        try {
            probe.writeText("ok")
        } catch (e: IOException) {
            throw IOException("Нет доступа на запись в папку игры (${e.message})", e)
        } finally {
            runCatching { probe.deleteIfExists() }
        }
        val tree = PathGameTree(root)
        return build.allSavesDirs().map { it.trimEnd('/') }.filter { tree.resolve(it) == null }.onEach {
            Files.createDirectories(root.resolve(it))
        }
    }

    /**
     * Перенести сохранения из прежней копии игры [from] (в приложении) в папку [to]: копируются
     * слоты, которых в папке нет; слот с тем же именем в папке не перезаписывается. Каталог
     * `current` (рабочая копия текущей игры Gothic) не переносится.
     */
    fun copyMissingSaves(from: Path, to: Path, build: SupportedBuild): SavesMigration {
        var copied = 0
        var kept = 0
        val fromTree = PathGameTree(from)
        val toTree = PathGameTree(to)
        for (dir in build.allSavesDirs().map { it.trimEnd('/') }) {
            val src = fromTree.resolve(dir)?.let { from.resolve(it) }?.takeIf { it.isDirectory() } ?: continue
            val dst = to.resolve(toTree.resolve(dir) ?: dir)
            Files.createDirectories(dst)
            for (slot in src.listDirectoryEntries().sortedBy { it.name }) {
                if (slot.name.equals(CURRENT, ignoreCase = true)) continue
                if (dst.listDirectoryEntries().any { it.name.equals(slot.name, ignoreCase = true) }) {
                    kept++
                    continue
                }
                val target = dst.resolve(slot.name)
                if (slot.isDirectory()) FileOps.copyTree(slot, target) else Files.copy(slot, target)
                copied++
            }
        }
        return SavesMigration(copied, kept)
    }

    private const val PROBE = ".gothic2mobile-probe"
    private const val CURRENT = "current"
}
