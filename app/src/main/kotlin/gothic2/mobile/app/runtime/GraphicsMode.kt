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
 * в самом D7VK 1.11 (падение при запуске). Вместо D7VK из Winlator работает наша сборка того же
 * D7VK 1.11 с исправлениями (FileReplacement.D7VK_1_11_GOTHIC), запасной путь — BinaryFix.D7VK_1_11_HEL_CAPS.
 *
 * @property title название в настройках — без названий библиотек: игрок выбирает «быстрый» или «совместимый»
 * @property graphicsDriver драйверы Winlator «Vulkan,OpenGL»
 */
enum class GraphicsMode(val id: String, val title: String, val graphicsDriver: String) {
    /**
     * D7VK через Turnip (только Adreno). На первом телефоне WineD3D упирался в процессор: главный
     * поток Gothic вместе с эмулируемым WineD3D — 98 % ядра при ~10 FPS; D7VK намного легче.
     */
    D7VK_TURNIP("d7vk_turnip", "Vulkan · Adreno — быстрый", "turnip,zink"),

    /** WineD3D → OpenGL через Zink → Vulkan через Turnip. Работает на первом телефоне, ~10 FPS. */
    WINED3D_ZINK_TURNIP("wined3d_zink_turnip", "OpenGL · Adreno — совместимый", "turnip,zink"),

    /** То же через Vortek. Дошёл до меню на первом телефоне, но вылетал после ролика. */
    WINED3D_ZINK("wined3d_zink", "OpenGL — совместимый", "vortek,zink"),

    /** D7VK через Vortek — для GPU не Adreno. */
    D7VK("d7vk", "Vulkan — быстрый", "vortek,zink"),

    /** WineD3D через Gladio — OpenGL Winlator по умолчанию. На первом телефоне не дал 3D-устройства. */
    WINED3D_GLADIO("wined3d", "OpenGL — запасной", "vortek,gladio");

    val isD7VK: Boolean get() = this == D7VK_TURNIP || this == D7VK

    /** Vulkan — Turnip (драйвер Mesa в процессе игры), а не Vortek. */
    val usesTurnip: Boolean get() = graphicsDriver.startsWith("turnip,")

    fun next(): GraphicsMode = entries[(ordinal + 1) % entries.size]

    companion object {
        /** По умолчанию: на Adreno — D7VK через Turnip, иначе WineD3D через Vortek. */
        fun defaultFor(gpuName: String?): GraphicsMode =
            if (gpuName?.contains("Adreno", ignoreCase = true) == true) D7VK_TURNIP else WINED3D_ZINK

        /** Наша сборка D7VK в assets (tools/build-d7vk.sh, FileReplacement.D7VK_1_11_GOTHIC). */
        const val D7VK_ASSET = "d7vk/ddraw.dll"

        fun of(id: String?, gpuName: String?): GraphicsMode = entries.firstOrNull { it.id == id } ?: defaultFor(gpuName)

        /**
         * Значение `TU_DEBUG` для Turnip на Adreno с номером [adrenoModel] (0 — не Adreno). Замысел
         * Winlator 11.2 (`TurnipConfigDialog.setEnvVars`): на Adreno 7xx — рисование прямо в память
         * (`sysmem`), на 710/720/732 — только через плиточную память GPU (`gmem`), и везде `deck_emu`.
         * Но там последняя строка записывает `TU_DEBUG` заново из исходного значения, и остаётся один
         * `deck_emu`: Adreno 740 рисовал через GMEM, а это сетка чёрных блоков и вспышки на кадре.
         */
        fun turnipDebug(adrenoModel: Int): String = when {
            adrenoModel == 710 || adrenoModel == 720 || adrenoModel == 732 -> "gmem,deck_emu"
            adrenoModel >= 700 -> "sysmem,deck_emu"
            else -> "deck_emu"
        }

        /** `TU_DEBUG` с добавками выбранного варианта против мерцания. */
        fun turnipDebug(adrenoModel: Int, fix: FlickerFix): String =
            (listOf(turnipDebug(adrenoModel)) + fix.turnipDebug).joinToString(",")

        /**
         * Настройка D7VK (`DXVK_CONFIG`). Профиль D7VK для Gothic II привязан к Gothic2.exe;
         * классика — Gothic2c.exe, поэтому его forceLegacyPresent (2D-вывод на основную поверхность —
         * в теневую) задаём сами. Strict: с Auto D7VK после Lock/Unlock, GetDC/ReleaseDC и Blt по
         * основной поверхности вне сцены сразу показывал её — а там не картинка игры, а остатки 2D:
         * белые кадры при повороте камеры. Так же сделано в профиле D7VK для Arx Fatalis.
         */
        fun d7vkConfig(fix: FlickerFix): String =
            (listOf("ddraw.forceLegacyPresent = True", "ddraw.legacyPresentGuard = Strict") + fix.d7vkConfig).joinToString("; ")
    }
}
