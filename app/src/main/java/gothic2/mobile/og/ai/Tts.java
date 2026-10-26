package gothic2.mobile.og.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Озвучка реплик. Голос выбирается по говорящему: Геральт — свой низкий голос, другие NPC — по полу и
 * номеру голоса из скриптов игры (каждый житель звучит одинаково от разговора к разговору).
 */
public interface Tts {
    /** Кто говорит и как. */
    final class Voice {
        public final boolean geralt;
        public final boolean female;
        public final int gameVoice;
        public final String mood;

        public Voice(boolean geralt, boolean female, int gameVoice, String mood) {
            this.geralt = geralt;
            this.female = female;
            this.gameVoice = gameVoice;
            this.mood = mood == null ? "" : mood;
        }

        public static Voice geralt(String mood) {
            return new Voice(true, false, 0, mood);
        }
    }

    Pcm synth(String text, Voice voice) throws IOException;

    /** Голос по настройкам; null — без голоса (только субтитры). */
    static Tts create(AiSettings s, android.content.Context ctx) {
        switch (s.ttsProvider()) {
            case AiSettings.TTS_GEMINI: return new Gemini(s);
            case AiSettings.TTS_SPEECHIFY: return new Speechify(s);
            case AiSettings.TTS_AZURE: return new Azure(s);
            case AiSettings.TTS_ELEVENLABS: return new ElevenLabs(s);
            case AiSettings.TTS_OPENAI: return new OpenAi(s);
            case AiSettings.TTS_OFF: return null;
            default: return new AndroidTts(ctx);
        }
    }

    /** Стиль подачи для моделей, которые понимают описание голоса. */
    static String style(Voice v) {
        String base = v.geralt
            ? "Низкий, хрипловатый, спокойный мужской голос; говорит сдержанно, сухо, с лёгкой мрачной иронией"
            : (v.female ? "Простая женщина из средневековой деревни" : "Грубоватый мужчина из средневековой деревни");
        switch (v.mood) {
            case "angry": return base + "; раздражённо, жёстко";
            case "hostile": return base + "; угрожающе, холодно";
            case "friendly": return base + "; чуть теплее обычного";
            case "afraid": return base + "; встревоженно";
            default: return base;
        }
    }

    /** Номер в списке по голосу из скриптов: один и тот же житель — один и тот же голос. */
    static <T> T pick(T[] list, int gameVoice) {
        return list[Math.floorMod(gameVoice * 7 + 3, list.length)];
    }

    // ---------- Gemini TTS (бесплатный уровень Google AI Studio) ----------

    final class Gemini implements Tts {
        static final String[] MALE = {"Charon", "Fenrir", "Orus", "Iapetus", "Rasalgethi", "Alnilam", "Schedar",
            "Achird", "Zubenelgenubi", "Sadaltager", "Umbriel", "Enceladus"};
        static final String[] FEMALE = {"Kore", "Leda", "Aoede", "Despina", "Erinome", "Gacrux", "Sulafat", "Vindemiatrix"};
        private final String key;
        private final String model;
        private final String geraltVoice;

        Gemini(AiSettings s) {
            key = s.get("tts_gemini_key");
            model = s.get("tts_gemini_model");
            geraltVoice = s.get("tts_gemini_voice");
        }

        @Override
        public Pcm synth(String text, Voice v) throws IOException {
            String voice = v.geralt ? geraltVoice : pick(v.female ? FEMALE : MALE, v.gameVoice);
            Map<String, Object> content = Json.of("type", "text", "text", text,
                "annotations", list(Json.of("type", "speech_metadata", "style", style(v))));
            Map<String, Object> req = Json.of("model", model,
                "input", list(Json.of("type", "user_input", "content", list(content))),
                "response_format", Json.of("type", "audio"),
                "generation_config", Json.of("speech_config", list(Json.of("voice", voice))));
            byte[] resp = post("https://generativelanguage.googleapis.com/v1beta/interactions",
                Json.write(req).getBytes(StandardCharsets.UTF_8), "application/json",
                new String[]{"x-goog-api-key", key});
            return parse(new String(resp, StandardCharsets.UTF_8));
        }

        /** Последний звук из шагов ответа: base64 WAV 24 кГц. */
        static Pcm parse(String json) throws IOException {
            Map<String, Object> root;
            try {
                root = Json.obj(Json.parse(json));
            } catch (IllegalArgumentException e) {
                throw new IOException("ответ озвучки не JSON");
            }
            String data = null;
            for (Object st : Json.arr(root.get("steps"))) {
                Map<String, Object> step = Json.obj(st);
                for (Object c : Json.arr(step.get("content"))) {
                    Map<String, Object> part = Json.obj(c);
                    if ("audio".equals(Json.str(part, "type")) && !Json.str(part, "data").isEmpty()) {
                        data = Json.str(part, "data");
                    }
                }
            }
            if (data == null) throw new IOException("в ответе озвучки нет звука");
            byte[] audio = Base64.getDecoder().decode(data.replace("\n", ""));
            if (audio.length > 4 && audio[0] == 'R' && audio[1] == 'I') return Pcm.fromWav(audio);
            return Pcm.fromRaw(audio, 24000);
        }
    }

