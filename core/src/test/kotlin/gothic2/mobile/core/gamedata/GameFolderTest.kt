package gothic2.mobile.core.gamedata

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Игра прямо из папки игрока: подготовка папки и перенос сохранений из прежней копии. */
class GameFolderTest {
    private val build = SupportedBuild.parseAll(
        """
        [build:gold]
        title=Gold
        exe=System/Gothic2.exe
        exeSha256=PENDING
        language=ru
        variants=notr,classic

        [variant:gold/notr]
        title=Ночь Ворона
        args=-game:GothicGame.ini

        [variant:gold/classic]
        title=Классика
        args=
        saves=Saves_GII
        """.trimIndent(),
    ).single()

    private val folder: Path = Files.createTempDirectory("g2folder")
    private val copy: Path = Files.createTempDirectory("g2copy")

    private fun slot(root: Path, rel: String, text: String) {
        Files.createDirectories(root.resolve(rel)).resolve("SAVEDAT.SAV").writeText(text)
    }

    @Test
    fun `prepare creates missing saves dirs of all variants and leaves no probe file`() {
        Files.createDirectories(folder.resolve("saves/savegame1"))
        assertEquals(listOf("Saves_GII"), GameFolder.prepare(folder, build))
        assertTrue(Files.isDirectory(folder.resolve("Saves_GII")))
        // Каталог в другом регистре — тот же, второй не создаётся.
        assertFalse(folder.listDirectoryEntries().any { it.fileName.toString() == "Saves" })
        assertFalse(folder.listDirectoryEntries().any { it.fileName.toString().startsWith(".gothic2mobile") })
        assertEquals(emptyList(), GameFolder.prepare(folder, build))
    }

    @Test
    fun `saves from the old copy are copied without overwriting slots in the folder`() {
        slot(copy, "Saves/savegame1", "old 1")
        slot(copy, "Saves/quicksave", "old quick")
        slot(copy, "Saves/current", "work")
        slot(copy, "Saves_GII/savegame3", "classic 3")
        slot(folder, "SAVES/savegame1", "folder 1")

        val result = GameFolder.copyMissingSaves(copy, folder, build)

        assertEquals(GameFolder.SavesMigration(copied = 2, kept = 1), result)
        assertEquals("folder 1", folder.resolve("SAVES/savegame1/SAVEDAT.SAV").readText())
        assertEquals("old quick", folder.resolve("SAVES/quicksave/SAVEDAT.SAV").readText())
        assertEquals("classic 3", folder.resolve("Saves_GII/savegame3/SAVEDAT.SAV").readText())
        assertFalse(folder.resolve("SAVES/current").exists())
        // Прежняя копия не тронута.
        assertEquals("old 1", copy.resolve("Saves/savegame1/SAVEDAT.SAV").readText())
    }

    @Test
    fun `nothing to migrate from a copy without saves`() {
        assertEquals(GameFolder.SavesMigration(0, 0), GameFolder.copyMissingSaves(copy, folder, build))
    }
}
