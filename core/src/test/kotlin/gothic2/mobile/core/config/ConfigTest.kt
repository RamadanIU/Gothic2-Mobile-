package gothic2.mobile.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigTest {
    private val sample = "\uFEFF[GAME]\r\n; comment\r\nenableMouse=0\r\nsomething = keep\r\n\r\n[VIDEO]\r\nzVidResFullscreenX=640\r\n"

    @Test
    fun `ini edit preserves unrelated content and comments`() {
        val ini = IniFile.parse(sample)
        ini.set("video", "ZVIDRESFULLSCREENX", "960")
        ini.set("VIDEO", "zVidResFullscreenY", "540")
        ini.set("PERFORMANCE", "sightValue", "6")
        val text = ini.toString()
        assertTrue("; comment" in text)
        assertTrue("something = keep" in text)
        assertTrue("zVidResFullscreenX=960" in text)
        assertEquals("540", IniFile.parse(text).get("VIDEO", "zVidResFullscreenY"))
        assertEquals("6", IniFile.parse(text).get("performance", "SIGHTVALUE"))
        assertFalse("640" in text)
    }

    @Test
    fun `gothic ini writer sets resolution and keeps cutscenes`() {
        val ini = GothicIniWriter.apply(IniFile.parse(sample), GraphicsPreset.BALANCED)
        assertEquals("960", ini.get("VIDEO", "zVidResFullscreenX"))
        assertEquals("1", ini.get("GAME", "enableMouse"))
        assertEquals("0", ini.get("GAME", "playLogoVideos"))
        assertEquals("1", ini.get("GAME", "useGothic1Controls"))
        assertEquals("1", ini.get("GAME", "useQuickSaveKeys"))
        assertTrue("d3d9.maxFrameRate = 30" in GothicIniWriter.dxvkConf(GraphicsPreset.BALANCED))
    }

    @Test
    fun `unknown gpu falls back to unverified compatibility profile`() {
        val p = DeviceProfiles.select("Mali-G710")
        assertEquals(DeviceProfiles.FALLBACK, p)
        assertFalse(p.verified)
        assertEquals("adreno-6xx", DeviceProfiles.select("Adreno (TM) 650").id)
    }

    @Test
    fun `user settings adjust profile within bounds`() {
        val s = UserSettings(viewDistanceOffset = 3, fpsLimit = 60)
        val g = s.effectiveGraphics(DeviceProfiles.FALLBACK)
        assertEquals(GraphicsPreset.COMPATIBILITY.sightValue + 3, g.sightValue)
        assertEquals(60, g.fpsLimit)
        assertTrue(UserSettings.requiresRestart(UserSettings(), s))
        assertFalse(UserSettings.requiresRestart(UserSettings(), UserSettings(cameraSensitivity = 2f)))
    }
}
