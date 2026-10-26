package gothic2.mobile.core.runtime

import gothic2.mobile.core.util.Hashing
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class FileReplacementTest {
    private val dir: Path = Files.createTempDirectory("g2repl")
    private val known = ByteArray(32) { it.toByte() }
    private val ours = ByteArray(48) { (it * 3).toByte() }
    private val replacement = FileReplacement("test", Hashing.sha256(ours), setOf(Hashing.sha256(known)))

    @Test
    fun `replaces the known file once and leaves no temporary files`() {
        val file = dir.resolve("ddraw.dll").also { it.writeBytes(known) }
        assertEquals(FileReplacement.Result.INSTALLED, replacement.apply(file) { ours.inputStream() })
        assertContentEquals(ours, file.readBytes())
        assertEquals(FileReplacement.Result.ALREADY_INSTALLED, replacement.apply(file) { error("не читается") })
        assertFalse(dir.resolve("ddraw.dll.tmp").exists())
    }

    @Test
    fun `other files are not touched`() {
        val other = known.copyOf().also { it[0] = 9 }
        val file = dir.resolve("ddraw.dll").also { it.writeBytes(other) }
        assertEquals(FileReplacement.Result.NOT_APPLICABLE, replacement.apply(file) { ours.inputStream() })
        assertContentEquals(other, file.readBytes())
        assertEquals(FileReplacement.Result.NOT_APPLICABLE, replacement.apply(dir.resolve("missing.dll")) { ours.inputStream() })
    }

    @Test
    fun `wrong source keeps the original file`() {
        val file = dir.resolve("ddraw.dll").also { it.writeBytes(known) }
        assertFailsWith<IllegalStateException> { replacement.apply(file) { ByteArray(48).inputStream() } }
        assertContentEquals(known, file.readBytes())
        assertFalse(dir.resolve("ddraw.dll.tmp").exists())
    }

    @Test
    fun `d7vk build replaces both the original and the byte-fixed file`() {
        assertFailsWith<IllegalArgumentException> { FileReplacement("bad", Hashing.sha256(ours), setOf(Hashing.sha256(ours))) }
        assertEquals("d7vk-1.11-gothic", FileReplacement.D7VK_1_11_GOTHIC.id)
    }
}
