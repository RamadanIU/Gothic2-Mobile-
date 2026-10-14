package gothic2.mobile.app.runtime

import gothic2.mobile.app.GothicApplication
import gothic2.mobile.core.config.DeviceProfile
import gothic2.mobile.core.config.GothicIniWriter
import gothic2.mobile.core.config.GraphicsPreset
import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.GameVariant
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.gamedata.VariantLayout
import gothic2.mobile.core.runtime.BridgeEndpoint
import gothic2.mobile.core.runtime.LaunchSpec
import gothic2.mobile.core.runtime.LaunchSpecBuilder
import gothic2.mobile.core.runtime.RendererSetup
import gothic2.mobile.core.runtime.RuntimeBackend
import gothic2.mobile.core.runtime.RuntimePaths
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Подготовка и запуск закреплённой конфигурации: настройки пишутся в GOTHIC.INI до старта,
 * игра запускается напрямую по [LaunchSpec] — без рабочего стола и выбора EXE.
 */
class GameLauncher(private val app: GothicApplication, private val backend: RuntimeBackend) {

    data class Launched(val spec: LaunchSpec, val graphics: GraphicsPreset)

    fun prepareAndLaunch(build: SupportedBuild, variant: GameVariant, requested: DeviceProfile, bridge: BridgeEndpoint?): Launched {
        val root = app.paths.gameRoot
        val tree = PathGameTree(root)

        // Графические DLL рядом с EXE: сторонний ddraw.dll (GD3D11 в GOG-издании) не включается.
        // Пока обёртка D3D7→D3D9 не входит в компоненты, цепочка DXVK недоступна — берём WineD3D.
        val exeDir = build.exeOf(variant).substringBeforeLast('/', "")
        val wrapper = app.paths.components.resolve("d3d7-wrapper/${LaunchSpecBuilder.WRAPPER_DLL}.dll")
        val profile = try {
            RendererSetup.prepare(root, exeDir, requested.renderer, wrapper)
                .forEach { app.diagnostics.log("launch", "renderer files: $it") }
            requested
        } catch (e: RendererSetup.MissingWrapperException) {
            app.diagnostics.log("launch", "wrapper missing, fallback to WINED3D")
            requested.copy(renderer = RendererChain.WINED3D)
        }
        val graphics = app.settings.userSettings().effectiveGraphics(profile)

        val iniRel = tree.resolve(build.iniPath) ?: build.iniPath
        val iniPath = root.resolve(iniRel)
        val ini = if (Files.exists(iniPath)) IniFile.parse(iniPath.readText(Charsets.ISO_8859_1)) else IniFile.empty()
        GothicIniWriter.apply(ini, graphics)
        Files.createDirectories(iniPath.parent)
        iniPath.writeText(ini.toString(), Charsets.ISO_8859_1)

        val dxvk = if (profile.renderer == RendererChain.D3D7_TO_D3D9_DXVK) GothicIniWriter.dxvkConf(graphics) else null
        val spec = LaunchSpecBuilder.build(
            build,
            variant,
            profile,
            RuntimePaths(
                wineprefix = app.paths.wineprefix.toString(),
                gameRoot = root.toString(),
                cacheDir = app.paths.shaderCache.toString(),
            ),
            dxvk,
            bridge,
        )
        for ((rel, content) in spec.generatedFiles) {
            val p = root.resolve(rel)
            Files.createDirectories(p.parent)
            p.writeText(content)
        }
        app.diagnostics.log("launch", "variant=${variant.id} profile=${profile.id} renderer=${profile.renderer} ${graphics.width}x${graphics.height}@${graphics.fpsLimit}")
        backend.prepare(app.components)
        // Файлы варианта (классика прячет архивы аддона) — непосредственно перед стартом;
        // обратно их возвращает GameActivity после выхода игры.
        val layout = VariantLayout(root)
        try {
            layout.apply(build, variant)
            backend.launch(spec)
        } catch (e: Exception) {
            runCatching { layout.restore(build) }
            throw e
        }
        return Launched(spec, graphics)
    }
}
