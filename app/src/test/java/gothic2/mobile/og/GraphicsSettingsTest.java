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
    public void throesAreOnByDefaultAndDependOnRagdoll() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        // ключ читает Gothic::Gothic (patch движка): ragdollThroes - агония убитых и оглушённых
        assertTrue(g.iniText().contains("\nragdollThroes=1\n"));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_THROES));
        g.setOn(GraphicsSettings.RAGDOLL_THROES, false);
        assertTrue(g.iniText().contains("\nragdollThroes=0\n"));
        g.setOn(GraphicsSettings.RAGDOLL, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_THROES));
    }

    @Test
    public void activeRagdollAndKnockdownAreOnByDefaultAndDependOnRagdoll() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        // ключи читает Gothic::Gothic (patch движка): ragdollActive - отклик тела на удары, ragdollKnockdown - сбивание с ног
        assertTrue(g.iniText().contains("\nragdollActive=1\n"));
        assertTrue(g.iniText().contains("\nragdollKnockdown=1\n"));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_KNOCKDOWN));
        g.setOn(GraphicsSettings.RAGDOLL_FALL, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_KNOCKDOWN));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_ACTIVE));
        g.setOn(GraphicsSettings.RAGDOLL_FALL, true);
        g.setOn(GraphicsSettings.RAGDOLL, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_ACTIVE));
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_FALL));
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_KNOCKDOWN));
        assertTrue(g.isAvailable(GraphicsSettings.SHOW_FPS));
        g.setOn(GraphicsSettings.RAGDOLL_ACTIVE, false);
        assertTrue(g.iniText().contains("\nragdollActive=0\n"));
    }

    @Test
    public void shoveAndRiseAreOnByDefaultAndDependOnRagdoll() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        // ключи читает Gothic::Gothic (patch движка): ragdollShove - толчок с разбега, ragdollRise - подъём: тело собирается по земле
        assertTrue(g.iniText().contains("\nragdollShove=1\n"));
        assertTrue(g.iniText().contains("\nragdollRise=1\n"));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_SHOVE));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_RISE));
        g.setOn(GraphicsSettings.RAGDOLL_ACTIVE, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_SHOVE));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_RISE));
        g.setOn(GraphicsSettings.RAGDOLL_ACTIVE, true);
        g.setOn(GraphicsSettings.RAGDOLL_FALL, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_SHOVE));
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_RISE));
        g.setOn(GraphicsSettings.RAGDOLL_SHOVE, false);
        assertTrue(g.iniText().contains("\nragdollShove=0\n"));
    }

    @Test
    public void combatIsOnByDefaultAndNeedsActiveRagdoll() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        // ключ ragdollCombat читает Gothic::Gothic: бой телом (game/fightbody.h)
        assertTrue(g.iniText().contains("\nragdollCombat=1\n"));
        assertTrue(g.isAvailable(GraphicsSettings.RAGDOLL_COMBAT));
        g.setOn(GraphicsSettings.RAGDOLL_ACTIVE, false);
        assertFalse(g.isAvailable(GraphicsSettings.RAGDOLL_COMBAT));
        g.setOn(GraphicsSettings.RAGDOLL_ACTIVE, true);
        g.setOn(GraphicsSettings.RAGDOLL_COMBAT, false);
        assertTrue(g.iniText().contains("\nragdollCombat=0\n"));
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

    @Test
    public void lightingIsSoftByDefaultAndNotInPresets() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        assertTrue(g.iniText().contains("\nlighting=1\n"));
        assertEquals("Освещение: мягкое — тени видны", g.label(GraphicsSettings.LIGHTING));
        g.cycle(GraphicsSettings.LIGHTING);
        assertTrue(g.iniText().contains("\nlighting=2\n"));
        g.cycle(GraphicsSettings.LIGHTING);
        assertTrue("как в движке", g.iniText().contains("\nlighting=0\n"));
        assertEquals(GraphicsSettings.DEFAULT_PRESET, g.currentPreset());
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        assertTrue("набор освещение не меняет", g.iniText().contains("\nlighting=0\n"));
    }

    @Test
    public void aimAssistIsOnByDefaultInControlsSection() {
        // [CONTROLS] aimAssist читает Gothic::Gothic и Gothic::reloadControls: прицел лука - в грудь цели
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        String ini = g.iniText();
        assertTrue(ini.contains("\n[CONTROLS]\naimAssist=1\n"));
        assertTrue("ключ - после раздела графики", ini.indexOf("aimAssist=") > ini.indexOf("occlusion="));
        g.setOn(GraphicsSettings.AIM_ASSIST, false);
        assertTrue(g.iniText().contains("\naimAssist=0\n"));
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        assertTrue("не входит в наборы", g.iniText().contains("\naimAssist=0\n"));
        assertTrue(g.isAvailable(GraphicsSettings.AIM_ASSIST));
    }

    @Test
    public void aimSmoothIsOnByDefaultAndNeedsAimAssist() {
        // [CONTROLS] aimSmooth: прицел задерживается на сражённом и плавно переходит к новой цели
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        assertTrue(g.iniText().contains("\naimAssist=1\naimSmooth=1\n"));
        assertTrue(g.isAvailable(GraphicsSettings.AIM_SMOOTH));
        g.setOn(GraphicsSettings.AIM_ASSIST, false);
        assertFalse("без автонаведения цели нет - и смены цели", g.isAvailable(GraphicsSettings.AIM_SMOOTH));
        g.setOn(GraphicsSettings.AIM_ASSIST, true);
        g.setOn(GraphicsSettings.AIM_SMOOTH, false);
        assertTrue(g.iniText().contains("\naimSmooth=0\n"));
    }

    @Test
    public void killCamDefaultsLastEnemyWithCameras() {
        // [CONTROLS] killCam* читает Gothic::Gothic и reloadControls (game/killcam.h): по умолчанию - включено,
        // последний враг в бою, камера со стороны и за стрелой
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        String ini = g.iniText();
        assertTrue(ini.contains("\nkillCam=1\nkillCamWhen=0\nkillCamCinema=1\nkillCamArrow=1\n"));
        assertTrue("в разделе управления", ini.indexOf("killCam=") > ini.indexOf("[CONTROLS]"));
        assertEquals("Когда: последний враг в бою", g.label(GraphicsSettings.KILLCAM_WHEN));
        g.cycle(GraphicsSettings.KILLCAM_WHEN);
        assertEquals("Когда: каждый побеждённый", g.label(GraphicsSettings.KILLCAM_WHEN));
        assertTrue(g.iniText().contains("\nkillCamWhen=1\n"));
        g.cycle(GraphicsSettings.KILLCAM_WHEN);
        assertTrue(g.iniText().contains("\nkillCamWhen=0\n"));
        assertEquals("смена «Когда» - не графика", GraphicsSettings.DEFAULT_PRESET, g.currentPreset());
    }

    @Test
    public void killCamOptionsNeedKillCam() {
        GraphicsSettings g = new GraphicsSettings(new MemoryPrefs());
        for (GraphicsSettings.Option o : GraphicsSettings.KILLCAM_OPTIONS) assertTrue(g.isAvailable(o));
        g.setOn(GraphicsSettings.KILLCAM, false);
        assertTrue(g.iniText().contains("\nkillCam=0\n"));
        assertTrue(g.isAvailable(GraphicsSettings.KILLCAM));
        assertFalse(g.isAvailable(GraphicsSettings.KILLCAM_WHEN));
        assertFalse(g.isAvailable(GraphicsSettings.KILLCAM_CINEMA));
        assertFalse(g.isAvailable(GraphicsSettings.KILLCAM_ARROW));
        g.setOn(GraphicsSettings.KILLCAM_ARROW, false);
        g.applyPreset(GraphicsSettings.ECONOMY_PRESET);
        assertTrue("наборы графики слоумо не трогают", g.iniText().contains("\nkillCam=0\n"));
        assertTrue(g.iniText().contains("\nkillCamArrow=0\n"));
    }

    @Test
    public void controlsListsEveryBowAndKillCamOption() {
        // всё, что на странице «Управление», пишется в [CONTROLS] (и сразу - isControl в LauncherActivity)
        java.util.List<GraphicsSettings.Option> all = java.util.Arrays.asList(GraphicsSettings.CONTROLS);
        for (GraphicsSettings.Option o : GraphicsSettings.BOW) assertTrue(o.iniKey, all.contains(o));
        for (GraphicsSettings.Option o : GraphicsSettings.KILLCAM_OPTIONS) assertTrue(o.iniKey, all.contains(o));
        assertEquals(GraphicsSettings.BOW.length + GraphicsSettings.KILLCAM_OPTIONS.length, all.size());
    }
}
