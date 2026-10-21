package gothic2.mobile.app.storage

import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsTest {
    @Test
    fun `log and wine output reach the exported file`() {
        val dir = Files.createTempDirectory("diag")
        val diagnostics = Diagnostics(dir)
        diagnostics.log("test", "первая строка")
        diagnostics.beginWineLog()
        diagnostics.wine("err:module:import_dll Library d3d9.dll not found")
        val text = diagnostics.exportToFile(dir.resolve("share/out.txt")).toFile().readText()
        assertTrue("первая строка" in text, text)
        assertTrue("import_dll" in text, text)
    }

    @Test
    fun `lines are on disk immediately, without flush`() {
        val dir = Files.createTempDirectory("diag")
        val diagnostics = Diagnostics(dir)
        diagnostics.log("crash", "последняя строка перед вылетом")
        diagnostics.beginWineLog()
        diagnostics.wine("wine: Unhandled page fault")
        assertTrue("последняя строка перед вылетом" in dir.resolve("session.log").readText())
        assertTrue("Unhandled page fault" in dir.resolve("wine.log").readText())
    }

    @Test
    fun `long wine output keeps the start of the run and the last lines`() {
        val dir = Files.createTempDirectory("diag")
        val diagnostics = Diagnostics(dir, maxBytes = 500)
        diagnostics.beginWineLog()
        for (i in 0 until 1000) diagnostics.wine("line $i")
        val head = dir.resolve("wine.head.log").readText()
        val tail = dir.resolve("wine.log").readText()
        assertTrue(head.startsWith("line 0\n"), head)
        assertTrue(tail.endsWith("line 999\n"), tail)
        assertTrue(Files.size(dir.resolve("wine.1.log")) <= 1000)

        // Следующий запуск: прошлый журнал целиком уходит в wine-prev.
        diagnostics.beginWineLog()
        diagnostics.wine("second run")
        assertTrue(dir.resolve("wine-prev.head.log").readText().startsWith("line 0\n"))
        assertTrue(dir.resolve("wine-prev.log").readText().endsWith("line 999\n"))
        assertEquals("second run\n", dir.resolve("wine.log").readText())
        assertFalse(Files.exists(dir.resolve("wine.head.log")))
    }

    @Test
    fun `relocate moves files and keeps writing in the new folder`() {
        val old = Files.createTempDirectory("diag-old")
        val target = Files.createTempDirectory("diag-new").resolve("logs")
        val diagnostics = Diagnostics(old)
        diagnostics.log("app", "до переноса")
        diagnostics.relocate(target)
        diagnostics.log("app", "после переноса")
        val text = target.resolve("session.log").readText()
        assertTrue("до переноса" in text && "после переноса" in text, text)
        assertFalse(Files.exists(old.resolve("session.log")))
        assertEquals(target, diagnostics.dir)
    }

    @Test
    fun `falls back to the spare folder when the log folder is unusable`() {
        val base = Files.createTempDirectory("diag")
        val dir = base.resolve("public")
        Files.write(dir, byteArrayOf()) // на месте папки — файл: писать в неё нельзя
        val spare = base.resolve("internal")
        val diagnostics = Diagnostics(dir, fallback = spare)
        diagnostics.log("app", "строка")
        assertEquals(spare, diagnostics.dir)
        assertTrue("строка" in spare.resolve("session.log").readText())
    }
}
