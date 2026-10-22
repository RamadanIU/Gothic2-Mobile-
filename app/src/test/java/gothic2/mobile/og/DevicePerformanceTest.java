package gothic2.mobile.og;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DevicePerformanceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    // realme C71 (Unisoc T7250): 6 × Cortex-A55 и 2 × Cortex-A75 1,8 ГГц
    private static final String T7250 = cpuInfo(new int[]{0xd05, 0xd05, 0xd05, 0xd05, 0xd05, 0xd05, 0xd0a, 0xd0a});

    private static String cpuInfo(int[] parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            sb.append("processor\t: ").append(i).append("\nBogoMIPS\t: 52.00\n")
                .append("CPU implementer\t: 0x41\nCPU part\t: 0x").append(Integer.toHexString(parts[i])).append("\n\n");
        }
        return sb.toString();
    }

    @Test
    public void parsesCoresFromCpuInfo() {
        Map<Integer, int[]> cores = DevicePerformance.parseCpuInfo(T7250);
        assertEquals(8, cores.size());
        assertEquals(0x41, cores.get(6)[0]);
        assertEquals(0xd0a, cores.get(6)[1]);
        assertEquals(0xd05, cores.get(0)[1]);
    }

    @Test
    public void unisocT7250IsWeak() {
        Map<Integer, int[]> cores = DevicePerformance.parseCpuInfo(T7250);
        // ядра 0..7; частоты: маленькие 1,6 ГГц, большие 1,8 ГГц
        List<Long> freq = Arrays.asList(1_600_000L, 1_600_000L, 1_600_000L, 1_600_000L,
            1_600_000L, 1_600_000L, 1_820_000L, 1_820_000L);
        assertEquals(1.55, DevicePerformance.score(freq, cores), 0.01);
        assertTrue(DevicePerformance.isWeak(freq, cores));
    }

    @Test
    public void newCoresAreNotWeak() {
        // Cortex-X3 (0xd4e) неизвестен таблице - считается не медленнее A76
        Map<Integer, int[]> cores = new HashMap<>();
        cores.put(7, new int[]{0x41, 0xd4e});
        List<Long> freq = Arrays.asList(2_016_000L, null, null, null, null, null, null, 3_360_000L);
        assertEquals(3.36, DevicePerformance.score(freq, cores), 0.001);
        assertFalse(DevicePerformance.isWeak(freq, cores));
    }

    @Test
    public void unknownFrequenciesAreNotWeak() {
        assertNull(DevicePerformance.score(Arrays.asList(null, null), new HashMap<>()));
        assertFalse(DevicePerformance.isWeak(Arrays.asList(null, null), new HashMap<>()));
    }

    @Test
    public void readsSysfsLayout() throws IOException {
        File cpuInfo = tmp.newFile("cpuinfo");
        Files.write(cpuInfo.toPath(), T7250.getBytes(StandardCharsets.US_ASCII));
        File cpuDir = tmp.newFolder("cpu");
        for (int i = 0; i < 8; i++) {
            File freq = new File(cpuDir, "cpu" + i + "/cpufreq");
            assertTrue(freq.mkdirs());
            Files.write(new File(freq, "cpuinfo_max_freq").toPath(),
                ((i >= 6 ? "1820000" : "1600000") + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(1.55, DevicePerformance.scoreOf(cpuInfo, cpuDir), 0.01);
    }
}
