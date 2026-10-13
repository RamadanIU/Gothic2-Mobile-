package gothic2.mobile.app.runtime

import gothic2.mobile.app.GothicApplication
import gothic2.mobile.core.config.DeviceProfile
import gothic2.mobile.core.config.GothicIniWriter
import gothic2.mobile.core.config.GraphicsPreset
import gothic2.mobile.core.config.IniFile
import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.PathGameTree
import gothic2.mobile.core.gamedata.SupportedBuild
import gothic2.mobile.core.runtime.BridgeEndpoint
import gothic2.mobile.core.runtime.LaunchSpec
import gothic2.mobile.core.runtime.LaunchSpecBuilder
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

    fun prepareAndLaunch(build: SupportedBuild, profile: DeviceProfile, bridge: BridgeEndpoint?): Launched {
        val graphics = app.settings.userSettings().effectiveGraphics(profile)
        val root = app.paths.gameRoot
        val tree = PathGameTree(root)

        val iniRel = tree.resolve(build.iniPath) ?: build.iniPath
        val iniPath = root.resolve(iniRel)
        val ini = if (Files.exists(iniPath)) IniFile.parse(iniPath.readText(Charsets.ISO_8859_1)) else IniFile.empty()
        GothicIniWriter.apply(ini, graphics)
        Files.createDirectories(iniPath.parent)
        iniPath.writeText(ini.toString(), Charsets.ISO_8859_1)

        val dxvk = if (profile.renderer == RendererChain.D3D7_TO_D3D9_DXVK) GothicIniWriter.dxvkConf(graphics) else null
        val spec = LaunchSpecBuilder.build(
            build,
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
        app.diagnostics.log("launch", "profile=${profile.id} renderer=${profile.renderer} ${graphics.width}x${graphics.height}@${graphics.fpsLimit}")
        backend.prepare(app.components)
        backend.launch(spec)
        return Launched(spec, graphics)
    }
}
