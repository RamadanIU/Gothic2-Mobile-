package gothic2.mobile.core.runtime

import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.util.Hashing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * Подготовка графических DLL рядом с EXE во внутренней копии игры (раздел 6 плана).
 *
 * Современное GOG-издание ставит графический мод GD3D11 со своим `ddraw.dll`. По плану
 * графические моды по умолчанию не включаются, поэтому:
 * - цепочка WineD3D файлы не трогает: `ddraw=b` в окружении запуска заставляет Wine взять
 *   встроенную библиотеку, и сторонний `ddraw.dll` не загружается;
 * - цепочка D3D7→D3D9→DXVK кладёт на место `ddraw.dll` закреплённую обёртку, а чужой файл
 *   переименовывает (не удаляет) — операция обратима и касается только копии в приложении.
 */
object RendererSetup {
    const val DISABLED_SUFFIX = ".g2m-disabled"

    class MissingWrapperException : IOException("Обёртка D3D7→D3D9 не установлена")

    sealed interface Change {
        data class Disabled(val file: String) : Change
        data class Installed(val file: String) : Change
    }

    /**
     * @param exeDir каталог EXE относительно корня игры (например, `System`)
     * @param wrapperDll файл закреплённой обёртки из компонентов среды; нужен для цепочки DXVK
     */
    fun prepare(gameRoot: Path, exeDir: String, chain: RendererChain, wrapperDll: Path?): List<Change> {
        if (chain == RendererChain.WINED3D) return emptyList()
        val wrapper = wrapperDll?.takeIf { it.exists() } ?: throw MissingWrapperException()
        val dirRel = PathGameTree(gameRoot).resolve(exeDir) ?: exeDir
        val dir = gameRoot.resolve(dirRel)
        Files.createDirectories(dir)
        val existingName = PathGameTree(gameRoot).list(dirRel)
            .firstOrNull { !it.isDirectory && it.name.equals("$DLL_NAME.dll", ignoreCase = true) }?.name
        val target = dir.resolve(existingName ?: "$DLL_NAME.dll")
        val wrapperSha = Hashing.sha256(wrapper)
        if (target.exists() && Hashing.sha256(target) == wrapperSha) return emptyList()

        val changes = mutableListOf<Change>()
        if (target.exists()) {
            val disabled = dir.resolve(target.fileName.toString() + DISABLED_SUFFIX)
            // Уже отключённый оригинал не перезаписываем: в нём — исходный файл игрока.
            if (disabled.exists()) Files.delete(target) else Files.move(target, disabled)
            changes += Change.Disabled("$dirRel/${target.fileName}")
        }
        val tmp = dir.resolve(".${target.fileName}.tmp")
        Files.copy(wrapper, tmp, StandardCopyOption.REPLACE_EXISTING)
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
        changes += Change.Installed("$dirRel/${target.fileName}")
        return changes
    }

    private const val DLL_NAME = LaunchSpecBuilder.WRAPPER_DLL
}
