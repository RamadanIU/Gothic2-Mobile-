package gothic2.mobile.app.runtime

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicsModeTest {
    @Test
    fun `adreno starts with D7VK and falls back to the working WineD3D mode`() {
        assertEquals(GraphicsMode.D7VK_TURNIP, GraphicsMode.defaultFor("Adreno (TM) 740"))
        assertEquals(GraphicsMode.WINED3D_ZINK, GraphicsMode.defaultFor("Mali-G715"))
        assertEquals(GraphicsMode.WINED3D_ZINK, GraphicsMode.defaultFor(null))
        // Кнопка «другой режим» после неудачи D7VK ведёт в режим, который уже работал на Adreno.
        assertEquals(GraphicsMode.WINED3D_ZINK_TURNIP, GraphicsMode.D7VK_TURNIP.next())
        // Выбор игрока важнее видеокарты; неизвестный id — режим по умолчанию.
        assertEquals(GraphicsMode.WINED3D_ZINK_TURNIP, GraphicsMode.of("wined3d_zink_turnip", "Adreno (TM) 740"))
        assertEquals(GraphicsMode.D7VK_TURNIP, GraphicsMode.of("removed", "Adreno (TM) 740"))
    }

    @Test
    fun `D7VK modes never use Gladio for the DirectDraw part`() {
        for (mode in GraphicsMode.entries.filter { it.isD7VK }) {
            assertTrue(mode.graphicsDriver.endsWith(",zink"), mode.id)
        }
    }

    @Test
    fun `turnip on adreno 7xx renders without GMEM`() {
        assertEquals("sysmem,deck_emu", GraphicsMode.turnipDebug(740))
        assertEquals("sysmem,deck_emu", GraphicsMode.turnipDebug(750))
        assertEquals("gmem,deck_emu", GraphicsMode.turnipDebug(732))
        assertEquals("deck_emu", GraphicsMode.turnipDebug(660))
        assertEquals("deck_emu", GraphicsMode.turnipDebug(0))
        assertEquals(listOf(GraphicsMode.D7VK_TURNIP, GraphicsMode.WINED3D_ZINK_TURNIP), GraphicsMode.entries.filter { it.usesTurnip })
    }

    @Test
    fun `flicker fix variants add to the standard launch`() {
        // «Обычный» — ровно то, что было до вариантов.
        assertEquals("sysmem,deck_emu", GraphicsMode.turnipDebug(740, FlickerFix.STANDARD))
        assertEquals(
            "ddraw.forceLegacyPresent = True; ddraw.legacyPresentGuard = Strict",
            GraphicsMode.d7vkConfig(FlickerFix.STANDARD),
        )
        assertEquals("sysmem,deck_emu,noubwc,nolrz", GraphicsMode.turnipDebug(740, FlickerFix.NO_COMPRESSION))
        assertEquals(
            "ddraw.forceLegacyPresent = True; ddraw.legacyPresentGuard = Strict; ddraw.supportD16 = False",
            GraphicsMode.d7vkConfig(FlickerFix.DEPTH24),
        )
        assertEquals("sysmem,deck_emu,noubwc,nolrz,flushall", GraphicsMode.turnipDebug(740, FlickerFix.STRICT))
        assertEquals(FlickerFix.STANDARD, FlickerFix.of(null))
        assertEquals(FlickerFix.STANDARD, FlickerFix.of("removed"))
        assertEquals(FlickerFix.DEPTH24, FlickerFix.of("depth24"))
    }

    @Test
    fun `settings list matches the modes`() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/values/arrays.xml"))
        val arrays = doc.getElementsByTagName("string-array")
        fun items(name: String): List<String> {
            for (i in 0 until arrays.length) {
                val node = arrays.item(i) as org.w3c.dom.Element
                if (node.getAttribute("name") == name) {
                    val list = node.getElementsByTagName("item")
                    return (0 until list.length).map { list.item(it).textContent }
                }
            }
            error("нет $name")
        }
        assertEquals(GraphicsMode.entries.map { it.id }, items("graphics_mode_values"))
        assertEquals(GraphicsMode.entries.map { it.title }, items("graphics_mode_entries"))
        assertEquals(FlickerFix.entries.map { it.id }, items("flicker_fix_values"))
        assertEquals(FlickerFix.entries.map { it.title }, items("flicker_fix_entries"))
    }

    @Test
    fun `titles name no runtime libraries`() {
        // Игрок видит «быстрый/совместимый», а не устройство среды исполнения.
        val technical = Regex("wine|d7vk|dxvk|turnip|zink|vortek|gladio|winlator|box64", RegexOption.IGNORE_CASE)
        val titles = GraphicsMode.entries.map { it.title } + FlickerFix.entries.map { it.title }
        for (title in titles) assertTrue(!technical.containsMatchIn(title), title)
    }
}
