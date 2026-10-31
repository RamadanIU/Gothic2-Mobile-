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
        /** Галочка: два варианта, первый — «включено»; на экране — флажок с подписью title. */
        final boolean check;

        Option(String key, String iniKey, String title, String[] labels, String[] values) {
            this(key, iniKey, title, labels, values, false);
        }

        Option(String key, String iniKey, String title, String[] labels, String[] values, boolean check) {
            this.key = key;
            this.iniKey = iniKey;
            this.title = title;
            this.labels = labels;
            this.values = values;
            this.check = check;
        }
    }

    static final Option RESOLUTION = new Option("gfx_resolution", "resolution", "Разрешение",
        new String[]{"100 %", "75 %", "50 %"}, new String[]{"0", "1", "2"});
    static final Option UPSCALE = new Option("gfx_upscale", "upscale", "Растяжение до экрана",
        new String[]{"простое (быстрее)", "чёткое"}, new String[]{"0", "1"});
    static final Option FPS = new Option("gfx_fps", "fpsLimit", "Частота кадров",
        new String[]{"30", "45", "60", "без ограничения"}, new String[]{"30", "45", "60", "0"});
    static final Option SHADOWS = new Option("gfx_shadows", "shadowResolution", "Тени",
        new String[]{"выключены", "низкие", "средние", "высокие"}, new String[]{"0", "512", "1024", "2048"});
    static final Option VIEW = new Option("gfx_view", "viewDistance", "Дальность прорисовки",
        new String[]{"120 м", "250 м", "500 м", "без ограничения"}, new String[]{"120", "250", "500", "0"});
    static final Option DETAIL = new Option("gfx_detail", "detailRatio", "Трава и мелкие предметы",
        new String[]{"только рядом", "до середины", "далеко", "все"},
        new String[]{"0.012", "0.006", "0.003", "0"});
    static final Option ANISOTROPY = new Option("gfx_anisotropy", "anisotropy", "Чёткость текстур вдали",
        new String[]{"2x", "4x", "8x", "16x"}, new String[]{"2", "4", "8", "16"});
    static final Option WIND = new Option("gfx_wind", "windRange", "Ветер в траве и деревьях",
        new String[]{"выключен", "рядом", "далеко"}, new String[]{"0", "60", "150"});
    static final Option REFLECTIONS = new Option("gfx_reflections", "reflections", "Отражения в воде",
        new String[]{"выключены", "включены"}, new String[]{"0", "1"});
    static final Option SUNSHAFTS = new Option("gfx_sunshafts", "sunshafts", "Лучи солнца и дымка",
        new String[]{"выключены", "включены"}, new String[]{"0", "1"});

    static final Option[] ALL = {RESOLUTION, UPSCALE, FPS, SHADOWS, VIEW, DETAIL, ANISOTROPY, WIND,
        REFLECTIONS, SUNSHAFTS};

    /** Не графика, а помощь в настройке: в наборы не входит, по умолчанию выключено. */
    static final Option SHOW_FPS = new Option("gfx_show_fps", "showFps", "Счётчик кадров на экране",
        new String[]{"скрыт", "показан"}, new String[]{"0", "1"});
    /**
     * Регдолл: убитые и оглушённые падают по физике; без галочки — анимацией смерти, как в обычной Gothic.
     * В наборы не входит, по умолчанию включён.
     */
    static final Option RAGDOLL = new Option("gfx_ragdoll", "ragdoll", "Регдолл: побеждённые падают по физике",
        new String[]{"включён", "выключен — смерть анимацией, как в Gothic"}, new String[]{"1", "0"}, true);
    /** И живые: падение дольше секунды, удар тролля; встают сами. Действует только с {@link #RAGDOLL}. */
    static final Option RAGDOLL_FALL = new Option("gfx_ragdoll_fall", "ragdollFall",
        "Регдолл и у живых: долгое падение, удар тролля",
        new String[]{"включён", "выключен"}, new String[]{"1", "0"}, true);
    /**
     * Активный регдолл (как Euphoria): живые на ногах откликаются телом на удары — корпус уходит от удара и медленно
     * возвращается, сильный удар отбрасывает на несколько шагов, рука тянется к ране, удар в ногу — хромает.
     * Действует только с {@link #RAGDOLL}.
     */
    static final Option RAGDOLL_ACTIVE = new Option("gfx_ragdoll_active", "ragdollActive",
        "Активный регдолл: тело откликается на удары",
        new String[]{"включён", "выключен"}, new String[]{"1", "0"}, true);
    /** Очень сильный удар (двуручник, огненный шар) или серия ударов сбивает с ног; встаёт сам. Без неё - только шаги. */
    static final Option RAGDOLL_KNOCKDOWN = new Option("gfx_ragdoll_knockdown", "ragdollKnockdown",
        "Сильные удары сбивают с ног",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    /**
     * Агония: убитые и оглушённые не замирают сразу — корчатся, держатся за рану, поджимают ноги, пытаются встать и
     * падают, дышат. Физика тела дольше (до 16 с у тел рядом с героем). Действует только с {@link #RAGDOLL}.
     */
    static final Option RAGDOLL_THROES = new Option("gfx_ragdoll_throes", "ragdollThroes",
        "Агония: раненые корчатся, пытаются встать, дышат",
        new String[]{"включена", "выключена"}, new String[]{"1", "0"}, true);
    /**
     * Толчок с разбега: герой, врезаясь в человека на бегу, подставляет плечо или толкает руками; тот всем телом (полный
     * регдолл) ловит равновесие шагами, руками, хватается за стену или соседа — устоит или упадёт. Только с активным
     * регдоллом и регдоллом живых.
     */
    static final Option RAGDOLL_SHOVE = new Option("gfx_ragdoll_shove", "ragdollShove",
        "Толчок с разбега: плечом или руками, тот ловит равновесие",
        new String[]{"включён", "выключен"}, new String[]{"1", "0"}, true);
    /**
     * Встают телом: упавший и оглушённый поднимаются физикой куклы, без анимации — садятся, опираясь на руки, или
     * отжимаются с живота, встают на колено, потом на ноги; мышцы ведут тело, вес держат опоры о землю. Выключено —
     * поза тела переходит в анимацию подъёма игры. Только с регдоллом живых.
     */
    static final Option RAGDOLL_RISE = new Option("gfx_ragdoll_rise", "ragdollRise",
        "Подъём телом: встают физикой, без анимации",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    /**
     * Бой телом: в бою с мечом, двуручником или кулаками нет записанных анимаций — стойка, шаги, замах, удар, блок и
     * доставание оружия делает кукла (мышцы к позе хода), попадание считает клинок: голова — урон вдвое, корпус — как
     * есть, руки — вдвое меньше, ноги — три четверти, клинок о клинок — блок. Только с активным регдоллом.
     */
    static final Option RAGDOLL_COMBAT = new Option("gfx_ragdoll_combat", "ragdollCombat",
        "Бой телом: удары и блоки — физикой, без анимаций",
        new String[]{"включён", "выключен"}, new String[]{"1", "0"}, true);
    /**
     * Не рисовать заслонённое (карта перекрытий движка): быстрее. Выключить — если пропадают стены, предметы или
     * тело героя, хотя видны: тогда рисуется всё в кадре.
     */
    static final Option OCCLUSION = new Option("gfx_occlusion", "occlusion",
        "Не рисовать заслонённое (быстрее; выключи, если пропадают стены)",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    /**
     * Освещение: в движке тени почти чёрные, а освещённое солнцем — очень яркое. «Мягкое» подсвечивает тени и не
     * давит тёмные тона, «светлое» — сильнее, «контрастное» — как в движке. Дело вкуса, а не скорости: в наборы
     * не входит, по умолчанию — первый вариант (ключ lighting читает Gothic::Gothic).
     */
    static final Option LIGHTING = new Option("gfx_lighting", "lighting", "Освещение",
        new String[]{"мягкое — тени видны", "светлое — тени ещё светлее", "контрастное — тёмные тени, как раньше"},
        new String[]{"1", "2", "0"});
    static final Option[] EXTRA = {LIGHTING, SHOW_FPS, RAGDOLL, RAGDOLL_FALL, RAGDOLL_ACTIVE, RAGDOLL_KNOCKDOWN,
        RAGDOLL_THROES, RAGDOLL_SHOVE, RAGDOLL_RISE, RAGDOLL_COMBAT, OCCLUSION};

    /**
     * Лук и арбалет: прицел сам наводится на грудь цели в фокусе. Без него прицел — середина экрана: куда смотрит
     * камера, туда и стрела (можно целиться в голову). Не графика: раздел [CONTROLS], страница «Управление»; движок
     * перечитывает его, когда игра снова на экране (Gothic::reloadControls). По умолчанию включено.
     */
    static final Option AIM_ASSIST = new Option("ctl_aim_assist", "aimAssist",
        "Автонаведение прицела в грудь",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    /**
     * Плавная смена цели: цель упала — прицел ещё держится на ней (видно попадание и падение), потом плавно переходит
     * к следующей, герой поворачивается вслед. Без неё прицел перескакивает сразу. Только с {@link #AIM_ASSIST}.
     */
    static final Option AIM_SMOOTH = new Option("ctl_aim_smooth", "aimSmooth",
        "Плавная смена цели: прицел задерживается на сражённом",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    static final Option[] BOW = {AIM_ASSIST, AIM_SMOOTH};

    /**
     * Слоумо-добивание, как в Max Payne: герой победил врага — время замедляется, камера со стороны показывает падение,
     * звук ниже, цвета приглушены. Остальные параметры слоумо действуют только с ним.
     */
    static final Option KILLCAM = new Option("ctl_killcam", "killCam",
        "Слоумо-добивание, как в Max Payne",
        new String[]{"включено", "выключено"}, new String[]{"1", "0"}, true);
    /** Когда: по умолчанию — последний враг в бою (как в Max Payne 3), иначе — каждый побеждённый. */
    static final Option KILLCAM_WHEN = new Option("ctl_killcam_when", "killCamWhen", "Когда",
        new String[]{"последний враг в бою", "каждый побеждённый"}, new String[]{"0", "1"});
    /** Камера со стороны: без неё — только замедление и цвет, камера обычная. */
    static final Option KILLCAM_CINEMA = new Option("ctl_killcam_cinema", "killCamCinema",
        "Камера со стороны (иначе — только замедление)",
        new String[]{"включена", "выключена"}, new String[]{"1", "0"}, true);
    /** Лук и арбалет: выстрел, который победит, — камера летит за стрелой и останавливается перед целью. */
    static final Option KILLCAM_ARROW = new Option("ctl_killcam_arrow", "killCamArrow",
        "Камера за стрелой (лук и арбалет)",
        new String[]{"включена", "выключена"}, new String[]{"1", "0"}, true);
    static final Option[] KILLCAM_OPTIONS = {KILLCAM, KILLCAM_WHEN, KILLCAM_CINEMA, KILLCAM_ARROW};

    /** Раздел [CONTROLS] g2m.ini: экран запуска пишет его сразу, идущая игра перечитывает (Gothic::reloadControls). */
    static final Option[] CONTROLS = {AIM_ASSIST, AIM_SMOOTH, KILLCAM, KILLCAM_WHEN, KILLCAM_CINEMA, KILLCAM_ARROW};

    /** Готовые наборы: индексы вариантов в порядке {@link #ALL}. */
    static final String[] PRESET_NAMES = {"Экономно", "Сбалансированно", "Красиво"};
    private static final int[][] PRESETS = {
        {2, 0, 0, 0, 1, 0, 0, 1, 0, 0}, // 50 %, простое растяжение, 30 FPS, без теней, 250 м, трава рядом, 2x
        {1, 1, 0, 2, 2, 1, 1, 1, 0, 0}, // 75 %, 30 FPS, средние тени, 500 м, 4x
        {0, 1, 2, 3, 3, 3, 3, 2, 1, 1}, // как у движка на компьютере
    };
    static final int ECONOMY_PRESET = 0;
    static final int DEFAULT_PRESET = 1;
    /** Последний выбранный набор: из него - параметры, которых ещё нет в сохранённых настройках. */
    private static final String PREF_PRESET = "gfx_preset";

    private final SharedPreferences prefs;

    GraphicsSettings(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    int get(Option o) {
        int idx = indexOf(o);
        int def = idx >= 0 ? PRESETS[basePreset()][idx] : 0;
        int v = prefs.getInt(o.key, def);
        return v >= 0 && v < o.labels.length ? v : def;
    }

    void cycle(Option o) {
        SharedPreferences.Editor e = prefs.edit();
        // набор, на котором начали менять, - запомнить до изменения: остальные параметры остаются его
        if (!prefs.contains(PREF_PRESET)) e.putInt(PREF_PRESET, basePreset());
        e.putInt(o.key, (get(o) + 1) % o.labels.length).apply();
    }

    /**
     * Действует ли галочка: регдолл живых, активный и агония — только с регдоллом, сбивание с ног и толчок с разбега — с
     * активным и с регдоллом живых (сбитый падает и встаёт, как упавший), подъём телом — с регдоллом живых, бой телом — с
     * активным. Недействующая
     * галочка на экране неактивна.
     */
    boolean isAvailable(Option o) {
        if (o == RAGDOLL_FALL || o == RAGDOLL_ACTIVE || o == RAGDOLL_THROES) return isOn(RAGDOLL);
        if (o == RAGDOLL_KNOCKDOWN || o == RAGDOLL_SHOVE) return isOn(RAGDOLL) && isOn(RAGDOLL_ACTIVE) && isOn(RAGDOLL_FALL);
        if (o == RAGDOLL_RISE) return isOn(RAGDOLL) && isOn(RAGDOLL_FALL);
        if (o == RAGDOLL_COMBAT) return isOn(RAGDOLL) && isOn(RAGDOLL_ACTIVE);
        // плавная смена цели - у прицела с автонаведением (без него прицел - середина экрана, цели нет)
        if (o == AIM_SMOOTH) return isOn(AIM_ASSIST);
        if (o == KILLCAM_WHEN || o == KILLCAM_CINEMA || o == KILLCAM_ARROW) return isOn(KILLCAM);
        return true;
    }

    /** Галочка стоит (первый вариант). */
    boolean isOn(Option o) {
        return get(o) == 0;
    }

    void setOn(Option o, boolean on) {
        prefs.edit().putInt(o.key, on ? 0 : 1).apply();
    }

    void applyPreset(int preset) {
        SharedPreferences.Editor e = prefs.edit();
        for (int i = 0; i < ALL.length; i++) e.putInt(ALL[i].key, PRESETS[preset][i]);
        e.putInt(PREF_PRESET, preset);
        e.apply();
    }

    /** Графика ещё ни разу не настраивалась (первый запуск): можно выбрать набор по телефону. */
    boolean isUntouched() {
        if (prefs.contains(PREF_PRESET)) return false;
        for (Option o : ALL) if (prefs.contains(o.key)) return false;
        return true;
    }

    /** Ограничение частоты кадров (0 - без ограничения): экран игры подстраивает под него дисплей. */
    int fpsLimit() {
        return Integer.parseInt(FPS.values[get(FPS)]);
    }

    /**
     * Набор, из которого берутся несохранённые параметры: последний выбранный, а для настроек прежних
     * версий (без номера набора) - набор, с которым совпадают все сохранённые параметры. Так новые
     * параметры у игрока с «Экономно» тоже экономные.
     */
    private int basePreset() {
        int stored = prefs.getInt(PREF_PRESET, -1);
        if (stored >= 0 && stored < PRESETS.length) return stored;
        for (int p = 0; p < PRESETS.length; p++) {
            boolean same = true;
            boolean any = false;
            for (int i = 0; i < ALL.length && same; i++) {
                if (!prefs.contains(ALL[i].key)) continue;
                any = true;
                same = prefs.getInt(ALL[i].key, -1) == PRESETS[p][i];
            }
            if (any && same) return p;
        }
        return DEFAULT_PRESET;
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

    /** Содержимое g2m.ini: разделы [GRAPHICS] и [CONTROLS], ключи — как читает Gothic::Gothic. */
    String iniText() {
        StringBuilder sb = new StringBuilder("[GRAPHICS]\n");
        for (Option o : ALL) sb.append(o.iniKey).append('=').append(o.values[get(o)]).append('\n');
        for (Option o : EXTRA) sb.append(o.iniKey).append('=').append(o.values[get(o)]).append('\n');
        sb.append("[CONTROLS]\n");
        for (Option o : CONTROLS) sb.append(o.iniKey).append('=').append(o.values[get(o)]).append('\n');
        return sb.toString();
    }

    void write(File filesDir) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(filesDir, "g2m.ini"))) {
            out.write(iniText().getBytes(StandardCharsets.US_ASCII));
        }
    }

    /** Коротко — для строки раздела на главной: набор, разрешение и частота. */
    String summaryShort() {
        int p = currentPreset();
        return (p >= 0 ? PRESET_NAMES[p] : "Свои настройки") + ": " + RESOLUTION.labels[get(RESOLUTION)] + ", "
            + FPS.labels[get(FPS)] + (get(FPS) == 3 ? "" : " кадров/с");
    }

    String summary() {
        int p = currentPreset();
        return "Графика: " + (p >= 0 ? PRESET_NAMES[p] : "свои настройки")
            + ". Меньше — холоднее телефон и дольше заряд.";
    }

    /** Номер параметра в наборах; -1 - параметр не из наборов ({@link #EXTRA}). */
    private static int indexOf(Option o) {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == o) return i;
        return -1;
    }
}
