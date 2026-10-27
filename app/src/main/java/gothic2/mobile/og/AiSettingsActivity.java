package gothic2.mobile.og;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gothic2.mobile.og.ai.AiSettings;
import gothic2.mobile.og.ai.Json;
import gothic2.mobile.og.ai.LlmClient;
import gothic2.mobile.og.ai.Pcm;
import gothic2.mobile.og.ai.Speechify;
import gothic2.mobile.og.ai.Tts;

/**
 * Настройки живого NPC (Геральт из Ривии, docs/AI_NPC.md): модель по адресу, совместимому с OpenAI
 * (по умолчанию Ollama Cloud), ключ и модель для разговора и отдельно — для действий без героя, запасной
 * ключ на случай лимита, голос (несколько бесплатных вариантов), распознавание речи, самостоятельность.
 */
public class AiSettingsActivity extends Activity {
    private static final int GOLD = Color.rgb(0xD9, 0xB6, 0x6A);
    private static final int TEXT = Color.rgb(0xE6, 0xDC, 0xC8);
    private static final int DIM = Color.rgb(0xA8, 0x9C, 0x86);

    private AiSettings ai;
    private final Map<String, EditText> fields = new HashMap<>();
    private final Map<String, View> ttsBoxes = new HashMap<>();
    private TextView result;
    private TextView voiceResult;
    private MediaPlayer preview;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ai = AiSettings.of(getSharedPreferences("ai", MODE_PRIVATE));
        pad = (int) (20 * getResources().getDisplayMetrics().density);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);

        TextView title = text("Живой NPC: Геральт из Ривии", 26, GOLD);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        col.addView(title);
        col.addView(text("Один персонаж с искусственным интеллектом: говорит своим голосом, помнит разговоры "
            + "(своё у каждого сохранения), ходит, дерётся, отдаёт, подбирает и надевает вещи, заговаривает с "
            + "жителями, может пойти с тобой. Думает языковая модель по твоему ключу — нужен интернет.\n\n"
            + "Где искать: в Хоринисе, в кабаке Корагона (нижний город) — сидит за столом. «Путь» → «Кабак Корагона».\n"
            + "Как говорить: рядом с ним справа появляется кнопка «Сказать» — голосом или текстом, без диалога. "
            + "В диалоге с ним — тоже «Сказать (голосом)» и «Написать», там же торговля.\n\n"
            + "Живые жители: посмотри на любого человека рядом (или коснись его) — «Сказать» обратится к нему (на кнопке "
            + "его имя). Он ответит в своём характере и может действовать так же, как Геральт: подойти, пойти с тобой, "
            + "отдать вещь, подраться. Родной диалог игры (главная кнопка «Говорить») остаётся для заданий и торговли.",
            14, DIM));

        CheckBox on = new CheckBox(this);
        on.setText("Включить Геральта");
        on.setTextColor(TEXT);
        on.setTextSize(17);
        on.setChecked(ai.enabled());
        on.setOnCheckedChangeListener((b, c) -> {
            ai.setEnabled(c);
            if (c && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
        });
        col.addView(on);

        section(col, "Модель — разговор");
        col.addView(text("Любой адрес, совместимый с OpenAI (/v1/chat/completions) и умеющий вызывать инструменты: "
            + "Ollama Cloud (по умолчанию), Gemini (https://generativelanguage.googleapis.com/v1beta/openai), "
            + "OpenRouter, Groq и другие.", 13, DIM));
        field(col, "chat_url", "Адрес", false);
        field(col, "chat_key", "Ключ API", true);
        field(col, "chat_key2", "Запасной ключ (когда у первого лимит; другого сервиса или аккаунта)", true);
        field(col, "chat_model", "Модель", false);
        LinearLayout row = row();
        row.addView(smallButton("Список моделей", v -> pickModel("chat_url", "chat_key", "chat_model")));
        row.addView(smallButton("Проверить связь", v -> testChat()));
        col.addView(row);
        result = text("", 13, DIM);
        col.addView(result);

        section(col, "Модель — действия без героя (необязательно)");
        col.addView(text("Самостоятельные решения, события и память — своим ключом, чтобы не тратить лимит "
            + "разговора. Пусто — как у разговора.", 13, DIM));
        field(col, "act_url", "Адрес", false);
        field(col, "act_key", "Ключ API", true);
        field(col, "act_key2", "Запасной ключ", true);
        field(col, "act_model", "Модель", false);
        col.addView(smallButton("Список моделей", v -> pickModel("act_url", "act_key", "act_model")));

        section(col, "Голоса: Геральт, жители и герой");
        RadioGroup tts = new RadioGroup(this);
        String cur = ai.ttsProvider();
        for (int i = 0; i < AiSettings.TTS_IDS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(AiSettings.TTS_TITLES[i]);
            rb.setTextColor(TEXT);
            rb.setId(View.generateViewId());
            final String id = AiSettings.TTS_IDS[i];
            rb.setChecked(id.equals(cur));
            rb.setOnCheckedChangeListener((b, c) -> {
                if (!c) return;
                ai.set("tts_provider", id);
                showTts(id);
            });
            tts.addView(rb);
        }
        col.addView(tts);

        LinearLayout gem = box(col, AiSettings.TTS_GEMINI);
        gem.addView(text("Ключ — бесплатно на aistudio.google.com (Get API key). Бесплатный уровень: запросы "
            + "используются Google для улучшения сервисов. Голоса: Algenib (хриплый), Charon, Orus, Fenrir…", 13, DIM));
        field(gem, "tts_gemini_key", "Ключ Gemini", true);
        field(gem, "tts_gemini_voice", "Голос Геральта", false);
        field(gem, "tts_gemini_hero", "Голос героя (Iapetus, Puck, Orus…)", false);
        field(gem, "tts_gemini_model", "Модель озвучки", false);

        LinearLayout sp = box(col, AiSettings.TTS_SPEECHIFY);
        sp.addView(text("Ключ — platform.speechify.ai → API Keys. Модель и голоса — из списков твоего аккаунта; "
            + "касание голоса в списке — его образец. Русские голоса — вверху (★ — рекомендованные Speechify), "
            + "«Все языки» — любые голоса и твои клоны: по-русски они тоже говорят, но могут с акцентом. "
            + "Жителям достаются голоса из списков по их голосу в игре, каждому — своя высота и темп.", 13, DIM));
        field(sp, "tts_speechify_key", "Ключ Speechify", true);
        field(sp, "tts_speechify_model", "Модель (simba-3.5 — 48 языков, русский тоже)", false);
        sp.addView(smallButton("Список моделей", v -> pickSpeechifyModel()));
        field(sp, "tts_speechify_voice", "Голос Геральта (id)", false);
        sp.addView(smallButton("Выбрать голос Геральта", v -> pickSpeechifyVoices("tts_speechify_voice", "", true)));
        field(sp, "tts_speechify_hero", "Голос героя (id)", false);
        sp.addView(smallButton("Выбрать голос героя", v -> pickSpeechifyVoices("tts_speechify_hero", "male", true)));
        field(sp, "tts_speechify_male", "Голоса мужчин (id через запятую)", false);
        sp.addView(smallButton("Выбрать голоса мужчин", v -> pickSpeechifyVoices("tts_speechify_male", "male", true)));
        field(sp, "tts_speechify_female", "Голоса женщин (id через запятую)", false);
        sp.addView(smallButton("Выбрать голоса женщин", v -> pickSpeechifyVoices("tts_speechify_female", "female", true)));

        LinearLayout az = box(col, AiSettings.TTS_AZURE);
        az.addView(text("Azure AI Speech, бесплатный уровень F0 (portal.azure.com → Speech): ключ и регион ресурса.", 13, DIM));
        field(az, "tts_azure_key", "Ключ", true);
        field(az, "tts_azure_region", "Регион (например westeurope)", false);
        field(az, "tts_azure_voice", "Голос Геральта (ru-RU-DmitryNeural)", false);
        field(az, "tts_azure_hero", "Голос героя (ru-RU-DmitryNeural)", false);

        LinearLayout el = box(col, AiSettings.TTS_ELEVENLABS);
        el.addView(text("elevenlabs.io → профиль → API Keys. Голос — id из библиотеки голосов.", 13, DIM));
        field(el, "tts_eleven_key", "Ключ", true);
        field(el, "tts_eleven_voice", "Id голоса", false);
        field(el, "tts_eleven_hero", "Id голоса героя", false);
        field(el, "tts_eleven_model", "Модель", false);

        LinearLayout oa = box(col, AiSettings.TTS_OPENAI);
        oa.addView(text("Любой сервис с /v1/audio/speech: OpenAI, свой сервер и т. п.", 13, DIM));
        field(oa, "tts_openai_url", "Адрес", false);
        field(oa, "tts_openai_key", "Ключ", true);
        field(oa, "tts_openai_model", "Модель", false);
        field(oa, "tts_openai_voice", "Голос Геральта", false);
        field(oa, "tts_openai_hero", "Голос героя", false);

        col.addView(smallButton("Прослушать голос Геральта", v -> testVoice(false)));
        col.addView(smallButton("Прослушать голос героя", v -> testVoice(true)));
        voiceResult = text("", 13, DIM);
        col.addView(voiceResult);
        showTts(cur);

        section(col, "Речь игрока");
        CheckBox stt = new CheckBox(this);
        stt.setText("«Сказать» — распознавание речи телефона (иначе — ввод текстом)");
        stt.setTextColor(TEXT);
        stt.setChecked(ai.voiceInput());
        stt.setOnCheckedChangeListener((b, c) -> ai.set("stt_voice", c ? "1" : "0"));
        col.addView(stt);
        CheckBox heroVoice = new CheckBox(this);
        heroVoice.setText("Озвучивать слова героя — его голосом, как в диалогах игры");
        heroVoice.setTextColor(TEXT);
        heroVoice.setChecked(ai.heroVoice());
        heroVoice.setOnCheckedChangeListener((b, c) -> ai.set("hero_voice", c ? "1" : "0"));
        col.addView(heroVoice);
        col.addView(text("Кому говоришь: коснись человека — скажешь ему лично, остальные не услышат. Без касания — "
            + "говоришь вслух тому, на кого смотришь (или кого назовёшь по имени), и слышат все рядом, до 10 шагов.", 13, DIM));

        section(col, "Самостоятельность");
        col.addView(text("Как часто Геральт думает сам, без твоего вопроса: заговаривает первым, отвечает на "
            + "события. Чаще — живее, но больше запросов к модели.", 13, DIM));
        RadioGroup au = new RadioGroup(this);
        au.setOrientation(RadioGroup.HORIZONTAL);
        for (int i = 0; i < AiSettings.AUTONOMY_TITLES.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(AiSettings.AUTONOMY_TITLES[i]);
            rb.setTextColor(TEXT);
            rb.setId(View.generateViewId());
            final int level = i;
            rb.setChecked(ai.autonomy() == i);
            rb.setOnCheckedChangeListener((b, c) -> {
                if (c) ai.set("autonomy", String.valueOf(level));
            });
            au.addView(rb);
        }
        col.addView(au);

        Button back = smallButton("Готово", v -> finish());
        col.addView(back);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0x12, 0x0E, 0x0A));
        scroll.addView(col);
        setContentView(scroll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPreview();
        for (Map.Entry<String, EditText> e : fields.entrySet()) ai.set(e.getKey(), e.getValue().getText().toString());
    }

    private void saveFields() {
        for (Map.Entry<String, EditText> e : fields.entrySet()) ai.set(e.getKey(), e.getValue().getText().toString());
    }

    private void showTts(String id) {
        for (Map.Entry<String, View> e : ttsBoxes.entrySet()) e.getValue().setVisibility(e.getKey().equals(id) ? View.VISIBLE : View.GONE);
    }

    private void pickModel(String urlKey, String keyKey, String modelKey) {
        saveFields();
        String url = ai.get(urlKey).isEmpty() ? ai.get("chat_url") : ai.get(urlKey);
        String key = ai.get(keyKey).isEmpty() ? ai.get("chat_key") : ai.get(keyKey);
        result.setText("Загружаю список моделей…");
        new Thread(() -> {
            try {
                List<String> models = LlmClient.models(url, key);
                runOnUiThread(() -> {
                    result.setText("Моделей: " + models.size());
                    if (models.isEmpty()) return;
                    String[] items = models.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                        .setTitle("Модель")
                        .setItems(items, (d, which) -> {
                            fields.get(modelKey).setText(items[which]);
                            ai.set(modelKey, items[which]);
                        })
                        .show();
                });
            } catch (Throwable e) {
                // любая ошибка в фоне - строкой на экране, а не вылетом приложения
                runOnUiThread(() -> result.setText("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private void testChat() {
        saveFields();
        result.setText("Спрашиваю модель…");
        AiSettings.Role role = ai.chat();
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                List<Map<String, Object>> msgs = new ArrayList<>();
                msgs.add(Json.of("role", "system", "content", "Ты — Геральт из Ривии. Отвечай одной короткой фразой по-русски."));
                msgs.add(Json.of("role", "user", "content", "Кто ты?"));
                LlmClient.Reply r = new LlmClient(role).chat(msgs, null, 400);
                long ms = System.currentTimeMillis() - t0;
                runOnUiThread(() -> result.setText("Работает (" + ms + " мс): " + r.text.trim()));
            } catch (Throwable e) {
                // любая ошибка в фоне - строкой на экране, а не вылетом приложения
                runOnUiThread(() -> result.setText("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private void testVoice(boolean hero) {
        saveFields();
        voiceResult.setText("Озвучиваю…");
        // Голос телефона (TextToSpeech) создаётся в главном потоке, синтез - в фоне
        final Tts tts;
        try {
            tts = Tts.create(ai, this);
        } catch (RuntimeException e) {
            voiceResult.setText("Голос не вышел: " + e.getMessage());
            return;
        }
        if (tts == null) {
            voiceResult.setText("Голос выключен — только субтитры.");
            return;
        }
        new Thread(() -> {
            try {
                Pcm pcm = hero ? tts.synth("Я ищу работу. И, пожалуй, кружку пива.", Tts.Voice.hero())
                    : tts.synth("Ведьмак. Монстров убиваю за плату. Тебе что-то нужно?", Tts.Voice.geralt(""));
                play(pcm);
                runOnUiThread(() -> voiceResult.setText("Голос работает (" + pcm.ms() + " мс)."));
            } catch (Throwable e) {
                // любая ошибка в фоне - строкой на экране, а не вылетом приложения
                runOnUiThread(() -> voiceResult.setText("Голос не вышел: " + e.getMessage()));
            }
        }).start();
    }

    private void setField(String key, String value) {
        EditText e = fields.get(key);
        if (e != null) e.setText(value);
        ai.set(key, value);
    }

    /** Модели Speechify из аккаунта (/v1/audio/models). */
    private void pickSpeechifyModel() {
        saveFields();
        String key = ai.get("tts_speechify_key");
        voiceResult.setText("Загружаю модели Speechify…");
        new Thread(() -> {
            try {
                List<Speechify.Model> models = Speechify.models(key);
                runOnUiThread(() -> {
                    voiceResult.setText("Моделей: " + models.size());
                    if (models.isEmpty() || isFinishing()) return;
                    String[] items = new String[models.size()];
                    for (int i = 0; i < items.length; i++) items[i] = models.get(i).title();
                    new AlertDialog.Builder(this)
                        .setTitle("Модель озвучки")
                        .setItems(items, (d, which) -> setField("tts_speechify_model", models.get(which).id))
                        .show();
                });
            } catch (Throwable e) {
                // любая ошибка в фоне - строкой на экране, а не вылетом приложения
                runOnUiThread(() -> voiceResult.setText("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    /**
     * Голоса Speechify для выбранной модели: Геральту — один (gender пусто), жителям — несколько своего пола.
     * Касание голоса — образец; «Все языки» / «Только русские» — другой список.
     */
    private void pickSpeechifyVoices(String fieldKey, String gender, boolean russianOnly) {
        saveFields();
        String key = ai.get("tts_speechify_key");
        String model = ai.get("tts_speechify_model");
        // Жителям - несколько голосов, Геральту и герою - один
        boolean multi = "tts_speechify_male".equals(fieldKey) || "tts_speechify_female".equals(fieldKey);
        voiceResult.setText("Загружаю голоса Speechify…");
        new Thread(() -> {
            try {
                List<Speechify.VoiceInfo> list = Speechify.voices(key, model, russianOnly, gender);
                runOnUiThread(() -> {
                    if (isFinishing()) return;
                    if (list.isEmpty()) {
                        voiceResult.setText(russianOnly ? "Русских голосов нет — показываю все языки." : "Голосов нет.");
                        if (russianOnly) pickSpeechifyVoices(fieldKey, gender, false);
                        return;
                    }
                    voiceResult.setText("Голосов: " + list.size() + ". Касание — образец.");
                    showVoiceDialog(fieldKey, gender, russianOnly, multi, list);
                });
            } catch (Throwable e) {
                // любая ошибка в фоне - строкой на экране, а не вылетом приложения
                runOnUiThread(() -> voiceResult.setText("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private void showVoiceDialog(String fieldKey, String gender, boolean russianOnly, boolean multi, List<Speechify.VoiceInfo> list) {
        String[] items = new String[list.size()];
        for (int i = 0; i < items.length; i++) items[i] = list.get(i).title();
        List<String> current = Arrays.asList(ai.get(fieldKey).replace(" ", "").split(","));
        String title = voiceTitle(fieldKey) + (russianOnly ? " — русские" : " — все языки");
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title);
        if (multi) {
            boolean[] checked = new boolean[list.size()];
            for (int i = 0; i < checked.length; i++) checked[i] = current.contains(list.get(i).id);
            b.setMultiChoiceItems(items, checked, (d, which, on) -> {
                checked[which] = on;
                if (on) playPreview(list.get(which));
            });
            b.setPositiveButton("Выбрать", (d, w) -> {
                List<String> ids = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) if (checked[i]) ids.add(list.get(i).id);
                setField(fieldKey, String.join(",", ids));
                voiceResult.setText(ids.isEmpty() ? "Голоса не выбраны — жители говорят голосом Геральта." : "Выбрано голосов: " + ids.size());
            });
        } else {
            int[] chosen = {-1};
            for (int i = 0; i < list.size(); i++) if (current.contains(list.get(i).id)) chosen[0] = i;
            b.setSingleChoiceItems(items, chosen[0], (d, which) -> {
                chosen[0] = which;
                playPreview(list.get(which));
            });
            b.setPositiveButton("Выбрать", (d, w) -> {
                if (chosen[0] < 0) return;
                setField(fieldKey, list.get(chosen[0]).id);
                voiceResult.setText(voiceTitle(fieldKey) + ": " + list.get(chosen[0]).title());
            });
        }
        b.setNeutralButton(russianOnly ? "Все языки" : "Только русские", (d, w) -> pickSpeechifyVoices(fieldKey, gender, !russianOnly));
        b.setNegativeButton("Отмена", null);
        b.setOnDismissListener(d -> stopPreview());
        b.show();
    }

    private static String voiceTitle(String fieldKey) {
        switch (fieldKey) {
            case "tts_speechify_hero": return "Голос героя";
            case "tts_speechify_male": return "Голоса мужчин";
            case "tts_speechify_female": return "Голоса женщин";
            default: return "Голос Геральта";
        }
    }

    /** Образец голоса по ссылке из списка (без трат знаков тарифа). */
    private void playPreview(Speechify.VoiceInfo v) {
        stopPreview();
        if (!v.preview.startsWith("https://")) {
            voiceResult.setText(v.title() + ": образца нет");
            return;
        }
        MediaPlayer mp = new MediaPlayer();
        preview = mp;
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            mp.setDataSource(v.preview);
            mp.setOnPreparedListener(MediaPlayer::start);
            mp.setOnErrorListener((m, what, extra) -> {
                voiceResult.setText(v.title() + ": образец не играет");
                return true;
            });
            mp.prepareAsync();
            voiceResult.setText("Образец: " + v.title());
        } catch (IOException | RuntimeException e) {
            stopPreview();
            voiceResult.setText(v.title() + ": образец не играет");
        }
    }

    private void stopPreview() {
        if (preview == null) return;
        preview.release();
        preview = null;
    }

    private static void play(Pcm pcm) {
        AudioTrack tr = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.samples.length * 2)
            .build();
        tr.write(pcm.samples, 0, pcm.samples.length);
        tr.play();
        try {
            Thread.sleep(pcm.ms() + 300L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tr.release();
    }

    private void section(LinearLayout col, String title) {
        TextView t = text(title, 19, GOLD);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setPadding(0, pad, 0, 0);
        col.addView(t);
    }

    private LinearLayout box(LinearLayout col, String id) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        col.addView(b);
        ttsBoxes.put(id, b);
        return b;
    }

    private void field(LinearLayout col, String key, String hint, boolean secret) {
        TextView label = text(hint, 13, DIM);
        label.setPadding(0, pad / 3, 0, 0);
        col.addView(label);
        EditText e = new EditText(this);
        e.setText(ai.get(key));
        e.setTextColor(TEXT);
        e.setSingleLine(true);
        e.setInputType(secret ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD)
            : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI));
        col.addView(e);
        fields.put(key, e);
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button smallButton(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setBackgroundColor(Color.rgb(0x2E, 0x22, 0x14));
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = pad / 3;
        lp.rightMargin = pad / 3;
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }
}
