package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BuildVerifierTest {
    private val root: Path = Files.createTempDirectory("g2game")
    private val exeBytes = "fake gothic exe".toByteArray()
    private val exeSha = Hashing.sha256(exeBytes)

    private fun manifest(sha: String) = """
        ; тестовый манифест
        [build:test]
        title=Gothic II Classic (тест)
        exe=System/Gothic2.exe
        exeSha256=$sha
        language=ru
        requires=Data/Worlds.vdf
    """.trimIndent()

    private fun install(addon: Boolean = false) {
        Files.createDirectories(root.resolve("SYSTEM")).resolve("GOTHIC2.EXE").writeBytes(exeBytes)
        Files.createDirectories(root.resolve("Data")).resolve("worlds.VDF").writeText("w")
        if (addon) root.resolve("Data/Worlds_Addon.vdf").writeText("a")
        Files.createDirectories(root.resolve("Saves/savegame1")).resolve("SAVEDAT.SAV").writeText("s")
    }

    @Test
    fun `pinned build is recognised case-insensitively`() {
        install()
        val v = BuildVerifier(SupportedBuild.parseAll(manifest(exeSha)))
        val r = v.verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.Supported>(r)
    }

    @Test
    fun `unknown exe with addon files suggests sending build report`() {
        install(addon = true)
        val v = BuildVerifier(SupportedBuild.parseAll(manifest("a".repeat(64))))
        val r = v.verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.UnknownExe>(r)
        assertTrue(r.addonFilesPresent)
        val text = BuildVerifier.describe(r)
        assertTrue("Gold" in text)
        assertTrue("отчёт о сборке" in text)
    }

    @Test
    fun `pending manifest yields unpinned result`() {
        install()
        val r = BuildVerifier(SupportedBuild.parseAll(manifest("PENDING"))).verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.Unpinned>(r)
        assertEquals(exeSha, r.exeSha256)
    }

    @Test
    fun `missing files are reported`() {
        install()
        Files.delete(root.resolve("Data/worlds.VDF"))
        val r = BuildVerifier(SupportedBuild.parseAll(manifest(exeSha))).verify(PathGameTree(root))
        assertIs<BuildVerifier.Result.MissingFiles>(r)
    }

    @Test
    fun `empty folder is not gothic`() {
        val r = BuildVerifier(SupportedBuild.parseAll(manifest(exeSha))).verify(PathGameTree(root))
        assertEquals(BuildVerifier.Result.NotGothic2, r)
    }

    @Test
    fun `import copies game without source saves and keeps existing app saves`() {
        install()
        val build = SupportedBuild.parseAll(manifest(exeSha)).single()
        val target = Files.createTempDirectory("g2target").resolve("game")
        val importer = GameImporter(target)
        importer.import(PathGameTree(root), build, exeSha)
        assertTrue(target.resolve("SYSTEM/GOTHIC2.EXE").exists())
        assertTrue(!target.resolve("Saves/savegame1").exists(), "сохранения источника не копируются поверх")

        Files.createDirectories(target.resolve("Saves/savegame4")).resolve("SAVEDAT.SAV").writeText("mine")
        importer.import(PathGameTree(root), build, exeSha)
        assertEquals("mine", target.resolve("Saves/savegame4/SAVEDAT.SAV").readText())
    }

    @Test
    fun `cancelled import leaves no partial game`() {
        install()
        val build = SupportedBuild.parseAll(manifest(exeSha)).single()
        val target = Files.createTempDirectory("g2target").resolve("game")
        assertFailsWith<GameImporter.Cancelled> {
            GameImporter(target).import(PathGameTree(root), build, exeSha, isCancelled = { true })
        }
        assertTrue(!target.exists())
    }

    @Test
    fun `interrupted replacement is recovered with saves`() {
        install()
        val build = SupportedBuild.parseAll(manifest(exeSha)).single()
        val target = Files.createTempDirectory("g2target").resolve("game")
        GameImporter(target).import(PathGameTree(root), build, exeSha)
        Files.createDirectories(target.resolve("Saves/savegame4")).resolve("SAVEDAT.SAV").writeText("mine")
        // Имитация гибели процесса: установка уже перенесена в .old, сохранения — в staging.
        val old = target.resolveSibling(".game.old")
        val staging = target.resolveSibling(".game.importing")
        Files.move(target, old)
        Files.createDirectories(staging)
        Files.move(old.resolve("Saves"), staging.resolve("Saves"))

        GameImporter(target).recoverInterrupted(build)
        assertEquals("mine", target.resolve("Saves/savegame4/SAVEDAT.SAV").readText())
    }

    @Test
    fun `saves dirs of all variants are created and survive reimport`() {
        install()
        val gold = manifest(exeSha) + """

            variants=notr,classic

            [variant:test/notr]
            title=Ночь Ворона
            args=

            [variant:test/classic]
            title=Классика
            args=
            saves=Saves_GII
        """.trimIndent()
        val build = SupportedBuild.parseAll(gold).single()
        assertEquals(listOf("Saves", "Saves_GII"), build.allSavesDirs())
        Files.createDirectories(root.resolve("Saves_GII/savegame2")).resolve("SAVEDAT.SAV").writeText("src")
        val target = Files.createTempDirectory("g2target").resolve("game")
        val importer = GameImporter(target)
        importer.import(PathGameTree(root), build, exeSha)
        assertTrue(Files.isDirectory(target.resolve("Saves_GII")), "каталог сохранений классики создан")
        assertTrue(!target.resolve("Saves_GII/savegame2").exists(), "сохранения источника не копируются")

        Files.createDirectories(target.resolve("Saves_GII/savegame3")).resolve("SAVEDAT.SAV").writeText("classic")
        Files.createDirectories(target.resolve("Saves/savegame4")).resolve("SAVEDAT.SAV").writeText("notr")
        importer.import(PathGameTree(root), build, exeSha)
        assertEquals("classic", target.resolve("Saves_GII/savegame3/SAVEDAT.SAV").readText())
        assertEquals("notr", target.resolve("Saves/savegame4/SAVEDAT.SAV").readText())
    }
}
