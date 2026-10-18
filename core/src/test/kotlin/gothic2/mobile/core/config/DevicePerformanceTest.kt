package gothic2.mobile.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DevicePerformanceTest {
    private fun cpuinfo(vararg parts: String) = parts.withIndex().joinToString("\n\n") { (i, part) ->
        "processor\t: $i\nBogoMIPS\t: 52.00\nCPU implementer\t: 0x41\nCPU architecture: 8\nCPU variant\t: 0x1\nCPU part\t: $part\nCPU revision\t: 0"
    } + "\n"

    @Test
    fun `unisoc T7250 is weak, snapdragon 8 gen 2 is not`() {
        // realme C71: 6 × Cortex-A55 1,6 ГГц + 2 × Cortex-A75 1,82 ГГц — 2–6 FPS в игре.
        val t7250 = DevicePerformance.parseCpuInfo(cpuinfo("0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd0a", "0xd0a"))
        val t7250Freq = List(6) { 1_612_000L } + List(2) { 1_820_000L }
        assertEquals(DevicePerformance.Core(0x41, 0xd0a), t7250[7])
        assertEquals(1.547, DevicePerformance.score(t7250Freq, t7250)!!, 0.001)
        assertTrue(DevicePerformance.isWeak(t7250Freq, t7250))

        // Galaxy S23 Ultra: Cortex-A510 + A715/A710 + X3 3,36 ГГц — 30 FPS.
        val sd8gen2 = DevicePerformance.parseCpuInfo(cpuinfo("0xd46", "0xd46", "0xd46", "0xd4d", "0xd4d", "0xd47", "0xd47", "0xd4e"))
        val sd8gen2Freq = listOf(2_016_000L, 2_016_000L, 2_016_000L, 2_803_200L, 2_803_200L, 2_803_200L, 2_803_200L, 3_360_000L)
        assertFalse(DevicePerformance.isWeak(sd8gen2Freq, sd8gen2))
    }

    @Test
    fun `cortex-a76 at 2_2 GHz passes, kryo silver alone does not`() {
        val g99 = DevicePerformance.parseCpuInfo(cpuinfo("0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd05", "0xd0b", "0xd0b"))
        assertFalse(DevicePerformance.isWeak(List(6) { 2_000_000L } + List(2) { 2_200_000L }, g99))
        val kryo = mapOf(0 to DevicePerformance.Core(0x51, 0x803))
        assertTrue(DevicePerformance.isWeak(listOf(1_800_000L), kryo))
    }

    @Test
    fun `unknown frequencies are not reported as weak`() {
        assertNull(DevicePerformance.score(listOf(null, null), emptyMap()))
        assertFalse(DevicePerformance.isWeak(listOf(null, null), emptyMap()))
        // Модель не прочитана — только частота, архитектура считается не медленнее Cortex-A76.
        assertFalse(DevicePerformance.isWeak(listOf(2_400_000L), emptyMap()))
        assertTrue(DevicePerformance.isWeak(listOf(1_500_000L), emptyMap()))
        assertTrue(DevicePerformance.parseCpuInfo("Hardware\t: Unisoc\n").isEmpty())
    }
}
