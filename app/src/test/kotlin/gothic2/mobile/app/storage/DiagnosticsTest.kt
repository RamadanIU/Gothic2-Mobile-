package gothic2.mobile.app.storage

import java.nio.file.Files
import kotlin.test.Test
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
}
