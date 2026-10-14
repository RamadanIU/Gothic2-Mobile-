package gothic2.mobile.core.config

/**
 * Ядра процессора, на которых работает игра (раздел 19 плана). Gothic под Box64 упирается в
 * свой главный поток, а кадр собирают ещё поток отрисовки WineD3D и потоки Mesa. Если
 * планировщик Android переносит один из них на энергоэффективное ядро (Cortex-A510 и т. п.),
 * этот кадр считается в 2–4 раза дольше — рывок. Поэтому игре отдаются все ядра, кроме самого
 * медленного кластера, если быстрых остаётся не меньше [MIN_CORES]; иначе (2 быстрых + 6
 * медленных, все ядра одинаковые, частоты неизвестны) — все ядра, как в Winlator по умолчанию.
 */
object CpuCores {
    const val MIN_CORES = 4

    /** @param maxFreqKHz наибольшая частота каждого ядра по номеру; null — не удалось прочитать */
    fun forGame(maxFreqKHz: List<Long?>): List<Int> {
        val all = maxFreqKHz.indices.toList()
        val freqs = maxFreqKHz.map { it ?: return all }
        if (freqs.isEmpty() || freqs.any { it <= 0 }) return all
        val slowest = freqs.min()
        val fast = all.filter { freqs[it] > slowest }
        return if (fast.size >= MIN_CORES) fast else all
    }

    /** Кластеры — ядра с одинаковой наибольшей частотой, от медленных к быстрым; пусто — частоты неизвестны. */
    fun clusters(maxFreqKHz: List<Long?>): List<List<Int>> {
        if (maxFreqKHz.isEmpty() || maxFreqKHz.any { it == null }) return emptyList()
        return maxFreqKHz.indices.groupBy { maxFreqKHz[it]!! }.toSortedMap().values.toList()
    }

    /** Список ядер из sysfs (`/sys/devices/system/cpu/present`): «0-7», «0-3,6»; null — не разобрать. */
    fun parseList(text: String): List<Int>? {
        val result = sortedSetOf<Int>()
        for (part in text.trim().split(',')) {
            val bounds = part.trim().split('-')
            val from = bounds[0].toIntOrNull() ?: return null
            val to = (if (bounds.size == 2) bounds[1].toIntOrNull() else from) ?: return null
            if (bounds.size > 2 || from < 0 || to < from || to > MAX_CPU) return null
            for (i in from..to) result += i
        }
        return result.toList()
    }

    /** «0-2,5,7» — для журнала. */
    fun format(cpus: Collection<Int>): String {
        val sorted = cpus.toSortedSet().toList()
        if (sorted.isEmpty()) return "-"
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        for (cpu in sorted.drop(1) + Int.MIN_VALUE) {
            if (cpu == prev + 1) {
                prev = cpu
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = cpu
            prev = cpu
        }
        return parts.joinToString(",")
    }

    /** Маска сродства в Winlator — 16 бит. */
    private const val MAX_CPU = 15
}
