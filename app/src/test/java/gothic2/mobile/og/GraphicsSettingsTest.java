package gothic2.mobile.og;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        for (String key : new String[]{"resolution=1", "upscale=1", "fpsLimit=30", "shadowResolution=1024",
            "viewDistance=500", "detailRatio=0.006", "anisotropy=4", "windRange=60", "reflections=0", "sunshafts=0"}) {
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
    public void economyPresetIsCheapEverywhere() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        String ini = g.iniText();
        for (String key : new String[]{"resolution=2", "upscale=0", "fpsLimit=30", "shadowResolution=0",
            "viewDistance=250", "anisotropy=2", "reflections=0", "sunshafts=0"}) {
            assertTrue(key, ini.contains("\n" + key + "\n"));
        }
        assertEquals(30, g.fpsLimit());
    }

    @Test
    public void newOptionsFollowPresetOfOlderSettings() {
        // Настройки прежней версии: «Экономно» сохранён по параметрам, без номера набора и без новых параметров
        MemoryPrefs prefs = new MemoryPrefs();
        int[] economyOld = {2, 0, 0, 1, 0, 0, 0};
        GraphicsSettings.Option[] old = {GraphicsSettings.RESOLUTION, GraphicsSettings.FPS, GraphicsSettings.SHADOWS,
            GraphicsSettings.VIEW, GraphicsSettings.DETAIL, GraphicsSettings.REFLECTIONS, GraphicsSettings.SUNSHAFTS};
        for (int i = 0; i < old.length; i++) prefs.values.put(old[i].key, economyOld[i]);
        GraphicsSettings g = new GraphicsSettings(prefs);
        assertEquals(GraphicsSettings.ECONOMY_PRESET, g.currentPreset());
        assertEquals("Растяжение до экрана: простое (быстрее)", g.label(GraphicsSettings.UPSCALE));
        assertEquals("Чёткость текстур вдали: 2x", g.label(GraphicsSettings.ANISOTROPY));
    }

    @Test
    public void untouchedUntilSomethingIsChosen() {
        MemoryPrefs prefs = new MemoryPrefs();
        GraphicsSettings g = new GraphicsSettings(prefs);
        assertTrue(g.isUntouched());
        g.cycle(GraphicsSettings.WIND);
        assertFalse(g.isUntouched());
        GraphicsSettings h = new GraphicsSettings(new MemoryPrefs());
        h.applyPreset(GraphicsSettings.DEFAULT_PRESET);
        assertFalse(h.isUntouched());
    }

    @Test
    public void unlimitedFpsIsZero() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        g.cycle(GraphicsSettings.FPS); // 30 → 45
        g.cycle(GraphicsSettings.FPS); // 45 → 60
        assertEquals(60, g.fpsLimit());
        g.cycle(GraphicsSettings.FPS); // 60 → без ограничения
        assertEquals(0, g.fpsLimit());
    }

    @Test
    public void fpsCounterIsNotPartOfPresets() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        assertTrue(g.iniText().contains("\nshowFps=0\n"));
        g.cycle(GraphicsSettings.SHOW_FPS);
        assertEquals("Счётчик кадров на экране: показан", g.label(GraphicsSettings.SHOW_FPS));
        assertEquals(GraphicsSettings.DEFAULT_PRESET, g.currentPreset());
        assertTrue(g.iniText().contains("\nshowFps=1\n"));
    }

    @Test
    public void brokenStoredValueFallsBackToDefault() {
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.values.put(GraphicsSettings.FPS.key, 99);
        assertEquals("Частота кадров: 30", new GraphicsSettings(prefs).label(GraphicsSettings.FPS));
    }

    @Test
    public void ragdollIsOnByDefaultAndNotPartOfPresets() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        // ключи читает Gothic::Gothic (patch движка): ragdoll=1 - тела падают по физике, ragdollFall=1 - и живые
        assertTrue(g.iniText().contains("\nragdoll=1\n"));
        assertTrue(g.iniText().contains("\nragdollFall=1\n"));
        assertTrue(g.isOn(GraphicsSettings.RAGDOLL));
        assertTrue(GraphicsSettings.RAGDOLL.check);
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        assertTrue(g.iniText().contains("\nragdoll=1\n"));
        g.setOn(GraphicsSettings.RAGDOLL, false);
        assertFalse(g.isOn(GraphicsSettings.RAGDOLL));
        assertTrue(g.iniText().contains("\nragdoll=0\n"));
        assertEquals(GraphicsSettings.ECONOMY_PRESET, g.currentPreset());
        g.setOn(GraphicsSettings.RAGDOLL_FALL, false);
        assertTrue(g.iniText().contains("\nragdollFall=0\n"));
        g.setOn(GraphicsSettings.RAGDOLL, true);
        assertTrue(g.iniText().contains("\nragdoll=1\n"));
    }

    @Test
    public void ragdollKeepsOldSetting() {
        // прежняя версия хранила тот же ключ с вариантами «по физике»/«анимацией игры» - значение не меняется
        MemoryPrefs prefs = new MemoryPrefs();
        prefs.values.put(GraphicsSettings.RAGDOLL.key, 1);
        assertFalse(new GraphicsSettings(prefs).isOn(GraphicsSettings.RAGDOLL));
    }

    @Test
    public void occlusionIsOnByDefaultAndCanBeTurnedOff() {
        // occlusion читает Gothic::Gothic: 0 - движок рисует и заслонённое (проверка пропадающих стен)
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        assertTrue(g.iniText().contains("\nocclusion=1\n"));
        g.setOn(GraphicsSettings.OCCLUSION, false);
        assertTrue(g.iniText().contains("\nocclusion=0\n"));
        g.applyPreset(GraphicsSettings.DEFAULT_PRESET);
        assertTrue("не входит в наборы", g.iniText().contains("\nocclusion=0\n"));
    }

    @Test
    public void shortSummaryNamesPresetResolutionAndFps() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        assertEquals("Экономно: 50 %, 30 кадров/с", g.summaryShort());
        g.cycle(GraphicsSettings.FPS);
        g.cycle(GraphicsSettings.FPS);
        g.cycle(GraphicsSettings.FPS);
        assertEquals("Свои настройки: 50 %, без ограничения", g.summaryShort());
    }
}
