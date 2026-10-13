package gothic2.mobile.core.runtime

import gothic2.mobile.core.bridge.BridgeProtocol
import gothic2.mobile.core.config.DeviceProfile
import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.SupportedBuild

/**
 * Полное описание запуска конкретной Classic-конфигурации. Строится заранее, без участия
 * пользователя: никакого рабочего стола, проводника и выбора EXE.
 */
data class LaunchSpec(
    /** Путь к EXE внутри Windows-пространства Wine, например `C:\Gothic2\System\Gothic2.exe`. */
    val windowsExePath: String,
    val workingDir: String,
    val args: List<String>,
    val env: Map<String, String>,
    /** Однократная подготовка префикса (версия Windows и т.п.), выполняется до запуска игры. */
    val prefixSetup: List<List<String>>,
    /** Файлы, которые нужно записать перед запуском: путь относительно корня игры → содержимое. */
    val generatedFiles: Map<String, String>,
)

data class RuntimePaths(
    val wineprefix: String,
    /** Корень игры на хосте (Linux-путь). */
    val gameRoot: String,
    /** Буква диска, под которой корень игры виден в Wine. */
    val gameDrive: Char = 'C',
    /** Каталог игры относительно корня диска. */
    val gameDirOnDrive: String = "Gothic2",
    val cacheDir: String,
)

/** Куда игровому адаптеру подключаться и чем подтверждать подлинность (раздел 12 плана). */
data class BridgeEndpoint(val socketPath: String, val token: String)

object LaunchSpecBuilder {
    /** DLL обёртки D3D7→D3D9, кладётся рядом с EXE (проверить имя для выбранной обёртки). */
    const val WRAPPER_DLL = "ddraw"

    fun build(
        build: SupportedBuild,
        profile: DeviceProfile,
        paths: RuntimePaths,
        dxvkConf: String?,
        bridge: BridgeEndpoint? = null,
    ): LaunchSpec {
        val winGameDir = "${paths.gameDrive}:\\${paths.gameDirOnDrive.replace('/', '\\')}"
        val exe = build.exePath.replace('/', '\\')
        val exeDir = exe.substringBeforeLast('\\', "")
        val env = linkedMapOf(
            "WINEPREFIX" to paths.wineprefix,
            "WINEDEBUG" to "-all",
            "WINEESYNC" to "1",
        )
        val files = linkedMapOf<String, String>()
        when (profile.renderer) {
            RendererChain.WINED3D -> {
                env["WINEDLLOVERRIDES"] = "$WRAPPER_DLL=b;d3d9=b"
            }
            RendererChain.D3D7_TO_D3D9_DXVK -> {
                env["WINEDLLOVERRIDES"] = "$WRAPPER_DLL=n,b;d3d9=n,b"
                env["DXVK_LOG_LEVEL"] = "none"
                env["DXVK_STATE_CACHE_PATH"] = "${paths.cacheDir}/dxvk"
                if (dxvkConf != null) {
                    val confRel = "${build.exePath.substringBeforeLast('/', "")}/dxvk.conf".trimStart('/')
                    files[confRel] = dxvkConf
                    env["DXVK_CONFIG_FILE"] = "${paths.gameRoot}/$confRel"
                }
            }
        }
        env.putAll(profile.translatorEnv)
        if (bridge != null) {
            env[BridgeProtocol.SOCKET_ENV] = bridge.socketPath
            env[BridgeProtocol.TOKEN_ENV] = bridge.token
        }
        return LaunchSpec(
            windowsExePath = "$winGameDir\\$exe",
            workingDir = if (exeDir.isEmpty()) winGameDir else "$winGameDir\\$exeDir",
            args = build.launchArgs,
            env = env,
            prefixSetup = listOf(
                listOf("reg", "add", "HKCU\\Software\\Wine", "/v", "Version", "/d", profile.windowsVersion.wineName, "/f"),
            ),
            generatedFiles = files,
        )
    }
}
