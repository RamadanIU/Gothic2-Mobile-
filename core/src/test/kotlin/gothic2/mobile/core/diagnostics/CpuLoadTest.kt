package gothic2.mobile.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CpuLoadTest {
    private fun t(tid: Int, ticks: Long, cpu: Int, pid: Int = 100, thread: String = "Gothic2c.exe") =
        CpuLoad.ThreadTicks(pid, tid, "Gothic2c.exe", thread, ticks, cpu)

    @Test
    fun `percent per thread and in total over the period`() {
        val load = CpuLoad(ticksPerSecond = 100)
        load.onSample(0, listOf(t(100, 0, 7), t(101, 0, 5, thread = "wined3d_cs"), t(102, 0, 1)), listOf(1000000L, 2000000L))
        // За 2 с: главный поток — 190 тиков (95 %), поток отрисовки — 60 (30 %), третий простаивает.
        load.onSample(2000, listOf(t(100, 190, 7), t(101, 60, 6, thread = "wined3d_cs"), t(102, 0, 1)), listOf(1200000L, 2000000L))
        val sum = load.summaryAndReset()!!
        assertEquals(125, sum.totalPercent)
        assertEquals(listOf(CpuLoad.ThreadLoad("Gothic2c.exe(main)", 95, listOf(7)), CpuLoad.ThreadLoad("Gothic2c.exe/wined3d_cs", 30, listOf(6))), sum.top)
        // Частоты — только по снимкам после первого.
        assertEquals(listOf(1200, 2000), sum.freqMHz)
        assertEquals(
            "total=125% top: Gothic2c.exe(main) 95%@7, Gothic2c.exe/wined3d_cs 30%@6; MHz 0:1200 1:2000",
            sum.format(emptyList()),
        )
        assertEquals("total=125% top: Gothic2c.exe(main) 95%@7, Gothic2c.exe/wined3d_cs 30%@6; MHz 0-1:1600", sum.format(listOf(listOf(0, 1))))
    }

    @Test
    fun `unnamed threads, reused tids and restarts`() {
        val load = CpuLoad(ticksPerSecond = 100)
        load.onSample(0, listOf(t(103, 10, 3), t(104, 50, 4)))
        // Поток 104 завершился, его номер занял другой процесс — не считается.
        load.onSample(1000, listOf(t(103, 30, 3), t(104, 80, 4, pid = 200)))
        assertEquals(listOf(CpuLoad.ThreadLoad("Gothic2c.exe/#103", 20, listOf(3))), load.summaryAndReset()!!.top)
        assertNull(load.summaryAndReset())
        // После паузы первый снимок — точка отсчёта, а не разница с давним снимком.
        load.restart()
        load.onSample(60_000, listOf(t(103, 500, 3)))
        load.onSample(61_000, listOf(t(103, 510, 3)))
        assertEquals(10, load.summaryAndReset()!!.totalPercent)
    }

    @Test
    fun `stat line with spaces and parentheses in the name`() {
        val fields = (4..52).joinToString(" ") { n ->
            when (n) {
                14 -> "1500"
                15 -> "250"
                39 -> "7"
                else -> "0"
            }
        }
        assertEquals(Triple("Gothic (2).exe", 1750L, 7), CpuLoad.parseStat("1234 (Gothic (2).exe) R $fields"))
        assertNull(CpuLoad.parseStat("1234 Gothic"))
        assertNull(CpuLoad.parseStat("1234 (x) R 1 2"))
    }
}
