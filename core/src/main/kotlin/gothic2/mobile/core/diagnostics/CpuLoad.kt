package gothic2.mobile.core.diagnostics

import gothic2.mobile.core.config.CpuCores

/**
 * Загрузка процессора по потокам игры и среды (раздел 14 плана). По FPS не видно, во что
 * упирается игра; по потокам видно: главный поток Gothic на ~100 % ядра — не хватает процессора
 * (Box64), высокий wineserver — синхронизация без ESync, поток на медленном ядре — планировщик,
 * низкая частота ядер — перегрев. Снимки (`/proc/<pid>/task/<tid>/stat`) берутся раз в несколько
 * секунд, сводка — раз в период, как у [FrameStats].
 *
 * Потокобезопасен: снимки приходят из фонового потока, сводка — из UI-потока.
 */
class CpuLoad(private val ticksPerSecond: Long = 100) {
    /** Поток в момент снимка: время на процессоре в тиках (utime + stime) и ядро, где он был последним. */
    data class ThreadTicks(
        val pid: Int,
        val tid: Int,
        val process: String,
        val thread: String,
        val ticks: Long,
        val cpu: Int,
    )

    /** @property cpus ядра, на которых поток застали работающим за период */
    data class ThreadLoad(val name: String, val percent: Int, val cpus: List<Int>)

    /**
     * @property totalPercent все потоки вместе: 100 % — одно ядро целиком
     * @property freqMHz средняя текущая частота каждого ядра за период; null — не читается
     */
    data class Summary(val totalPercent: Int, val top: List<ThreadLoad>, val freqMHz: List<Int?>) {
        fun format(clusters: List<List<Int>>): String = buildString {
            append("total=").append(totalPercent).append('%')
            if (top.isNotEmpty()) {
                append(" top: ")
                top.joinTo(this, ", ") { "${it.name} ${it.percent}%@${CpuCores.format(it.cpus)}" }
            }
            // Средняя частота по кластерам; если кластеры неизвестны — по каждому ядру.
            val groups = clusters.ifEmpty { freqMHz.indices.map { listOf(it) } }.mapNotNull { cpus ->
                val known = cpus.mapNotNull { freqMHz.getOrNull(it) }
                if (known.isEmpty()) null else cpus to known.average().toInt()
            }
            if (groups.isNotEmpty()) {
                append("; MHz ")
                groups.joinTo(this, " ") { (cpus, mhz) -> "${CpuCores.format(cpus)}:$mhz" }
            }
        }
    }

    private class Acc(val name: String) {
        var ticks = 0L
        val cpus = sortedSetOf<Int>()
    }

    private var last: Map<Int, ThreadTicks> = emptyMap()
    private var lastAt = -1L
    private var elapsedMs = 0L
    private val threads = HashMap<Int, Acc>()
    private var freqSum = LongArray(0)
    private var freqCount = IntArray(0)

    /** @param freqKHz текущая частота каждого ядра; null — не читается */
    @Synchronized
    fun onSample(nowMs: Long, sample: List<ThreadTicks>, freqKHz: List<Long?> = emptyList()) {
        if (lastAt >= 0) {
            elapsedMs += (nowMs - lastAt).coerceAtLeast(0)
            for (t in sample) {
                val prev = last[t.tid] ?: continue
                // Номер потока мог достаться новому процессу — тогда разница бессмысленна.
                if (prev.pid != t.pid) continue
                val delta = t.ticks - prev.ticks
                if (delta <= 0) continue
                val acc = threads.getOrPut(t.tid) { Acc(nameOf(t)) }
                acc.ticks += delta
                acc.cpus += t.cpu
            }
            if (freqSum.size < freqKHz.size) {
                freqSum = freqSum.copyOf(freqKHz.size)
                freqCount = freqCount.copyOf(freqKHz.size)
            }
            freqKHz.forEachIndexed { i, f ->
                if (f != null && f > 0) {
                    freqSum[i] += f
                    freqCount[i]++
                }
            }
        }
        last = sample.associateBy { it.tid }
        lastAt = nowMs
    }

    /** Следующий снимок — новая точка отсчёта (после паузы, когда процессы Wine были остановлены). */
    @Synchronized
    fun restart() {
        last = emptyMap()
        lastAt = -1
    }

    /** Сводка с прошлого вызова: [top] самых загруженных потоков (не меньше 1 %); null — снимков не было. */
    @Synchronized
    fun summaryAndReset(top: Int = 6): Summary? {
        val elapsed = elapsedMs
        val result = if (elapsed <= 0) {
            null
        } else {
            fun percent(ticks: Long) = (ticks * 100_000 / (ticksPerSecond * elapsed)).toInt()
            Summary(
                totalPercent = percent(threads.values.sumOf { it.ticks }),
                top = threads.values.sortedByDescending { it.ticks }
                    .map { ThreadLoad(it.name, percent(it.ticks), it.cpus.toList()) }
                    .filter { it.percent >= 1 }
                    .take(top),
                freqMHz = freqSum.indices.map { i -> if (freqCount[i] > 0) (freqSum[i] / freqCount[i] / 1000).toInt() else null },
            )
        }
        threads.clear()
        elapsedMs = 0
        freqSum = LongArray(freqSum.size)
        freqCount = IntArray(freqCount.size)
        return result
    }

    /**
     * Имя для журнала. Wine называет потоки процесса именем EXE; безымянные потоки различаются
     * номером, главный помечен «(main)».
     */
    private fun nameOf(t: ThreadTicks): String = when {
        t.tid == t.pid -> "${t.process}(main)"
        t.thread == t.process || t.thread.isEmpty() -> "${t.process}/#${t.tid}"
        else -> "${t.process}/${t.thread}"
    }

    companion object {
        /**
         * Строка `/proc/<pid>/task/<tid>/stat`: имя (comm), utime + stime и ядро (поле 39).
         * Имя может содержать пробелы и скобки, поэтому поля считаются от последней «)».
         */
        fun parseStat(line: String): Triple<String, Long, Int>? {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            if (open < 0 || close < open) return null
            val fields = line.substring(close + 1).trim().split(' ')
            // fields[0] — поле 3 (состояние), значит поле N — fields[N - 3].
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: return null
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: return null
            val cpu = fields.getOrNull(36)?.toIntOrNull() ?: return null
            return Triple(line.substring(open + 1, close), utime + stime, cpu)
        }
    }
}
