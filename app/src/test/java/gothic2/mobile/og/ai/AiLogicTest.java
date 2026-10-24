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
            // hostile - злость, а не вражда насмерть: модель не должна ждать, что драка с человеком его убьёт
            if ("set_attitude".equals(Json.str(fn, "name")))
                assertTrue(Json.str(fn, "description").contains("не насмерть"));
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
    public void npcsAreNotRudeAndKnowWhoTheyAre() {
        String p = Persona.npcReply("Корагон", "Горожанин", false, NpcLore.of("vlk_420_coragon"),
            java.util.Arrays.asList("Что ты продаёшь?"), java.util.Arrays.asList("Пиво или вино?"));
        assertFalse(p, p.contains("грубовато"));
        assertTrue(p, p.contains("Хозяин кабака"));
        assertTrue(p, p.contains("Что ты продаёшь?"));
        assertTrue(p, p.contains("- Пиво или вино?"));
        assertTrue(p, p.contains("Не обрывай разговор"));
        assertTrue(p, p.contains(NpcLore.WORLD));
        // нет в справочнике - без раздела «Кто ты»
        assertFalse(Persona.npcReply("Горожанин", "", false, NpcLore.of("VLK_450_BUERGER"),
            new ArrayList<>(), new ArrayList<>()).contains("Кто ты:"));
    }

    @Test
    public void geraltCannotEndDialogHimself() {
        for (Map<String, Object> t : Tools.all()) {
            assertFalse("end_conversation".equals(Json.str(Json.obj(t.get("function")), "name")));
        }
        assertNull(Tools.engineAct("A1", "end_conversation", Json.of()));
        assertTrue(Persona.system().contains("Разговор заканчивает герой"));
    }

    @Test
    public void residentHasToolsRulesAndOwnLife() {
        String p = Persona.resident("Корагон", "Горожанин", false, NpcLore.of("VLK_420_CORAGON"),
            new ArrayList<>(), new ArrayList<>());
        assertTrue(p, p.contains("Хозяин кабака"));
        assertTrue(p, p.contains("go_home"));
        assertTrue(p, p.contains("give_item"));
        assertTrue(p, p.contains("self — это ты"));
        assertTrue(p, p.contains("Не обрывай разговор"));
        // живой житель - не помощник и не ИИ
        assertTrue(p, p.contains("а не помощник и не ИИ"));
        Map<String, Object> c = Tools.engineAct("A9", "go_home", Json.of());
        assertEquals("go_home", c.get("name"));
    }

    @Test
    public void gesturesOnlyWithWords() throws IOException {
        // жест, взгляд, занятие - только вместе со словами; действия - без ограничений
        assertTrue(Tools.needsWords("gesture"));
        assertTrue(Tools.needsWords("look_at"));
        assertTrue(Tools.needsWords("activity"));
        assertFalse(Tools.needsWords("give_item"));
        assertFalse(Tools.needsWords("follow_hero"));
        assertFalse(Persona.system().contains("Можно промолчать"));
        assertTrue(Persona.system().contains("только вместе со словами"));
        String r = Persona.resident("Регис", "", false, "", new ArrayList<>(), new ArrayList<>());
        assertFalse(r, r.contains("Можно промолчать"));
        assertTrue(r, r.contains("отвечай ему словами всегда"));
        // повтор «только словами»: инструменты описаны, но вызывать их нельзя
        List<String> bodies = new ArrayList<>();
        LlmClient c = new LlmClient(AiSettings.inMemory().chat(), (url, key, body, t) -> {
            bodies.add(body);
            return "{\"choices\":[{\"message\":{\"content\":\"Да.\"}}]}";
        });
        c.chat(new ArrayList<>(), Tools.all(), 10, true);
        c.chat(new ArrayList<>(), Tools.all(), 10);
        assertTrue(bodies.get(0), bodies.get(0).contains("\"tool_choice\":\"none\""));
        assertFalse(bodies.get(1), bodies.get(1).contains("tool_choice"));
    }

    @Test
    public void observationShowsSquadState() {
        String waiting = Observation.describe(Json.obj(Json.parse(
            "{\"self\":{\"hp\":1,\"hpMax\":1,\"weapon\":\"убрано\",\"inParty\":1,\"waiting\":1,\"items\":[]}}")));
        assertTrue(waiting, waiting.contains("ждёшь героя здесь (ты в его отряде)"));
        assertFalse(waiting, waiting.contains("идёшь с героем"));
        String following = Observation.describe(Json.obj(Json.parse(
            "{\"self\":{\"hp\":1,\"hpMax\":1,\"weapon\":\"убрано\",\"inParty\":1,\"items\":[]}}")));
        assertTrue(following, following.contains("идёшь с героем (в его отряде)"));
    }

    @Test
    public void npcsSeeWhoTalksToThem() {
        // как говорящий выглядит для слушающего (describe из движка)
        Map<String, Object> d = Json.obj(Json.parse("{\"ev\":\"describe\",\"id\":\"VLK_420_CORAGON\",\"name\":\"Корагон\","
            + "\"guild\":\"Горожанин\",\"armor\":\"Одежда горожанина\",\"melee\":\"Дубинка\",\"drawn\":1,\"level\":7,"
            + "\"hp%\":40,\"myAtt\":\"сердито\",\"party\":1}"));
        String sp = Observation.speaker(d, "Корагон", NpcLore.reputation("VLK_420_CORAGON"));
        assertTrue(sp, sp.startsWith("Корагон — гильдия: Горожанин, мужчина; одежда: Одежда горожанина, оружие: Дубинка"));
        assertTrue(sp, sp.contains("(оружие обнажено!)"));
        assertTrue(sp, sp.contains("уровень 7"));
        assertTrue(sp, sp.contains("Ранен: здоровье 40%"));
        assertTrue(sp, sp.contains("Ты относишься к нему: сердито"));
        assertTrue(sp, sp.contains("Идёт с героем"));
        assertTrue(sp, sp.contains("Что о нём известно в округе: Хозяин кабака в нижнем городе."));
        // герой - из снимка обстановки слушающего
        String hero = Observation.speaker(Json.obj(Json.parse("{\"guild\":\"Наёмник\",\"armor\":\"Лёгкие доспехи наёмника\","
            + "\"weaponDrawn\":0,\"hp\":40,\"hpMax\":40,\"level\":5,\"attitude\":\"нейтрально\"}")), "Герой", "");
        assertTrue(hero, hero.contains("гильдия: Наёмник"));
        assertTrue(hero, hero.contains("одежда: Лёгкие доспехи наёмника"));
        assertFalse(hero, hero.contains("обнажено"));
        // в списке «Рядом» - одежда и оружие каждого человека
        String obs = Observation.describe(Json.obj(Json.parse("{\"npcs\":[{\"ref\":\"n2\",\"name\":\"Бандит\","
            + "\"guild\":\"Бандит\",\"armor\":\"Доспехи бандита\",\"melee\":\"Ржавый меч\",\"att\":\"враждебно\",\"dist\":5}]}")));
        assertTrue(obs, obs.contains("одежда: Доспехи бандита, оружие: Ржавый меч"));
        assertTrue(obs, obs.contains("к тебе: враждебно"));
        // правило: отвечать с оглядкой на того, кто говорит
        String p = Persona.npcReply("Регис", "", false, "", new ArrayList<>(), new ArrayList<>(), sp);
        assertTrue(p, p.contains("Кто с тобой говорит: Корагон"));
        assertTrue(p, p.contains("бандиту — недоверие"));
    }

    @Test
    public void toolCallsWrittenAsTextAreExecutedAndHidden() throws IOException {
        // как на скриншоте пользователя: теги вместо function calling
        String text = "<p>Наконец-то! Получай, грязный наемник!</p> <weapon draw=\"true\"/> <attack target=\"n5\"/>";
        assertEquals("Наконец-то! Получай, грязный наемник!", Speech.clean(text));
        java.util.Set<String> known = LlmClient.toolNames(Tools.all());
        List<LlmClient.ToolCall> calls = TextCalls.extract(text, known);
        assertEquals(2, calls.size());
        assertEquals("weapon", calls.get(0).name);
        assertEquals(Boolean.TRUE, calls.get(0).args.get("draw"));
        assertEquals("attack", calls.get(1).name);
        assertEquals("n5", Json.str(calls.get(1).args, "target"));
        // JSON, вызов со скобками, тег с JSON внутри; неизвестные имена и <p> - не вызовы
        calls = TextCalls.extract("Идём. {\"name\":\"follow_hero\",\"arguments\":{}} give_item(item=\"ITMI_GOLD\", count=5) "
            + "<look_at>{\"target\":\"hero\"}</look_at> <dance/> fly()", known);
        assertEquals(3, calls.size());
        assertEquals("follow_hero", calls.get(0).name);
        assertEquals("give_item", calls.get(1).name);
        assertEquals(5L, Json.num(calls.get(1).args, "count", 0));
        assertEquals("look_at", calls.get(2).name);
        assertEquals("hero", Json.str(calls.get(2).args, "target"));
        assertEquals("Идём.", Speech.clean("Идём. follow_hero() wait_here"));
        // через клиент: вызовы текстом становятся настоящими, история - с tool_calls; «только словами» - не исполнять
        LlmClient c = new LlmClient(AiSettings.inMemory().chat(), (url, key, body, t) ->
            "{\"choices\":[{\"message\":{\"content\":\"Стой! <attack target=\\\"n5\\\"/>\"}}]}");
        LlmClient.Reply r = c.chat(new ArrayList<>(), Tools.all(), 10);
        assertEquals(1, r.calls.size());
        assertEquals("attack", r.calls.get(0).name);
        assertEquals(1, Json.arr(r.message.get("tool_calls")).size());
        assertEquals("Стой!", Speech.clean(r.text));
        assertTrue(c.chat(new ArrayList<>(), Tools.all(), 10, true).calls.isEmpty());
        assertTrue(c.chat(new ArrayList<>(), null, 10).calls.isEmpty());
        assertTrue(Persona.system().contains("никогда не пиши их в тексте"));
    }

    @Test
    public void memoryKeepsResidentNotes() throws IOException {
        File dir = tmp.newFolder("notes");
        Memory m = new Memory(dir);
        m.load(4, 0);
        for (int i = 0; i < Memory.NPC_NOTES + 3; i++) m.npcNote("VLK_420_CORAGON", "заметка " + i);
        m.addTalk("VLK_420_CORAGON", "Герой: Налей пива");
        m.save(4, 9);
        Memory back = new Memory(dir);
        back.load(4, 9);
        String ctx = back.npcContext("VLK_420_CORAGON");
        assertTrue(ctx, ctx.startsWith("Ты запомнил:"));
        assertFalse(ctx, ctx.contains("заметка 0\n"));
        assertTrue(ctx, ctx.contains("заметка " + (Memory.NPC_NOTES + 2)));
        assertTrue(ctx, ctx.contains("Недавние разговоры:\nГерой: Налей пива"));
        assertEquals("", back.npcContext("NONE"));
    }

    @Test
    public void memoryKeepsTalksWithNpcs() throws IOException {
        File dir = tmp.newFolder("talks");
        Memory m = new Memory(dir);
        m.load(3, 0);
        for (int i = 0; i < Memory.TALK_LINES + 4; i++) m.addTalk("VLK_420_CORAGON", "Геральт: реплика " + i);
        m.addTalk("VLK_425_REGIS", "Регис: Слыхал новости?");
        m.save(3, 5);
        Memory back = new Memory(dir);
        back.load(3, 5);
        List<String> t = back.talk("VLK_420_CORAGON");
        assertEquals(Memory.TALK_LINES, t.size());
        assertEquals("Геральт: реплика " + (Memory.TALK_LINES + 3), t.get(t.size() - 1));
        assertEquals("Регис: Слыхал новости?", back.talk("VLK_425_REGIS").get(0));
        for (int i = 0; i < Memory.TALKS_NPCS + 5; i++) back.addTalk("N" + i, "x");
        assertEquals(Memory.TALKS_NPCS, back.talks.size());
        assertTrue(back.talk("VLK_420_CORAGON").isEmpty());
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

    @Test
    public void storyTopicsInObservation() {
        String snap = "{\"ev\":\"snapshot\",\"story\":{\"topics\":["
            + "{\"id\":\"t4512\",\"hero\":\"Что мы можем сделать?\",\"says\":\"Только сила Инноса может помочь нам.\","
            + "\"quest\":\"Глаз Инноса\",\"log\":\"В городе Хоринис расположился отряд паладинов.\"},"
            + "{\"id\":\"t4400\",\"imp\":1,\"says\":\"Наконец-то!\"},"
            + "{\"id\":\"t4600\",\"hero\":\"Я принёс траву.\",\"perm\":1,\"done\":\"Трава для алхимика\"}],"
            + "\"choices\":[{\"id\":\"c7731\",\"text\":\"Что это за НОВАЯ угроза?\",\"of\":\"t4400\"}],"
            + "\"quests\":[{\"name\":\"Глаз Инноса\",\"entry\":\"Я должен убедить паладинов.\"}]}}";
        String d = Story.describe(Json.obj(Json.parse(snap)));
        assertTrue(d, d.contains("t4512 — герой спрашивает или говорит: «Что мы можем сделать?»"));
        assertTrue(d, d.contains("Даёт задание «Глаз Инноса»"));
        assertTrue(d, d.contains("t4400 — ты сам хочешь сказать это герою"));
        assertTrue(d, d.contains("(можно не раз)"));
        assertTrue(d, d.contains("Задание «Трава для алхимика» будет выполнено"));
        assertTrue(d, d.contains("c7731 — «Что это за НОВАЯ угроза?»"));
        assertTrue(d, d.contains("«Глаз Инноса»: Я должен убедить паладинов."));
        // Нет тем - нет и раздела
        assertEquals("", Story.describe(Json.obj(Json.parse("{\"ev\":\"snapshot\"}"))));
        // Длинные слова темы - урезаны до подсказки
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) longText.append("слово ");
        String cut = Story.cut(longText.toString(), Story.PREVIEW);
        assertTrue(cut.length() <= Story.PREVIEW + 1);
        assertTrue(cut.endsWith("…"));
    }

    @Test
    public void storyResultForModel() {
        String done = "{\"ev\":\"act_done\",\"id\":\"G2M_AI_A3\",\"name\":\"story\",\"ok\":true,\"note\":\"тема сыграна\","
            + "\"story\":{\"title\":\"Что мы можем сделать?\",\"lines\":[{\"hero\":1,\"text\":\"Что мы можем сделать?\"},"
            + "{\"text\":\"Найди Глаз Инноса.\"},{\"text\":\"Он у паладинов.\"}],"
            + "\"quests\":[{\"name\":\"Глаз Инноса\",\"status\":\"new\",\"entry\":\"Я должен убедить паладинов.\"},"
            + "{\"name\":\"Трава\",\"status\":\"success\"}],"
            + "\"got\":[\"Письмо x2\"],\"gave\":[],\"gold\":-50,\"xp\":100,\"attrs\":[\"сила +5\"],\"ended\":1,"
            + "\"choices\":[{\"id\":\"c1\",\"text\":\"Да\"},{\"id\":\"c2\",\"text\":\"Нет\"}],"
            + "\"next\":[{\"id\":\"t9\",\"hero\":\"Где паладины?\"}]}}";
        Map<String, Object> m = Json.obj(Json.parse(done));
        String r = Story.result(m);
        assertTrue(r, r.contains("Ты сказал в игре: «Найди Глаз Инноса. Он у паладинов.»"));
        assertTrue(r, r.contains("Герой говорил в игре: «Что мы можем сделать?»"));
        assertTrue(r, r.contains("новое задание «Глаз Инноса»: «Я должен убедить паладинов.»"));
        assertTrue(r, r.contains("задание «Трава» выполнено"));
        assertTrue(r, r.contains("герой получил: Письмо x2"));
        assertTrue(r, r.contains("герой заплатил золота: 50"));
        assertTrue(r, r.contains("герой получил опыт: 100"));
        assertTrue(r, r.contains("герой научился: сила +5"));
        assertTrue(r, r.contains("закончил разговор"));
        assertTrue(r, r.contains("c1 «Да»; c2 «Нет»."));
        assertTrue(r, r.contains("t9 «Где паладины?»"));
        String memo = Story.memo(m);
        assertTrue(memo, memo.startsWith("(по сюжету — «Что мы можем сделать?»: "));
        assertTrue(memo, memo.endsWith(")"));

        Map<String, Object> fail = Json.obj(Json.parse(
            "{\"ev\":\"act_done\",\"ok\":false,\"note\":\"эта тема сейчас недоступна\"}"));
        assertTrue(Story.result(fail).startsWith("не вышло: эта тема сейчас недоступна"));
        assertEquals("", Story.memo(fail));
    }

    @Test
    public void storyToolOnlyForResidents() {
        boolean geralt = false;
        for (Map<String, Object> t : Tools.all()) {
            if ("story_topic".equals(Json.str(Json.obj(t.get("function")), "name"))) geralt = true;
        }
        assertFalse("у Геральта нет сюжетных тем", geralt);
        boolean resident = false;
        for (Map<String, Object> t : Tools.resident()) {
            if ("story_topic".equals(Json.str(Json.obj(t.get("function")), "name"))) resident = true;
        }
        assertTrue(resident);
        Map<String, Object> c = Tools.engineAct("A7", "story_topic", Json.of("topic", "t4512"));
        assertEquals("story", c.get("name"));
        assertEquals("t4512", Json.str(Json.obj(c.get("args")), "topic"));
        assertTrue(Tools.isStory("story_topic"));
        assertFalse(Tools.isStory("talk_to_npc"));
    }

    @Test
    public void npcsTalkToEachOtherAndAnswerWithDeeds() {
        // житель, которого герой просит сказать что-то другому, говорит тому (talk_to_npc), а не герою
        String r = Persona.resident("Наемник", "Наёмник", false, "", new ArrayList<>(), new ArrayList<>());
        assertTrue(r, r.contains(Persona.TALK_OTHERS));
        assertTrue(r, r.contains("не тема: это talk_to_npc"));
        assertTrue(Persona.system().contains(Persona.TALK_OTHERS));
        // слушающий отвечает и делом: у него инструменты, кроме своих бесед и сюжетных тем
        String l = Persona.npcReply("Мика", "Ополченец", false, "", new ArrayList<>(), new ArrayList<>(), "Наёмник");
        assertTrue(l, l.contains("Чужак тебе не начальник"));
        java.util.Set<String> names = LlmClient.toolNames(Tools.listener(false));
        assertFalse(names.contains("talk_to_npc"));
        // слушающий может заговорить с третьим (передать, разобраться) - но не во вложенной беседе
        assertTrue(LlmClient.toolNames(Tools.listener(true)).contains("talk_to_npc"));
        assertFalse(LlmClient.toolNames(Tools.listener(true)).contains("story_topic"));
        String third = Persona.npcReply("Сентенза", "Наёмник", false, "", new ArrayList<>(), new ArrayList<>(), "Наёмник", true);
        assertTrue(third, third.contains("подойди и скажи ему сам"));
        assertFalse(l, l.contains("подойди и скажи ему сам"));
        // говорящий продолжает беседу: его текст слышит собеседник, пустой ответ - конец беседы
        StringBuilder conv = new StringBuilder(Persona.npcIdentity("Сентенза", "Наёмник", false, "", new ArrayList<>(),
            new ArrayList<>()));
        Persona.converse(conv, "Мика", "Мика — ополченец", "герой сказал тебе: «скажи Мике, чтобы платил»");
        assertTrue(conv.toString(), conv.toString().contains("Твой текст сейчас слышит «Мика», а не герой"));
        assertTrue(conv.toString(), conv.toString().contains("скажи Мике, чтобы платил"));
        assertTrue(conv.toString(), conv.toString().contains("пустой ответ"));
        // слово - дело: пообещал напасть - attack в том же ответе; драться можно и по долгу, и за своих
        assertTrue(conv.toString(), conv.toString().contains(Persona.DEEDS));
        assertTrue(r, r.contains(Persona.DEEDS));
        assertTrue(r, r.contains(Persona.FIGHT));
        assertTrue(l, l.contains(Persona.DEEDS));
        assertTrue(l, l.contains("свои просят помочь в драке"));
        assertTrue(Persona.system().contains(Persona.DEEDS));
        // «возмутиться» и «обрадоваться» были анимациями зеваки у драки - житель «плясал» вместо удара
        java.util.List<String> gestures = java.util.Arrays.asList(Tools.GESTURES);
        assertFalse(gestures.contains("angry"));
        assertFalse(gestures.contains("cheer"));
        assertFalse(names.contains("story_topic"));
        assertTrue(names.contains("go_home"));
        assertTrue(names.contains("follow_hero"));
        assertTrue(names.contains("give_item"));
        assertFalse(Tools.forListener("talk_to_npc"));
        // talk_to_npc герою - обычная реплика; другому человеку - слова, при которых жест уместен
        List<LlmClient.ToolCall> toHero = java.util.Arrays.asList(new LlmClient.ToolCall("1", "talk_to_npc",
            Json.of("target", "hero", "text", "Вот так-то."), "{}"));
        assertEquals("Вот так-то.", Tools.heroLine(toHero));
        assertFalse(Tools.talksToNpc(toHero));
        List<LlmClient.ToolCall> toMika = java.util.Arrays.asList(
            new LlmClient.ToolCall("2", "look_at", Json.of("target", "n1"), "{}"),
            new LlmClient.ToolCall("3", "talk_to_npc", Json.of("target", "n1", "text", "Проваливай!"), "{}"));
        assertTrue(Tools.talksToNpc(toMika));
        assertEquals("", Tools.heroLine(toMika));
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
