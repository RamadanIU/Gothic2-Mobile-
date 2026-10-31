package gothic2.mobile.og.ai;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Мозг Геральта: события движка идут в очередь и разбираются по одному в своём потоке. Ход — снимок
 * обстановки, память и повод уходят модели; её реплика озвучивается и говорится по кускам, вызовы
 * инструментов исполняет движок. Пока ход ждёт ответа движка (снимок, конец реплики, конец действия),
 * остальные события откладываются и разбираются после хода.
 */
final class GeraltBrain {
    interface Engine {
        void send(Map<String, Object> cmd);
    }

    interface Ui {
        void status(String text, long ms);

        void hideStatus();
    }

    interface Audio {
        void put(String id, Pcm pcm);
    }

    /** Кто действует в ходе: Геральт (ref self) или живой житель (ref nN), ключ его памяти и голос. */
    static final class Actor {
        final String ref;
        final String key;
        final String name;
        final String guild;
        final boolean female;
        final int voice;

        Actor(String ref, String key, String name, String guild, boolean female, int voice) {
            this.ref = ref;
            this.key = key;
            this.name = name;
            this.guild = guild;
            this.female = female;
            this.voice = voice;
        }

        boolean geralt() {
            return "self".equals(ref);
        }

        Tts.Voice tts() {
            return geralt() ? Tts.Voice.geralt("") : new Tts.Voice(false, female, voice, "");
        }
    }

    private static final Actor GERALT = new Actor("self", "", Persona.NAME, "", false, 0);

    private static final int MAX_STEPS = 3;
    /** Сколько ждать голос куска реплики, прежде чем сказать его субтитром. */
    private static final long VOICE_WAIT_MS = 12000;
    /** Жест или занятие без слов на слова героя не исполняется — модель должна сначала ответить. */
    private static final String[] SILENT = {
        "не сделано: жест, взгляд и занятие — только вместе со словами. Сначала скажи вслух: герою — текстом ответа, "
            + "другому человеку — talk_to_npc.", "again"};
    /** Сколько раз подряд ход из одних жестов без слов не считается шагом: иначе на нужное действие шагов не хватало. */
    private static final int SILENT_RETRIES = 2;
    static final String WORDS_PLEASE = "Ты не ответил герою словами. Ответь ему вслух сейчас: 1–3 предложения, "
        + "только то, что произносишь, без жестов и инструментов.";
    /** Беседа с жителем: ход модели продлевается, пока Геральт продолжает её (talk_to_npc), но не дальше. */
    private static final int MAX_TALK_STEPS = 8;
    /** Беседа с жителем: сколько ещё реплик говорящий скажет после первой (во вложенной беседе — одну). */
    private static final int TALK_ROUNDS = 2;
    /** Слушающий может заговорить с третьим (передать, разобраться), но третий — уже нет. */
    private static final int MAX_TALK_DEPTH = 2;
    /** Геральт в скриптах игры (ainpc.cpp): житель может заговорить и с ним. */
    private static final String GERALT_ID = Memory.GERALT;
    /** Очнувшийся житель отвечает на то, что с ним сделали, если герой не дальше (м) — иначе только помнит. */
    private static final double REACT_DIST = 20;
    /** Один и тот же житель отвечает на происшествие не чаще (мс). */
    private static final long REACT_GAP = 60000;

    private final AiSettings settings;
    private final Engine engine;
    private final Ui ui;
    private final Audio audio;
    private final Tts tts;
    private final Memory memory;
    private final LlmClient chat;
    private final LlmClient act;
    private final List<Map<String, Object>> tools = Tools.all();
    /** У живых жителей — ещё сюжетные темы их диалогов из игры (story_topic). */
    private final List<Map<String, Object>> residentTools = Tools.resident();
    /** У того, к кому обратился другой NPC (talk_to_npc), — ответ делом, без своих бесед. */
    private final List<Map<String, Object>> listenerTools = Tools.listener(false);
    /** Слушающий, который может заговорить с третьим (не глубже MAX_TALK_DEPTH). */
    private final List<Map<String, Object>> listenerTalkTools = Tools.listener(true);
    /** Кто сейчас в беседе (talkKey): с ними вложенная беседа не начинается — они и так слышат. */
    private final java.util.Set<String> inTalk = new java.util.HashSet<>();
    private int talkDepth;
    /** Зачем говорящий начал беседу (слова героя или того, кто к нему обратился) — для его следующих реплик. */
    private String purpose = "";

    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<Map<String, Object>> deferred = new ArrayDeque<>();
    private final ExecutorService ttsPool = Executors.newSingleThreadExecutor();
    /** Слова героя: голос готовится здесь, пока модель думает над ответом (ответ ждёт конца его слов). */
    private final ExecutorService heroPool = Executors.newSingleThreadExecutor();
    private volatile Future<?> heroLine;
    private volatile long heroVoiceUntil;
    /** Сжатие памяти — в своём потоке: модель переписывает всю память долго, ходы Геральта и жителей не ждут. */
    private final ExecutorService memoryPool = Executors.newSingleThreadExecutor();
    private volatile boolean digesting;
    /** Сжатие не вышло (нет сети, ключа) — не раньше этого времени. */
    private volatile long digestAfter;
    /** Герой говорит лично (выбрал адресата касанием): ответ тоже вполголоса — соседи не слышат. */
    private boolean whisper;
    /** Когда житель последний раз отвечал на происшествие (ключ жителя → время). */
    private final Map<String, Long> reacted = new java.util.HashMap<>();
    /** Кто сейчас в беседе (talkKey → сколько бесед): напарник не идёт за героем, пока все его беседы не кончатся. */
    private final Map<String, Integer> holds = new java.util.HashMap<>();
    private final Map<String, String> pendingActs = new java.util.HashMap<>();
    /** Кто такой житель (id экземпляра → событие lore движка): запрашивается один раз за сеанс. */
    private final Map<String, Map<String, Object>> lore = new java.util.HashMap<>();
    private Thread worker;
    private volatile boolean running;

    private Map<String, Object> snapshot = Json.of();
    private int seq;
    private boolean talking;
    private boolean ready;
    private boolean heroNear;
    private long lastAuto;
    private long lastActivity;
    private long lastErrorShown;

    GeraltBrain(AiSettings settings, Engine engine, Ui ui, Audio audio, Tts tts, Memory memory) {
        this.settings = settings;
        this.engine = engine;
        this.ui = ui;
        this.audio = audio;
        this.tts = tts;
        this.memory = memory;
        this.chat = new LlmClient(settings.chat());
        this.act = new LlmClient(settings.act());
    }

    void start() {
        running = true;
        worker = new Thread(this::loop, "ai-brain");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        if (worker != null) worker.interrupt();
        ttsPool.shutdownNow();
        heroPool.shutdownNow();
        memoryPool.shutdownNow();
    }

    /** Событие движка или панели (из любого потока). */
    void post(Map<String, Object> ev) {
        queue.offer(ev);
    }

    Memory memory() {
        return memory;
    }

    // ---------------- цикл ----------------

    private void loop() {
        while (running) {
            Map<String, Object> ev;
            try {
                ev = deferred.isEmpty() ? queue.poll(1000, TimeUnit.MILLISECONDS) : deferred.poll();
            } catch (InterruptedException e) {
                return;
            }
            try {
                if (ev == null) idle();
                else handle(ev);
                if (ready) digest();
            } catch (Throwable e) {
                // ошибка одного хода не останавливает мозг и не роняет приложение (необработанная ошибка
                // в любом потоке Android завершает процесс)
                showError("сбой — " + e);
            }
        }
    }

