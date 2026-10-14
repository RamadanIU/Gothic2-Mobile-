package gothic2.mobile.app.runtime

/**
 * Как Gothic (Direct3D 7, ddraw) рисует в среде Winlator. Выбирается в настройках и на экране
 * неудачного запуска; раздел 6 плана. Порядок — порядок перебора кнопкой «другой режим».
 *
 * Vortek выполняет команды Vulkan игры в процессе приложения через системный драйвер; Turnip —
 * открытый драйвер Vulkan для Adreno прямо в процессе игры (на Snapdragon обычно надёжнее).
 *
 * @property graphicsDriver драйверы Winlator «Vulkan,OpenGL»
 */
enum class GraphicsMode(val id: String, val title: String, val graphicsDriver: String) {
    /** WineD3D → OpenGL через Zink → Vulkan через Vortek. Дошёл до меню на первом телефоне (Adreno 7xx). */
    WINED3D_ZINK("wined3d_zink", "WineD3D + Zink (Vortek)", "vortek,zink"),

    /** То же, но Vulkan — Turnip (только Adreno). */
    WINED3D_ZINK_TURNIP("wined3d_zink_turnip", "WineD3D + Zink (Turnip, Adreno)", "turnip,zink"),

    /** D7VK: Direct3D 7 сразу в Vulkan (на основе DXVK). На первом телефоне — ошибка текстур. */
    D7VK("d7vk", "D7VK (Direct3D 7 → Vulkan)", "vortek,gladio"),

    /** WineD3D через Gladio — OpenGL Winlator по умолчанию. На первом телефоне не дал 3D-устройства. */
    WINED3D_GLADIO("wined3d", "WineD3D + Gladio (OpenGL)", "vortek,gladio");

    val isWineD3D: Boolean get() = this != D7VK

    fun next(): GraphicsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** По умолчанию: на Adreno — Turnip, иначе Vortek. */
        fun defaultFor(gpuName: String?): GraphicsMode =
            if (gpuName?.contains("Adreno", ignoreCase = true) == true) WINED3D_ZINK_TURNIP else WINED3D_ZINK

        fun of(id: String?, gpuName: String?): GraphicsMode = entries.firstOrNull { it.id == id } ?: defaultFor(gpuName)
    }
}
