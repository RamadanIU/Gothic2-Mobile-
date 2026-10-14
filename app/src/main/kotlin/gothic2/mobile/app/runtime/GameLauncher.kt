package gothic2.mobile.app.runtime

import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import gothic2.mobile.app.GothicApplication
import gothic2.mobile.core.config.DeviceProfile
import gothic2.mobile.core.config.DeviceProfiles
import gothic2.mobile.core.config.GothicIniWriter
import gothic2.mobile.core.config.GraphicsPreset
import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout
import org.json.JSONObject
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Подготовка запуска закреплённой конфигурации в среде Winlator: системные файлы Wine,
 * контейнер (префикс Wine) с диском D: = папка игры, настройки в GOTHIC.INI, файлы варианта.
 * Сам запуск делает [gothic2.mobile.app.ui.GameActivity] — без рабочего стола и выбора EXE.
 */
class GameLauncher(private val app: GothicApplication) {

    /** Всё, что нужно экрану игры: контейнер, EXE (путь Linux) и его аргументы. */
    data class Prepared(
        val containerId: Int,
        val exePath: String,
        val args: List<String>,
        val graphics: GraphicsPreset,
        val profile: DeviceProfile,
    )

    /** @param onStep текст шага и прогресс 0..100 (null — без процента) */
    fun prepare(build: SupportedBuild, variant: GameVariant, onStep: (String, Int?) -> Unit): Prepared {
        val root = app.paths.gameRoot
        val tree = PathGameTree(root)
        val args = variant.args ?: error("Вариант ${variant.id} ещё не подтверждён: нет аргументов запуска")

        RootFS.find(app)
        if (RootFSInstaller.isInstallNeeded(app)) {
            app.diagnostics.log("launch", "installing rootfs")
            onStep(INSTALL_STEP, 0)
            val ok = RootFSInstaller.installBlocking(app) { p -> onStep(INSTALL_STEP, p) }
            check(ok) { "Не удалось установить системные файлы Wine" }
        }

        onStep("Подготовка игры…", null)
        val profile = DeviceProfiles.select(GpuInfo.rendererName())
        val graphics = app.settings.userSettings().effectiveGraphics(profile)

        val iniRel = tree.resolve(build.iniPath) ?: build.iniPath
        val iniPath = root.resolve(iniRel)
        val ini = if (Files.exists(iniPath)) IniFile.parse(iniPath.readText(Charsets.ISO_8859_1)) else IniFile.empty()
        GothicIniWriter.apply(ini, graphics)
        Files.createDirectories(iniPath.parent)
        iniPath.writeText(ini.toString(), Charsets.ISO_8859_1)

        val container = ensureContainer(profile, graphics)
        val exeRel = tree.resolve(build.exeOf(variant)) ?: error("В папке игры нет ${build.exeOf(variant)}")
        // Файлы варианта (классика прячет архивы аддона) — последним шагом; обратно их
        // возвращает экран игры после выхода, а после сбоя — следующий запуск приложения.
        VariantLayout(root).apply(build, variant)

        app.diagnostics.log(
            "launch",
            "variant=${variant.id} profile=${profile.id} renderer=${profile.renderer} ${graphics.width}x${graphics.height} container=${container.id}",
        )
        return Prepared(container.id, root.resolve(exeRel).toString(), args, graphics, profile)
    }

    /** Контейнер Wine для игры: создаётся один раз, настройки обновляются при каждом запуске. */
    private fun ensureContainer(profile: DeviceProfile, graphics: GraphicsPreset): Container {
        val manager = ContainerManager(app)
        val container = manager.containers.firstOrNull { it.name == CONTAINER_NAME }
            ?: manager.createContainer(
                JSONObject()
                    .put("name", CONTAINER_NAME)
                    .put("wincomponents", Container.DEFAULT_WINCOMPONENTS)
                    .put("drives", drives()),
            ) ?: error("Не удалось создать контейнер Wine")

        // Только папка игры, без доступа ко всему накопителю.
        container.drives = drives()
        container.screenSize = "${graphics.width}x${graphics.height}"
        when (profile.renderer) {
            // Gothic рисует через Direct3D 7 (ddraw): встроенный WineD3D поверх OpenGL.
            RendererChain.WINED3D -> {
                container.dxWrapper = DXWrappers.WINED3D
                container.dxWrapperConfig = ""
            }
            // D7VK переводит Direct3D 7 сразу в Vulkan.
            RendererChain.D3D7_TO_D3D9_DXVK -> {
                container.dxWrapper = DXWrappers.DXVK
                container.dxWrapperConfig = "ddrawWrapper=${DXWrappers.D7VK}"
            }
        }
        container.saveData()
        return container
    }

    private fun drives(): String = "D:" + app.paths.gameRoot.toString()

    private companion object {
        const val CONTAINER_NAME = "Gothic II"
        const val INSTALL_STEP = "Первый запуск: установка Wine (один раз, 1–3 минуты)…"
    }
}
