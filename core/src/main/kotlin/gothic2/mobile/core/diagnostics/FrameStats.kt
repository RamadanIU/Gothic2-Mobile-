package gothic2.mobile.core.diagnostics

/**
 * Плавность картинки (раздел 14 плана): интервалы между кадрами окна игры за период сводки.
 * Средний FPS фризы не показывает, поэтому считаются ещё перцентили интервалов и число
 * подвисаний. Кадр — обновление содержимого окна X-сервера; при нескольких окнах берётся
 * то, что обновлялось чаще (окно игры). Паузы дольше [pauseMs] (свёрнутое приложение,
 * загрузка мира) считаются отдельно и в перцентили не входят.
 *
 * Потокобезопасен: кадры приходят из потока X-сервера, сводка — из UI-потока.
 */
class FrameStats(
    private val capacity: Int = 4096,
    private val hitchMs: Long = 100,
    private val pauseMs: Long = 1000,
) {
    data class Summary(
        val frames: Int,
        val fps: Double,
        val p50: Long,
        val p95: Long,
        val p99: Long,
        val max: Long,
        /** Кадры дольше hitchMs, но короче паузы — заметные глазу фризы. */
        val hitches: Int,
        /** Остановки дольше pauseMs (загрузка, свёрнутое приложение). */
        val pauses: Int,
    )

    private class Window {
        var last = -1L
        var frames = 0
        var busyMs = 0L
        var hitches = 0
        var pauses = 0
        var samples = LongArray(16)
        var size = 0
    }

    private val windows = HashMap<Int, Window>()

    @Synchronized
    fun onFrame(windowId: Int, nowMs: Long) {
        val w = windows.getOrPut(windowId) { Window() }
        if (w.last >= 0) {
            val dt = (nowMs - w.last).coerceAtLeast(0)
            if (dt >= pauseMs) {
                w.pauses++
            } else {
                if (dt > hitchMs) w.hitches++
                w.busyMs += dt
                w.frames++
                if (w.size < capacity) {
                    if (w.size == w.samples.size) w.samples = w.samples.copyOf(minOf(capacity, w.size * 2))
                    w.samples[w.size++] = dt
                }
            }
        }
        w.last = nowMs
    }

    /** Сводка по окну игры с прошлого вызова; null — кадров не было. */
    @Synchronized
    fun summaryAndReset(): Summary? {
        val w = windows.values.maxByOrNull { it.frames }
        val result = if (w == null || w.size == 0) {
            null
        } else {
            val sorted = w.samples.copyOf(w.size).also { it.sort() }
            fun pct(p: Double) = sorted[((w.size - 1) * p).toInt()]
            Summary(
                frames = w.frames,
                fps = if (w.busyMs > 0) w.frames * 1000.0 / w.busyMs else 0.0,
                p50 = pct(0.50),
                p95 = pct(0.95),
                p99 = pct(0.99),
                max = sorted.last(),
                hitches = w.hitches,
                pauses = w.pauses,
            )
        }
        // Окна без кадров за период (закрытые) забываются; у остальных отсчёт идёт от последнего кадра.
        windows.values.removeAll { it.frames == 0 && it.pauses == 0 }
        for (each in windows.values) {
            each.frames = 0
            each.busyMs = 0
            each.hitches = 0
            each.pauses = 0
            each.size = 0
        }
        return result
    }
}
