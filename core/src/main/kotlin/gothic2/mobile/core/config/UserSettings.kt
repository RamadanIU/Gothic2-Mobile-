package gothic2.mobile.core.config

/**
 * Пользовательские настройки — только понятные игровые параметры (раздел 1).
 * Технические параметры среды берутся из [DeviceProfile] и игроку не показываются.
 */
data class UserSettings(
    val quality: Quality = Quality.AUTO,
    /** Дальность видимости относительно профиля: −3..+3 шага. */
    val viewDistanceOffset: Int = 0,
    val fpsLimit: Int? = null,
    val uiScale: Float = 1f,
    val cameraSensitivity: Float = 1f,
    val invertY: Boolean = false,
    val leftHanded: Boolean = false,
    val buttonScale: Float = 1f,
    val overlayOpacity: Float = 0.6f,
    val masterVolume: Float = 1f,
    /** Короткая вибрация при нажатии сенсорной кнопки. */
    val haptics: Boolean = true,
) {
    enum class Quality { AUTO, COMPATIBILITY, BALANCED, QUALITY }

    init {
        require(viewDistanceOffset in -3..3)
        require(uiScale in 0.75f..2f && cameraSensitivity in 0.1f..5f)
        require(buttonScale in 0.5f..2.5f && overlayOpacity in 0.1f..1f && masterVolume in 0f..1f)
        require(fpsLimit == null || fpsLimit in setOf(25, 30, 45, 60))
    }

    /** Итоговые графические параметры: профиль устройства + выбор игрока. */
    fun effectiveGraphics(profile: DeviceProfile): GraphicsPreset {
        val base = when (quality) {
            Quality.AUTO -> profile.graphics
            Quality.COMPATIBILITY -> GraphicsPreset.COMPATIBILITY
            Quality.BALANCED -> GraphicsPreset.BALANCED
            Quality.QUALITY -> GraphicsPreset.QUALITY
        }
        return base.copy(
            sightValue = (base.sightValue + viewDistanceOffset).coerceIn(0, 14),
            fpsLimit = fpsLimit ?: base.fpsLimit,
        )
    }

    companion object {
        /** Параметры, которые нельзя менять на ходу: изменение применяется после перезапуска игры. */
        fun requiresRestart(old: UserSettings, new: UserSettings): Boolean =
            old.quality != new.quality || old.viewDistanceOffset != new.viewDistanceOffset ||
                old.fpsLimit != new.fpsLimit || old.uiScale != new.uiScale
    }
}
