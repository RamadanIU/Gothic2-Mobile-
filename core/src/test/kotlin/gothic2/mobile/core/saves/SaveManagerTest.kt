package gothic2.mobile.core.saves

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SaveManagerTest {
    private val root: Path = Files.createTempDirectory("g2saves")
    private val saves = root.resolve("game/Saves")
    private val backups = root.resolve("backups")
    private var now = 1_700_000_000_000L
    private val manager = SaveManager(saves, backups, generations = 3, clock = { now++ })

    private fun writeSlot(slot: String, content: String) {
        val d = Files.createDirectories(saves.resolve(slot))
        d.resolve("SAVEDAT.SAV").writeText(content)
        d.resolve("SAVEINFO.SAV").writeText("info-$content")
    }

    @Test
    fun `lists only gothic slot folders`() {
        writeSlot("savegame2", "a")
        writeSlot("savegame10", "b")
        Files.createDirectories(saves.resolve("random"))
        assertEquals(listOf("savegame2", "savegame10"), manager.slots())
    }

    @Test
    fun `unchanged slot is not backed up twice`() {
        writeSlot("savegame1", "a")
        assertNotNull(manager.backupSlot("savegame1", "test"))
        assertNull(manager.backupSlot("savegame1", "test"))
        writeSlot("savegame1", "b")
        assertNotNull(manager.backupSlot("savegame1", "test"))
        assertEquals(2, manager.listBackups("savegame1").size)
    }

    @Test
    fun `keeps limited generations, newest first`() {
        for (i in 1..5) {
            writeSlot("savegame1", "v$i")
            manager.backupSlot("savegame1", "gen$i")
        }
        val list = manager.listBackups("savegame1")
        assertEquals(listOf("gen5", "gen4", "gen3"), list.map { it.reason })
    }

    @Test
    fun `restore backs up current state first`() {
        writeSlot("savegame1", "old")
        val first = manager.backupSlot("savegame1", "first")!!
        writeSlot("savegame1", "new")
        manager.restore(first)
        assertEquals("old", saves.resolve("savegame1/SAVEDAT.SAV").readText())
        val reasons = manager.listBackups("savegame1").map { it.reason }
        assertTrue("before-restore" in reasons, "текущее состояние сохранено перед восстановлением")
    }

    @Test
    fun `corrupted backup is refused`() {
        writeSlot("savegame1", "x")
        val b = manager.backupSlot("savegame1", "t")!!
        b.dir.resolve("SAVEDAT.SAV").writeText("tampered")
        assertFailsWith<IllegalStateException> { manager.restore(b) }
        assertEquals("x", saves.resolve("savegame1/SAVEDAT.SAV").readText())
    }

    @Test
    fun `export then import round trip`() {
        writeSlot("savegame3", "progress")
        val out = ByteArrayOutputStream()
        manager.exportZip(out)
        writeSlot("savegame3", "overwritten")
        val imported = manager.importZip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(listOf("savegame3"), imported)
        assertEquals("progress", saves.resolve("savegame3/SAVEDAT.SAV").readText())
        assertTrue(manager.listBackups("savegame3").any { it.reason == "before-import" })
    }

    @Test
    fun `zip slip and foreign paths are rejected`() {
        for (name in listOf("../evil.txt", "savegame1/../../evil", "/abs/file", "notaslot/file", "savegame1/sub/file")) {
            val bytes = ByteArrayOutputStream().also { bo ->
                ZipOutputStream(bo).use { z ->
                    z.putNextEntry(ZipEntry(name))
                    z.write("x".toByteArray())
                    z.closeEntry()
                }
            }.toByteArray()
            assertFailsWith<SaveImportException>(name) { manager.importZip(ByteArrayInputStream(bytes)) }
        }
        assertTrue(!root.resolve("evil.txt").exists())
    }

    @Test
    fun `oversized archive is rejected`() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                z.putNextEntry(ZipEntry("savegame1/SAVEDAT.SAV"))
                z.write(ByteArray(10_000))
                z.closeEntry()
            }
        }.toByteArray()
        assertFailsWith<SaveImportException> {
            manager.importZip(ByteArrayInputStream(bytes), SaveManager.ImportLimits(maxFileBytes = 1_000))
        }
        assertTrue(!saves.resolve("savegame1").exists())
    }

    @Test
    fun `policy waits for stable files after save event`() {
        writeSlot("savegame1", "a")
        var t = 0L
        var step = 0
        val policy = SaveBackupPolicy(manager, quietPeriodMs = 100, maxWaitMs = 1_000, sleep = {
            t += it
            // Игра ещё дописывает файл на первом шаге ожидания.
            if (step++ == 0) writeSlot("savegame1", "ab")
        }, clock = { t })
        val backup = policy.onSaveCompleted("savegame1")
        assertNotNull(backup)
        assertTrue(step >= 2, "копия сделана только после стабилизации")
    }
}