    // ---------- Azure AI Speech (бесплатный уровень F0) ----------

    final class Azure implements Tts {
        static final String[] MALE = {"ru-RU-DmitryNeural"};
        static final String[] FEMALE = {"ru-RU-SvetlanaNeural", "ru-RU-DariyaNeural"};
        private final String key;
        private final String region;
        private final String geraltVoice;

        Azure(AiSettings s) {
            key = s.get("tts_azure_key");
            region = s.get("tts_azure_region");
            geraltVoice = s.get("tts_azure_voice");
        }

        @Override
        public Pcm synth(String text, Voice v) throws IOException {
            String voice = v.geralt ? geraltVoice : pick(v.female ? FEMALE : MALE, v.gameVoice);
            // Разные жители одним голосом Дмитрия - разной высотой и темпом
            int pitch = v.geralt ? -12 : (Math.floorMod(v.gameVoice * 5, 21) - 10);
            int rate = v.geralt ? -6 : (Math.floorMod(v.gameVoice * 3, 11) - 5);
            String ssml = "<speak version='1.0' xml:lang='ru-RU'><voice name='" + voice + "'>"
                + "<prosody pitch='" + pitch + "%' rate='" + rate + "%'>" + xml(text) + "</prosody></voice></speak>";
            byte[] resp = post("https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1",
                ssml.getBytes(StandardCharsets.UTF_8), "application/ssml+xml",
                new String[]{"Ocp-Apim-Subscription-Key", key,
                    "X-Microsoft-OutputFormat", "riff-24khz-16bit-mono-pcm"});
            return Pcm.fromWav(resp);
        }
    }

    // ---------- ElevenLabs ----------

    final class ElevenLabs implements Tts {
        private final String key;
        private final String geraltVoice;
        private final String model;

        ElevenLabs(AiSettings s) {
            key = s.get("tts_eleven_key");
            geraltVoice = s.get("tts_eleven_voice");
            model = s.get("tts_eleven_model");
        }

        @Override
        public Pcm synth(String text, Voice v) throws IOException {
            // Другим NPC - тот же голос: на бесплатном уровне своих голосов мало, высота не меняется
            Map<String, Object> req = Json.of("text", text, "model_id", model,
                "voice_settings", Json.of("stability", v.geralt ? 0.55 : 0.4, "similarity_boost", 0.75));
            byte[] resp = post("https://api.elevenlabs.io/v1/text-to-speech/" + geraltVoice + "?output_format=pcm_24000",
                Json.write(req).getBytes(StandardCharsets.UTF_8), "application/json", new String[]{"xi-api-key", key});
            return Pcm.fromRaw(resp, 24000);
        }
    }

    // ---------- Свой адрес, совместимый с OpenAI (/audio/speech) ----------

    final class OpenAi implements Tts {
        static final String[] MALE = {"echo", "fable", "ash", "verse"};
        static final String[] FEMALE = {"nova", "shimmer", "coral", "sage"};
        private final String url;
        private final String key;
        private final String model;
        private final String geraltVoice;

        OpenAi(AiSettings s) {
            url = s.get("tts_openai_url");
            key = s.get("tts_openai_key");
            model = s.get("tts_openai_model");
            geraltVoice = s.get("tts_openai_voice");
        }

        @Override
        public Pcm synth(String text, Voice v) throws IOException {
            String voice = v.geralt ? geraltVoice : pick(v.female ? FEMALE : MALE, v.gameVoice);
            Map<String, Object> req = Json.of("model", model, "input", text, "voice", voice,
                "response_format", "wav", "instructions", style(v));
            byte[] resp = post(LlmClient.endpoint(url, "audio/speech"), Json.write(req).getBytes(StandardCharsets.UTF_8),
                "application/json", key.isEmpty() ? new String[0] : new String[]{"Authorization", "Bearer " + key});
            return Pcm.fromWav(resp);
        }
    }

    // ---------- общее ----------

    static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>();
        for (Object o : items) l.add(o);
        return l;
    }

    static String xml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;");
    }

    static byte[] post(String url, byte[] body, String type, String[] headers) throws IOException {
        HttpURLConnection c = LlmClient.open(url);
        // Озвучка, которая не пришла за 20 с, уже не нужна: реплика идёт субтитром (GeraltBrain.speak)
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", type);
        for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        if (in != null) {
            try (InputStream is = in) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = is.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 32 * 1024 * 1024) throw new IOException("слишком большой ответ озвучки");
                }
            }
        }
        if (code < 200 || code >= 300) {
            throw new LlmClient.LlmException(code, LlmClient.shortError(code, bo.toString("UTF-8")));
        }
        return bo.toByteArray();
    }
}
