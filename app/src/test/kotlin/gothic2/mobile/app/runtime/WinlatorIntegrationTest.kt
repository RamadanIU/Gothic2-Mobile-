package gothic2.mobile.app.runtime

import com.winlator.core.PathRelocator
import com.winlator.xenvironment.RootFS
import com.winlator.xserver.XKeycode
import gothic2.mobile.core.input.GameKey
import gothic2.mobile.core.input.KeyBindings
import gothic2.mobile.core.input.ScanCode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WinlatorIntegrationTest {
    @Test
    fun `every game key reaches the X server as the right key`() {
        for (key in GameKey.entries) {
            val scan = KeyBindings.GOTHIC2_DEFAULT.scan(key)
            assertNotNull(XServerInputSink.keycode(scan), "нет кода X для $key (0x${scan.toString(16)})")
        }
        assertEquals(XKeycode.KEY_UP, XServerInputSink.keycode(ScanCode.UP))
        assertEquals(XKeycode.KEY_DEL, XServerInputSink.keycode(ScanCode.DELETE))
        assertEquals(XKeycode.KEY_NEXT, XServerInputSink.keycode(ScanCode.NEXT))
        assertEquals(XKeycode.KEY_CTRL_L, XServerInputSink.keycode(ScanCode.LCONTROL))
        assertEquals(XKeycode.KEY_ALT_L, XServerInputSink.keycode(ScanCode.LMENU))
        assertEquals(XKeycode.KEY_X, XServerInputSink.keycode(ScanCode.X))
        assertEquals(XKeycode.KEY_F5, XServerInputSink.keycode(ScanCode.F5))
        assertEquals(XKeycode.KEY_ESC, XServerInputSink.keycode(ScanCode.ESCAPE))
    }

    @Test
    fun `relocated rootfs path keeps the length and points into our data dir`() {
        for (pkg in listOf("gothic2.mobile", "gothic2.mobile.dev")) {
            val path = RootFS.relocatedPath(pkg)
            assertEquals(RootFS.ORIGINAL_PATH.length, path.length)
            assertTrue(path.startsWith("/data/data/$pkg/"))
            assertEquals("/data/data/$pkg/r", path.replace(Regex("/+"), "/"))
        }
        assertFailsWith<IllegalStateException> { RootFS.relocatedPath("a.very.long.package.name.x") }
    }

    @Test
    fun `relocator replaces the path across read boundaries`() {
        val target = RootFS.relocatedPath("gothic2.mobile.dev")
        val relocator = PathRelocator(RootFS.ORIGINAL_PATH, target)
        val prefix = ByteArray(70_000) { (it % 251).toByte() }
        val payload = prefix + "${RootFS.ORIGINAL_PATH}/lib/ld-linux-aarch64.so.1\u0000x${RootFS.ORIGINAL_PATH}".toByteArray()
        val out = ByteArrayOutputStream()
        // Читаем по 7 байт, чтобы совпадения попадали на границы блоков.
        assertTrue(relocator.copy(Chunked(ByteArrayInputStream(payload), 7), out))
        val expected = prefix + "$target/lib/ld-linux-aarch64.so.1\u0000x$target".toByteArray()
        assertTrue(expected.contentEquals(out.toByteArray()))
        assertEquals("$target/lib", relocator.relocate("${RootFS.ORIGINAL_PATH}/lib"))
    }

    private class Chunked(private val inner: InputStream, private val chunk: Int) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, chunk))
    }
}
