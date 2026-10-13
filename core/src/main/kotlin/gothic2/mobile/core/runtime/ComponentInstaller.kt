package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.FileOps
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Установка закреплённых компонентов среды (Wine, Box64, rootfs, DXVK, обёртка) из архивов.
 *
 * - Хэш архива проверяется по ходу распаковки; при несовпадении распакованное удаляется
 *   и компонент не появляется (распаковка идёт во временный каталог, затем атомарный перенос).
 * - Повторная установка той же версии пропускается по маркеру — подготовка среды идемпотентна.
 * - Незакреплённые компоненты (sha256=PENDING) ставятся только в режиме разработчика.
 * - Формат определяется по расширению. Встроены `.tar`, `.tar.gz`/`.tgz`; остальные
 *   (например, `.tzst` у Winlator) подключаются через [decompressors].
 */
class ComponentInstaller(
    private val installRoot: Path,
    private val openArchive: (RuntimeComponent) -> InputStream,
    private val decompressors: Map<String, (InputStream) -> InputStream> = emptyMap(),
    private val extractor: TarExtractor = TarExtractor(),
) {
    class ComponentException(val componentId: String, message: String) : IOException(message)

    sealed interface Outcome {
        val component: RuntimeComponent
        data class Installed(override val component: RuntimeComponent, val stats: TarExtractor.Stats) : Outcome
        data class AlreadyInstalled(override val component: RuntimeComponent) : Outcome
    }

    fun dirOf(id: String): Path = installRoot.resolve(id)

    fun isInstalled(c: RuntimeComponent): Boolean {
        val marker = dirOf(c.id).resolve(MARKER)
        return marker.exists() && marker.readText() == markerText(c)
    }

    fun install(c: RuntimeComponent, allowUnpinned: Boolean = false, onFile: (String, Long) -> Unit = { _, _ -> }): Outcome {
        if (!c.isPinned && !allowUnpinned) throw ComponentException(c.id, "Компонент ${c.id} не закреплён (sha256=PENDING)")
        if (isInstalled(c)) return Outcome.AlreadyInstalled(c)

        Files.createDirectories(installRoot)
        val staging = installRoot.resolve(".${c.id}.installing")
        FileOps.deleteTree(staging)
        try {
            val md = MessageDigest.getInstance("SHA-256")
            val stats = openArchive(c).use { raw ->
                val hashed = DigestInputStream(raw, md)
                val stream = decompress(c, hashed)
                val s = extractor.extract(stream, staging, onFile)
                // Хэш покрывает весь файл: дочитываем хвост после конца tar.
                val buf = ByteArray(64 * 1024)
                while (hashed.read(buf) >= 0) Unit
                s
            }
            val actual = md.digest().joinToString("") { "%02x".format(it) }
            if (c.sha256 != null && actual != c.sha256) {
                throw ComponentException(c.id, "Контрольная сумма ${c.id} не совпадает: $actual")
            }
            staging.resolve(MARKER).writeText(markerText(c))
            val target = dirOf(c.id)
            val old = installRoot.resolve(".${c.id}.old")
            FileOps.deleteTree(old)
            if (target.exists()) FileOps.moveAtomic(target, old)
            FileOps.moveAtomic(staging, target)
            FileOps.deleteTree(old)
            return Outcome.Installed(c, stats)
        } catch (e: Exception) {
            FileOps.deleteTree(staging)
            throw e
        }
    }

    /** Ставит обязательные компоненты по порядку; первый сбой прерывает подготовку. */
    fun ensure(components: RuntimeComponents, ids: List<String>, allowUnpinned: Boolean = false): List<Outcome> =
        ids.map { id ->
            val c = components[id] ?: throw ComponentException(id, "Компонент $id отсутствует в манифесте")
            install(c, allowUnpinned)
        }

    private fun decompress(c: RuntimeComponent, input: InputStream): InputStream {
        val name = c.archive.lowercase()
        return when {
            name.endsWith(".tar") -> input
            name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(input, 64 * 1024)
            else -> {
                val ext = decompressors.keys.firstOrNull { name.endsWith(it) }
                    ?: throw ComponentException(c.id, "Формат архива ${c.archive} не поддерживается")
                decompressors.getValue(ext)(input)
            }
        }
    }

    private fun markerText(c: RuntimeComponent) = "${c.id} ${c.version} ${c.sha256 ?: "unpinned"}"

    private companion object {
        const val MARKER = ".g2m-installed"
    }
}
