package gothic2.mobile.og;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GraphicsSettingsTest {
    @Test
    public void defaultIsBalancedPreset() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        assertEquals(GraphicsSettings.DEFAULT_PRESET, g.currentPreset());
        assertEquals("Разрешение: 75 %", g.label(GraphicsSettings.RESOLUTION));
        assertEquals("Частота кадров: 30", g.label(GraphicsSettings.FPS));
    }

    @Test
    public void iniHasEveryKeyTheEngineReads() {
        String ini = new GraphicsSettings(new MemoryPrefs()).iniText();
        assertTrue(ini.startsWith("[GRAPHICS]\n"));
        // Ключи читает Gothic::Gothic (патч движка): переименование здесь сломает настройки
        for (String key : new String[]{"resolution=1", "fpsLimit=30", "shadowResolution=1024",
            "viewDistance=500", "detailRatio=0.006", "reflections=0", "sunshafts=0"}) {
            assertTrue(key, ini.contains("\n" + key + "\n"));
        }
    }

    @Test
    public void cycleWrapsAndBreaksPreset() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        g.cycle(GraphicsSettings.SHADOWS); // средние → высокие
        assertEquals("Тени: высокие", g.label(GraphicsSettings.SHADOWS));
        assertEquals(-1, g.currentPreset());
        g.cycle(GraphicsSettings.SHADOWS); // высокие → выключены
        assertEquals("Тени: выключены", g.label(GraphicsSettings.SHADOWS));
    }

    @Test
    public void presetsApplyAllOptions() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        g.applyPreset(0);
        assertEquals(0, g.currentPreset());
        assertTrue(g.iniText().contains("\nresolution=2\n"));
        assertTrue(g.iniText().contains("\nshadowResolution=0\n"));
        g.applyPreset(2);
        assertEquals(2, g.currentPreset());
        assertTrue(g.iniText().contains("\nviewDistance=0\n"));
    }

    @Test
    public void brokenStoredValueFallsBackToDefault() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.values.put(GraphicsSettings.FPS.key, 99);
        assertEquals("Частота кадров: 30", new GraphicsSettings(prefs).label(GraphicsSettings.FPS));
    }
}
