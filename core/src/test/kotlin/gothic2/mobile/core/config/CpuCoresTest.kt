package gothic2.mobile.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CpuCoresTest {
    private val sd8gen2 = listOf(2016000L, 2016000L, 2016000L, 2803200L, 2803200L, 2803200L, 2803200L, 3187200L)

    @Test
    fun `slowest cluster is left out when enough fast cores remain`() {
        assertEquals(listOf(3, 4, 5, 6, 7), CpuCores.forGame(sd8gen2))
        // Snapdragon 865: 4 × A55 + 3 × A77 + 1 × A77 prime — остаются ровно 4 ядра.
        assertEquals(listOf(4, 5, 6, 7), CpuCores.forGame(listOf(1804800L, 1804800L, 1804800L, 1804800L, 2419200L, 2419200L, 2419200L, 2841600L)))
    }

    @Test
    fun `all cores when too few fast ones, identical cores or unknown frequencies`() {
        val all = (0..7).toList()
        // 6 × A55 + 2 × A76: двух ядер игре мало.
        assertEquals(all, CpuCores.forGame(List(6) { 2000000L } + List(2) { 2200000L }))
        assertEquals(all, CpuCores.forGame(List(8) { 2400000L }))
        assertEquals(all, CpuCores.forGame(sd8gen2.toMutableList<Long?>().also { it[7] = null }))
        assertEquals(emptyList(), CpuCores.forGame(emptyList()))
    }

    @Test
    fun `clusters are ordered from slow to fast`() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5, 6), listOf(7)), CpuCores.clusters(sd8gen2))
        assertEquals(emptyList(), CpuCores.clusters(listOf(1L, null)))
    }

    @Test
    fun `sysfs cpu lists are parsed and formatted`() {
        assertEquals((0..7).toList(), CpuCores.parseList("0-7\n"))
        assertEquals(listOf(0, 1, 2, 3, 6), CpuCores.parseList("0-3,6"))
        assertNull(CpuCores.parseList("3-1"))
        assertNull(CpuCores.parseList("0-99"))
        assertNull(CpuCores.parseList("x"))
        assertEquals("0-2,5,7", CpuCores.format(listOf(7, 0, 1, 2, 5)))
        assertEquals("3-7", CpuCores.format(listOf(3, 4, 5, 6, 7)))
        assertEquals("-", CpuCores.format(emptyList()))
    }
}
