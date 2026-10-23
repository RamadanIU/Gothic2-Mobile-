package gothic2.mobile.og.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public class AiLogicTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void jsonRoundTrip() {
        String src = "{\"a\":1,\"b\":[true,null,\"x\\\"y\\n\"],\"c\":{\"d\":2.5,\"e\":\"Геральт \\u0438\"}}";
        Map<String, Object> m = Json.obj(Json.parse(src));
        assertEquals(1L, m.get("a"));
        assertEquals("x\"y\n", Json.arr(m.get("b")).get(2));
        assertEquals(2.5, Json.dbl(Json.obj(m.get("c")), "d", 0), 1e-9);
        assertEquals("Геральт и", Json.str(Json.obj(m.get("c")), "e"));
        Map<String, Object> back = Json.obj(Json.parse(Json.write(m)));
        assertEquals(Json.write(m), Json.write(back));
    }

    @Test(expected = IllegalArgumentException.class)
    public void jsonRejectsGarbage() {
        Json.parse("{\"a\":1,}");
    }

    @Test
    public void speechCleansRemarksAndThinking() {
        assertEquals("Ведьмак. Чего надо?",
            Speech.clean("<think>герой спрашивает...</think>Геральт: «Ведьмак. *хмурится* Чего надо?»"));
        assertEquals("Хм.", Speech.clean("(тихо) Хм."));
        // вызов инструмента текстом не озвучивается
        assertEquals("Идём.", Speech.clean("Идём. {\"name\": \"follow_hero\", \"arguments\": {}}"));
        assertEquals("Держи.", Speech.clean("Держи.\n```json\n{\"item\":\"ITMI_GOLD\"}\n```"));
        assertEquals("", Speech.clean("   "));
    }

    @Test
    public void speechSplitsIntoChunks() {
        List<String> c = Speech.chunks("Да. Пойдём. Только не отставай — тут волки.");
        assertEquals(1, c.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20; i++) sb.append("Это довольно длинное предложение номер ").append(i).append(". ");
        List<String> many = Speech.chunks(sb.toString().trim());
        assertTrue(many.size() > 1);
        for (String s : many) assertTrue(s.length() <= 400);
    }

    @Test
    public void observationDescribesSnapshot() {
        String snap = "{\"ev\":\"snapshot\",\"time\":\"14:05\",\"day\":2,\"place\":\"Пещера\",\"placeDist\":12.3,"
            + "\"waypoint\":\"NW_XARDAS_TOWER_WATERFALL_CAVE_03\",\"places\":[{\"name\":\"Башня Ксардаса\",\"dist\":60}],"
            + "\"self\":{\"hp\":900,\"hpMax\":900,\"weapon\":\"убрано\",\"items\":[{\"id\":\"ITMI_GOLD\",\"name\":\"Золото\",\"n\":250}]},"
            + "\"hero\":{\"dist\":3.2,\"side\":\"спереди\",\"hp\":40,\"hpMax\":40,\"level\":1,\"attitude\":\"дружески\",\"sees\":1},"
            + "\"npcs\":[{\"ref\":\"n3\",\"name\":\"Молодой гоблин\",\"monster\":1,\"dist\":8.1,\"side\":\"сзади\",\"hp%\":100,\"hostile\":1,\"seen\":1}],"
            + "\"ground\":[{\"ref\":\"i4\",\"name\":\"Яблоко\",\"dist\":2}],\"mobs\":[]}";
        String d = Observation.describe(Json.obj(Json.parse(snap)));
        assertTrue(d, d.contains("день 2, 14:05"));
        assertTrue(d, d.contains("Золото [ITMI_GOLD] x250"));
        assertTrue(d, d.contains("n3 «Молодой гоблин»"));
        assertTrue(d, d.contains("ВРАГ"));
        assertTrue(d, d.contains("i4 «Яблоко» 2,0 м"));
        assertTrue(d, d.contains("«Башня Ксардаса» 60 м"));
        assertTrue(Observation.describe(Json.of()).contains("неизвестна"));
    }

    @Test
    public void observationSeparatesWornGear() {
        String snap = "{\"ev\":\"snapshot\",\"time\":\"20:00\",\"day\":3,"
            + "\"self\":{\"hp\":900,\"hpMax\":900,\"str\":160,\"dex\":140,\"weapon\":\"убрано\",\"items\":["
            + "{\"id\":\"ITAR_DJG_L\",\"name\":\"Лёгкие доспехи\",\"n\":1,\"kind\":\"armor\",\"equipped\":1,\"prot\":80},"
            + "{\"id\":\"ITMW_SCHWERT5\",\"name\":\"Изысканный полуторный меч\",\"n\":1,\"kind\":\"melee\",\"equipped\":1,\"dmg\":70},"
            + "{\"id\":\"ITMW_1H_SPECIAL_01\",\"name\":\"Длинный рудный меч\",\"n\":1,\"kind\":\"melee\",\"dmg\":80},"
            + "{\"id\":\"ITAR_PAL_H\",\"name\":\"Тяжёлые доспехи\",\"n\":1,\"kind\":\"armor\",\"prot\":150,\"need\":\"силы 200\"},"
            + "{\"id\":\"ITPO_HEALTH_02\",\"name\":\"Лечебный эликсир\",\"n\":5,\"kind\":\"potion\"}]},"
            + "\"hero\":{\"dist\":2,\"side\":\"спереди\",\"hp\":40,\"hpMax\":40,\"level\":1,\"attitude\":\"дружески\",\"ranged\":\"Короткий лук\"}}";
        String d = Observation.describe(Json.obj(Json.parse(snap)));
        assertTrue(d, d.contains("сила 160, ловкость 140"));
        assertTrue(d, d.contains("Надето на тебе: Лёгкие доспехи [ITAR_DJG_L] — доспехи/одежда, защита 80; "
            + "Изысканный полуторный меч [ITMW_SCHWERT5] — одноручное оружие, урон 70."));
        assertTrue(d, d.contains("Длинный рудный меч [ITMW_1H_SPECIAL_01] — одноручное оружие, урон 80"));
        assertTrue(d, d.contains("не надеть — нужно силы 200"));
        assertTrue(d, d.contains("Лечебный эликсир [ITPO_HEALTH_02] — зелье x5"));
        assertTrue(d, d.contains("за спиной: Короткий лук"));
        // надетое - не в списке инвентаря
        assertFalse(d, d.substring(d.indexOf("Твой инвентарь")).contains("ITAR_DJG_L"));
    }

    @Test
    public void observationWithoutGear() {
        String snap = "{\"self\":{\"hp\":10,\"hpMax\":900,\"weapon\":\"кулаки\",\"items\":[]}}";
        String d = Observation.describe(Json.obj(Json.parse(snap)));
        assertTrue(d, d.contains("Надето на тебе: ничего"));
        assertFalse(d, d.contains("Твой инвентарь"));
    }

    @Test
    public void parsesToolCalls() throws IOException {
        String resp = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Идём.\",\"tool_calls\":["
            + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"follow_hero\",\"arguments\":\"{}\"}},"
            + "{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"give_item\",\"arguments\":{\"item\":\"ITMI_GOLD\",\"count\":5}}}]}}]}";
        LlmClient.Reply r = LlmClient.parse(resp);
        assertEquals("Идём.", r.text);
        assertEquals(2, r.calls.size());
        assertEquals("follow_hero", r.calls.get(0).name);
        assertEquals("ITMI_GOLD", Json.str(r.calls.get(1).args, "item"));
        assertEquals(5L, Json.num(r.calls.get(1).args, "count", 0));
        // в истории аргументы - строкой, как требует формат
        List<Object> tcs = Json.arr(r.message.get("tool_calls"));
        assertTrue(Json.obj(Json.obj(tcs.get(1)).get("function")).get("arguments") instanceof String);
    }

    @Test(expected = IOException.class)
    public void reportsApiError() throws IOException {
        LlmClient.parse("{\"error\":{\"message\":\"invalid key\"}}");
    }

    @Test
    public void fallsBackToSpareKeyOnRateLimit() throws IOException {
        List<String> keys = new ArrayList<>();
        LlmClient.Http http = (url, key, body, t) -> {
            keys.add(key);
            if (key.equals("k1")) throw new LlmClient.LlmException(429, "limit");
            return "{\"choices\":[{\"message\":{\"content\":\"Да.\"}}]}";
        };
        AiSettings s = AiSettings.inMemory();
        s.set("chat_key", "k1");
        s.set("chat_key2", "k2");
        LlmClient c = new LlmClient(s.chat(), http);
        assertEquals("Да.", c.chat(new ArrayList<>(), null, 10).text);
        assertEquals(2, keys.size());
        assertEquals("k2", keys.get(1));
    }

    @Test
    public void requestsUseOwnUserAgent() throws IOException {
        // Ollama Cloud отвечает 403 на «Dalvik/…» - заголовок Android по умолчанию. Соединение не открывается:
        // openConnection только создаёт объект запроса
        java.net.HttpURLConnection c = LlmClient.open("https://ollama.com/v1/models");
        assertEquals(LlmClient.USER_AGENT, c.getRequestProperty("User-Agent"));
        assertFalse(LlmClient.USER_AGENT.contains("Dalvik"));
    }

    @Test
    public void htmlErrorPageIsShortened() {
        String e = LlmClient.shortError(403, "<!doctype html><meta charset=\"utf-8\"><title>403</title>403 Forbidden");
        assertEquals("HTTP 403 — доступ запрещён: 403 Forbidden", e);
        assertEquals("HTTP 401 — ключ не подходит: Unauthorized",
            LlmClient.shortError(401, "{\"error\":{\"message\":\"Unauthorized\"}}"));
    }

    @Test
    public void endpointJoinsPaths() {
        assertEquals("https://ollama.com/v1/chat/completions", LlmClient.endpoint("https://ollama.com/v1/", "chat/completions"));
        assertEquals("https://x/v1/chat/completions", LlmClient.endpoint("https://x/v1/chat/completions", "chat/completions"));
    }

    @Test
    public void toolsMapToEngineActions() {
        Map<String, Object> c = Tools.engineAct("A1", "weapon", Json.of("draw", false));
        assertEquals("holster_weapon", c.get("name"));
        c = Tools.engineAct("A2", "look_at", Json.of("target", "n5"));
        assertEquals("turn_to", c.get("name"));
        assertEquals("n5", Json.str(Json.obj(c.get("args")), "target"));
        assertNull(Tools.engineAct("A3", "remember", Json.of("note", "x")));
        c = Tools.engineAct("A4", "equip_item", Json.of("item", "ITMW_1H_SPECIAL_01"));
        assertEquals("equip", c.get("name"));
        assertEquals("ITMW_1H_SPECIAL_01", Json.str(Json.obj(c.get("args")), "item"));
        assertEquals("unequip", Tools.engineAct("A5", "unequip_item", Json.of("item", "ITAR_DJG_L")).get("name"));
        // все инструменты - корректный JSON с именем и параметрами
        for (Map<String, Object> t : Tools.all()) {
            Map<String, Object> fn = Json.obj(t.get("function"));
            assertFalse(Json.str(fn, "name").isEmpty());
            assertEquals("object", Json.str(Json.obj(fn.get("parameters")), "type"));
        }
    }

    @Test
    public void memoryFollowsSaveTokens() throws IOException {
        File dir = tmp.newFolder("mem");
        Memory m = new Memory(dir);
        m.load(7, 0);
        m.add("[д1 10:00] Герой: «Привет»");
        m.note("Герой обещал 100 золотых");
        m.save(7, 111);
        m.add("[д1 11:00] Ты: «Позже»");
        m.save(7, 222);

        Memory other = new Memory(dir);
        other.load(7, 111);
        assertEquals(1, other.journal.size());
        assertEquals(1, other.notes.size());
        other.load(7, 222);
        assertEquals(2, other.journal.size());
        other.load(7, 999);
        assertTrue(other.journal.isEmpty());
        assertTrue(other.context(10).isEmpty());
    }

    @Test
    public void personaKnowsTavernAndGear() {
        String p = Persona.system();
        assertTrue(p.contains("кабаке Корагона"));
        assertTrue(p.contains("equip_item"));
        // не выдумывает слов героя: прежде отвечал, будто герой уже что-то сказал
        assertTrue(p.contains("Не выдумывай за него слов"));
    }

    @Test
    public void memorySummaryDropsOldEntries() {
        Memory m = new Memory(new File(tmp.getRoot(), "x"));
        for (int i = 0; i < Memory.JOURNAL_MAX + 5; i++) m.add("запись " + i);
        assertTrue(m.needsSummary());
        List<String> old = m.oldEntries();
        m.applySummary("Кратко.", old.size());
        assertEquals(Memory.JOURNAL_KEEP, m.journal.size());
        assertTrue(m.context(5).startsWith("Что ты помнишь: Кратко."));
    }

    @Test
    public void wavIsDecoded() throws IOException {
        short[] s = {0, 1000, -1000, 32767};
        byte[] wav = wav(s, 24000, 1);
        Pcm p = Pcm.fromWav(wav);
        assertEquals(24000, p.rate);
        assertEquals(4, p.samples.length);
        assertEquals(-1000, p.samples[2]);
        Pcm st = Pcm.fromWav(wav(new short[]{100, 300, -200, -400}, 16000, 2));
        assertEquals(2, st.samples.length);
        assertEquals(200, st.samples[0]);
    }

    @Test
    public void geminiAudioIsExtracted() throws IOException {
        byte[] w = wav(new short[]{1, 2, 3}, 24000, 1);
        String resp = "{\"steps\":[{\"type\":\"thought\"},{\"type\":\"model_output\",\"content\":[{\"type\":\"audio\",\"data\":\""
            + Base64.getEncoder().encodeToString(w) + "\"}]}]}";
        Pcm p = Tts.Gemini.parse(resp);
        assertEquals(3, p.samples.length);
    }

    @Test
    public void settingsDefaultsAndRoles() {
        AiSettings s = AiSettings.inMemory();
        assertFalse(s.enabled());
        assertEquals(AiSettings.DEFAULT_URL, s.chat().url);
        assertEquals(AiSettings.TTS_ANDROID, s.ttsProvider());
        // действия без своих полей - как разговор; со своим ключом - свой
        assertEquals(s.chat().model, s.act().model);
        s.set("chat_key", "a");
        s.set("act_key", "b");
        assertEquals("b", s.act().key);
        assertEquals(AiSettings.DEFAULT_URL, s.act().url);
        s.set("autonomy", "9");
        assertEquals(2, s.autonomy());
    }

    @Test
    public void sameNpcGetsSameVoice() {
        assertEquals(Tts.pick(Tts.Gemini.MALE, 7), Tts.pick(Tts.Gemini.MALE, 7));
    }

    private static byte[] wav(short[] samples, int rate, int ch) {
        int data = samples.length * 2;
        byte[] b = new byte[44 + data];
        put(b, 0, "RIFF");
        le32(b, 4, 36 + data);
        put(b, 8, "WAVE");
        put(b, 12, "fmt ");
        le32(b, 16, 16);
        le16(b, 20, 1);
        le16(b, 22, ch);
        le32(b, 24, rate);
        le32(b, 28, rate * ch * 2);
        le16(b, 32, ch * 2);
        le16(b, 34, 16);
        put(b, 36, "data");
        le32(b, 40, data);
        for (int i = 0; i < samples.length; i++) le16(b, 44 + 2 * i, samples[i]);
        return b;
    }

    private static void put(byte[] b, int at, String s) {
        for (int i = 0; i < 4; i++) b[at + i] = (byte) s.charAt(i);
    }

    private static void le16(byte[] b, int at, int v) {
        b[at] = (byte) v;
        b[at + 1] = (byte) (v >> 8);
    }

    private static void le32(byte[] b, int at, int v) {
        le16(b, at, v);
        le16(b, at + 2, v >> 16);
    }
}
