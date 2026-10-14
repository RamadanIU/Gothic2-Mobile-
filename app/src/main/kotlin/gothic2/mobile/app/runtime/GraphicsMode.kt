package gothic2.mobile.app.runtime

/**
 * Как Gothic (Direct3D 7, ddraw) рисует в среде Winlator. Выбирается в настройках и на экране
 * неудачного запуска; раздел 6 плана. Порядок — порядок перебора кнопкой «другой режим».
 *
 * Vortek выполняет команды Vulkan игры в процессе приложения через системный драйвер; Turnip —
 * открытый драйвер Vulkan для Adreno прямо в процессе игры (на Snapdragon обычно надёжнее).
 *
 * В режимах D7VK трёхмерную часть рисует D7VK (Direct3D 7 → Vulkan), а DirectDraw без 3D Winlator
 * отдаёт встроенному ddraw Wine (`ddraw_.dll`), который работает через OpenGL второго драйвера. С
 * Gladio этот ddraw сообщал игре негодные возможности («maximum texture size is lower than 32» — так
 * же падал и WineD3D + Gladio), поэтому OpenGL в режимах D7VK — Zink. С Zink игра доходила до ошибки
 * в самом D7VK 1.11 (падение при запуске) — её исправляет BinaryFix.D7VK_1_11_HEL_CAPS.
 *
 * @property graphicsDriver драйверы Winlator «Vulkan,OpenGL»
 */
enum class GraphicsMode(val id: String, val title: String, val graphicsDriver: String) {
    /**
     * D7VK через Turnip (только Adreno). На первом телефоне WineD3D упирался в процессор: главный
     * поток Gothic вместе с эмулируемым WineD3D — 98 % ядра при ~10 FPS; D7VK намного легче.
     */
    D7VK_TURNIP("d7vk_turnip", "D7VK + Turnip (Adreno)", "turnip,zink"),

    /** WineD3D → OpenGL через Zink → Vulkan через Turnip. Работает на первом телефоне, ~10 FPS. */
    WINED3D_ZINK_TURNIP("wined3d_zink_turnip", "WineD3D + Zink (Turnip, Adreno)", "turnip,zink"),

    /** То же через Vortek. Дошёл до меню на первом телефоне, но вылетал после ролика. */
    WINED3D_ZINK("wined3d_zink", "WineD3D + Zink (Vortek)", "vortek,zink"),

    /** D7VK через Vortek — для GPU не Adreno. */
    D7VK("d7vk", "D7VK + Vortek", "vortek,zink"),

    /** WineD3D через Gladio — OpenGL Winlator по умолчанию. На первом телефоне не дал 3D-устройства. */
    WINED3D_GLADIO("wined3d", "WineD3D + Gladio (OpenGL)", "vortek,gladio");

    val isD7VK: Boolean get() = this == D7VK_TURNIP || this == D7VK

    fun next(): GraphicsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** По умолчанию: на Adreno — D7VK через Turnip, иначе WineD3D через Vortek. */
        fun defaultFor(gpuName: String?): GraphicsMode =
            if (gpuName?.contains("Adreno", ignoreCase = true) == true) D7VK_TURNIP else WINED3D_ZINK

        fun of(id: String?, gpuName: String?): GraphicsMode = entries.firstOrNull { it.id == id } ?: defaultFor(gpuName)
    }
}
