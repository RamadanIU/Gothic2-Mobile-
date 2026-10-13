package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoldEditionTest {
    private val manifest = """
        [build:g2-gold]
        title=Gothic II Gold
        exe=System/Gothic2.exe
        exeSha256=PENDING
        language=ru
        requires=Data/Worlds.vdf,Data/Worlds_Addon.vdf
        variants=notr,classic

        [variant:g2-gold/notr]
        title=Ночь Ворона
        args=

        [variant:g2-gold/classic]
        title=Классическая Gothic II
        args=PENDING
        saves=Saves_Classic

        [build:g2-classic]
        title=Gothic II Classic
        exe=System/Gothic2.exe
        exeSha256=PENDING
        language=ru
        requires=Data/Worlds.vdf
        forbids=Data/Worlds_Addon.vdf
    """.trimIndent()

    private val builds = SupportedBuild.parseAll(manifest)
    private val root: Path = Files.createTempDirectory("g2gold")

    private fun install(addon: Boolean) {
        Files.createDirectories(root.resolve("System")).resolve("Gothic2.exe").writeBytes("exe".toByteArray())
        root.resolve("System/Classic.ini").writeText("[INFO]")
        Files.createDirectories(root.resolve("Data")).resolve("Worlds.vdf").writeText("w")
        if (addon) root.resolve("Data/Worlds_Addon.vdf").writeText("a")
        Files.createDirectories(root.resolve("Saves/savegame1")).resolve("SAVEDAT.SAV").writeText("private")
    }

    @Test
    fun `variants parse with pending classic and default for single-variant build`() {
        val gold = builds.first { it.id == "g2-gold" }
        assertEquals(listOf("notr", "classic"), gold.variants.map { it.id })
        assertEquals(listOf("notr"), gold.definedVariants().map { it.id })
        assertNull(gold.variant("classic"), "неподтверждённый вариант не предлагается")
        assertEquals(emptyList(), gold.variant("notr")!!.args)
        assertEquals(listOf("Saves", "Saves_Classic"), gold.allSavesDirs())
        val classic = builds.first { it.id == "g2-classic" }
        assertEquals("default", classic.defaultVariant()!!.id)
    }

    @Test
    fun `gold copy is recognised as gold, plain copy as classic`() {
        install(addon = true)
        val gold = BuildVerifier(builds).verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.Unpinned>(gold)
        assertEquals("g2-gold", gold.build.id)

        Files.delete(root.resolve("Data/Worlds_Addon.vdf"))
        val classic = BuildVerifier(builds).verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.Unpinned>(classic)
        assertEquals("g2-classic", classic.build.id)
    }

    @Test
    fun `fingerprint lists hashes without saves and drafts manifest`() {
        install(addon = true)
        val verifier = BuildVerifier(builds)
        val report = BuildFingerprint.collect(PathGameTree(root), verifier)
        assertEquals(Hashing.sha256("exe".toByteArray()), report.exeSha256)
        assertTrue(report.addonPresent)
        assertTrue(report.files.none { it.path.startsWith("Saves") }, "сохранения не попадают в отчёт")
        val text = BuildFingerprint.format(report, "test")
        assertTrue("exeSha256=${report.exeSha256}" in text)
        assertTrue("Data/Worlds_Addon.vdf:" in text)
        assertTrue(";   System/Classic.ini" in text)
        assertFalse("private" in text)
        assertFalse(root.toString() in text, "в отчёте нет путей устройства")
    }
}
