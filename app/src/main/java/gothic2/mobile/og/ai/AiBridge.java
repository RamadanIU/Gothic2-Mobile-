package gothic2.mobile.og.ai;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Связь с движком (common/game/ailink.cpp): команды — байтами UTF-8 в nativeCommand, события приходят в
 * onEngineEvent из потока игры. Голос реплик и микрофон — сразу (их нельзя задерживать ходом модели),
 * запись памяти перед сохранением — в этом же вызове, остальное — в очередь мозга.
 */
public final class AiBridge {
    private static final int REQ_MIC = 71;

    private static native void nativeAttach(boolean enable);

    private static native void nativeCommand(byte[] utf8);

    private static volatile AiBridge inst;

    private final Activity activity;
    private final AiSettings settings;
    private final VoicePlayer player = new VoicePlayer();
    private final AiOverlay overlay;
    private final GeraltBrain brain;
    private final Memory memory;
    private boolean askedMic;

    private AiBridge(Activity activity, AiSettings settings) {
        AiLog.init(new File(activity.getFilesDir(), "ai"));
        AiLog.i("start: model=" + settings.chat().model + " tts=" + settings.ttsProvider()
            + " autonomy=" + settings.autonomy());
        this.activity = activity;
        this.settings = settings;
        this.overlay = new AiOverlay(activity);
        this.memory = new Memory(new File(activity.getFilesDir(), "ai/memory"));
        Tts tts;
        try {
            tts = Tts.create(settings, activity);
        } catch (RuntimeException e) {
            tts = null;
        }
        this.brain = new GeraltBrain(settings, AiBridge::send,
            new GeraltBrain.Ui() {
                @Override
                public void status(String text, long ms) {
                    overlay.showStatus(text, ms);
                }

                @Override
                public void hideStatus() {
                    overlay.hideStatus();
                }
            },
            player::put, tts, memory);
    }

    /** Экран игры создан: движок узнаёт, включён ли живой NPC. */
    public static void start(Activity activity) {
        AiSettings s = AiSettings.of(activity.getSharedPreferences("ai", Activity.MODE_PRIVATE));
        boolean on = s.enabled();
        try {
            if (on) {
                AiBridge b = new AiBridge(activity, s);
                inst = b;
                b.brain.start();
            }
            nativeAttach(on);
        } catch (UnsatisfiedLinkError e) {
            // движок не связан с приложением - игра идёт как обычно, но Геральта не будет: сказать об этом
            AiLog.i("start: no engine link - " + e);
            if (inst != null) inst.brain.stop();
            inst = null;
            if (on) {
                android.widget.Toast.makeText(activity, "Живой NPC: движок не подключился — Геральта не будет. "
                    + "Пришли журнал (экран запуска → «Сохранить журнал»).", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    public static void stop() {
        AiBridge b = inst;
        inst = null;
        if (b != null) {
            b.brain.stop();
            b.player.stopAll();
            b.overlay.close();
        }
    }

    /** Ответ на запрос микрофона (GameActivity.onRequestPermissionsResult). */
    public static boolean onPermission(int requestCode, int[] results) {
        return requestCode == REQ_MIC;
    }

    static void send(Map<String, Object> cmd) {
        try {
            nativeCommand(Json.write(cmd).getBytes(StandardCharsets.UTF_8));
        } catch (UnsatisfiedLinkError ignored) {
            // нет движка
        }
    }

    /** Из движка (поток игры). */
    @SuppressWarnings("unused")
    static void onEngineEvent(byte[] utf8) {
        AiBridge b = inst;
        if (b == null) return;
        Map<String, Object> ev;
        try {
            ev = Json.obj(Json.parse(new String(utf8, StandardCharsets.UTF_8)));
        } catch (IllegalArgumentException e) {
            return;
        }
        b.dispatch(ev);
    }

    private void dispatch(Map<String, Object> ev) {
        String kind = Json.str(ev, "ev");
        switch (kind) {
            case "line_start":
                player.start(Json.str(ev, "id"), (float) Json.dbl(ev, "vol", 1));
                return;
            case "line_skip":
                player.stopAll();
                return;
            case "line_end":
                player.end(Json.str(ev, "id"), Json.bool(ev, "skipped", false));
                brain.post(ev);
                return;
            case "saving":
                // Сохранение пишется сразу после этого вызова: память - под той же меткой и сейчас же
                memory.save(Json.num(ev, "story", 0), Json.num(ev, "token", 0));
                return;
            case "listen": {
                // free - кнопка «Сказать» без диалога: ответ субтитрами над миром
                // target - кому: self (Геральт) или житель nN, на которого смотрит герой
                // private - выбран касанием: слышит только он; иначе near - кто ещё рядом слышит (в 10 м)
                boolean free = Json.bool(ev, "free", false);
                String target = Json.str(ev, "target");
                boolean priv = Json.bool(ev, "private", false);
                String name = Json.str(ev, "name");
                String who = target.isEmpty() || "self".equals(target) ? null : name;
                // Подпись панели: кому и кто ещё услышит
                if (who != null && priv) who = who + " — лично (другие не слышат)";
                else if (who != null && !Json.arr(ev.get("near")).isEmpty()) who = who + " и все рядом";
                Consumer<String> done = t -> brain.post(Json.of("ev", "said", "text", t == null ? "" : t, "free", free,
                    "target", target, "name", name, "private", priv, "near", Json.arr(ev.get("near"))));
                if (settings.voiceInput() && hasMic()) overlay.listen(who, done);
                else overlay.type(who, done, voice(who, done));
                return;
            }
            case "type": {
                Consumer<String> done = t -> said(t, Json.bool(ev, "free", false), "", "");
                overlay.type(null, done, voice(null, done));
                return;
            }
            default:
                brain.post(ev);
        }
    }

    /** Кнопка «Голосом» в панели текста — если распознавание речи включено и микрофон разрешён. */
    private Runnable voice(String who, Consumer<String> done) {
        if (!settings.voiceInput()) return null;
        return () -> {
            if (hasMic()) overlay.listen(who, done);
            else overlay.type(who, done, voice(who, done));
        };
    }

    private void said(String text, boolean free, String target, String name) {
        brain.post(Json.of("ev", "said", "text", text == null ? "" : text, "free", free, "target", target, "name", name));
    }

    private boolean hasMic() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true;
        if (!askedMic) {
            askedMic = true;
            activity.runOnUiThread(() -> activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC));
        }
        return false;
    }
}
