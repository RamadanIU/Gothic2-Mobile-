package gothic2.mobile.og.ai;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Speechify (api.speechify.ai, docs.speechify.ai): модели Simba, голоса из каталога и клоны своего аккаунта.
 * Модель и голоса выбираются в настройках из списков API (/v1/audio/models, /v1/voices); реплика — POST
 * /v1/audio/speech с языком ru-RU, ответ — WAV в base64. Жители — голоса из списков «мужчины»/«женщины» по
 * номеру голоса из скриптов, плюс своя высота и темп (SSML prosody), чтобы один голос звучал по-разному.
 */
public final class Speechify implements Tts {
    static final String BASE = "https://api.speechify.ai/v1/";
    /** Версия формата API (заголовок Speechify-Version): ответы не меняются, пока она та же. */
    static final String API_VERSION = "2026-09-30";
    static final String LANGUAGE = "ru-RU";

    /** Модель из /v1/audio/models — для выбора в настройках. */
    public static final class Model {
        public final String id;
        public final String name;
        public final boolean recommended;
        public final boolean deprecated;
        public final boolean russian;

        Model(String id, String name, boolean recommended, boolean deprecated, boolean russian) {
            this.id = id;
            this.name = name;
            this.recommended = recommended;
            this.deprecated = deprecated;
            this.russian = russian;
        }

        /** Строка списка: имя и пометки. */
        public String title() {
            StringBuilder sb = new StringBuilder(name.isEmpty() ? id : name);
            if (recommended) sb.append(" — рекомендуется");
            if (!russian) sb.append(" (без русского)");
            if (deprecated) sb.append(" (устаревшая)");
            return sb.toString();
        }
    }

    /** Голос из /v1/voices. */
    public static final class VoiceInfo {
        public final String id;
        public final String name;
        public final String gender;
        public final String locale;
        /** Образец (ссылка на звук): по-русски, если есть, иначе общий; пусто — нет образца. */
        public final String preview;
        /** Место среди рекомендованных для своего языка (1 — первый); 0 — не рекомендован. */
        public final int rank;
        public final boolean personal;

        VoiceInfo(String id, String name, String gender, String locale, String preview, int rank, boolean personal) {
            this.id = id;
            this.name = name;
            this.gender = gender;
            this.locale = locale;
            this.preview = preview;
            this.rank = rank;
            this.personal = personal;
        }

        public boolean russian() {
            return locale.toLowerCase(java.util.Locale.ROOT).startsWith("ru");
        }

        /** Строка списка: «Vladimir — муж., ru-RU, ★1». */
        public String title() {
            String g = "male".equals(gender) ? "муж." : "female".equals(gender) ? "жен." : "";
            StringBuilder sb = new StringBuilder(name.isEmpty() ? id : name).append(" —");
            if (!g.isEmpty()) sb.append(' ').append(g).append(',');
            sb.append(' ').append(locale.isEmpty() ? "?" : locale);
            if (rank > 0) sb.append(", ★").append(rank);
            if (personal) sb.append(", свой");
            return sb.toString();
        }
    }

    /** Страница списка голосов. */
    static final class VoicePage {
        final List<VoiceInfo> voices;
        final String next;

        VoicePage(List<VoiceInfo> voices, String next) {
            this.voices = voices;
            this.next = next;
        }
    }

    private final String key;
    private final String model;
    private final String geraltVoice;
    private final String heroVoice;
    private final String[] male;
    private final String[] female;

    Speechify(AiSettings s) {
        key = s.get("tts_speechify_key");
        // Пустое поле — значение по умолчанию: без модели Speechify берёт simba-3.0, а в ней нет русского
        model = orDefault(s.get("tts_speechify_model"), "tts_speechify_model");
        geraltVoice = orDefault(s.get("tts_speechify_voice"), "tts_speechify_voice");
        heroVoice = orDefault(s.get("tts_speechify_hero"), "tts_speechify_hero");
        male = ids(s.get("tts_speechify_male"), geraltVoice);
        female = ids(s.get("tts_speechify_female"), geraltVoice);
    }

