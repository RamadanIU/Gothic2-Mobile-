package gothic2.mobile.core.runtime

import gothic2.mobile.core.config.RendererChain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RendererSetupTest {
    private val root = Files.createTempDirectory("g2render").resolve("game")
    private val wrapper = Files.createTempDirectory("g2wrap").resolve("ddraw.dll").also { it.writeText("wrapper") }

    private fun gd3d11() = Files.createDirectories(root.resolve("System")).resolve("ddraw.dll").also { it.writeText("gd3d11") }

    @Test
    fun `wined3d chain leaves third-party ddraw untouched`() {
        gd3d11()
        assertEquals(emptyList(), RendererSetup.prepare(root, "System", RendererChain.WINED3D, null))
        assertEquals("gd3d11", root.resolve("System/ddraw.dll").readText())
    }

    @Test
    fun `dxvk chain disables gd3d11 reversibly and installs wrapper once`() {
        gd3d11()
        val changes = RendererSetup.prepare(root, "System", RendererChain.D3D7_TO_D3D9_DXVK, wrapper)
        assertEquals(2, changes.size)
        assertEquals("wrapper", root.resolve("System/ddraw.dll").readText())
        assertEquals("gd3d11", root.resolve("System/ddraw.dll.g2m-disabled").readText())
        // Повторный запуск ничего не меняет и не теряет оригинал.
        assertTrue(RendererSetup.prepare(root, "System", RendererChain.D3D7_TO_D3D9_DXVK, wrapper).isEmpty())
        assertEquals("gd3d11", root.resolve("System/ddraw.dll.g2m-disabled").readText())
    }

    @Test
    fun `dxvk chain without wrapper fails clearly`() {
        gd3d11()
        assertFailsWith<RendererSetup.MissingWrapperException> {
            RendererSetup.prepare(root, "System", RendererChain.D3D7_TO_D3D9_DXVK, null)
        }
        assertTrue(!root.resolve("System/ddraw.dll.g2m-disabled").exists())
    }
}
