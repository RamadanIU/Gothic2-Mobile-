package gothic2.mobile.core.gamedata

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * Доступ к дереву файлов игры. Пути относительные, разделитель «/», поиск имён без учёта
 * регистра (у Windows-игр регистр в разных поставках различается). На Android реализуется
 * поверх Storage Access Framework, в тестах и во внутреннем хранилище — поверх [Path].
 */
interface GameTree {
    /** Имена элементов каталога (пустая строка — корень). */
    fun list(dir: String): List<Entry>

    fun open(file: String): InputStream

    data class Entry(val name: String, val isDirectory: Boolean, val size: Long)

    /** Находит реальный путь с учётом регистра или null. */
    fun resolve(path: String): String? {
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            val match = list(current).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            current = if (current.isEmpty()) match.name else "$current/${match.name}"
        }
        return current
    }

    /** Все файлы дерева (рекурсивно), относительные пути. */
    fun walkFiles(dir: String = ""): Sequence<Pair<String, Long>> = sequence {
        for (e in list(dir)) {
            val p = if (dir.isEmpty()) e.name else "$dir/${e.name}"
            if (e.isDirectory) yieldAll(walkFiles(p)) else yield(p to e.size)
        }
    }
}

class PathGameTree(private val root: Path) : GameTree {
    override fun list(dir: String): List<GameTree.Entry> {
        val d = if (dir.isEmpty()) root else root.resolve(dir)
        if (!d.isDirectory()) return emptyList()
        return d.listDirectoryEntries().map {
            GameTree.Entry(it.name, it.isDirectory(), if (it.isRegularFile()) Files.size(it) else 0)
        }.sortedBy { it.name }
    }

    override fun open(file: String): InputStream = Files.newInputStream(root.resolve(file))
}