    @Override
    public Pcm synth(String text, Voice v) throws IOException {
        if (key.isEmpty()) throw new IOException("нет ключа Speechify");
        String voice = voiceFor(v);
        Map<String, Object> req = request(text, voice, model, v);
        byte[] body = Json.write(req).getBytes(StandardCharsets.UTF_8);
        byte[] resp;
        try {
            resp = Tts.post(BASE + "audio/speech", body, "application/json", headers());
        } catch (LlmClient.LlmException e) {
            // Лимит запросов (бесплатно — 1 в секунду) и сбой сервиса: одна пауза и повтор
            if (!retryable(e.status)) throw e;
            sleep(1500);
            resp = Tts.post(BASE + "audio/speech", body, "application/json", headers());
        }
        return parse(new String(resp, StandardCharsets.UTF_8));
    }

    String voiceFor(Voice v) {
        if (v.geralt) return geraltVoice;
        if (v.hero) return heroVoice;
        return Tts.pick(v.female ? female : male, v.gameVoice);
    }

    /** Тело запроса: реплика в SSML, жители — своя высота и темп. */
    static Map<String, Object> request(String text, String voice, String model, Voice v) {
        String ssml = "<speak>" + prosody(Tts.xml(text), v) + "</speak>";
        Map<String, Object> req = Json.of("input", ssml, "voice_id", voice, "language", LANGUAGE,
            "output_format", "wav_24000",
            "options", Json.of("loudness_normalization", true, "text_normalization", true));
        if (!model.isEmpty()) req.put("model", model);
        return req;
    }

    static String prosody(String escaped, Voice v) {
        if (v.geralt || v.hero) return escaped;
        // Один голос на многих жителей: высота и темп по номеру голоса из скриптов — каждый звучит по-своему
        int pitch = Math.floorMod(v.gameVoice * 5, 17) - 8;
        int rate = Math.floorMod(v.gameVoice * 3, 9) - 4;
        if (pitch == 0 && rate == 0) return escaped;
        return "<prosody pitch=\"" + signed(pitch) + "%\" rate=\"" + signed(rate) + "%\">" + escaped + "</prosody>";
    }

