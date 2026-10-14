package gothic2.mobile.core.gamedata

import gothic2.mobile.core.util.FileOps
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Классика из GOG-издания: то же, что делает Gothic2c.bat, но с откатом после сбоя. */
class VariantLayoutTest {
    private val manifest = """
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
        exe=System/Gothic2c.exe
        args=
        saves=Saves_GII
        rename=Data/Worlds_Addon.vdf>Data/Worlds_Addon.disabled,System/Paths.d>System/Paths.disabled,System/Paths_GII.d>System/Paths.d,System/SPLASH.BMP>System/SPLASH.disabled,System/SPLASH_GII.BMP>System/SPLASH.BMP
    """.trimIndent()

    private val build = SupportedBuild.parseAll(manifest).single()
    private val notr = build.variant("notr")!!
    private val classic = build.variant("classic")!!
    private val root: Path = Files.createTempDirectory("g2layout")
    private val layout = VariantLayout(root)

    init {
        Files.createDirectories(root.resolve("Data"))
        Files.createDirectories(root.resolve("System"))
        // Регистр как у реальной поставки может отличаться от манифеста.
        root.resolve("Data/worlds_addon.vdf").writeText("addon")
        root.resolve("System/Paths.d").writeText("notr")
        root.resolve("System/Paths_GII.d").writeText("classic")
        root.resolve("System/SPLASH_GII.BMP").writeText("splash")
    }

    private fun text(rel: String) = root.resolve(rel).readText()

    private fun assertOriginal() {
        assertEquals("addon", text("Data/worlds_addon.vdf"))
        assertEquals("notr", text("System/Paths.d"))
        assertEquals("classic", text("System/Paths_GII.d"))
        assertEquals("splash", text("System/SPLASH_GII.BMP"))
        assertFalse(root.resolve("System/SPLASH.BMP").exists())
        assertFalse(root.resolve("System/Paths.disabled").exists())
        assertNull(layout.appliedVariantId())
    }

    @Test
    fun `classic hides addon and swaps paths, restore brings everything back`() {
        layout.apply(build, classic)
        assertEquals("classic", layout.appliedVariantId())
        assertFalse(root.resolve("Data/worlds_addon.vdf").exists())
        assertEquals("addon", text("Data/Worlds_Addon.disabled"))
        assertEquals("classic", text("System/Paths.d"))
        assertEquals("notr", text("System/Paths.disabled"))
        assertEquals("splash", text("System/SPLASH.BMP"))

        layout.restore(build)
        assertEquals("addon", text("Data/Worlds_Addon.vdf"))
        assertEquals("notr", text("System/Paths.d"))
        assertEquals("classic", text("System/Paths_GII.d"))
        assertEquals("splash", text("System/SPLASH_GII.BMP"))
        assertNull(layout.appliedVariantId())
    }

    @Test
    fun `notr launch after crashed classic session restores files first`() {
        layout.apply(build, classic)
        // Процесс погиб, restore не вызывался; следующий запуск — «Ночь Ворона».
        VariantLayout(root).apply(build, notr)
        assertTrue(root.resolve("Data/Worlds_Addon.vdf").exists())
        assertEquals("notr", text("System/Paths.d"))
        assertNull(layout.appliedVariantId())
    }

    @Test
    fun `restore after interruption in the middle of apply`() {
        // Отметка уже записана, успели только первые два переименования.
        root.resolve(VariantLayout.MARKER).writeText("classic")
        FileOps.moveAtomic(root.resolve("Data/worlds_addon.vdf"), root.resolve("Data/Worlds_Addon.disabled"))
        FileOps.moveAtomic(root.resolve("System/Paths.d"), root.resolve("System/Paths.disabled"))

        layout.restore(build)
        assertEquals("addon", text("Data/Worlds_Addon.vdf"))
        assertEquals("notr", text("System/Paths.d"))
        assertEquals("classic", text("System/Paths_GII.d"))
        assertNull(layout.appliedVariantId())
        // Повторный откат ничего не ломает.
        layout.restore(build)
        assertEquals("notr", text("System/Paths.d"))
    }

    @Test
    fun `without marker restore touches nothing`() {
        layout.restore(build)
        assertOriginal()
        layout.apply(build, notr)
        assertOriginal()
    }

    @Test
    fun `conflicting leftover file stops the launch`() {
        root.resolve("System/Paths.disabled").writeText("stale")
        assertFailsWith<VariantLayout.LayoutException> { layout.apply(build, classic) }
        // То, что успели переименовать, возвращается при следующем запуске.
        root.resolve("System/Paths.disabled").toFile().delete()
        layout.restore(build)
        assertEquals("addon", text("Data/Worlds_Addon.vdf"))
        assertEquals("notr", text("System/Paths.d"))
    }

    @Test
    fun `unsafe rename paths are rejected`() {
        val bad = manifest.replace("Data/Worlds_Addon.vdf>Data/Worlds_Addon.disabled", "Data/Worlds_Addon.vdf>../x")
        assertFailsWith<IllegalArgumentException> { SupportedBuild.parseAll(bad) }
    }

    @Test
    fun `missing saves dir of the variant is created, existing one is kept`() {
        assertEquals("Saves_GII", layout.ensureSavesDir(build, classic))
        assertTrue(Files.isDirectory(root.resolve("Saves_GII")))
        assertNull(layout.ensureSavesDir(build, classic))

        // Каталог в другом регистре — тот же каталог, второй не создаётся.
        Files.createDirectories(root.resolve("saves/savegame1")).resolve("SAVEDAT.SAV").writeText("s")
        assertNull(layout.ensureSavesDir(build, notr))
        assertFalse(root.listDirectoryEntries().any { it.fileName.toString() == "Saves" })
        assertEquals("s", text("saves/savegame1/SAVEDAT.SAV"))
    }
}
