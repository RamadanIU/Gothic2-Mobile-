package gothic2.mobile.core.config

/**
 * Применяет настройки к GOTHIC.INI до запуска игры.
 *
 * Разрешение задаётся только здесь: смена разрешения внутри игры с обёрткой рендерера
 * может привести к чёрному экрану. Имена ключей собраны в [Keys] и сверены с `System/Gothic.ini`
 * закреплённой GOG-сборки (отчёт от 2026-10-14); действие значений проверяется на телефоне
 * (docs/TEST_PLAN.md, этап 1).
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
        const val GOTHIC1_CONTROLS = "useGothic1Controls"
        const val QUICKSAVE_KEYS = "useQuickSaveKeys"
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
        // Удары «действие + направление» рассчитаны на управление как в Gothic 1; при 0 игра
        // ждёт отдельных клавиш боковых ударов, которых у сенсорного управления нет.
        ini.set(Keys.GAME, Keys.GOTHIC1_CONTROLS, "1")
        // Без этого F5/F9 из быстрого меню не делают ничего (в поставке выключено).
        ini.set(Keys.GAME, Keys.QUICKSAVE_KEYS, "1")
        return ini
    }

    /** Конфигурация DXVK для цепочки D3D7→D3D9→DXVK: ограничение кадров и кэш. */
    fun dxvkConf(graphics: GraphicsPreset): String = buildString {
        append("d3d9.maxFrameRate = ").append(graphics.fpsLimit).append('\n')
    }
}