    /** Звук из ответа: audio_data — WAV (или сырые 16 бит при другом формате) в base64. */
    static Pcm parse(String json) throws IOException {
        Map<String, Object> root;
        try {
            root = Json.obj(Json.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IOException("ответ Speechify не JSON");
        }
        String data = Json.str(root, "audio_data");
        if (data.isEmpty()) throw new IOException("в ответе Speechify нет звука");
        byte[] audio;
        try {
            audio = Base64.getMimeDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new IOException("звук Speechify не читается");
        }
        if (audio.length > 4 && audio[0] == 'R' && audio[1] == 'I') return Pcm.fromWav(audio);
        String fmt = Json.str(root, "audio_format");
        if (!fmt.isEmpty() && !"pcm".equals(fmt) && !"wav".equals(fmt)) throw new IOException("Speechify прислал " + fmt + ", а не WAV");
        return Pcm.fromRaw(audio, 24000);
    }

    // ---------- списки для настроек ----------

    /** Модели аккаунта: сначала рекомендованная, затем с русским. */
    public static List<Model> models(String key) throws IOException {
        return parseModels(get(BASE + "audio/models", key));
    }

    static List<Model> parseModels(String json) throws IOException {
        List<Model> out = new ArrayList<>();
        Map<String, Object> root = parseObj(json);
        for (Object o : Json.arr(root.get("models"))) {
            Map<String, Object> m = Json.obj(o);
            String id = Json.str(m, "id");
            if (id.isEmpty()) continue;
            // Модели не для /v1/audio/speech (только поток и т. п.) озвучку не дадут
            List<Object> endpoints = Json.arr(m.get("endpoints"));
            if (!endpoints.isEmpty() && !endpoints.contains("/v1/audio/speech")) continue;
            boolean ru = false;
            for (Object l : Json.arr(m.get("languages"))) {
                if (String.valueOf(l).toLowerCase(java.util.Locale.ROOT).startsWith("ru")) ru = true;
            }
            out.add(new Model(id, Json.str(m, "name"), Json.bool(m, "recommended", false), Json.bool(m, "deprecated", false), ru));
        }
        Collections.sort(out, (a, b) -> Integer.compare(order(a), order(b)));
        return out;
    }

    private static int order(Model m) {
        return (m.recommended ? 0 : 2) + (m.russian ? 0 : 4) + (m.deprecated ? 8 : 0);
    }

    /**
     * Голоса для модели: только русские (locale ru) или все; пол — male/female или пусто. Список идёт
     * страницами (до 200), следующая — по next_cursor, пока has_more.
     */
    public static List<VoiceInfo> voices(String key, String model, boolean russianOnly, String gender) throws IOException {
        List<VoiceInfo> out = new ArrayList<>();
        String cursor = "";
        for (int page = 0; page < 20; page++) {
            StringBuilder url = new StringBuilder(BASE).append("voices?limit=200");
            if (russianOnly) url.append("&locale=ru");
            if (!gender.isEmpty()) url.append("&gender=").append(gender);
            if (!model.isEmpty()) url.append("&model=").append(enc(model));
            if (!cursor.isEmpty()) url.append("&cursor=").append(enc(cursor));
            VoicePage p = parseVoices(get(url.toString(), key), model);
            out.addAll(p.voices);
            if (p.next.isEmpty()) break;
            cursor = p.next;
        }
        sortVoices(out);
        return out;
    }

    static VoicePage parseVoices(String json, String model) throws IOException {
        Map<String, Object> root = parseObj(json);
        List<VoiceInfo> out = new ArrayList<>();
        for (Object o : Json.arr(root.get("voices"))) {
            Map<String, Object> v = Json.obj(o);
            String id = Json.str(v, "id");
            if (id.isEmpty()) continue;
            out.add(new VoiceInfo(id, Json.str(v, "display_name"), Json.str(v, "gender"), Json.str(v, "locale"),
                preview(v, model), (int) Json.num(v, "staff_pick_rank", 0), "personal".equals(Json.str(v, "type"))));
        }
        String next = Json.bool(root, "has_more", false) ? Json.str(root, "next_cursor") : "";
        return new VoicePage(out, next);
    }

    /** Образец по-русски для выбранной модели, иначе любой русский, иначе общий образец голоса. */
    static String preview(Map<String, Object> v, String model) {
        String any = "";
        for (Object mo : Json.arr(v.get("models"))) {
            Map<String, Object> m = Json.obj(mo);
            for (Object lo : Json.arr(m.get("languages"))) {
                Map<String, Object> l = Json.obj(lo);
                String url = Json.str(l, "preview_audio");
                if (url.isEmpty() || !Json.str(l, "locale").toLowerCase(java.util.Locale.ROOT).startsWith("ru")) continue;
                if (model.equals(Json.str(m, "name"))) return url;
                if (any.isEmpty()) any = url;
            }
        }
        return any.isEmpty() ? Json.str(v, "preview_audio") : any;
    }

    /** Русские — вперёд; среди них рекомендованные по месту; дальше по имени. */
    static void sortVoices(List<VoiceInfo> list) {
        Collections.sort(list, (a, b) -> {
            if (a.russian() != b.russian()) return a.russian() ? -1 : 1;
            if (a.personal != b.personal) return a.personal ? -1 : 1;
            int ra = a.rank > 0 ? a.rank : Integer.MAX_VALUE;
            int rb = b.rank > 0 ? b.rank : Integer.MAX_VALUE;
            if (ra != rb) return Integer.compare(ra, rb);
            return a.name.compareToIgnoreCase(b.name);
        });
    }

    // ---------- общее ----------

    /** Список id через запятую; пусто — голос Геральта. */
    static String[] ids(String list, String fallback) {
        List<String> out = new ArrayList<>();
        for (String s : list.split(",")) {
            if (!s.trim().isEmpty()) out.add(s.trim());
        }
        if (out.isEmpty()) out.add(fallback);
        return out.toArray(new String[0]);
    }

    private static String orDefault(String v, String key) {
        return v.isEmpty() ? AiSettings.defaultOf(key) : v;
    }

    static boolean retryable(int status) {
        return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    private String[] headers() {
        return headers(key);
    }

    private static String[] headers(String key) {
        return new String[]{"Authorization", "Bearer " + key, "Speechify-Version", API_VERSION,
            "Speechify-Caller", "gothic2-mobile", "Accept", "application/json"};
    }

    private static String get(String url, String key) throws IOException {
        if (key.isEmpty()) throw new IOException("сначала вставь ключ Speechify");
        HttpURLConnection c = LlmClient.open(url);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        String[] h = headers(key);
        for (int i = 0; i + 1 < h.length; i += 2) c.setRequestProperty(h[i], h[i + 1]);
        int code = c.getResponseCode();
        String body = LlmClient.read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code < 200 || code >= 300) throw new LlmClient.LlmException(code, LlmClient.shortError(code, body));
        return body;
    }

    private static Map<String, Object> parseObj(String json) throws IOException {
        try {
            return Json.obj(Json.parse(json));
        } catch (IllegalArgumentException e) {
            throw new IOException("ответ Speechify не JSON");
        }
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
