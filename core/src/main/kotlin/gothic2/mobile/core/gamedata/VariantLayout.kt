package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.FileOps
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Переименование файла игры на время запуска варианта ([GameVariant.renames]).
 * Пути относительные, как в `supported_builds.ini`.
 */
data class FileRename(val from: String, val to: String)

/**
 * Раскладка файлов варианта запуска. Классическая Gothic II в GOG-издании запускается через
 * `Gothic2c.bat`: на время игры он прячет архивы аддона и подменяет `System/Paths.d`, а после
 * выхода возвращает всё обратно. Здесь то же самое без .bat: [apply] перед запуском,
 * [restore] после выхода игры и при старте приложения.
 *
 * Отметка [MARKER] в корне игры пишется до первого переименования и удаляется только после
 * полного возврата, поэтому прерванная сессия (падение, убитый процесс) откатывается при
 * следующем [restore]. Без отметки [restore] ничего не трогает. Откат идёт в обратном порядке
 * и пропускает уже возвращённые файлы, так что его можно повторять.
 *
 * Если на месте назначения уже лежит файл (в папке игры и `ddraw.dll`, и `ddraw.dll.gd3d11` без
 * отметки — так было у второго игрока), запуск не останавливается: копия того же содержимого
 * удаляется, другой файл откладывается под свободным именем `<имя>.old` — ничего не теряется.
 */
class VariantLayout(private val root: Path) {
    private val tree = PathGameTree(root)

    class LayoutException(message: String) : Exception(message)

    /** Привести файлы к раскладке [variant]; перед этим вернуть раскладку прошлого запуска. */
    fun apply(build: SupportedBuild, variant: GameVariant) {
        restore(build)
        if (variant.renames.isEmpty()) return
        writeMarker(variant.id)
        for (r in variant.renames) {
            // Как rename в .bat: если исходного файла нет (SPLASH.BMP в GOG), шаг пропускается.
            val from = tree.resolve(r.from) ?: continue
            tree.resolve(r.to)?.let { clearTarget(root.resolve(from), root.resolve(it)) }
            FileOps.moveAtomic(root.resolve(from), target(r.to))
        }
    }

    /** Освободить место назначения: та же копия удаляется, другой файл откладывается рядом. */
    private fun clearTarget(from: Path, existing: Path) {
        if (Files.isRegularFile(existing) && sameContent(from, existing)) {
            Files.delete(existing)
            return
        }
        val name = existing.fileName.toString()
        val aside = (0..MAX_ASIDE).asSequence()
            .map { existing.resolveSibling(if (it == 0) "$name.old" else "$name.old$it") }
            .firstOrNull { !Files.exists(it) }
            ?: throw LayoutException("Не удалось подготовить файлы игры: ${existing.fileName} уже существует")
        FileOps.moveAtomic(existing, aside)
    }

    private fun sameContent(a: Path, b: Path): Boolean {
        if (!Files.isRegularFile(a) || Files.size(a) != Files.size(b)) return false
        val bufA = ByteArray(COMPARE_BUFFER)
        val bufB = ByteArray(COMPARE_BUFFER)
        Files.newInputStream(a).use { x ->
            Files.newInputStream(b).use { y ->
                while (true) {
                    val n = fill(x, bufA)
                    if (n != fill(y, bufB)) return false
                    if (n == 0) return true
                    for (i in 0 until n) if (bufA[i] != bufB[i]) return false
                }
            }
        }
    }

    /** Прочитать сколько влезет в [buf] (InputStream.readNBytes — только с Java 9). */
    private fun fill(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** Вернуть файлы на место, если раскладка какого-либо варианта осталась применённой. */
    fun restore(build: SupportedBuild) {
        val marker = root.resolve(MARKER)
        if (!marker.exists()) return
        val id = marker.readText().trim()
        // Если вариант из отметки пропал из манифеста (обновление приложения), откатываются все.
        val variants = build.variants.filter { it.id == id }.ifEmpty { build.variants }
        for (variant in variants) {
            for (r in variant.renames.asReversed()) {
                if (tree.resolve(r.from) != null) continue
                val moved = tree.resolve(r.to) ?: continue
                FileOps.moveAtomic(root.resolve(moved), target(r.from))
            }
        }
        marker.deleteIfExists()
    }

    /**
     * Создать каталог сохранений [variant], если его нет (поиск без учёта регистра).
     * Gothic сама создаёт только `savegameN` внутри него, а без самого каталога падает при
     * первом сохранении — так было с `Saves_GII` классики: импорт его не создавал.
     *
     * @return относительный путь созданного каталога или null, если он уже был
     */
    fun ensureSavesDir(build: SupportedBuild, variant: GameVariant): String? {
        val rel = build.savesDirOf(variant).trimEnd('/')
        if (tree.resolve(rel) != null) return null
        Files.createDirectories(target(rel))
        return rel
    }

    /** Вариант, раскладка которого сейчас применена, или null. */
    fun appliedVariantId(): String? = root.resolve(MARKER).takeIf { it.exists() }?.readText()?.trim()

    /** Путь назначения: каталог ищется без учёта регистра, имя файла берётся из манифеста. */
    private fun target(rel: String): Path {
        val dir = rel.substringBeforeLast('/', "")
        val realDir = if (dir.isEmpty()) "" else tree.resolve(dir) ?: throw LayoutException("Нет каталога $dir")
        return root.resolve(realDir).resolve(rel.substringAfterLast('/'))
    }

    private fun writeMarker(variantId: String) {
        val tmp = root.resolve("$MARKER.tmp")
        tmp.writeText(variantId)
        FileOps.moveAtomic(tmp, root.resolve(MARKER))
    }

    companion object {
        const val MARKER = ".gothic2mobile-variant"
        private const val MAX_ASIDE = 99
        private const val COMPARE_BUFFER = 64 * 1024
    }
}
