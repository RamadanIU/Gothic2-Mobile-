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

class BinaryFixTest {
    private val dir: Path = Files.createTempDirectory("g2fix")
    private val original = ByteArray(64) { it.toByte() }.also {
        it[10] = 0x81.toByte()
        it[11] = 0x63
        it[12] = 0x08
    }
    private val fixedBytes = original.copyOf().also { it[11] = 0x66 }
    private val fix = BinaryFix(
        id = "test",
        size = original.size.toLong(),
        originalSha256 = Hashing.sha256(original),
        fixedSha256 = Hashing.sha256(fixedBytes),
        offset = 10,
        original = byteArrayOf(0x81.toByte(), 0x63, 0x08),
        fixed = byteArrayOf(0x81.toByte(), 0x66, 0x08),
    )

    @Test
    fun `fixes the known file once and leaves no temporary files`() {
        val file = dir.resolve("ddraw.dll").also { it.writeBytes(original) }
        assertEquals(BinaryFix.Result.FIXED, fix.apply(file))
        assertContentEquals(fixedBytes, file.readBytes())
        assertEquals(BinaryFix.Result.ALREADY_FIXED, fix.apply(file))
        assertFalse(dir.resolve("ddraw.dll.tmp").exists())
    }

    @Test
    fun `other files are not touched`() {
        val other = original.copyOf().also { it[0] = 7 }
        val file = dir.resolve("ddraw.dll").also { it.writeBytes(other) }
        assertEquals(BinaryFix.Result.NOT_APPLICABLE, fix.apply(file))
        assertContentEquals(other, file.readBytes())
        // Другой размер (встроенный ddraw Wine) и отсутствующий файл.
        dir.resolve("small.dll").writeBytes(ByteArray(3))
        assertEquals(BinaryFix.Result.NOT_APPLICABLE, fix.apply(dir.resolve("small.dll")))
        assertEquals(BinaryFix.Result.NOT_APPLICABLE, fix.apply(dir.resolve("missing.dll")))
    }

    @Test
    fun `fix description is checked`() {
        assertFailsWith<IllegalArgumentException> {
            BinaryFix("bad", 4, Hashing.sha256(original), Hashing.sha256(original), 2, byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3))
        }
        assertFailsWith<IllegalArgumentException> {
            BinaryFix("bad", 64, Hashing.sha256(original), Hashing.sha256(original), 0, byteArrayOf(1, 2), byteArrayOf(1))
        }
        // Исправление D7VK описано корректно (проверки в init) и меняет только байт ModRM.
        assertEquals("d7vk-1.11-hel-caps", BinaryFix.D7VK_1_11_HEL_CAPS.id)
    }
}
