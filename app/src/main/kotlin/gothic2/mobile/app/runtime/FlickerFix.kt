package gothic2.mobile.app.runtime

/**
 * Варианты против мерцания в режимах D7VK: чёрные чёрточки и линии, белые вспышки на весь кадр.
 * Путь кадра от игры до экрана (X-сервер Winlator) после исправлений гонок выглядит верным, а
 * увидеть экран телефона нельзя — поэтому каждый вариант проверяет одну догадку о том, где
 * рождается артефакт, и игрок переключает их в настройках (docs/PROGRESS.md, 2026-10-15).
 *
 * @property title название в настройках — без названий библиотек
 * @property turnipDebug что добавить к `TU_DEBUG` (только режимы с Turnip)
 * @property d7vkConfig что добавить к настройке D7VK (`DXVK_CONFIG`)
 */
enum class FlickerFix(
    val id: String,
    val title: String,
    val turnipDebug: List<String> = emptyList(),
    val d7vkConfig: List<String> = emptyList(),
) {
    /** Как раньше: рисование в память без плиточной памяти (GraphicsMode.turnipDebug). */
    STANDARD("standard", "Обычный"),

    /**
     * Turnip без сжатия UBWC и без ранней проверки глубины LRZ: у Adreno 7xx ошибки в них дают
     * чёрные блоки 16×4 и мигающие полосы на геометрии — то самое, что было до `sysmem`.
     */
    NO_COMPRESSION("no_compression", "1 — без сжатия кадра", turnipDebug = listOf("noubwc", "nolrz")),

    /**
     * Буфер глубины 24 бита вместо 16: D7VK первым предлагает 16-битный, и если Gothic берёт его,
     * далёкие и наложенные поверхности спорят за пиксель — мигающие линии при повороте камеры.
     */
    DEPTH24("depth24", "2 — точная глубина", d7vkConfig = listOf("ddraw.supportD16 = False")),

    /**
     * Оба варианта сразу и сброс кэшей GPU после каждой операции рисования: медленнее, но если
     * мерцание уйдёт только здесь — дело в синхронизации внутри драйвера.
     */
    STRICT(
        "strict",
        "3 — всё сразу, строгий режим",
        turnipDebug = listOf("noubwc", "nolrz", "flushall"),
        d7vkConfig = listOf("ddraw.supportD16 = False"),
    );

    companion object {
        fun of(id: String?): FlickerFix = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}