    private void handle(Map<String, Object> ev) {
        String kind = Json.str(ev, "ev");
        switch (kind) {
            case "ready":
                // Загрузка сохранения или новая игра - память этого сохранения; смена мира - та же память
                if (Json.bool(ev, "load", true) || memory.story() != Json.num(ev, "story", 0)) {
                    memory.load(Json.num(ev, "story", 0), Json.num(ev, "token", 0));
                    AiLog.i("ready: story=" + Json.num(ev, "story", 0) + " token=" + Json.num(ev, "token", 0)
                        + " journal=" + memory.journal.size());
                }
                ready = true;
                talking = false;
                pendingActs.clear();
                break;
            case "snapshot":
                if (Json.str(ev, "actor").isEmpty()) snapshot = ev;
                break;
            case "talk_start":
                // Сам не заговаривает: ждёт, что скажет герой («Сказать», «Написать», торговля). Прежде здоровался
                // моделью, не дожидаясь героя, - и отвечал так, будто герой уже что-то сказал
                talking = true;
                lastActivity = now();
                break;
            case "talk_end":
                talking = false;
                ui.hideStatus();
                break;
            case "said": {
                String text = Json.str(ev, "text").trim();
                if (text.isEmpty()) {
                    engine.send(Json.of("cmd", "think", "on", false));
                    break;
                }
                lastActivity = now();
                if (heroSpokeAgain()) {
                    memory.add(stamp() + " Герой: «" + text + "»");
                    AiLog.i("said superseded: «" + AiLog.cut(text, 120) + "»");
                    break;
                }
                // «Сказать», обращённое к жителю (фокус или выбранный касанием) - отвечает модель этого жителя.
                // Лично (выбран касанием) - слышит только он; вслух - все в 10 м (near), отвечает названный по имени
                String target = Json.str(ev, "target");
                String name = Json.str(ev, "name");
                boolean priv = Json.bool(ev, "private", false);
                List<Object> near = Json.arr(ev.get("near"));
                if (!priv && !talking) {
                    String named = Hearing.named(text, near);
                    if (!named.isEmpty()) {
                        for (Object o : near) {
                            Map<String, Object> n = Json.obj(o);
                            if (named.equals(Json.str(n, "ref"))) name = Json.str(n, "name");
                        }
                        // Прежний адресат - тоже слышит: он стоит рядом
                        near = withPerson(near, target, Json.str(ev, "name"), named);
                        AiLog.i("said to named " + named + " «" + name + "» instead of " + target);
                        target = named;
                    }
                }
                String heard = priv ? "Герой сказал это тебе лично, вполголоса: другие рядом не слышали."
                    : Hearing.names(near, target).isEmpty() ? ""
                    : "Герой сказал это вслух; рядом слышали: " + Hearing.names(near, target) + ".";
                if (!target.isEmpty() && !"self".equals(target) && !talking) {
                    // Живые жители выключены: движок и не шлёт им «Сказать» (AiNpc::addressee) - на случай гонки
                    if (settings.residents()) residentTurn(target, name, text, priv, heard);
                    else engine.send(Json.of("cmd", "think", "on", false));
                    break;
                }
                boolean free = Json.bool(ev, "free", false) && !talking;
                // Слова героя - его субтитром и голосом: в диалоге - очередью реплик, без диалога - над миром
                if (talking || free) heroSays("self", text, priv && !talking);
                memory.add(stamp() + " Герой: «" + text + "»");
                whisper = priv && !talking;
                try {
                    turn("Герой говорит тебе: «" + text + "»" + (free ? " (вы не в диалоге: он сказал это, стоя рядом)" : "")
                        + (heard.isEmpty() || talking ? "" : "\n" + heard), talking, true, chat);
                } finally {
                    whisper = false;
                }
                break;
            }
            case "bye":
                memory.add(stamp() + " Герой попрощался.");
                break;
            case "act_done":
                onActDone(ev);
                break;
            case "event":
                onEvent(ev);
                break;
            case "heard":
                onHeard(ev);
                break;
            case "incident":
                onIncident(ev);
                break;
            default:
                break;
        }
    }

    private void onActDone(Map<String, Object> ev) {
        String id = Json.str(ev, "id");
        String desc = pendingActs.remove(id);
        if (desc == null) return;
        boolean ok = Json.bool(ev, "ok", false);
        String note = Json.str(ev, "note");
        memory.add(stamp() + " Итог действия " + desc + ": " + (ok ? "" : "не вышло — ") + note);
        if (!talking && settings.autonomy() >= 1 && heroNear) {
            autoTurn("Действие " + desc + " закончилось: " + (ok ? "" : "не вышло — ") + note
                + ". Если стоит — скажи герою пару слов или займись следующим делом; иначе промолчи.", false);
        }
    }

    private void onEvent(Map<String, Object> ev) {
        String k = Json.str(ev, "kind");
        String text = Json.str(ev, "text");
        int a = settings.autonomy();
        switch (k) {
            case "hero_near":
                heroNear = true;
                memory.add(stamp() + " " + text);
                if (a >= 1) autoTurn(text + " Он пока ничего тебе не сказал. Можешь коротко окликнуть его первым или продолжить своё дело (тогда промолчи).", false);
                break;
            case "trade":
                // Итог торговли или обмена: вещи уже у него - может надеть полученное
                memory.add(stamp() + " " + text);
                if (ready && !talking) {
                    turn(text + " Если получил одежду или оружие получше надетого — можешь надеть (equip_item)."
                        + " Скажи герою пару слов, если есть что, иначе промолчи.", false, false, act);
                }
                break;
            case "teach":
                // Уроки в разговоре («Научи меня.»): чему научил героя - помнить; после разговора можно сказать пару слов
                memory.add(stamp() + " " + text);
                if (ready && !talking) {
                    turn(text + " Скажи герою пару слов о его выучке, если есть что, иначе промолчи.", false, false, act);
                }
                break;
            case "hero_left":
                heroNear = false;
                memory.add(stamp() + " " + text);
                break;
            case "hero_hit_me":
                memory.add(stamp() + " " + text);
                autoTurn(text + " Реши, как ответить: словами, предупреждением или сталью.", true);
                break;
            case "down":
                memory.add(stamp() + " " + text);
                break;
            case "up":
                // Очнулся: кто сбил с ног и чем - в тексте события, кто вокруг - в обстановке хода
                memory.add(stamp() + " " + text);
                if (a >= 1 || heroNear) autoTurn(text + " Реши, что сказать и сделать (тому, кто тебя избил, герою или тем, "
                    + "кто рядом); можно и промолчать.", true);
                break;
            case "hit":
            case "enemy":
            case "hero_attacks":
            case "hero_attacked":
                memory.add(stamp() + " " + text);
                if (a >= 1) autoTurn(text, true);
                break;
            case "death":
                // Герой убил человека у тебя на глазах - повод сказать или сделать что-то сразу
                memory.add(stamp() + " " + text);
                if (text.contains("убил(а) герой") && a >= 1) autoTurn(text, true);
                else if (a >= 2 && heroNear) autoTurn(text, false);
                break;
            default:
                memory.add(stamp() + " " + text);
                if (a >= 2 && heroNear) autoTurn(text, false);
                break;
        }
    }

    /**
     * Реплику слышали рядом (движок, heard): реплики вслух — героя, Геральта, жителей и родных диалогов игры. Тем, кто
     * стоял рядом (до 10 м), — в память «видел и слышал рядом»; жителю, который сам говорил в родном диалоге (с героем
     * или другим), — в его беседы: потом он помнит, о чём шла речь. Реплики модели участники запоминают сами.
     */
    private void onHeard(Map<String, Object> ev) {
        Map<String, Object> sp = Json.obj(ev.get("speaker"));
        Map<String, Object> to = Json.obj(ev.get("to"));
        String text = Json.str(ev, "text").trim();
        if (text.isEmpty() || sp.isEmpty()) return;
        if (!Json.bool(ev, "ai", false)) {
            String own = Hearing.who(sp, true) + ": " + text;
            if (isResident(sp)) memory.addTalk(Json.str(sp, "id"), own);
            if (isResident(to)) memory.addTalk(Json.str(to, "id"), own);
        }
        String line = stamp() + " " + Hearing.overheard(sp, to, text);
        for (Object o : Json.arr(ev.get("near"))) {
            Map<String, Object> n = Json.obj(o);
            memory.hear("self".equals(Json.str(n, "ref")) ? GERALT_ID : Json.str(n, "id"), line);
        }
    }

