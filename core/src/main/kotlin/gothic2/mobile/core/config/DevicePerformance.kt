package gothic2.mobile.core.config

/**
 * Хватит ли процессора на Gothic II (раздел 19 плана). Gothic под Box64 упирается в свой главный
 * поток, поэтому важно одно самое быстрое ядро: его частота, умноженная на относительную скорость
 * архитектуры (Cortex-A76 = 1). Образцы: на Galaxy S23 Ultra (Cortex-X3, 3,36 ГГц) главный поток
 * занимает ~45 % ядра при 30 FPS; на realme C71 (Unisoc T7250: Cortex-A75, 1,8 ГГц — 1,55 по этой
 * шкале) — 95–99 % при 2–6 FPS, а в лучшем режиме графики — до 20 FPS. Порог [MIN_SCORE] — это
 * Cortex-A76 на 2 ГГц; уточняется по отзывам игроков.
 */
object DevicePerformance {
    /** Наименьшая оценка самого быстрого ядра, при которой телефон не считается слабым. */
    const val MIN_SCORE = 2.0

    /** Ядро из /proc/cpuinfo: производитель (`CPU implementer`) и модель (`CPU part`). */
    data class Core(val implementer: Int, val part: Int)

    /**
     * Оценка самого быстрого ядра: ГГц × скорость архитектуры; null — частоты не читаются.
     * Неизвестная (новая) архитектура считается не медленнее Cortex-A76.
     *
     * @param maxFreqKHz наибольшая частота каждого ядра по номеру
     * @param cores модель каждого ядра по номеру; null — не прочитана
     */
    fun score(maxFreqKHz: List<Long?>, cores: Map<Int, Core>): Double? =
        maxFreqKHz.indices.mapNotNull { cpu ->
            val khz = maxFreqKHz[cpu]?.takeIf { it > 0 } ?: return@mapNotNull null
            khz / 1_000_000.0 * (cores[cpu]?.let(::speedOf) ?: 1.0)
        }.maxOrNull()

    /** Телефон заметно слабее нужного; если частоты неизвестны — не слабый (не пугать зря). */
    fun isWeak(maxFreqKHz: List<Long?>, cores: Map<Int, Core>): Boolean =
        score(maxFreqKHz, cores)?.let { it < MIN_SCORE } ?: false

    /**
     * Модели ядер из текста /proc/cpuinfo (arm64: блок на ядро, `processor : N`, затем
     * `CPU implementer : 0x41` и `CPU part : 0xd05`). Ядра без обоих полей пропускаются.
     */
    fun parseCpuInfo(text: String): Map<Int, Core> {
        val result = HashMap<Int, Core>()
        var cpu: Int? = null
        var implementer: Int? = null
        var part: Int? = null
        fun flush() {
            val c = cpu
            val i = implementer
            val p = part
            if (c != null && i != null && p != null) result[c] = Core(i, p)
        }
        for (line in text.lineSequence()) {
            val key = line.substringBefore(':').trim()
            val value = line.substringAfter(':', "").trim()
            when (key) {
                "processor" -> {
                    flush()
                    cpu = value.toIntOrNull()
                    implementer = null
                    part = null
                }
                "CPU implementer" -> implementer = parseHex(value)
                "CPU part" -> part = parseHex(value)
            }
        }
        flush()
        return result
    }

    private fun parseHex(value: String): Int? = value.removePrefix("0x").toIntOrNull(16)

    /** Скорость архитектуры на гигагерц относительно Cortex-A76; новые и неизвестные — 1. */
    private fun speedOf(core: Core): Double = when (core.implementer) {
        ARM -> ARM_SPEED[core.part]
        QUALCOMM -> QUALCOMM_SPEED[core.part]
        else -> null
    } ?: 1.0

    private const val ARM = 0x41
    private const val QUALCOMM = 0x51

    /** Ядра ARM старше Cortex-A76 и энергоэффективные (грубые оценки по SPECint на гигагерц). */
    private val ARM_SPEED = mapOf(
        0xd04 to 0.40, // Cortex-A35
        0xd03 to 0.45, // Cortex-A53
        0xd05 to 0.50, // Cortex-A55
        0xd46 to 0.55, // Cortex-A510
        0xd80 to 0.60, // Cortex-A520
        0xd07 to 0.60, // Cortex-A57
        0xd08 to 0.70, // Cortex-A72
        0xd09 to 0.75, // Cortex-A73
        0xd0a to 0.85, // Cortex-A75
    )

    /** Ядра Kryo со своими номерами (Snapdragon 6xx/8xx до 855). */
    private val QUALCOMM_SPEED = mapOf(
        0x800 to 0.75, // Kryo 2xx Gold (A73)
        0x801 to 0.45, // Kryo 2xx Silver (A53)
        0x802 to 0.85, // Kryo 3xx Gold (A75)
        0x803 to 0.50, // Kryo 3xx Silver (A55)
        0x805 to 0.50, // Kryo 4xx Silver (A55)
    )
}
