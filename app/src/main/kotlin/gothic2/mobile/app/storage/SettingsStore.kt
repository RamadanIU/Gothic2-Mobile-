package gothic2.mobile.app.storage

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import gothic2.mobile.app.BuildConfig
import gothic2.mobile.core.config.UserSettings

/** Хранение пользовательских настроек и состояния импорта. */
class SettingsStore(context: Context) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    var importedBuildId: String?
        get() = prefs.getString(KEY_BUILD, null)
        set(v) = prefs.edit { putString(KEY_BUILD, v) }

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

    fun userSettings(): UserSettings = runCatching {
        UserSettings(
            quality = UserSettings.Quality.valueOf(prefs.getString("quality", "AUTO")!!),
            viewDistanceOffset = prefs.getInt("view_distance", 0),
            fpsLimit = prefs.getString("fps_limit", "auto")!!.toIntOrNull(),
            cameraSensitivity = prefs.getInt("camera_sensitivity", 100) / 100f,
            invertY = prefs.getBoolean("invert_y", false),
            leftHanded = prefs.getBoolean("left_handed", false),
            buttonScale = prefs.getInt("button_scale", 100) / 100f,
            overlayOpacity = prefs.getInt("overlay_opacity", 60) / 100f,
            masterVolume = prefs.getInt("volume", 100) / 100f,
        )
    }.getOrElse { UserSettings() }

    private companion object {
        const val KEY_BUILD = "imported_build_id"
        const val KEY_EXE = "imported_exe_sha256"
        const val KEY_DEV = "dev_mode"
        const val KEY_LAYOUT = "layout_overrides"
    }
}