    /** Житель (не герой и не Геральт) с id экземпляра — ключом его памяти. */
    private static boolean isResident(Map<String, Object> p) {
        String ref = Json.str(p, "ref");
        return !p.isEmpty() && !"hero".equals(ref) && !"self".equals(ref) && !Json.str(p, "id").isEmpty();
    }

    /**
     * Происшествие рядом с героем (движок, incident): человека сбили с ног, он очнулся или погиб. Кто видел — запоминает
     * (кто кого и чем); пострадавший житель — что его избили; очнувшийся рядом с героем — сразу отвечает на это
     * (residentReact): кто его избил, кто вокруг, что делать. Геральт о себе узнаёт событиями down/up.
     */
    private void onIncident(Map<String, Object> ev) {
        String kind = Json.str(ev, "kind");
        Map<String, Object> v = Json.obj(ev.get("victim"));
        Map<String, Object> by = Json.obj(ev.get("by"));
        String seen = Incidents.seen(kind, v, by, Json.str(ev, "weapon"), Json.num(ev, "lay", 0));
        if (seen.isEmpty()) return;
        String st = stamp();
        String victimKey = "self".equals(Json.str(v, "ref")) ? GERALT_ID : Json.str(v, "id");
        for (Object o : Json.arr(ev.get("witnesses"))) {
            Map<String, Object> n = Json.obj(o);
            String key = "self".equals(Json.str(n, "ref")) ? GERALT_ID : Json.str(n, "id");
            if (!key.equals(victimKey)) memory.hear(key, st + " Видел: " + seen);
        }
        AiLog.i("incident " + kind + ": " + seen);
        if (!isResident(v)) return;
        String note = Incidents.victimNote(kind, v, by, Json.str(ev, "weapon"));
        if (!note.isEmpty()) memory.npcNote(victimKey, st + " " + note);
        if (!"up".equals(kind)) return;
        double heroDist = Json.dbl(ev, "heroDist", 99);
        if (heroDist > REACT_DIST || talking) {
            // Почему очнувшийся промолчал - в журнал: иначе со стороны кажется, что модель не вызывается
            AiLog.i("react " + victimKey + ": skip - " + (talking ? "hero in dialog" : "hero " + Math.round(heroDist) + " m away"));
            return;
        }
        String byRef = Json.str(by, "ref");
        residentReact(Json.str(v, "ref"), v, Incidents.wokeUp(v, by, Json.str(ev, "weapon"), Json.num(ev, "lay", 0)),
            byRef.isEmpty() ? "hero" : byRef);
    }

