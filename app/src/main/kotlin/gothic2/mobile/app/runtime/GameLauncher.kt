package gothic2.mobile.app.runtime

import com.winlator.container.Container
import com.winlator.container.ContainerManager
import com.winlator.container.DXWrappers
import com.winlator.core.RemoteAssets
import com.winlator.xenvironment.RootFS
import com.winlator.xenvironment.RootFSInstaller
import gothic2.mobile.app.GothicApplication
import gothic2.mobile.core.config.DeviceProfile
import gothic2.mobile.core.config.DeviceProfiles
import gothic2.mobile.core.config.GothicIniWriter
import gothic2.mobile.core.config.GraphicsPreset
import gothic2.mobile.core.config.IniFile
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

        // Остатки прошлой игры (если экран игры закрылся, а процесс приложения жив) мешают новой.
        if (!WinlatorBackend.sessionActive) {
            val killed = WinlatorBackend.killWineProcesses()
            if (killed > 0) app.diagnostics.log("launch", "killed $killed leftover Wine processes")
        }
        RootFS.find(app)
        val installNeeded = RootFSInstaller.isInstallNeeded(app)
        download(installNeeded, onStep)
        if (installNeeded) {
            app.diagnostics.log("launch", "installing rootfs")
            onStep(INSTALL_STEP, 0)
            val ok = RootFSInstaller.installBlocking(app) { p -> onStep(INSTALL_STEP, p) }
            check(ok) { "Не удалось установить системные файлы Wine" }
            // Архив rootfs (65 МБ) после установки не нужен; при обновлении Winlator скачается снова.
            RemoteAssets.find(app, RootFSInstaller.FILENAME)?.let { RemoteAssets.evict(app, it) }
        }

        onStep("Подготовка игры…", null)
        val gpu = GpuInfo.rendererName()
        app.settings.gpuName = gpu
        val profile = DeviceProfiles.select(gpu)
        val graphics = app.settings.userSettings().effectiveGraphics(profile)

        val iniRel = tree.resolve(build.iniPath) ?: build.iniPath
        val iniPath = root.resolve(iniRel)
        val ini = if (Files.exists(iniPath)) IniFile.parse(iniPath.readText(Charsets.ISO_8859_1)) else IniFile.empty()
        GothicIniWriter.apply(ini, graphics)
        Files.createDirectories(iniPath.parent)
        iniPath.writeText(ini.toString(), Charsets.ISO_8859_1)

        val mode = app.settings.graphicsMode
        val container = ensureContainer(mode, graphics)
        val exeRel = tree.resolve(build.exeOf(variant)) ?: error("В папке игры нет ${build.exeOf(variant)}")
        // Файлы варианта (классика прячет архивы аддона) — последним шагом; обратно их
        // возвращает экран игры после выхода, а после сбоя — следующий запуск приложения.
        VariantLayout(root).apply(build, variant)

        app.diagnostics.log(
            "launch",
            "variant=${variant.id} gpu=$gpu profile=${profile.id} graphics=${mode.id} ${graphics.width}x${graphics.height} container=${container.id}",
        )
        return Prepared(container.id, root.resolve(exeRel).toString(), args, graphics, profile)
    }

    /**
     * Архивы среды, которых нет в APK (RemoteAssets): скачиваются один раз, с общим прогрессом.
     * Пропускаются те, что этой конфигурации не нужны; если всё же понадобятся, Winlator скачает их сам.
     */
    private fun download(includeRootfs: Boolean, onStep: (String, Int?) -> Unit) {
        val needed = RemoteAssets.entries(app).filter { e ->
            SKIPPED_ASSETS.none { e.path.startsWith(it) } &&
                (includeRootfs || e.path != RootFSInstaller.FILENAME) &&
                !RemoteAssets.isAvailable(app, e)
        }
        if (needed.isEmpty()) return
        val total = needed.sumOf { it.size }.coerceAtLeast(1)
        app.diagnostics.log("launch", "downloading ${needed.size} archives, $total bytes")
        var before = 0L
        var lastPercent = -1
        for (entry in needed) {
            try {
                RemoteAssets.ensure(app, entry) { done, _ ->
                    val percent = ((before + done) * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        onStep(DOWNLOAD_STEP.format(total / 1_000_000), percent)
                    }
                }
            } catch (e: java.io.IOException) {
                app.diagnostics.log("launch", "download failed: ${entry.path}: $e")
                throw IllegalStateException("Не удалось скачать файлы Wine (${e.message}). Нужен интернет — проверьте подключение и повторите.", e)
            }
            before += entry.size
        }
    }

    /** Контейнер Wine для игры: создаётся один раз, настройки обновляются при каждом запуске. */
    private fun ensureContainer(mode: GraphicsMode, graphics: GraphicsPreset): Container {
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
        // Из библиотек Windows Gothic нужны DirectSound и DirectMusic (музыка); остальное — встроенное Wine.
        container.winComponents = WIN_COMPONENTS
        container.screenSize = "${graphics.width}x${graphics.height}"
        container.graphicsDriver = mode.graphicsDriver
        when (mode) {
            // D7VK переводит Direct3D 7 (ddraw Gothic) сразу в Vulkan.
            GraphicsMode.D7VK -> {
                container.dxWrapper = DXWrappers.DXVK
                container.dxWrapperConfig = "ddrawWrapper=${DXWrappers.D7VK}"
            }
            // Встроенный ddraw Wine → WineD3D → OpenGL (Zink или Gladio).
            GraphicsMode.WINED3D_ZINK, GraphicsMode.WINED3D_ZINK_TURNIP, GraphicsMode.WINED3D_GLADIO -> {
                container.dxWrapper = DXWrappers.WINED3D
                container.dxWrapperConfig = ""
            }
        }
        container.saveData()
        return container
    }

    private fun drives(): String = "D:" + app.paths.gameRoot.toString()

    private companion object {
        const val CONTAINER_NAME = "Gothic II"
        const val INSTALL_STEP = "Первый запуск: установка Wine (один раз, 1–3 минуты)…"
        const val DOWNLOAD_STEP = "Первый запуск: загрузка файлов Wine (%d МБ, нужен интернет)…"
        const val WIN_COMPONENTS =
            "direct3d=0,directsound=1,directmusic=1,directshow=0,directplay=0,xaudio=0,vcrun2005=0,vcrun2010=0,wmdecoder=0"

        /** Архивы, которые этой конфигурации не нужны (другие драйверы и библиотеки Windows). */
        val SKIPPED_ASSETS = listOf(
            "wincomponents/direct3d", "wincomponents/directshow", "wincomponents/directplay",
            "wincomponents/xaudio", "wincomponents/vcrun", "wincomponents/wmdecoder",
            "graphics_driver/virgl",
            "dxwrapper/cnc-ddraw",
        )
    }
}
