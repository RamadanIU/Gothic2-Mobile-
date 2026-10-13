package gothic2.mobile.core.runtime

import gothic2.mobile.core.config.DeviceProfiles
import gothic2.mobile.core.config.GothicIniWriter
import gothic2.mobile.core.config.RendererChain
import gothic2.mobile.core.gamedata.SupportedBuild
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeTest {
    private val build = SupportedBuild.parseAll(
        """
        [build:t]
        title=T
        exe=System/Gothic2.exe
        exeSha256=PENDING
        language=ru
        """.trimIndent(),
    ).single()
    private val paths = RuntimePaths(wineprefix = "/data/prefix", gameRoot = "/data/game", cacheDir = "/cache")

    @Test
    fun `dxvk chain overrides wrapper and d3d9 and writes conf`() {
        val profile = DeviceProfiles.KNOWN.first { it.renderer == RendererChain.D3D7_TO_D3D9_DXVK }
        val spec = LaunchSpecBuilder.build(build, build.variants.single(), profile, paths, GothicIniWriter.dxvkConf(profile.graphics))
        assertEquals("C:\\Gothic2\\System\\Gothic2.exe", spec.windowsExePath)
        assertEquals("C:\\Gothic2\\System", spec.workingDir)
        assertEquals("ddraw=n,b;d3d9=n,b", spec.env["WINEDLLOVERRIDES"])
        assertEquals("/data/game/System/dxvk.conf", spec.env["DXVK_CONFIG_FILE"])
        assertTrue(spec.generatedFiles.containsKey("System/dxvk.conf"))
        assertTrue(spec.prefixSetup.single().contains("win7"))
    }

    @Test
    fun `bridge endpoint is passed to adapter via environment`() {
        val spec = LaunchSpecBuilder.build(build, build.variants.single(), DeviceProfiles.FALLBACK, paths, null, BridgeEndpoint("/data/bridge.sock", "ab".repeat(16)))
        assertEquals("/data/bridge.sock", spec.env["G2M_BRIDGE_SOCKET"])
        assertEquals("ab".repeat(16), spec.env["G2M_BRIDGE_TOKEN"])
    }

    @Test
    fun `wined3d chain uses builtin libraries`() {
        val spec = LaunchSpecBuilder.build(build, build.variants.single(), DeviceProfiles.FALLBACK, paths, null)
        assertEquals("ddraw=b;d3d9=b", spec.env["WINEDLLOVERRIDES"])
        assertTrue(spec.generatedFiles.isEmpty())
    }

    @Test
    fun `component manifest marks pending entries as unpinned`() {
        val c = RuntimeComponents.parse(
            """
            [component:wine]
            version=9.x-wow64
            source=https://example.invalid/wine
            license=LGPL-2.1-or-later
            archive=wine.tzst
            sha256=PENDING
            [component:box64]
            version=0.3.x
            source=https://github.com/ptitSeb/box64
            license=MIT
            archive=box64.tzst
            sha256=${"b".repeat(64)}
            """.trimIndent(),
        )
        assertEquals(listOf("wine"), c.unpinned().map { it.id })
        assertTrue(c["box64"]!!.isPinned)
    }
}
