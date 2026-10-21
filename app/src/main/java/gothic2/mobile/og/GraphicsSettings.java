package gothic2.mobile.og;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Настройки графики. Настройки видео самой Gothic написаны для старого движка —
 * OpenGothic читает из них лишь часть (дальность обзора и качество текстур не действуют), поэтому
 * графику задаёт экран запуска: перед игрой пишется files/g2m.ini, движок читает его при старте
 * (патч Gothic::Gothic).
 */
final class GraphicsSettings {
    /** Один параметр: подписи вариантов и значения для g2m.ini. */
    static final class Option {
        final String key;
        final String iniKey;
        final String title;
        final String[] labels;
        final String[] values;

        Option(String key, String iniKey, String title, String[] labels, String[] values) {
            this.key = key;
            this.iniKey = iniKey;
            this.title = title;
            this.labels = labels;
            this.values = values;
        }
    }

    static final Option RESOLUTION = new Option("gfx_resolution", "resolution", "Разрешение",
        new String[]{"100 %", "75 %", "50 %"}, new String[]{"0", "1", "2"});
    static final Option FPS = new Option("gfx_fps", "fpsLimit", "Частота кадров",
        new String[]{"30", "45", "60", "без ограничения"}, new String[]{"30", "45", "60", "0"});
    static final Option SHADOWS = new Option("gfx_shadows", "shadowResolution", "Тени",
        new String[]{"выключены", "низкие", "средние", "высокие"}, new String[]{"0", "512", "1024", "2048"});
    static final Option VIEW = new Option("gfx_view", "viewDistance", "Дальность прорисовки",
        new String[]{"120 м", "250 м", "500 м", "без ограничения"}, new String[]{"120", "250", "500", "0"});
    static final Option DETAIL = new Option("gfx_detail", "detailRatio", "Трава и мелкие предметы",
        new String[]{"только рядом", "до середины", "далеко", "все"},
        new String[]{"0.012", "0.006", "0.003", "0"});
    static final Option REFLECTIONS = new Option("gfx_reflections", "reflections", "Отражения в воде",
        new String[]{"выключены", "включены"}, new String[]{"0", "1"});
    static final Option SUNSHAFTS = new Option("gfx_sunshafts", "sunshafts", "Лучи солнца и дымка",
        new String[]{"выключены", "включены"}, new String[]{"0", "1"});

    static final Option[] ALL = {RESOLUTION, FPS, SHADOWS, VIEW, DETAIL, REFLECTIONS, SUNSHAFTS};

    /** Готовые наборы: индексы вариантов в порядке {@link #ALL}. */
    static final String[] PRESET_NAMES = {"Экономно", "Сбалансированно", "Красиво"};
    private static final int[][] PRESETS = {
        {2, 0, 0, 1, 0, 0, 0}, // 50 %, 30 FPS, без теней, 250 м, трава рядом
        {1, 0, 2, 2, 1, 0, 0}, // 75 %, 30 FPS, средние тени, 500 м
        {0, 2, 3, 3, 3, 1, 1}, // как у движка на компьютере
    };
    static final int DEFAULT_PRESET = 1;

    private final SharedPreferences prefs;

    GraphicsSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    int get(Option o) {
        int def = PRESETS[DEFAULT_PRESET][indexOf(o)];
        int v = prefs.getInt(o.key, def);
        return v >= 0 && v < o.labels.length ? v : def;
    }

    void cycle(Option o) {
        prefs.edit().putInt(o.key, (get(o) + 1) % o.labels.length).apply();
    }

    void applyPreset(int preset) {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < ALL.length; i++) e.putInt(ALL[i].key, PRESETS[preset][i]);
        e.apply();
    }

    /** Номер набора, если параметры совпадают с одним из них; иначе -1 («свои»). */
    int currentPreset() {
        for (int p = 0; p < PRESETS.length; p++) {
            boolean same = true;
            for (int i = 0; i < ALL.length; i++) same &= get(ALL[i]) == PRESETS[p][i];
            if (same) return p;
        }
        return -1;
    }

    String label(Option o) {
        return o.title + ": " + o.labels[get(o)];
    }

    /** Содержимое g2m.ini: раздел [GRAPHICS], ключи — как читает Gothic::Gothic. */
    String iniText() {
        StringBuilder sb = new StringBuilder("[GRAPHICS]\n");
        for (Option o : ALL) sb.append(o.iniKey).append('=').append(o.values[get(o)]).append('\n');
        return sb.toString();
    }

    void write(File filesDir) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(filesDir, "g2m.ini"))) {
            out.write(iniText().getBytes(StandardCharsets.US_ASCII));
        }
    }

    String summary() {
        int p = currentPreset();
        return "Графика: " + (p >= 0 ? PRESET_NAMES[p] : "свои настройки")
            + ". Меньше — холоднее телефон и дольше заряд.";
    }

    private static int indexOf(Option o) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == o) return i;
        throw new IllegalArgumentException(o.key);
    }
}
