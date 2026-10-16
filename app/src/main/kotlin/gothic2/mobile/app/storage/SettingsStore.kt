package gothic2.mobile.app.storage

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.app.runtime.FlickerFix
import gothic2.mobile.app.runtime.GraphicsMode
import gothic2.mobile.core.config.UserSettings

/** Хранение пользовательских настроек и состояния импорта. */
class SettingsStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        migrateOverlayOpacity()
    }

    /**
     * Ключ «overlay_opacity» занят Winlator: тот читает его как Float из тех же настроек, а мы писали
     * Int — экран игры падал при создании (ClassCastException в XServerDisplayActivity.setupUI), и
     * после этого каждый запуск вылетал снова. Значение переносится под свой ключ, чужой удаляется.
     */
    private fun migrateOverlayOpacity() {
        val old = prefs.all[KEY_WINLATOR_OPACITY] ?: return
        prefs.edit(commit = true) {
            if (old is Int && !prefs.contains(KEY_OPACITY)) putInt(KEY_OPACITY, old)
            remove(KEY_WINLATOR_OPACITY)
        }
    }

    var importedBuildId: String?
        get() = prefs.getString(KEY_BUILD, null)
        set(v) = prefs.edit { putString(KEY_BUILD, v) }

    /** Выбранный вариант запуска (например, «Ночь Ворона» или классическая игра). */
    var variantId: String?
        get() = prefs.getString(KEY_VARIANT, null)
        set(v) = prefs.edit { putString(KEY_VARIANT, v) }

    var importedExeSha256: String?
        get() = prefs.getString(KEY_EXE, null)
        set(v) = prefs.edit { putString(KEY_EXE, v) }

    /** Режим разработчика (незакреплённые сборки) существует только в debug-сборке. */
    var devMode: Boolean
        get() = BuildConfig.DEBUG && prefs.getBoolean(KEY_DEV, BuildConfig.DEV_MODE_DEFAULT)
        set(v) = prefs.edit { putBoolean(KEY_DEV, v) }

    /** Положения кнопок, переставленных игроком: id → (x, y) в нормированных координатах. */
    var layoutOverrides: Map<String, Pair<Float, Float>>
        get() = prefs.getString(KEY_LAYOUT, "").orEmpty().split(';').mapNotNull { e ->
            val p = e.split(':')
            if (p.size != 3) return@mapNotNull null
            val x = p[1].toFloatOrNull() ?: return@mapNotNull null
            val y = p[2].toFloatOrNull() ?: return@mapNotNull null
            p[0] to (x to y)
        }.toMap()
        set(v) = prefs.edit { putString(KEY_LAYOUT, v.entries.joinToString(";") { "${it.key}:${it.value.first}:${it.value.second}" }) }

    /** Прошлая игра закрылась сразу после запуска: лаунчер покажет экран с журналом. */
    var lastSessionFailed: Boolean
        get() = prefs.getBoolean(KEY_FAILED, false)
        set(v) = prefs.edit { putBoolean(KEY_FAILED, v) }

    /**
     * Папка игры на накопителе (путь файловой системы), из которой игра запускается напрямую;
     * null — игра скопирована во внутреннее хранилище приложения.
     */
    var gameDir: String?
        get() = prefs.getString(KEY_GAME_DIR, null)
        set(v) = prefs.edit { putString(KEY_GAME_DIR, v) }

    /** Разрешение «Файлы» для журналов уже спрашивали (спрашиваем один раз, при первом запуске). */
    var logPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_LOG_PERMISSION, false)
        set(v) = prefs.edit { putBoolean(KEY_LOG_PERMISSION, v) }

    /** Режим графики; пока игрок не выбрал сам — по видеокарте (GraphicsMode.defaultFor). */
    var graphicsMode: GraphicsMode
        get() = GraphicsMode.of(prefs.getString(KEY_GRAPHICS, null), gpuName)
        set(v) = prefs.edit { putString(KEY_GRAPHICS, v.id) }

    /** Вариант против мерцания в режимах D7VK (пункт «Мерцание» в настройках). */
    val flickerFix: FlickerFix
        get() = FlickerFix.of(prefs.getString(KEY_FLICKER, null))

    /** Имя GPU (GL_RENDERER), запомненное при подготовке запуска. */
    var gpuName: String?
        get() = prefs.getString(KEY_GPU, null)
        set(v) = prefs.edit { putString(KEY_GPU, v) }

    fun userSettings(): UserSettings = runCatching {
        UserSettings(
            quality = UserSettings.Quality.valueOf(prefs.getString("quality", "AUTO")!!),
            viewDistanceOffset = prefs.getInt("view_distance", 0),
            fpsLimit = prefs.getString("fps_limit", "auto")!!.toIntOrNull(),
            cameraSensitivity = prefs.getInt("camera_sensitivity", 100) / 100f,
            invertY = prefs.getBoolean("invert_y", false),
            leftHanded = prefs.getBoolean("left_handed", false),
            buttonScale = prefs.getInt("button_scale", 100) / 100f,
            overlayOpacity = prefs.getInt(KEY_OPACITY, 60) / 100f,
            masterVolume = prefs.getInt("volume", 100) / 100f,
            haptics = prefs.getBoolean("haptics", true),
        )
    }.getOrElse { UserSettings() }

    private companion object {
        const val KEY_BUILD = "imported_build_id"
        const val KEY_EXE = "imported_exe_sha256"
        const val KEY_VARIANT = "game_variant"
        const val KEY_DEV = "dev_mode"
        /** v2 — раскладка с главной кнопкой: положения кнопок прежней раскладки к ней не подходят. */
        const val KEY_LAYOUT = "layout_overrides_v2"
        const val KEY_FAILED = "last_session_failed"
        const val KEY_GRAPHICS = "graphics_mode"
        const val KEY_GPU = "gpu_name"
        const val KEY_FLICKER = "flicker_fix"
        const val KEY_GAME_DIR = "game_dir"
        const val KEY_LOG_PERMISSION = "log_permission_asked"
        const val KEY_OPACITY = "gothic_overlay_opacity"
        private const val KEY_WINLATOR_OPACITY = "overlay_opacity"
    }
}