    /**
     * Житель очнулся после драки рядом с героем: ход его модели — кто его избил и чем (событие), кто вокруг (снимок его
     * глазами), что сказать и сделать: пожаловаться страже, дать сдачи, уйти или промолчать. Говорит вслух — слышат все
     * рядом. Прежде очнувшийся ничего не знал о том, что с ним случилось, и вёл себя, будто ничего не было.
     */
    private void residentReact(String ref, Map<String, Object> v, String trigger, String to) {
        // Без ready (Геральта нет в этом мире: Долина рудников, Яркендар, ещё не появился) житель тоже отвечает - как
        // на «Сказать» (residentTurn); прежде очнувшийся там молчал
        String key = Json.str(v, "id");
        if (!settings.act().isSet() || !settings.residents()) {
            AiLog.i("react " + key + ": skip - " + (settings.residents() ? "no model in settings" : "residents off"));
            return;
        }
        long t = now();
        Long last = reacted.get(key);
        if (last != null && t - last < REACT_GAP) {
            AiLog.i("react " + key + ": skip - reacted " + (t - last) / 1000 + " s ago");
            return;
        }
        AiLog.i("react " + key + ": " + AiLog.cut(trigger, 200));
        Map<String, Object> snap = requestSnapshot(ref);
        Map<String, Object> me = Json.obj(snap.get("self"));
        if (me.isEmpty()) {
            // Снимка нет - реакция не засчитывается: следующий подъём ответит
            AiLog.i("react " + key + ": skip - no snapshot of " + ref);
            return;
        }
        reacted.put(key, t);
        String name = Json.str(me, "name").isEmpty() ? Json.str(v, "name") : Json.str(me, "name");
        Actor a = new Actor(ref, key.isEmpty() ? name : key, name, Json.str(me, "guild"), Json.bool(me, "female", false),
            (int) Json.num(me, "voice", 1));
        String old = purpose;
        purpose = trigger;
        try {
            Map<String, Object> info = loreOf(a.key, ref);
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content", Persona.residentEvent(a.name, a.guild, a.female, NpcLore.of(key),
                strings(info.get("topics")), strings(info.get("lines")))));
            String mem = memory.npcContext(a.key);
            msgs.add(Json.of("role", "user", "content",
                (mem.isEmpty() ? "" : mem + "\n\n") + Observation.describe(snap) + "\n\n" + trigger));
            for (int step = 0; step < MAX_STEPS; step++) {
                LlmClient.Reply r = act.chat(msgs, tools, 900);
                String speech = heroSpeech(r);
                StringBuilder calls = new StringBuilder();
                for (LlmClient.ToolCall c : r.calls) calls.append(' ').append(c.name).append(c.rawArgs);
                AiLog.i("react model: «" + AiLog.cut(speech, 200) + "»" + calls);
                if (!speech.isEmpty()) {
                    memory.addTalk(a.key, a.name + ": " + speech);
                    speak(ref, to, speech, a.tts(), false);
                }
                if (r.calls.isEmpty()) break;
                msgs.add(r.message);
                boolean again = false;
                for (LlmClient.ToolCall c : r.calls) {
                    String[] res = runTool(c, a, snap);
                    msgs.add(Json.of("role", "tool", "tool_call_id", c.id, "content", res[0]));
                    if ("again".equals(res[1])) again = true;
                }
                if (!again) break;
            }
        } catch (IOException e) {
            showError("модель — " + LlmClient.describe(e));
        } finally {
            purpose = old;
            lastActivity = now();
        }
    }

    /** Ход без вопроса героя — с лимитом частоты, чтобы беречь бесплатные запросы. */
    private void autoTurn(String trigger, boolean urgent) {
        if (talking || !ready) return;
        long gap = new long[]{90000, 25000, 10000}[settings.autonomy()];
        if (urgent) gap /= 3;
        long t = now();
        if (t - lastAuto < gap) return;
        lastAuto = t;
        turn(trigger, false, false, act);
    }

    private void idle() {
        if (!ready || talking || !heroNear) return;
        int a = settings.autonomy();
        if (a == 0) return;
        long t = now();
        long gap = a == 1 ? 180000 : 90000;
        if (t - lastActivity > gap && t - lastAuto > gap) {
            lastActivity = t;
            autoTurn("Какое-то время ничего не происходит. Можешь заняться делом, сказать что-нибудь герою или промолчать.", false);
        }
    }

    // ---------------- ход ----------------

    /**
     * Ход модели. dialog — идёт диалог с героем (выбор реплик ждёт ответа), heroAsked — герой что-то сказал
     * (видно «Геральт думает…»); иначе ход сам по себе, и его реплика не нужна, если герой тем временем открыл
     * диалог: там Геральт ждёт слов героя.
     */
    private void turn(String trigger, boolean dialog, boolean heroAsked, LlmClient llm) {
        AiSettings.Role role = llm == chat ? settings.chat() : settings.act();
        if (!role.isSet()) {
            showError("в настройках ИИ не задан адрес или модель");
            if (dialog) engine.send(Json.of("cmd", "think", "on", false));
            return;
        }
        if (dialog) engine.send(Json.of("cmd", "think", "on", true));
        if (heroAsked) ui.status("Геральт думает…", 0);
        boolean wasTalking = talking;
        AiLog.i("turn" + (dialog ? " (dialog)" : "") + ": " + AiLog.cut(trigger, 160));
        purpose = trigger;
        try {
            Map<String, Object> snap = requestSnapshot();
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content", Persona.system()));
            String mem = memory.context();
            String who = heroAsked && !Json.obj(snap.get("hero")).isEmpty()
                ? "\n\nКто с тобой говорит: " + Observation.speaker(Json.obj(snap.get("hero")), "Герой (безымянный странник)", "")
                : "";
            msgs.add(Json.of("role", "user", "content",
                (mem.isEmpty() ? "" : mem + "\n\n") + Observation.describe(snap) + who + "\n\n" + trigger));
            int steps = MAX_STEPS;
            int silent = 0;
            boolean spoke = false;
            for (int step = 0; step < steps; step++) {
                long t0 = now();
                LlmClient.Reply r = llm.chat(msgs, tools, 1500);
                String speech = heroSpeech(r);
                StringBuilder calls = new StringBuilder();
                for (LlmClient.ToolCall c : r.calls) calls.append(' ').append(c.name).append(c.rawArgs);
                AiLog.i("model " + (now() - t0) + " ms: «" + AiLog.cut(speech, 200) + "»" + calls);
                if (heroAsked && step == 0) ui.hideStatus();
                if (!heroAsked && talking && !wasTalking) {
                    AiLog.i("turn dropped: hero opened a dialog");
                    break;
                }
                if (!speech.isEmpty()) {
                    spoke = true;
                    memory.add(stamp() + " Ты: «" + speech + "»");
                    speak("self", "hero", speech, Tts.Voice.geralt(""), whisper);
                }
                if (r.calls.isEmpty()) break;
                msgs.add(r.message);
                // Слова жителю (talk_to_npc) - тоже слова: жест или взгляд при них уместны
                if (Tools.talksToNpc(r.calls)) spoke = true;
                boolean again = false;
                boolean allSilent = true;
                for (LlmClient.ToolCall c : r.calls) {
                    boolean mute = heroAsked && !spoke && Tools.needsWords(c.name);
                    if (!mute) allSilent = false;
                    String[] res = mute ? SILENT : runTool(c, GERALT, snap);
                    msgs.add(Json.of("role", "tool", "tool_call_id", c.id, "content", res[0]));
                    if ("again".equals(res[1])) again = true;
                    // Беседа с жителем: Геральт может ответить на его слова ещё раз
                    if ("talk_to_npc".equals(c.name)) steps = Math.min(MAX_TALK_STEPS, Math.max(steps, step + 2));
                }
                if (allSilent && silent++ < SILENT_RETRIES) steps++;
                if (!again) break;
            }
            // Герой что-то сказал - ответ словами обязателен
            if (heroAsked && !spoke) {
                String speech = forceWords(msgs, llm);
                if (!speech.isEmpty()) {
                    memory.add(stamp() + " Ты: «" + speech + "»");
                    speak("self", "hero", speech, Tts.Voice.geralt(""), whisper);
                }
            }
        } catch (IOException e) {
            showError("модель — " + LlmClient.describe(e) + (heroAsked ? ". Скажи ещё раз" : ""));
        } finally {
            if (dialog) engine.send(Json.of("cmd", "think", "on", false));
            if (heroAsked) ui.hideStatus();
            lastActivity = now();
        }
    }

    /**
     * Инструмент того, кто действует (Геральт или житель); snap — его снимок обстановки (ref людей и предметов).
     * Результат — текст для модели и нужно ли ей ответить ещё раз ("again").
     */
    private String[] runTool(LlmClient.ToolCall c, Actor a, Map<String, Object> snap) {
        String desc = c.name + (c.args.isEmpty() ? "" : " " + Json.write(c.args));
        switch (c.name) {
            case "remember": {
                String note = Json.str(c.args, "note");
                if (a.geralt()) memory.note(note);
                else memory.npcNote(a.key, note);
                return new String[]{"запомнил", ""};
            }
            case "talk_to_npc": {
                String target = Json.str(c.args, "target");
                // Слова герою вызовом talk_to_npc уже сказаны репликой (heroSpeech)
                if ("hero".equals(target)) return new String[]{"герою говоришь текстом ответа, не talk_to_npc", ""};
                String r = talkToNpc(a, snap, target, Json.str(c.args, "text"));
                if (a.geralt()) memory.add(stamp() + " " + r);
                return new String[]{r, "again"};
            }
            case "story_topic":
                return storyTopic(c, a);
            default:
                break;
        }
        // Отдать самому себе - так модели пытались «взять» деньги, которые герой только пообещал
        if ("give_item".equals(c.name) && "self".equals(Json.str(c.args, "target"))) {
            return new String[]{"не вышло: себе отдать нельзя. Вещи и деньги героя станут твоими, только когда он сам "
                + "их отдаст", ""};
        }
        String id = nextLine("A");
        Map<String, Object> cmd = Tools.engineAct(id, c.name, c.args);
        if (cmd == null) return new String[]{"нет такого инструмента", "again"};
        cmd.put("who", a.ref);
        engine.send(cmd);
        boolean isLong = Tools.LONG.contains(c.name);
        Map<String, Object> done = waitFor(e -> "act_done".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")),
            isLong ? 2500 : 4000);
        if (done == null) {
            if (isLong) {
                if (a.geralt()) {
                    pendingActs.put(id, desc);
                    memory.add(stamp() + " Ты начал: " + desc);
                } else {
                    memory.addTalk(a.key, a.name + ": (начал: " + c.name + ")");
                }
                return new String[]{"начато — движок сообщит, когда закончится", ""};
            }
            return new String[]{"нет ответа от тела", ""};
        }
        boolean ok = Json.bool(done, "ok", false);
        String note = Json.str(done, "note");
        AiLog.i("act " + (a.geralt() ? "" : a.key + " ") + c.name + (ok ? " ok: " : " fail: ") + note);
        if (a.geralt()) memory.add(stamp() + " Ты: " + desc + " → " + (ok ? "" : "не вышло: ") + note);
        else if (ok) memory.addTalk(a.key, a.name + ": (" + note + ")");
        return new String[]{(ok ? "готово: " : "не вышло: ") + note, ok ? "" : "again"};
    }

    /**
     * Сюжетная тема жителя (story_topic): движок исполняет её скриптом игры сразу (AiNpc::cmdStory) и присылает итог —
     * его слова в игре, дневник, вещи, опыт, варианты ответа. Модель затем пересказывает это своими словами.
     */
    private String[] storyTopic(LlmClient.ToolCall c, Actor a) {
        if (a.geralt()) return new String[]{"у тебя нет сюжетных тем", ""};
        String id = nextLine("A");
        Map<String, Object> cmd = Tools.engineAct(id, c.name, c.args);
        cmd.put("who", a.ref);
        engine.send(cmd);
        Map<String, Object> done = waitFor(e -> "act_done".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")), 5000);
        if (done == null) return new String[]{"нет ответа от игры — тема не сыграна; ответь герою своими словами", "again"};
        boolean ok = Json.bool(done, "ok", false);
        AiLog.i("story " + a.key + " " + Json.str(c.args, "topic") + (ok ? " ok: " : " fail: ")
            + AiLog.cut(Json.write(done.containsKey("story") ? done.get("story") : Json.str(done, "note")), 300));
        String memo = Story.memo(done);
        // Без имени жителя: в беседах с ним это не его реплика, а пометка о том, что случилось
        if (!memo.isEmpty()) memory.addTalk(a.key, memo);
        return new String[]{Story.result(done), "again"};
    }

    /**
     * Живой житель: герой сказал ему что-то кнопкой «Сказать». Ход — как у Геральта (те же инструменты), но
     * глазами жителя (снимок с who), с его характером (NpcLore, его реплики из игры), памятью бесед и голосом.
     */
    private void residentTurn(String ref, String name, String text, boolean priv, String heard) {
        if (!settings.chat().isSet()) {
            showError("в настройках ИИ не задан адрес или модель");
            return;
        }
        String who = name.isEmpty() ? "Житель" : name;
        // Слова героя - его субтитром над миром и его голосом
        heroSays(ref, text, priv);
        ui.status(who + " думает…", 0);
        AiLog.i("resident " + ref + " «" + who + "»: " + AiLog.cut(text, 160));
        purpose = "герой сказал тебе: «" + text + "»";
        try {
            Map<String, Object> snap = requestSnapshot(ref);
            Map<String, Object> me = Json.obj(snap.get("self"));
            if (me.isEmpty()) {
                showError("«" + who + "» не слышит — отошёл или занят");
                return;
            }
            String id = Json.str(me, "id");
            Actor a = new Actor(ref, id.isEmpty() ? who : id, Json.str(me, "name").isEmpty() ? who : Json.str(me, "name"),
                Json.str(me, "guild"), Json.bool(me, "female", false), (int) Json.num(me, "voice", 1));
            Map<String, Object> info = loreOf(a.key, ref);
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content", Persona.resident(a.name, a.guild, a.female, NpcLore.of(id),
                strings(info.get("topics")), strings(info.get("lines")))));
            String mem = memory.npcContext(a.key);
            String topics = Story.describe(snap);
            msgs.add(Json.of("role", "user", "content",
                (mem.isEmpty() ? "" : mem + "\n\n") + Observation.describe(snap)
                    + (topics.isEmpty() ? "" : "\n\n" + topics)
                    + "\n\nКто с тобой говорит: " + Observation.speaker(Json.obj(snap.get("hero")), "Герой (безымянный странник)", "")
                    + "\n\nГерой говорит тебе: «" + text + "»" + (heard.isEmpty() ? "" : "\n" + heard)));
            memory.addTalk(a.key, "Герой" + (priv ? " (лично)" : "") + ": " + text);
            int steps = MAX_STEPS;
            int silent = 0;
            boolean spoke = false;
            for (int step = 0; step < steps; step++) {
                long t0 = now();
                LlmClient.Reply r = chat.chat(msgs, residentTools, 1200);
                String speech = heroSpeech(r);
                StringBuilder calls = new StringBuilder();
                boolean story = false;
                for (LlmClient.ToolCall c : r.calls) {
                    calls.append(' ').append(c.name).append(c.rawArgs);
                    if (Tools.isStory(c.name)) story = true;
                }
                AiLog.i("resident model " + (now() - t0) + " ms: «" + AiLog.cut(speech, 200) + "»" + calls);
                if (step == 0) ui.hideStatus();
                // Слова до сюжетной темы - догадка о том, что в ней: настоящие слова придут с итогом темы, и
                // модель скажет их следующим шагом (иначе житель говорил бы одно и то же дважды)
                if (story && !speech.isEmpty()) {
                    AiLog.i("speech before story dropped");
                    speech = "";
                }
                if (!speech.isEmpty()) {
                    spoke = true;
                    memory.addTalk(a.key, a.name + ": " + speech);
                    speak(ref, "hero", speech, a.tts(), priv);
                }
                if (r.calls.isEmpty()) break;
                msgs.add(r.message);
                // Герой просил сказать что-то другому - слова тому (talk_to_npc) герой слышит: это и есть ответ
                if (Tools.talksToNpc(r.calls)) spoke = true;
                boolean again = false;
                boolean allSilent = true;
                for (LlmClient.ToolCall c : r.calls) {
                    boolean mute = !spoke && Tools.needsWords(c.name);
                    if (!mute) allSilent = false;
                    String[] res = mute ? SILENT : runTool(c, a, snap);
                    msgs.add(Json.of("role", "tool", "tool_call_id", c.id, "content", res[0]));
                    if ("again".equals(res[1])) again = true;
                    if ("talk_to_npc".equals(c.name)) steps = Math.min(MAX_TALK_STEPS, Math.max(steps, step + 2));
                    // После темы - ещё шаг, чтобы пересказать её герою (и, если нужно, выбрать вариант ответа)
                    if (Tools.isStory(c.name)) steps = Math.min(MAX_TALK_STEPS, Math.max(steps, step + 2));
                }
                if (allSilent && silent++ < SILENT_RETRIES) steps++;
                if (!again) break;
            }
            // Герой обратился к жителю - ответ словами обязателен
            if (!spoke) {
                String speech = forceWords(msgs, chat);
                if (!speech.isEmpty()) {
                    memory.addTalk(a.key, a.name + ": " + speech);
                    speak(ref, "hero", speech, a.tts(), priv);
                }
            }
        } catch (IOException e) {
            showError("модель — " + LlmClient.describe(e));
        } finally {
            ui.hideStatus();
            lastActivity = now();
        }
    }

    /** Реплика герою: текст ответа, а без него — слова, написанные вызовом talk_to_npc с target hero. */
    private static String heroSpeech(LlmClient.Reply r) {
        String speech = Speech.clean(r.text);
        return speech.isEmpty() ? Speech.clean(Tools.heroLine(r.calls)) : speech;
    }

    /** Ответ модели без слов на слова героя: ещё раз, только речью (вызывать инструменты нельзя). */
    private String forceWords(List<Map<String, Object>> msgs, LlmClient llm) throws IOException {
        msgs.add(Json.of("role", "user", "content", WORDS_PLEASE));
        // Тот же набор инструментов, что в ходе: в истории есть их вызовы (у жителя - и story_topic)
        String speech = Speech.clean(llm.chat(msgs, toolsOf(msgs), 600, true).text);
        AiLog.i("words forced: «" + AiLog.cut(speech, 200) + "»");
        return speech;
    }

    /** Набор инструментов хода: у жителя — с сюжетными темами, если модель их вызывала. */
    private List<Map<String, Object>> toolsOf(List<Map<String, Object>> msgs) {
        for (Map<String, Object> m : msgs) {
            for (Object o : Json.arr(m.get("tool_calls"))) {
                Map<String, Object> f = Json.obj(Json.obj(o).get("function"));
                if (Tools.isStory(Json.str(f, "name"))) return residentTools;
            }
        }
        return tools;
    }

    private static List<String> strings(Object arr) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(arr)) out.add(String.valueOf(o));
        return out;
    }

    /**
     * Беседа с NPC (talk_to_npc): подойти, сказать фразу, выслушать ответ (та же модель в его характере, может ответить
     * и делом) — и так несколько реплик подряд (converse). Говорит Геральт или живой житель (speaker), ref — из его
     * снимка обстановки. Итог — запись беседы для модели говорящего.
     */
    private String talkToNpc(Actor speaker, Map<String, Object> snap, String ref, String text) {
        Map<String, Object> who = null;
        for (Object o : Json.arr(snap.get("npcs"))) {
            Map<String, Object> n = Json.obj(o);
            if (ref.equals(Json.str(n, "ref"))) who = n;
        }
        if (who == null || Json.bool(who, "monster", false) || text.trim().isEmpty()) {
            return "поговорить не вышло: нет такого человека рядом";
        }
        String name = Json.str(who, "name");
        String npcId = Json.str(who, "id");
        String key = npcId.isEmpty() ? name : npcId;
        String listenerKey = talkKey(key);
        // Тот, с кем уже идёт беседа, и так слышит: вложенная беседа с ним путала бы, кто кому отвечает
        if (inTalk.contains(listenerKey)) return "ты и так говоришь с «" + name + "»: он тебя слышит, отвечай ему словами";
        if (talkDepth >= MAX_TALK_DEPTH) return "не сейчас: ты уже в беседе — скажешь «" + name + "» потом";
        String id = nextLine("A");
        engine.send(Json.of("cmd", "act", "id", id, "name", "approach", "who", speaker.ref, "args", Json.of("target", ref)));
        Map<String, Object> done = waitFor(e -> "act_done".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")), 70000);
        if (done == null || !Json.bool(done, "ok", false)) {
            return "не подошёл к «" + name + "»: " + (done == null ? "долго" : Json.str(done, "note"));
        }
        String speakerKey = talkKey(speaker.geralt() ? GERALT_ID : speaker.key);
        boolean addSpeaker = inTalk.add(speakerKey);
        inTalk.add(listenerKey);
        talkDepth++;
        // Оба стоят друг у друга, пока беседа не кончится: напарник прежде через 15 с поворачивался к герою и
        // уходил к нему, продолжая говорить
        hold(speakerKey, speaker.ref, true);
        hold(listenerKey, ref, true);
        try {
            return converse(speaker, snap, who, ref, key, Speech.clean(text));
        } finally {
            hold(listenerKey, ref, false);
            hold(speakerKey, speaker.ref, false);
            talkDepth--;
            inTalk.remove(listenerKey);
            if (addSpeaker) inTalk.remove(speakerKey);
        }
    }

    /**
     * Напарник в беседе не идёт за героем и не поворачивается к нему (движок, hold). Счёт — беседа бывает вложенной
     * (слушающий говорит с третьим): отпускаем, когда кончилась последняя. Не в отряде — движок ничего не делает.
     */
    private void hold(String key, String ref, boolean on) {
        int n = holds.getOrDefault(key, 0) + (on ? 1 : -1);
        if (n > 0) holds.put(key, n);
        else holds.remove(key);
        if (on ? n == 1 : n <= 0) engine.send(Json.of("cmd", "hold", "who", ref, "on", on));
    }

    /**
     * Сама беседа: реплика говорящего — ответ слушающего (слова и дела) — следующая реплика говорящего, пока он не
     * добьётся своего или не кончатся реплики. Текст говорящего здесь слышит собеседник, а не герой: прежде вся
     * беседа шла через ход модели, и после первого ответа она продолжала её текстом, который слышал только герой.
     */
    private String converse(Actor speaker, Map<String, Object> snap, Map<String, Object> who, String ref, String key,
                            String said) {
        String name = Json.str(who, "name");
        String npcId = Json.str(who, "id");
        boolean toGeralt = GERALT_ID.equalsIgnoreCase(npcId);
        Actor listener = toGeralt ? GERALT : new Actor(ref, key, name, Json.str(who, "guild"),
            Json.bool(who, "female", false), (int) Json.num(who, "voice", 1));
        // Кто говорит - глазами слушающего: имя, гильдия, одежда, оружие, его отношение (движок, describe)
        String looks = seenBy(speaker.ref, speaker.geralt(), speaker.name, speaker.guild, ref);
        String partner = null;
        List<String> transcript = new ArrayList<>();
        int rounds = talkDepth > 1 ? 1 : TALK_ROUNDS;
        for (int round = 0; ; round++) {
            speak(speaker.ref, ref, said, speaker.tts(), false);
            String line = speaker.name + ": " + said;
            transcript.add(line);
            if (!toGeralt && !settings.residents()) {
                // Живые жители выключены в настройках: житель выслушал, но моделью не отвечает
                transcript.add("(«" + name + "» выслушал и промолчал)");
                break;
            }
            String[] heard = listen(speaker, listener, toGeralt, who, ref, key, said, looks, transcript);
            if (heard == null) {
                transcript.add("(«" + name + "» не ответил)");
                break;
            }
            if (!heard[0].isEmpty()) transcript.add(name + ": " + heard[0]);
            if (!heard[1].isEmpty()) transcript.add("(" + name + " сделал: " + heard[1] + ")");
            if (round >= rounds || (heard[0].isEmpty() && heard[1].isEmpty())) break;
            if (partner == null) partner = seenBy(ref, toGeralt, name, Json.str(who, "guild"), speaker.ref);
            String[] next = speakerNext(speaker, snap, name, partner, transcript);
            if (!next[1].isEmpty()) transcript.add("(" + speaker.name + " сделал: " + next[1] + ")");
            said = next[0];
            if (said.isEmpty()) break;
        }
        return "Беседа с «" + name + "» (" + ref + "):\n" + String.join("\n", transcript) + "\nБеседа окончена. Теперь "
            + "твой текст снова слышит только герой; сказать что-то ещё «" + name + "» — снова talk_to_npc.";
    }

    /**
     * Слушающий: ответ словами (его характер, память бесед, его снимок обстановки) и делом (Tools.listener, после слов).
     * Итог — {слова, что сделал}; null — модель не ответила.
     */
    private String[] listen(Actor speaker, Actor listener, boolean toGeralt, Map<String, Object> who, String ref,
                            String key, String said, String looks, List<String> transcript) {
        String name = listener.name;
        String line = speaker.name + ": " + said;
        // Слушающий видит обстановку своими глазами, ref - из его снимка
        Map<String, Object> lsnap = toGeralt ? requestSnapshot() : requestSnapshot(ref);
        // Слушающий может заговорить и с третьим (передать, разобраться), но не глубже MAX_TALK_DEPTH
        boolean canTalk = talkDepth < MAX_TALK_DEPTH;
        String reply;
        List<LlmClient.ToolCall> acts = new ArrayList<>();
        try {
            List<Map<String, Object>> msgs = new ArrayList<>();
            String heard = line;
            if (toGeralt) {
                StringBuilder sys = new StringBuilder(Persona.system())
                    .append("\n\nСейчас с тобой заговорил житель. Кто он: ").append(looks)
                    .append("\nОтветь ему вслух, с оглядкой на то, кто он: он рядом и слышит тебя.");
                Persona.reactions(sys, canTalk);
                msgs.add(Json.of("role", "system", "content", sys.toString()));
                if (transcript.size() > 1) {
                    heard = "Беседа до этого:\n" + String.join("\n", transcript.subList(0, transcript.size() - 1))
                        + "\n\n" + said;
                } else {
                    heard = said;
                }
            } else {
                Map<String, Object> info = loreOf(key, ref);
                msgs.add(Json.of("role", "system", "content", Persona.npcReply(name, Json.str(who, "guild"),
                    Json.bool(who, "female", false), NpcLore.of(Json.str(who, "id")), strings(info.get("topics")),
                    strings(info.get("lines")), looks, canTalk)));
                // Свои реплики - ответы модели, чужие (Геральт, герой, другие жители) - с именем говорящего
                String own = name + ": ";
                for (String l : memory.talk(key)) {
                    if (l.startsWith(own)) msgs.add(Json.of("role", "assistant", "content", l.substring(own.length())));
                    else msgs.add(Json.of("role", "user", "content", l));
                }
            }
            msgs.add(Json.of("role", "user", "content", lsnap.isEmpty() ? heard : Observation.describe(lsnap) + "\n\n" + heard));
            LlmClient.Reply r = chat.chat(msgs, lsnap.isEmpty() ? null : canTalk ? listenerTalkTools : listenerTools, 700);
            reply = Speech.clean(r.text);
            StringBuilder calls = new StringBuilder();
            for (LlmClient.ToolCall c : r.calls) {
                if (!Tools.forListener(c.name) && !(canTalk && "talk_to_npc".equals(c.name))) continue;
                acts.add(c);
                calls.append(' ').append(c.name).append(c.rawArgs);
            }
            AiLog.i("npc " + key + " to " + speaker.name + ": «" + AiLog.cut(reply, 200) + "»" + calls);
            if (reply.isEmpty() && !hasTalk(acts)) {
                // Промолчал на обращённые к нему слова - ещё раз: ответ словами обязателен
                msgs.add(Json.of("role", "user", "content", "Ответь ему вслух: 1–3 предложения, только то, что произносишь."));
                reply = Speech.clean(chat.chat(msgs, null, 700).text);
                AiLog.i("npc " + key + " words: «" + AiLog.cut(reply, 200) + "»");
            }
        } catch (IOException e) {
            showError("модель — " + LlmClient.describe(e));
            return null;
        }
        if (toGeralt) {
            memory.add(stamp() + " " + speaker.name + " сказал тебе: «" + said + "»"
                + (reply.isEmpty() ? "" : "; ты ответил: «" + reply + "»"));
        } else {
            memory.addTalk(key, line);
            if (!reply.isEmpty()) memory.addTalk(key, name + ": " + reply);
        }
        if (!speaker.geralt()) {
            memory.addTalk(speaker.key, line);
            if (!reply.isEmpty()) memory.addTalk(speaker.key, name + ": " + reply);
        }
        if (!reply.isEmpty()) speak(ref, speaker.ref, reply, listener.tts(), false);
        // Ответ делом - после слов: «Ладно, ухожу» - и уходит. Беседа с третьим - ради сказанного говорящим
        String old = purpose;
        purpose = "«" + speaker.name + "» сказал тебе: «" + said + "»";
        List<String> did = new ArrayList<>();
        try {
            for (LlmClient.ToolCall c : acts) did.add(deed(c, runTool(c, listener, lsnap)[0]));
        } finally {
            purpose = old;
        }
        String deeds = String.join("; ", did);
        if (!speaker.geralt() && !did.isEmpty()) memory.addTalk(speaker.key, name + ": (" + deeds + ")");
        return new String[]{reply, deeds};
    }

    /**
     * Следующая реплика говорящего в беседе: его характер, зачем он заговорил (purpose), беседа до сих пор. Его текст
     * слышит собеседник; пустой — беседа окончена. Может и сделать что-то (Tools.listener). Итог — {слова, что сделал}.
     */
    private String[] speakerNext(Actor speaker, Map<String, Object> snap, String partnerName, String partner,
                                 List<String> transcript) {
        StringBuilder sys;
        if (speaker.geralt()) {
            sys = new StringBuilder(Persona.system());
        } else {
            Map<String, Object> info = loreOf(speaker.key, speaker.ref);
            sys = new StringBuilder(Persona.npcIdentity(speaker.name, speaker.guild, speaker.female, NpcLore.of(speaker.key),
                strings(info.get("topics")), strings(info.get("lines"))));
        }
        Persona.converse(sys, partnerName, partner, purpose);
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(Json.of("role", "system", "content", sys.toString()));
        // Беседа одним сообщением: история не может начинаться с ответа модели (его реплики)
        msgs.add(Json.of("role", "user", "content", (snap.isEmpty() ? "" : Observation.describe(snap) + "\n\n")
            + "Беседа до сих пор (ты — " + speaker.name + "):\n" + String.join("\n", transcript)
            + "\n\nТвоя следующая реплика «" + partnerName + "» (или ничего, если беседа окончена):"));
        LlmClient.Reply r;
        try {
            r = chat.chat(msgs, snap.isEmpty() ? null : listenerTools, 500);
        } catch (IOException e) {
            showError("модель — " + LlmClient.describe(e));
            return new String[]{"", ""};
        }
        String said = Speech.clean(r.text);
        StringBuilder calls = new StringBuilder();
        List<String> did = new ArrayList<>();
        for (LlmClient.ToolCall c : r.calls) calls.append(' ').append(c.name).append(c.rawArgs);
        AiLog.i("talk " + (speaker.geralt() ? "geralt" : speaker.key) + " to " + partnerName + ": «"
            + AiLog.cut(said, 200) + "»" + calls);
        for (LlmClient.ToolCall c : r.calls) {
            if (Tools.forListener(c.name)) did.add(deed(c, runTool(c, speaker, snap)[0]));
        }
        return new String[]{said, String.join("; ", did)};
    }

    private static String deed(LlmClient.ToolCall c, String result) {
        return c.name + (c.args.isEmpty() ? "" : " " + Json.write(c.args)) + " — " + result;
    }

    private static boolean hasTalk(List<LlmClient.ToolCall> calls) {
        for (LlmClient.ToolCall c : calls) if ("talk_to_npc".equals(c.name)) return true;
        return false;
    }

    /** Ключ участника беседы: id экземпляра (или имя) заглавными — одинаково, кто бы его ни назвал. */
    private static String talkKey(String key) {
        return key.toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * Как targetRef (Геральт или житель) выглядит для viewerRef: описание из движка (describe) и его известность в
     * округе. Нет ответа движка — по имени и гильдии.
     */
    private String seenBy(String targetRef, boolean geralt, String name, String guild, String viewerRef) {
        String req = "d" + (++seq);
        engine.send(Json.of("cmd", "describe", "req", req, "target", targetRef, "viewer", viewerRef));
        Map<String, Object> d = waitFor(e -> "describe".equals(Json.str(e, "ev")) && req.equals(Json.str(e, "req")), 2500);
        String known = geralt
            ? "чужак, назвавшийся Геральтом; несколько дней сидит в кабаке Корагона; седой, со шрамом, жёлтые кошачьи "
                + "глаза, два меча."
            : NpcLore.reputation(d == null ? "" : Json.str(d, "id"));
        if (d == null || Json.str(d, "name").isEmpty()) {
            return name + (guild.isEmpty() ? "" : " — гильдия: " + guild)
                + (known.isEmpty() ? "" : ". Что о нём известно в округе: " + known);
        }
        return Observation.speaker(d, name, known);
    }

    /** Сведения о жителе из движка (темы и реплики его диалогов в игре) — один раз за сеанс. */
    private Map<String, Object> loreOf(String key, String ref) {
        Map<String, Object> l = lore.get(key);
        if (l != null) return l;
        String req = "l" + (++seq);
        engine.send(Json.of("cmd", "lore", "req", req, "target", ref));
        l = waitFor(e -> "lore".equals(Json.str(e, "ev")) && req.equals(Json.str(e, "req")), 2500);
        if (l == null) return Json.of();
        AiLog.i("lore " + key + ": topics=" + Json.arr(l.get("topics")).size() + " lines=" + Json.arr(l.get("lines")).size());
        lore.put(key, l);
        return l;
    }

    /**
     * Реплика по кускам: голос готовится заранее, следующий кусок — когда кончился предыдущий. Голос, который не
     * готов за VOICE_WAIT_MS, не ждём: реплика идёт субтитром, остальные куски этой реплики — тоже без голоса
     * (прежде ждали по 40 с на кусок — Геральт «молчал» минутами, пока сервер озвучки тормозил).
     */
    private void speak(String who, String to, String text, Tts.Voice voice, boolean priv) {
        List<String> parts = Speech.chunks(text);
        List<Future<Pcm>> pcm = new ArrayList<>();
        for (String p : parts) {
            if (tts == null) {
                pcm.add(null);
                continue;
            }
            pcm.add(ttsPool.submit(() -> tts.synth(p, voice)));
        }
        boolean mute = false;
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            Pcm sound = null;
            Future<Pcm> f = pcm.get(i);
            if (f != null && mute) {
                f.cancel(true);
            } else if (f != null) {
                try {
                    sound = f.get(VOICE_WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    f.cancel(true);
                    mute = true;
                    showError("озвучка не успела за " + VOICE_WAIT_MS / 1000 + " с — реплика без голоса");
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showError("озвучка — " + (cause instanceof IOException ? LlmClient.describe((IOException) cause) : String.valueOf(cause)));
                }
            }
            // Ответ - после слов героя: не перебивать его голос
            if (i == 0) awaitHero();
            String id = nextLine("L");
            int ms = sound != null ? Math.max(400, sound.ms()) : Speech.estimateMs(part);
            if (sound != null) audio.put(id, sound);
            Map<String, Object> say = Json.of("cmd", "say", "id", id, "who", who, "to", to, "text", part, "ms", ms,
                "mood", voice.mood);
            if (priv) say.put("private", true);
            engine.send(say);
            waitFor(e -> "line_end".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")), ms + 20000L);
        }
    }

    /**
     * Слова героя — субтитром и его голосом (Tts.Voice.hero, если включено «Озвучивать слова героя»), как реплики героя
     * в диалогах игры; прежде шли только субтитром. Голос готовится в своём потоке, пока модель думает над ответом, и
     * реплика уходит в движок, когда голос готов (не дольше VOICE_WAIT_MS — дальше без голоса). priv — лично: соседи не
     * слышат (движок не рассылает её как heard).
     */
    private void heroSays(String to, String text, boolean priv) {
        String id = nextLine("H");
        String clean = Speech.clean(text);
        int est = Math.min(6000, Speech.estimateMs(text));
        Map<String, Object> say = Json.of("cmd", "say", "id", id, "who", "hero", "to", to, "text", text, "ms", est);
        if (priv) say.put("private", true);
        if (tts == null || !settings.heroVoice() || clean.isEmpty()) {
            heroVoiceUntil = 0;
            engine.send(say);
            return;
        }
        Future<Pcm> voice = ttsPool.submit(() -> tts.synth(clean, Tts.Voice.hero()));
        heroLine = heroPool.submit(() -> {
            Pcm pcm = null;
            try {
                pcm = voice.get(VOICE_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                voice.cancel(true);
                AiLog.i("hero voice: not ready in " + VOICE_WAIT_MS + " ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                AiLog.i("hero voice: " + (e.getCause() != null ? e.getCause() : e));
            }
            int ms = pcm != null ? Math.max(400, pcm.ms()) : est;
            if (pcm != null) audio.put(id, pcm);
            say.put("ms", ms);
            heroVoiceUntil = now() + ms + 250;
            engine.send(say);
        });
    }

    /** Ответ NPC ждёт, пока герой договорит (его голос готовится и звучит параллельно с ходом модели). */
    private void awaitHero() {
        Future<?> f = heroLine;
        if (f != null) {
            try {
                f.get(VOICE_WAIT_MS + 3000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // не дождались голоса героя - отвечаем
            }
            heroLine = null;
        }
        long left = heroVoiceUntil - now();
        if (left > 0) waitFor(e -> false, Math.min(left, 20000));
    }

    /** near с добавленным человеком (ref, name), кроме except: прежний адресат тоже слышал слова героя. */
    private static List<Object> withPerson(List<Object> near, String ref, String name, String except) {
        List<Object> out = new ArrayList<>();
        if (!ref.isEmpty() && !ref.equals(except)) out.add(Json.of("ref", ref, "name", name));
        for (Object o : near) if (!except.equals(Json.str(Json.obj(o), "ref"))) out.add(o);
        return out;
    }

    /**
     * Герой уже сказал что-то ещё, пока ждал ответа (не дождался и переспросил): прежние слова — только в память,
     * отвечать на них отдельно не нужно — ответ пойдёт на последние.
     */
    private boolean heroSpokeAgain() {
        for (Map<String, Object> e : queue) {
            if ("said".equals(Json.str(e, "ev")) && !Json.str(e, "text").trim().isEmpty()) return true;
        }
        for (Map<String, Object> e : deferred) {
            if ("said".equals(Json.str(e, "ev")) && !Json.str(e, "text").trim().isEmpty()) return true;
        }
        return false;
    }

    private Map<String, Object> requestSnapshot() {
        Map<String, Object> s = requestSnapshot("self");
        if (!s.isEmpty()) snapshot = s;
        return snapshot;
    }

    /** Снимок глазами Геральта (self) или жителя (nN); пусто — нет ответа (житель пропал). */
    private Map<String, Object> requestSnapshot(String who) {
        String req = "r" + (++seq);
        engine.send(Json.of("cmd", "snapshot", "req", req, "who", who));
        Map<String, Object> s = waitFor(e -> "snapshot".equals(Json.str(e, "ev")) && req.equals(Json.str(e, "req")), 2500);
        return s == null ? Json.of() : s;
    }

    /** Ждать событие; остальные откладываются до конца хода. */
    private Map<String, Object> waitFor(Predicate<Map<String, Object>> match, long timeoutMs) {
        long end = now() + timeoutMs;
        while (running) {
            long left = end - now();
            if (left <= 0) return null;
            Map<String, Object> e;
            try {
                e = queue.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (e == null) return null;
            if (match.test(e)) return e;
            String k = Json.str(e, "ev");
            if ("snapshot".equals(k) && Json.str(e, "actor").isEmpty()) snapshot = e;
            else if ("talk_end".equals(k)) talking = false;
            else if ("talk_start".equals(k)) talking = true;
            if (!"line_end".equals(k) && !"snapshot".equals(k)) deferred.add(e);
        }
        return null;
    }

    /**
     * Память больше Memory.STEPS шагов (у Геральта — записей, у жителя — реплик) — вся она уходит модели и
     * переписывается заново сжатой: поворотные события фразой, мелочи словом-двумя (Memory.Digest, Persona.digest).
     * Сжимается одна память за раз, в своём потоке; пока модель пишет, новые записи копятся как обычно.
     */
    private void digest() {
        if (digesting || now() < digestAfter || !settings.act().isSet()) return;
        Memory.Digest d = memory.digestDue();
        if (d == null) return;
        digesting = true;
        memoryPool.execute(() -> {
            try {
                List<Map<String, Object>> msgs = new ArrayList<>();
                msgs.add(Json.of("role", "system", "content", Persona.digest(d.geralt() ? "Геральта из Ривии"
                    : "жителя (id " + d.id + "; его реплики в записях — под его именем)")));
                msgs.add(Json.of("role", "user", "content", d.text()));
                String text = act.digest(msgs, 2500);
                boolean ok = memory.applyDigest(d, text);
                AiLog.i("memory digest " + d.id + (ok ? " ok: " : " skipped: ") + d.entries.size() + " entries → "
                    + Memory.cleanSummary(text).length() + " chars");
                if (!ok) digestAfter = now() + 60000;
            } catch (IOException e) {
                AiLog.i("memory digest " + d.id + " failed: " + LlmClient.describe(e));
                digestAfter = now() + 60000;
            } catch (RuntimeException e) {
                digestAfter = now() + 60000;
            } finally {
                digesting = false;
            }
        });
    }

    private String stamp() {
        String t = Json.str(snapshot, "time");
        long day = Json.num(snapshot, "day", 0);
        return t.isEmpty() ? "[—]" : "[д" + day + " " + t + "]";
    }

    private String nextLine(String kind) {
        return "G2M_AI_" + kind + (++seq);
    }

    private void showError(String msg) {
        AiLog.i("error: " + msg);
        long t = now();
        if (t - lastErrorShown < 5000) return;
        lastErrorShown = t;
        ui.status("Геральт: " + msg, 6000);
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
