package gothic2.mobile.og;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Слабый ли телефон: тогда при первом запуске графика — «Экономно». Главный поток движка (логика игры,
 * подготовка кадра) занимает одно ядро, поэтому важна оценка самого быстрого ядра: его частота,
 * умноженная на скорость архитектуры (Cortex-A76 = 1). Образцы: Galaxy S23 Ultra (Cortex-X3, 3,36 ГГц) —
 * 3,36; realme C71 (Unisoc T7250: Cortex-A75, 1,8 ГГц) — 1,55. Порог — Cortex-A76 на 2 ГГц.
 * Перенесено из прежней версии на эмуляции Windows (docs/LEGACY_WINLATOR.md).
 */
final class DevicePerformance {
    /** Наименьшая оценка самого быстрого ядра, при которой телефон не считается слабым. */
    static final double MIN_SCORE = 2.0;

    private static final int ARM = 0x41;
    private static final int QUALCOMM = 0x51;

    /** Ядра ARM старше Cortex-A76 и энергоэффективные (грубые оценки по SPECint на гигагерц). */
    private static final Map<Integer, Double> ARM_SPEED = new HashMap<>();
    /** Ядра Kryo со своими номерами (Snapdragon 6xx/8xx до 855). */
    private static final Map<Integer, Double> QUALCOMM_SPEED = new HashMap<>();

    static {
        ARM_SPEED.put(0xd04, 0.40); // Cortex-A35
        ARM_SPEED.put(0xd03, 0.45); // Cortex-A53
        ARM_SPEED.put(0xd05, 0.50); // Cortex-A55
        ARM_SPEED.put(0xd46, 0.55); // Cortex-A510
        ARM_SPEED.put(0xd80, 0.60); // Cortex-A520
        ARM_SPEED.put(0xd07, 0.60); // Cortex-A57
        ARM_SPEED.put(0xd08, 0.70); // Cortex-A72
        ARM_SPEED.put(0xd09, 0.75); // Cortex-A73
        ARM_SPEED.put(0xd0a, 0.85); // Cortex-A75
        QUALCOMM_SPEED.put(0x800, 0.75); // Kryo 2xx Gold (A73)
        QUALCOMM_SPEED.put(0x801, 0.45); // Kryo 2xx Silver (A53)
        QUALCOMM_SPEED.put(0x802, 0.85); // Kryo 3xx Gold (A75)
        QUALCOMM_SPEED.put(0x803, 0.50); // Kryo 3xx Silver (A55)
        QUALCOMM_SPEED.put(0x805, 0.50); // Kryo 4xx Silver (A55)
    }

    private DevicePerformance() {
    }

    /**
     * Оценка самого быстрого ядра: ГГц × скорость архитектуры; null — частоты не читаются.
     * Неизвестная (новая) архитектура считается не медленнее Cortex-A76.
     *
     * @param maxFreqKHz наибольшая частота каждого ядра по номеру (null — не прочитана)
     * @param cores      модель ядра по номеру: {implementer, part}
     */
    static Double score(List<Long> maxFreqKHz, Map<Integer, int[]> cores) {
        Double best = null;
        for (int cpu = 0; cpu < maxFreqKHz.size(); cpu++) {
            Long khz = maxFreqKHz.get(cpu);
            if (khz == null || khz <= 0) continue;
            double s = khz / 1_000_000.0 * speedOf(cores.get(cpu));
            if (best == null || s > best) best = s;
        }
        return best;
    }

    /** Телефон заметно слабее нужного; если частоты неизвестны — не слабый (не занижать зря). */
    static boolean isWeak(List<Long> maxFreqKHz, Map<Integer, int[]> cores) {
        Double s = score(maxFreqKHz, cores);
        return s != null && s < MIN_SCORE;
    }

    /**
     * Модели ядер из текста /proc/cpuinfo (arm64: блок на ядро, «processor : N», затем
     * «CPU implementer : 0x41» и «CPU part : 0xd05»). Ядра без обоих полей пропускаются.
     */
    static Map<Integer, int[]> parseCpuInfo(String text) {
        Map<Integer, int[]> result = new HashMap<>();
        Integer cpu = null;
        Integer implementer = null;
        Integer part = null;
        for (String line : text.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "processor":
                    put(result, cpu, implementer, part);
                    cpu = parseInt(value, 10);
                    implementer = null;
                    part = null;
                    break;
                case "CPU implementer":
                    implementer = parseInt(value, 16);
                    break;
                case "CPU part":
                    part = parseInt(value, 16);
                    break;
                default:
                    break;
            }
        }
        put(result, cpu, implementer, part);
        return result;
    }

    /** Оценка этого телефона по /proc/cpuinfo и cpufreq; null — не прочитать. */
    static Double scoreOfThisDevice() {
        return scoreOf(new File("/proc/cpuinfo"), new File("/sys/devices/system/cpu"));
    }

    static Double scoreOf(File cpuInfo, File cpuDir) {
        Map<Integer, int[]> cores;
        try {
            cores = parseCpuInfo(new String(Files.readAllBytes(cpuInfo.toPath()), StandardCharsets.US_ASCII));
        } catch (IOException | RuntimeException e) {
            cores = new HashMap<>();
        }
        List<Long> freq = new ArrayList<>();
        for (int cpu = 0; cpu < 64; cpu++) {
            File f = new File(cpuDir, "cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
            if (!new File(cpuDir, "cpu" + cpu).isDirectory()) break;
            Long khz = null;
            try {
                khz = Long.parseLong(new String(Files.readAllBytes(f.toPath()), StandardCharsets.US_ASCII).trim());
            } catch (IOException | RuntimeException e) {
                // ядро выключено или частота закрыта - пропустить
            }
            freq.add(khz);
        }
        return score(freq, cores);
    }

    private static void put(Map<Integer, int[]> result, Integer cpu, Integer implementer, Integer part) {
        if (cpu != null && implementer != null && part != null) result.put(cpu, new int[]{implementer, part});
    }

    private static Integer parseInt(String value, int radix) {
        try {
            return Integer.parseInt(radix == 16 && value.startsWith("0x") ? value.substring(2) : value, radix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Скорость архитектуры на гигагерц относительно Cortex-A76; новые и неизвестные — 1. */
    private static double speedOf(int[] core) {
        if (core == null) return 1.0;
        Double s = null;
        if (core[0] == ARM) s = ARM_SPEED.get(core[1]);
        else if (core[0] == QUALCOMM) s = QUALCOMM_SPEED.get(core[1]);
        return s != null ? s : 1.0;
    }
}
