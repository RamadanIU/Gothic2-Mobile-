package gothic2.mobile.core.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameExitTest {
    private val crash = "0124:err:seh:NtRaiseException Exception frame is not in stack limits => unable to dispatch exception."

    @Test
    fun `quick exit by the player is not a failure`() {
        // Раньше выход быстрее трёх минут считался сбоем, и следующий запуск показывал экран сбоя.
        assertEquals(GameExit.USER_QUIT, GameExit.classify(userQuit = true, windowShown = true, crashLine = null))
        assertEquals(GameExit.GAME_QUIT, GameExit.classify(userQuit = false, windowShown = true, crashLine = null))
        assertFalse(GameExit.USER_QUIT.failed)
        assertFalse(GameExit.GAME_QUIT.failed)
    }

    @Test
    fun `crash in wine output or no window is a failure`() {
        assertEquals(GameExit.CRASHED, GameExit.classify(userQuit = false, windowShown = true, crashLine = crash))
        assertEquals(GameExit.NOT_STARTED, GameExit.classify(userQuit = false, windowShown = false, crashLine = null))
        assertTrue(GameExit.CRASHED.failed)
        assertTrue(GameExit.NOT_STARTED.failed)
    }

    @Test
    fun `crash lines are recognized, ordinary wine errors are not`() {
        assertTrue(GameExit.isCrashLine(crash))
        assertTrue(GameExit.isCrashLine("wine: Unhandled page fault on read access to 00000000 at address 0071A2B3"))
        assertTrue(GameExit.isCrashLine("0124:err:msvcrt:symbol_demangle unknown type"))
        assertFalse(GameExit.isCrashLine("0024:err:winediag:nodrv_CreateWindow Application tried to create a window"))
        assertFalse(GameExit.isCrashLine("[frames] fps=30.0"))
    }

    @Test
    fun `vulkan without adapters is a graphics failure even when the player quits`() {
        // Журнал realme C71 (Mali-G57): D7VK без Vulkan 1.3 — игра закрывается сама через минуту.
        val line = "warn:  DXVK: No adapters found. Please check your device filter settings"
        assertTrue(GameExit.isGraphicsFailureLine(line))
        assertTrue(GameExit.isGraphicsFailureLine("err:   Failed to initialize DXVK."))
        assertFalse(GameExit.isGraphicsFailureLine(crash))
        assertFalse(GameExit.isCrashLine(line))
        assertEquals(GameExit.GRAPHICS_UNSUPPORTED, GameExit.classify(userQuit = false, windowShown = true, crashLine = null, graphicsLine = line))
        assertEquals(GameExit.GRAPHICS_UNSUPPORTED, GameExit.classify(userQuit = true, windowShown = true, crashLine = null, graphicsLine = line))
        assertTrue(GameExit.GRAPHICS_UNSUPPORTED.failed)
    }
}
