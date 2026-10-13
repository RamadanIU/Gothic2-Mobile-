package gothic2.mobile.core.config

/**
 * Применяет настройки к GOTHIC.INI до запуска игры.
 *
 * Разрешение задаётся только здесь: смена разрешения внутри игры с обёрткой рендерера
 * может привести к чёрному экрану. Имена ключей собраны в [Keys]; их необходимо
 * подтвердить на закреплённой Classic-сборке (docs/TEST_PLAN.md, этап 1).
 */
object GothicIniWriter {
    object Keys {
        const val VIDEO = "VIDEO"
        const val RES_X = "zVidResFullscreenX"
        const val RES_Y = "zVidResFullscreenY"
        const val RES_BPP = "zVidResFullscreenBPP"
        const val PERFORMANCE = "PERFORMANCE"
        const val SIGHT = "sightValue"
        const val GAME = "GAME"
        const val PLAY_LOGOS = "playLogoVideos"
        const val ENABLE_MOUSE = "enableMouse"
        const val MOUSE_SENS = "mouseSensitivity"
    }

    fun apply(ini: IniFile, graphics: GraphicsPreset, skipLogoVideos: Boolean = true): IniFile {
        ini.set(Keys.VIDEO, Keys.RES_X, graphics.width.toString())
        ini.set(Keys.VIDEO, Keys.RES_Y, graphics.height.toString())
        ini.set(Keys.VIDEO, Keys.RES_BPP, "32")
        ini.set(Keys.PERFORMANCE, Keys.SIGHT, graphics.sightValue.toString())
        // Камера управляется относительным движением мыши; чувствительность — на стороне приложения.
        ini.set(Keys.GAME, Keys.ENABLE_MOUSE, "1")
        // Пропускаются только логотипы при запуске; сюжетные ролики не трогаются.
        if (skipLogoVideos) ini.set(Keys.GAME, Keys.PLAY_LOGOS, "0")
        return ini
    }

    /** Конфигурация DXVK для цепочки D3D7→D3D9→DXVK: ограничение кадров и кэш. */
    fun dxvkConf(graphics: GraphicsPreset): String = buildString {
        append("d3d9.maxFrameRate = ").append(graphics.fpsLimit).append('\n')
    }
}
