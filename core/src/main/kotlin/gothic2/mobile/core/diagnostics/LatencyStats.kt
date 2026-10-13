package gothic2.mobile.core.diagnostics

/**
 * Скользящая статистика задержек (раздел 14 плана): хранит последние [capacity] замеров
 * и отдаёт перцентили. Высокий FPS с большой задержкой ввода для Gothic бесполезен —
 * поэтому задержка измеряется отдельно.
 */
class LatencyStats(private val capacity: Int = 512) {
    private val samples = LongArray(capacity)
    private var size = 0
    private var next = 0
    var total: Long = 0
        private set

    data class Summary(val count: Int, val p50: Long, val p95: Long, val max: Long)

    fun record(ms: Long) {
        samples[next] = ms.coerceAtLeast(0)
        next = (next + 1) % capacity
        if (size < capacity) size++
        total++
    }

    fun summary(): Summary? {
        if (size == 0) return null
        val sorted = samples.copyOf(size).also { it.sort() }
        fun pct(p: Double) = sorted[((size - 1) * p).toInt()]
        return Summary(size, pct(0.50), pct(0.95), sorted.last())
    }

    fun reset() {
        size = 0
        next = 0
    }
}
