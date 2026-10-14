package gothic2.mobile.core.gamedata

import gothic2.mobile.core.runtime.RuntimeComponents
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals("ru", gog.language)
        assertEquals(3, gog.requiredSha256.size)

        // Классика повторяет Gothic2c.bat: свой EXE без аргументов, аддон спрятан, Paths.d подменён.
        val classic = gog.variant("classic")!!
        assertEquals("System/Gothic2c.exe", gog.exeOf(classic))
        assertEquals(emptyList(), classic.args)
        val renames = classic.renames.associate { it.from to it.to }
        val addon = gog.requiredFiles.filter { it.contains("_Addon", ignoreCase = true) }
        assertTrue(addon.isNotEmpty() && addon.all { renames[it] == it.removeSuffix(".vdf") + ".disabled" })
        assertEquals("System/Paths.d", renames["System/Paths_GII.d"])
        assertTrue("System/Paths_GII.d" in gog.requiredFiles)
        // Сохранения классики не смешиваются с «Ночью Ворона» (DIR_SAVEGAMES в Paths_GII.d).
        assertEquals("Saves_GII", gog.savesDirOf(classic))
        assertEquals("Saves", gog.savesDirOf(gog.variant("notr")!!))
        assertEquals(listOf("Saves", "Saves_GII"), gog.allSavesDirs())
    }

    @Test
    fun `runtime components manifest is valid`() {
        val c = RuntimeComponents.parse(asset("runtime_components.ini"))
        assertTrue(RuntimeComponents.REQUIRED.all { c[it] != null })
    }
}
