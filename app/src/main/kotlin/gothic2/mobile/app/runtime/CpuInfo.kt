package gothic2.mobile.app.runtime

import android.system.Os
import android.system.OsConstants
import gothic2.mobile.core.config.CpuCores
import gothic2.mobile.core.config.DevicePerformance
import gothic2.mobile.core.diagnostics.CpuLoad
import java.io.File

/**
 * Процессор устройства (sysfs) и потоки игры (/proc) — для выбора ядер ([CpuCores]) и журнала
 * ([CpuLoad]). Root не нужен: частоты ядер читаются приложениями, а в /proc видны только
 * процессы своего UID — само приложение и запущенные им Wine, wineserver и игра. Что не читается
 * на конкретном устройстве, просто пропускается.
 */
object CpuInfo {
    /** Номера ядер; если `present` не читается — по числу доступных. */
    val cpus: List<Int> by lazy {
        read("/sys/devices/system/cpu/present")?.let(CpuCores::parseList)
            ?: (0 until Runtime.getRuntime().availableProcessors()).toList()
    }

    val ticksPerSecond: Long by lazy { runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L).takeIf { it > 0 } ?: 100L }

    /** Наибольшая частота каждого ядра по номеру, кГц; null — не читается. */
    fun maxFreqKHz(): List<Long?> = freqKHz("cpuinfo_max_freq")

    /** Текущая частота каждого ядра, кГц: падение у быстрых ядер — перегрев или экономия энергии. */
    fun curFreqKHz(): List<Long?> = freqKHz("scaling_cur_freq")

    /** Модели ядер из /proc/cpuinfo — для оценки процессора ([DevicePerformance]). */
    fun cores(): Map<Int, DevicePerformance.Core> = read("/proc/cpuinfo")?.let(DevicePerformance::parseCpuInfo).orEmpty()

    private fun freqKHz(name: String): List<Long?> =
        List((cpus.maxOrNull() ?: -1) + 1) { read("/sys/devices/system/cpu/cpu$it/cpufreq/$name")?.trim()?.toLongOrNull() }

    /** Снимок всех потоков процессов приложения (тот же UID). Вызывать не из UI-потока. */
    fun sampleThreads(): List<CpuLoad.ThreadTicks> {
        val uid = Os.getuid()
        val result = ArrayList<CpuLoad.ThreadTicks>(256)
        for (name in File("/proc").list().orEmpty()) {
            val pid = name.toIntOrNull() ?: continue
            val dir = "/proc/$pid"
            if (runCatching { Os.stat(dir).st_uid }.getOrNull() != uid) continue
            val process = read("$dir/stat")?.let(CpuLoad::parseStat)?.first ?: continue
            for (tidName in File("$dir/task").list().orEmpty()) {
                val tid = tidName.toIntOrNull() ?: continue
                // Поток или процесс могли завершиться между чтением списка и файла.
                val (thread, ticks, cpu) = read("$dir/task/$tid/stat")?.let(CpuLoad::parseStat) ?: continue
                result += CpuLoad.ThreadTicks(pid, tid, process, thread, ticks, cpu)
            }
        }
        return result
    }

    private fun read(path: String): String? = runCatching { File(path).readText() }.getOrNull()
}
