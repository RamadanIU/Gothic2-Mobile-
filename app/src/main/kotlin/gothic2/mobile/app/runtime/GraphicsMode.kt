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

    /**
     * То же через Vortek. Дошёл до меню на первом телефоне, но вылетал после ролика. На Mali-G57
     * (realme C71) Zink выдавал ~25 тысяч ошибок OpenGL за игру и однажды терял устройство Vulkan.
     */
    WINED3D_ZINK("wined3d_zink", "OpenGL — совместимый", "vortek,zink"),

    /** D7VK через Vortek — для GPU не Adreno. Нужен Vulkan 1.3 у системного драйвера. */
    D7VK("d7vk", "Vulkan — быстрый", "vortek,zink"),

    /**
     * WineD3D через Gladio — OpenGL Winlator по умолчанию. На первом телефоне (Adreno) не дал
     * 3D-устройства, а на Mali-G57 — самый быстрый из доступных режимов (до 20–23 FPS).
     */
    WINED3D_GLADIO("wined3d", "OpenGL — запасной", "vortek,gladio");

    val isD7VK: Boolean get() = this == D7VK_TURNIP || this == D7VK

    /** Vulkan — Turnip (драйвер Mesa в процессе игры), а не Vortek. */
    val usesTurnip: Boolean get() = graphicsDriver.startsWith("turnip,")

    /**
     * Режим работает на этом телефоне: Turnip — только драйвер Adreno (свой Vulkan 1.3), а D7VK
     * через системный драйвер требует от него Vulkan 1.3 (без него — «DXVK: No adapters found» в
     * журнале, и игра закрывается через минуту). Пока видеокарта неизвестна (до первого запуска),
     * ничего не скрывается.
     *
     * @param vulkan13 системный драйвер Vulkan не ниже 1.3 и быстрый режим ещё не падал (GpuInfo)
     */
    fun supportedOn(gpuName: String?, vulkan13: Boolean): Boolean = when {
        usesTurnip -> gpuName == null || isAdreno(gpuName)
        isD7VK -> vulkan13
        else -> true
    }

    /** Следующий режим, работающий на этом телефоне, — кнопка «другой режим» на экране сбоя. */
    fun next(gpuName: String?, vulkan13: Boolean): GraphicsMode {
        val all = entries
        return (1..all.size).map { all[(ordinal + it) % all.size] }.first { it.supportedOn(gpuName, vulkan13) }
    }

    companion object {
        fun isAdreno(gpuName: String?): Boolean = gpuName?.contains("Adreno", ignoreCase = true) == true

        /**
         * По умолчанию: на Adreno — D7VK через Turnip; на Mali — WineD3D через Gladio (на Mali-G57
         * быстрее Zink и без потери устройства); на остальных — WineD3D через Zink.
         */
        fun defaultFor(gpuName: String?): GraphicsMode = when {
            isAdreno(gpuName) -> D7VK_TURNIP
            gpuName?.contains("Mali", ignoreCase = true) == true -> WINED3D_GLADIO
            else -> WINED3D_ZINK
        }

        /** Режимы для списка в настройках: только работающие на этом телефоне. */
        fun available(gpuName: String?, vulkan13: Boolean): List<GraphicsMode> = entries.filter { it.supportedOn(gpuName, vulkan13) }

        /** Выбор игрока, если режим работает на этом телефоне; иначе — режим по видеокарте. */
        fun of(id: String?, gpuName: String?, vulkan13: Boolean = true): GraphicsMode =
            entries.firstOrNull { it.id == id }?.takeIf { it.supportedOn(gpuName, vulkan13) } ?: defaultFor(gpuName)

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
