package gothic2.mobile.core.gamedata

import gothic2.mobile.core.runtime.RuntimeComponents
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Манифесты из assets приложения разбираются без ошибок и содержат закреплённую GOG-сборку. */
class ShippedManifestTest {
    private fun asset(name: String) = File("../app/src/main/assets/$name").readText()

    @Test
    fun `supported builds manifest is valid and gog build is pinned`() {
        val builds = SupportedBuild.parseAll(asset("supported_builds.ini"))
        val gog = builds.first { it.id == "g2-gold-gog" }
        assertTrue(gog.isPinned)
        assertEquals("0655b4811e008a353ce372fde463ef00b6101f3e89be850e2f14980140447c7f", gog.exeSha256)
        assertEquals(listOf("-game:GothicGame.ini"), gog.variant("notr")!!.args)
        assertNull(gog.variant("classic"), "классика скрыта, пока не подтверждены аргументы")
        assertEquals("System/Gothic2c.exe", gog.exeOf(gog.variants.first { it.id == "classic" }))
        assertEquals(3, gog.requiredSha256.size)
    }

    @Test
    fun `runtime components manifest is valid`() {
        val c = RuntimeComponents.parse(asset("runtime_components.ini"))
        assertTrue(RuntimeComponents.REQUIRED.all { c[it] != null })
    }
}
