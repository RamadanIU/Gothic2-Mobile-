package gothic2.mobile.og;

import android.content.SharedPreferences;

/**
 * Партия режима стратегии «Война за Хоринис» (docs/RTS.md): за кого, как играть, сложность, переговоры языковой
 * моделью. Экран запуска хранит выбор в настройках и перед запуском пишет files/rts_setup.txt — его читает движок
 * (RtsGame::readSetup). Ключи файла менять вместе с движком.
 */
final class RtsSetup {
    static final String[] FACTIONS = {"paladins", "mercs", "orcs"};
    static final String[] FACTION_NAMES = {"Паладины и ополчение", "Наёмники Онара", "Орки"};
    static final String[] MODES = {"commander", "observer", "hero"};
    static final String[] MODE_NAMES = {"Полководец (вид сверху)", "Наблюдатель (все стороны — ИИ)", "Свой герой посреди войны"};
    static final String[] DIFFICULTY_NAMES = {"Легко", "Нормально", "Трудно"};

    private static final String KEY_FACTION = "rts_faction";
    private static final String KEY_MODE = "rts_mode";
    private static final String KEY_DIFFICULTY = "rts_difficulty";
    private static final String KEY_LLM = "rts_llm";

    private final SharedPreferences prefs;

    RtsSetup(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    int faction() {
        return clamp(prefs.getInt(KEY_FACTION, 0), FACTIONS.length);
    }

    int mode() {
        return clamp(prefs.getInt(KEY_MODE, 0), MODES.length);
    }

    int difficulty() {
        return clamp(prefs.getInt(KEY_DIFFICULTY, 1), DIFFICULTY_NAMES.length);
    }

    /** Переговоры языковой моделью (ключ — в «Живые NPC»); выключено — правила, они работают всегда. */
    boolean llm() {
        return prefs.getBoolean(KEY_LLM, false);
    }

    void cycleFaction() {
        prefs.edit().putInt(KEY_FACTION, (faction() + 1) % FACTIONS.length).apply();
    }

    void cycleMode() {
        prefs.edit().putInt(KEY_MODE, (mode() + 1) % MODES.length).apply();
    }

    void cycleDifficulty() {
        prefs.edit().putInt(KEY_DIFFICULTY, (difficulty() + 1) % DIFFICULTY_NAMES.length).apply();
    }

    void toggleLlm() {
        prefs.edit().putBoolean(KEY_LLM, !llm()).apply();
    }

    String factionLabel() {
        return "Сторона: " + FACTION_NAMES[faction()];
    }

    String modeLabel() {
        return "Как играть: " + MODE_NAMES[mode()];
    }

    String difficultyLabel() {
        return "Сложность ИИ: " + DIFFICULTY_NAMES[difficulty()];
    }

    String llmLabel() {
        return "Переговоры языковой моделью: " + (llm() ? "включены" : "выключены (правила)");
    }

    /** Текст files/rts_setup.txt. */
    String fileText() {
        return "faction=" + FACTIONS[faction()] + "\n"
            + "mode=" + MODES[mode()] + "\n"
            + "difficulty=" + difficulty() + "\n"
            + "llm=" + (llm() ? 1 : 0) + "\n";
    }

    private static int clamp(int v, int n) {
        return v < 0 || v >= n ? 0 : v;
    }
}
