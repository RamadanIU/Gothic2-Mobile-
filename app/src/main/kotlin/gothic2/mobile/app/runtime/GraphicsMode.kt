package gothic2.mobile.app.runtime

/**
 * Как Gothic (Direct3D 7, ddraw) рисует в среде Winlator. Выбирается в настройках и на экране
 * неудачного запуска; раздел 6 плана. Порядок — порядок перебора кнопкой «другой режим».
 *
 * @property graphicsDriver драйверы Winlator «Vulkan,OpenGL»
 */
enum class GraphicsMode(val id: String, val title: String, val graphicsDriver: String) {
    /** D7VK: Direct3D 7 сразу в Vulkan (на основе DXVK). */
    D7VK("d7vk", "D7VK (Direct3D 7 → Vulkan)", "vortek,gladio"),

    /** Встроенный в Wine ddraw → WineD3D → OpenGL через Zink (OpenGL поверх Vulkan). */
    WINED3D_ZINK("wined3d_zink", "WineD3D + Zink (OpenGL поверх Vulkan)", "vortek,zink"),

    /** То же через Gladio — OpenGL Winlator по умолчанию. На первом телефоне не дал 3D-устройства. */
    WINED3D_GLADIO("wined3d", "WineD3D + Gladio (OpenGL)", "vortek,gladio");

    fun next(): GraphicsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = D7VK

        fun of(id: String?): GraphicsMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
