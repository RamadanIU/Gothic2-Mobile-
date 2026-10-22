package gothic2.mobile.og.ai;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.Map;

/**
 * Настройки живого NPC: адрес, ключ и модель для разговора и для самостоятельных действий (у каждой роли
 * свой ключ — бесплатные лимиты не делятся), запасной ключ на случай лимита, озвучка, распознавание речи.
 * Хранятся в настройках приложения (каталог приложения, резервная копия выключена в манифесте).
 */
public final class AiSettings {
    /** Хранилище ключ-строка: настройки Android или Map в тестах. */
    public interface Store {
        String get(String key, String def);

        void put(String key, String value);
    }

    /** Роль модели: разговор с героем или решения без героя (события, память). */
    public static final class Role {
        public final String url;
        public final String key;
        public final String spareKey;
        public final String model;

        Role(String url, String key, String spareKey, String model) {
            this.url = url;
            this.key = key;
            this.spareKey = spareKey;
            this.model = model;
        }

        public boolean isSet() {
            return !url.isEmpty() && !model.isEmpty();
        }
    }

    public static final String DEFAULT_URL = "https://ollama.com/v1";
    public static final String DEFAULT_MODEL = "gpt-oss:120b";

    public static final String TTS_ANDROID = "android";
    public static final String TTS_GEMINI = "gemini";
    public static final String TTS_AZURE = "azure";
    public static final String TTS_ELEVENLABS = "elevenlabs";
    public static final String TTS_OPENAI = "openai";
    public static final String TTS_OFF = "off";

    public static final String[] TTS_IDS = {TTS_GEMINI, TTS_AZURE, TTS_ELEVENLABS, TTS_OPENAI, TTS_ANDROID, TTS_OFF};
    public static final String[] TTS_TITLES = {
        "Gemini — бесплатно, живой голос (ключ Google AI Studio)",
        "Azure — бесплатно 0,5 млн знаков в месяц (ключ и регион)",
        "ElevenLabs — бесплатно 10 тыс. знаков в месяц",
        "Свой адрес, совместимый с OpenAI (/audio/speech)",
        "Голос телефона — без интернета",
        "Без голоса — только субтитры",
    };

    /** Самостоятельность: как часто Геральт думает сам, без вопроса героя (бережёт бесплатные лимиты). */
    public static final String[] AUTONOMY_TITLES = {"Редко", "Обычно", "Часто"};

    private final Store store;

    public AiSettings(Store store) {
        this.store = store;
    }

    public static AiSettings of(SharedPreferences prefs) {
        return new AiSettings(new Store() {
            @Override
            public String get(String key, String def) {
                return prefs.getString(key, def);
            }

            @Override
            public void put(String key, String value) {
                prefs.edit().putString(key, value).apply();
            }
        });
    }

    /** Для тестов. */
    public static AiSettings inMemory() {
        Map<String, String> m = new HashMap<>();
        return new AiSettings(new Store() {
            @Override
            public String get(String key, String def) {
                String v = m.get(key);
                return v == null ? def : v;
            }

            @Override
            public void put(String key, String value) {
                m.put(key, value);
            }
        });
    }

    public String get(String key) {
        return store.get(key, defaultOf(key)).trim();
    }

    public void set(String key, String value) {
        store.put(key, value == null ? "" : value.trim());
    }

    public boolean enabled() {
        return "1".equals(get("ai_enabled"));
    }

    public void setEnabled(boolean on) {
        set("ai_enabled", on ? "1" : "0");
    }

    public Role chat() {
        return new Role(get("chat_url"), get("chat_key"), get("chat_key2"), get("chat_model"));
    }

    /** Действия: пустые поля — как у разговора. */
    public Role act() {
        Role c = chat();
        String url = get("act_url");
        String key = get("act_key");
        String model = get("act_model");
        if (url.isEmpty() && key.isEmpty() && model.isEmpty()) return c;
        return new Role(url.isEmpty() ? c.url : url, key.isEmpty() ? c.key : key, get("act_key2"),
            model.isEmpty() ? c.model : model);
    }

    public String ttsProvider() {
        String p = get("tts_provider");
        for (String id : TTS_IDS) if (id.equals(p)) return p;
        return TTS_ANDROID;
    }

    /** Распознавание речи Android (иначе — только текст). */
    public boolean voiceInput() {
        return !"0".equals(get("stt_voice"));
    }

    public int autonomy() {
        try {
            return Math.max(0, Math.min(2, Integer.parseInt(get("autonomy"))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String defaultOf(String key) {
        switch (key) {
            case "chat_url": return DEFAULT_URL;
            case "chat_model": return DEFAULT_MODEL;
            case "tts_provider": return TTS_ANDROID;
            case "tts_gemini_model": return "gemini-3.8-flash-tts";
            case "tts_gemini_voice": return "Algenib";
            case "tts_azure_region": return "westeurope";
            case "tts_azure_voice": return "ru-RU-DmitryNeural";
            case "tts_eleven_voice": return "pNInz6obpgDQGcFmaJgB";
            case "tts_eleven_model": return "eleven_multilingual_v2";
            case "tts_openai_url": return "https://api.openai.com/v1";
            case "tts_openai_model": return "gpt-4o-mini-tts";
            case "tts_openai_voice": return "onyx";
            case "stt_voice": return "1";
            case "autonomy": return "1";
            default: return "";
        }
    }
}
