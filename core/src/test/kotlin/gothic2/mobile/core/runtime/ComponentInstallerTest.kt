package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.Hashing
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ComponentInstallerTest {
    private val root: Path = Files.createTempDirectory("g2rt")
    private val extractor = TarExtractor()

    private fun extract(bytes: ByteArray, to: Path = root.resolve("out")) = extractor.extract(ByteArrayInputStream(bytes), to)

    @Test
    fun `extracts files, dirs, links, long names and pax paths with exec bits`() {
        val long = "usr/lib/" + "x".repeat(120) + ".so"
        val tar = TarBuilder()
            .dir("bin/")
            .file("bin/box64", "elf", mode = 0b111_101_101)
            .symlink("bin/box", "box64")
            .hardlink("bin/box64-copy", "bin/box64")
            .longName(long, "long")
            .pax(mapOf("path" to "share/юникод.txt"), "share/ignored.txt", "pax")
            .build()
        val stats = extract(tar)
        val out = root.resolve("out")
        assertEquals("elf", out.resolve("bin/box").readText())
        assertEquals("elf", out.resolve("bin/box64-copy").readText())
        assertEquals("long", out.resolve(long).readText())
        assertEquals("pax", out.resolve("share/юникод.txt").readText())
        assertTrue(Files.isExecutable(out.resolve("bin/box64")))
        assertEquals(1, stats.symlinks)
    }

    @Test
    fun `rejects traversal, absolute paths and escaping links`() {
        val bad = listOf(
            TarBuilder().file("../evil", "x"),
            TarBuilder().file("/etc/evil", "x"),
            TarBuilder().symlink("lib/link", "/etc/passwd"),
            TarBuilder().symlink("lib/link", "../../outside"),
            // Запись через ссылку, указывающую наружу, даже если сама ссылка «безобидна» по пути.
            TarBuilder().symlink("up", ".").symlink("up/a/../../../x", "y"),
        )
        for ((i, b) in bad.withIndex()) {
            assertFailsWith<java.io.IOException>("case $i") { extract(b.build(), root.resolve("case$i")) }
        }
        assertFalse(root.parent.resolve("evil").exists())
    }

    @Test
    fun `write through previously created link is refused`() {
        val outside = Files.createTempDirectory("g2outside")
        val tar = TarBuilder().symlink("dir", outside.toString()).file("dir/pwned", "x")
        assertFailsWith<java.io.IOException> { extract(tar.build()) }
        assertFalse(outside.resolve("pwned").exists())
    }

    @Test
    fun `corrupted header is detected`() {
        val bytes = TarBuilder().file("a", "b").build()
        bytes[10] = 'Z'.code.toByte()
        assertFailsWith<java.io.IOException> { extract(bytes) }
    }

    private fun gz(b: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

    private fun component(archive: ByteArray, name: String = "box64.tar.gz", sha: String? = Hashing.sha256(archive)) =
        RuntimeComponent("box64", "0.3", "src", "MIT", name, sha) to archive

    @Test
    fun `installs once, verifies hash and is idempotent`() {
        val (c, bytes) = component(gz(TarBuilder().file("bin/box64", "v1", 0b111_101_101).build()))
        var opens = 0
        val installer = ComponentInstaller(root.resolve("rt"), { opens++; ByteArrayInputStream(bytes) })
        assertIs<ComponentInstaller.Outcome.Installed>(installer.install(c))
        assertIs<ComponentInstaller.Outcome.AlreadyInstalled>(installer.install(c))
        assertEquals(1, opens)
        assertEquals("v1", installer.dirOf("box64").resolve("bin/box64").readText())
    }

    @Test
    fun `hash mismatch leaves previous installation intact`() {
        val (good, goodBytes) = component(TarBuilder().file("f", "good").build(), "box64.tar")
        val installer = ComponentInstaller(root.resolve("rt"), { ByteArrayInputStream(if (it.version == "0.3") goodBytes else TarBuilder().file("f", "evil").build()) })
        installer.install(good)
        val tampered = good.copy(version = "0.4") // хэш от старого архива — не совпадёт
        assertFailsWith<ComponentInstaller.ComponentException> { installer.install(tampered) }
        assertEquals("good", installer.dirOf("box64").resolve("f").readText())
        assertFalse(root.resolve("rt/.box64.installing").exists())
    }

    @Test
    fun `unpinned component requires dev mode and unknown format is reported`() {
        val (c, bytes) = component(TarBuilder().file("f", "x").build(), "box64.tar", sha = null)
        val installer = ComponentInstaller(root.resolve("rt"), { ByteArrayInputStream(bytes) })
        assertFailsWith<ComponentInstaller.ComponentException> { installer.install(c) }
        installer.install(c, allowUnpinned = true)

        val (z, zb) = component(bytes, "box64.tzst")
        val ex = assertFailsWith<ComponentInstaller.ComponentException> {
            ComponentInstaller(root.resolve("rt2"), { ByteArrayInputStream(zb) }).install(z)
        }
        assertTrue("tzst" in ex.message!!)
        // С подключённым распаковщиком формат принимается.
        ComponentInstaller(root.resolve("rt3"), { ByteArrayInputStream(zb) }, mapOf(".tzst" to { it })).install(z)
    }
}
