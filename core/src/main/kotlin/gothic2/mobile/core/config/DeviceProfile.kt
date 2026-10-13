package gothic2.mobile.core.config

/** Графическая цепочка (раздел 6 плана). */
enum class RendererChain {
    /** Gothic D3D7 → графическая реализация Wine → backend → GPU. Эталон корректности. */
    WINED3D,

    /** Gothic D3D7 → обёртка D3D7→D3D9 → DXVK → Vulkan → GPU. Кандидат на основной путь. */
    D3D7_TO_D3D9_DXVK,
}

/** Версия Windows, сообщаемая игре через Wine. */
enum class WindowsVersion(val wineName: String) { WIN7("win7"), WINXP("winxp") }

/**
 * Проверенный профиль устройства. Профиль считается «проверенным» только после
 * прохождения тестов этапа 1/5 на конкретном аппарате (docs/TEST_PLAN.md).
 */
data class DeviceProfile(
    val id: String,
    val title: String,
    /** Подстроки, хотя бы одна из которых должна входить в GL_RENDERER / имя Vulkan-устройства. */
    val gpuMatch: List<String>,
    val verified: Boolean,
    val renderer: RendererChain,
    val windowsVersion: WindowsVersion,
    val graphics: GraphicsPreset,
    /** Переменные окружения транслятора (Box64). Пусто — значения по умолчанию закреплённой версии. */
    val translatorEnv: Map<String, String> = emptyMap(),
)

/** Набор графических параметров, применяемых к GOTHIC.INI и рендереру. */
data class GraphicsPreset(
    val width: Int,
    val height: Int,
    val fpsLimit: Int,
    /** Дальность видимости в единицах GOTHIC.INI (sightValue). */
    val sightValue: Int,
) {
    init {
        require(width in 640..3840 && height in 480..2160) { "Недопустимое разрешение ${width}x$height" }
        require(fpsLimit in setOf(25, 30, 45, 60)) { "Недопустимое ограничение FPS $fpsLimit" }
        require(sightValue in 0..14) { "sightValue вне диапазона" }
    }

    val aspect: Float get() = width.toFloat() / height

    companion object {
        /** Стартовые профили из раздела 15 — кандидаты для измерений, не обещание производительности. */
        val COMPATIBILITY = GraphicsPreset(800, 600, 30, 4)
        val BALANCED = GraphicsPreset(960, 540, 30, 6)
        val QUALITY = GraphicsPreset(1280, 720, 30, 8)
    }
}

/** Выбор профиля для текущего GPU. Неизвестное устройство получает режим совместимости. */
object DeviceProfiles {
    val FALLBACK = DeviceProfile(
        id = "fallback-compat",
        title = "Непроверенное устройство (совместимость)",
        gpuMatch = emptyList(),
        verified = false,
        renderer = RendererChain.WINED3D,
        windowsVersion = WindowsVersion.WIN7,
        graphics = GraphicsPreset.COMPATIBILITY,
    )

    /**
     * Профили-кандидаты для тестовой базы Adreno (раздел 19). verified=false, пока не
     * пройдены тесты; после прохождения флаг и выбранная цепочка фиксируются здесь.
     */
    val KNOWN = listOf(
        DeviceProfile(
            id = "adreno-6xx",
            title = "Adreno 6xx (класс Snapdragon 865/870)",
            gpuMatch = listOf("Adreno (TM) 650", "Adreno (TM) 660"),
            verified = false,
            renderer = RendererChain.D3D7_TO_D3D9_DXVK,
            windowsVersion = WindowsVersion.WIN7,
            graphics = GraphicsPreset.BALANCED,
        ),
        DeviceProfile(
            id = "adreno-7xx",
            title = "Adreno 7xx (класс Snapdragon 8 Gen 2)",
            gpuMatch = listOf("Adreno (TM) 730", "Adreno (TM) 740", "Adreno (TM) 750"),
            verified = false,
            renderer = RendererChain.D3D7_TO_D3D9_DXVK,
            windowsVersion = WindowsVersion.WIN7,
            graphics = GraphicsPreset.QUALITY,
        ),
    )

    fun select(gpuName: String, profiles: List<DeviceProfile> = KNOWN): DeviceProfile =
        profiles.firstOrNull { p -> p.gpuMatch.any { gpuName.contains(it, ignoreCase = true) } } ?: FALLBACK
}
