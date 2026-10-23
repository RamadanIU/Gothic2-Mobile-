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

    private static final int MAX_STEPS = 3;

    private final AiSettings settings;
    private final Engine engine;
    private final Ui ui;
    private final Audio audio;
    private final Tts tts;
    private final Memory memory;
    private final LlmClient chat;
    private final LlmClient act;
    private final List<Map<String, Object>> tools = Tools.all();

    private final LinkedBlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<Map<String, Object>> deferred = new ArrayDeque<>();
    private final ExecutorService ttsPool = Executors.newSingleThreadExecutor();
    private final Map<String, String> pendingActs = new java.util.HashMap<>();
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
                snapshot = ev;
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
                boolean free = Json.bool(ev, "free", false) && !talking;
                if (talking || free) {
                    // Слова героя - его субтитром, без голоса: в диалоге - очередью реплик, без диалога - над миром
                    engine.send(Json.of("cmd", "say", "id", nextLine("H"), "who", "hero", "to", "self", "text", text,
                        "ms", Math.min(4000, Speech.estimateMs(text))));
                }
                memory.add(stamp() + " Герой: «" + text + "»");
                turn("Герой говорит тебе: «" + text + "»" + (free ? " (вы не в диалоге: он сказал это, стоя рядом)" : ""),
                    talking, true, chat);
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
            case "hero_left":
                heroNear = false;
                memory.add(stamp() + " " + text);
                break;
            case "hero_hit_me":
                memory.add(stamp() + " " + text);
                autoTurn(text + " Реши, как ответить: словами, предупреждением или сталью.", true);
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
            return;
        }
        if (memory.needsSummary()) summarize();
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
        try {
            Map<String, Object> snap = requestSnapshot();
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content", Persona.system()));
            String mem = memory.context(30);
            msgs.add(Json.of("role", "user", "content",
                (mem.isEmpty() ? "" : mem + "\n\n") + Observation.describe(snap) + "\n\n" + trigger));
            for (int step = 0; step < MAX_STEPS; step++) {
                long t0 = now();
                LlmClient.Reply r = llm.chat(msgs, tools, 1500);
                String speech = Speech.clean(r.text);
                StringBuilder calls = new StringBuilder();
                for (LlmClient.ToolCall c : r.calls) calls.append(' ').append(c.name).append(c.rawArgs);
                AiLog.i("model " + (now() - t0) + " ms: «" + AiLog.cut(speech, 200) + "»" + calls);
                if (heroAsked && step == 0) ui.hideStatus();
                if (!heroAsked && talking && !wasTalking) {
                    AiLog.i("turn dropped: hero opened a dialog");
                    break;
                }
                if (!speech.isEmpty()) {
                    memory.add(stamp() + " Ты: «" + speech + "»");
                    speak("self", "hero", speech, Tts.Voice.geralt(""));
                }
                if (r.calls.isEmpty()) break;
                msgs.add(r.message);
                boolean again = false;
                for (LlmClient.ToolCall c : r.calls) {
                    String[] res = runTool(c);
                    msgs.add(Json.of("role", "tool", "tool_call_id", c.id, "content", res[0]));
                    if ("again".equals(res[1])) again = true;
                }
                if (!again) break;
            }
        } catch (IOException e) {
            showError("модель — " + e.getMessage());
        } finally {
            if (dialog) engine.send(Json.of("cmd", "think", "on", false));
            if (heroAsked) ui.hideStatus();
            lastActivity = now();
        }
    }

    /** Результат инструмента для модели и нужно ли ей ответить ещё раз ("again"). */
    private String[] runTool(LlmClient.ToolCall c) {
        String desc = c.name + (c.args.isEmpty() ? "" : " " + Json.write(c.args));
        switch (c.name) {
            case "remember": {
                String note = Json.str(c.args, "note");
                memory.note(note);
                return new String[]{"запомнил", ""};
            }
            case "talk_to_npc": {
                String r = talkToNpc(Json.str(c.args, "target"), Json.str(c.args, "text"));
                memory.add(stamp() + " " + r);
                return new String[]{r, "again"};
            }
            default:
                break;
        }
        String id = nextLine("A");
        Map<String, Object> cmd = Tools.engineAct(id, c.name, c.args);
        if (cmd == null) return new String[]{"нет такого инструмента", "again"};
        engine.send(cmd);
        boolean isLong = Tools.LONG.contains(c.name);
        Map<String, Object> done = waitFor(e -> "act_done".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")),
            isLong ? 2500 : 4000);
        if (done == null) {
            if (isLong) {
                pendingActs.put(id, desc);
                memory.add(stamp() + " Ты начал: " + desc);
                return new String[]{"начато — движок сообщит, когда закончится", ""};
            }
            return new String[]{"нет ответа от тела", ""};
        }
        boolean ok = Json.bool(done, "ok", false);
        String note = Json.str(done, "note");
        AiLog.i("act " + c.name + (ok ? " ok: " : " fail: ") + note);
        memory.add(stamp() + " Ты: " + desc + " → " + (ok ? "" : "не вышло: ") + note);
        return new String[]{(ok ? "готово: " : "не вышло: ") + note, ok ? "" : "again"};
    }

    /** Подойти к NPC, сказать ему фразу и озвучить его ответ (ответ — та же модель в его характере). */
    private String talkToNpc(String ref, String text) {
        Map<String, Object> who = null;
        for (Object o : Json.arr(snapshot.get("npcs"))) {
            Map<String, Object> n = Json.obj(o);
            if (ref.equals(Json.str(n, "ref"))) who = n;
        }
        if (who == null || Json.bool(who, "monster", false) || text.trim().isEmpty()) {
            return "поговорить не вышло: нет такого человека рядом";
        }
        String name = Json.str(who, "name");
        String id = nextLine("A");
        engine.send(Json.of("cmd", "act", "id", id, "name", "approach", "args", Json.of("target", ref)));
        Map<String, Object> done = waitFor(e -> "act_done".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")), 70000);
        if (done == null || !Json.bool(done, "ok", false)) {
            return "не подошёл к «" + name + "»: " + (done == null ? "долго" : Json.str(done, "note"));
        }
        String said = Speech.clean(text);
        speak("self", ref, said, Tts.Voice.geralt(""));
        String reply;
        try {
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content",
                Persona.npcReply(name, Json.str(who, "guild"), Json.bool(who, "female", false))));
            msgs.add(Json.of("role", "user", "content", "Он говорит тебе: «" + said + "»"));
            reply = Speech.clean(chat.chat(msgs, null, 600).text);
        } catch (IOException e) {
            showError("модель — " + e.getMessage());
            return "ты сказал «" + name + "»: «" + said + "», но ответа не расслышал";
        }
        if (reply.isEmpty()) return "ты сказал «" + name + "»: «" + said + "»; он промолчал";
        Tts.Voice v = new Tts.Voice(false, Json.bool(who, "female", false), (int) Json.num(who, "voice", 1), "");
        speak(ref, "self", reply, v);
        return "ты сказал «" + name + "»: «" + said + "». «" + name + "» ответил: «" + reply + "»";
    }

    /** Реплика по кускам: голос готовится заранее, следующий кусок — когда кончился предыдущий. */
    private void speak(String who, String to, String text, Tts.Voice voice) {
        List<String> parts = Speech.chunks(text);
        List<Future<Pcm>> pcm = new ArrayList<>();
        for (String p : parts) {
            if (tts == null) {
                pcm.add(null);
                continue;
            }
            pcm.add(ttsPool.submit(() -> tts.synth(p, voice)));
        }
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            Pcm sound = null;
            if (pcm.get(i) != null) {
                try {
                    sound = pcm.get(i).get(40, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    showError("озвучка — " + cause.getMessage());
                }
            }
            String id = nextLine("L");
            int ms = sound != null ? Math.max(400, sound.ms()) : Speech.estimateMs(part);
            if (sound != null) audio.put(id, sound);
            engine.send(Json.of("cmd", "say", "id", id, "who", who, "to", to, "text", part, "ms", ms, "mood", voice.mood));
            waitFor(e -> "line_end".equals(Json.str(e, "ev")) && id.equals(Json.str(e, "id")), ms + 20000L);
        }
    }

    private Map<String, Object> requestSnapshot() {
        String req = "r" + (++seq);
        engine.send(Json.of("cmd", "snapshot", "req", req));
        Map<String, Object> s = waitFor(e -> "snapshot".equals(Json.str(e, "ev")) && req.equals(Json.str(e, "req")), 2500);
        if (s != null) snapshot = s;
        return snapshot;
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
            if ("snapshot".equals(k)) snapshot = e;
            else if ("talk_end".equals(k)) talking = false;
            else if ("talk_start".equals(k)) talking = true;
            if (!"line_end".equals(k) && !"snapshot".equals(k)) deferred.add(e);
        }
        return null;
    }

    private void summarize() {
        List<String> old = memory.oldEntries();
        if (old.isEmpty()) return;
        try {
            List<Map<String, Object>> msgs = new ArrayList<>();
            msgs.add(Json.of("role", "system", "content", Persona.summarize()));
            msgs.add(Json.of("role", "user", "content", "Прежняя память: " + (memory.summary.isEmpty() ? "нет" : memory.summary)
                + "\n\nЗаписи:\n" + String.join("\n", old)));
            String s = Speech.clean(act.chat(msgs, null, 800).text);
            if (!s.isEmpty()) memory.applySummary(s, old.size());
        } catch (IOException e) {
            // в следующий раз
        }
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
