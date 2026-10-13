package gothic2.mobile.core.util

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

object FileOps {
    /** Рекурсивное удаление; безопасно для отсутствующего пути. */
    fun deleteTree(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { s -> s.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { s ->
            s.forEach { src ->
                val dst = to.resolve(from.relativize(src).toString())
                if (src.isDirectory()) Files.createDirectories(dst)
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    /** Атомарное переименование в пределах одной файловой системы. */
    fun moveAtomic(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    /**
     * Проверка относительного пути из внешнего источника (архив, импорт):
     * без абсолютных путей, «..», пустых сегментов и обратных слэшей.
     */
    fun isSafeRelative(rel: String): Boolean {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains('\\') || rel.contains('\u0000')) return false
        if (rel.length >= 2 && rel[1] == ':') return false
        return rel.trimEnd('/').split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
