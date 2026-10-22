package gothic2.mobile.og;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import gothic2.mobile.og.ai.AiSettings;
import gothic2.mobile.og.ai.Json;
import gothic2.mobile.og.ai.LlmClient;
import gothic2.mobile.og.ai.Pcm;
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
            + "(своё у каждого сохранения), ходит, дерётся, отдаёт и подбирает вещи, заговаривает с жителями, "
            + "может пойти с тобой. Думает языковая модель по твоему ключу — нужен интернет.\n\n"
            + "Где искать: пещера у водопада прямо под башней Ксардаса. Выйди из башни, спустись по тропе "
            + "к водопаду — вход в пещеру рядом с ним (там же в начале игры молодые гоблины). "
            + "Заговори с Геральтом: «Сказать (голосом)» или «Написать».", 14, DIM));

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

        section(col, "Голос Геральта и жителей");
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
        field(gem, "tts_gemini_model", "Модель озвучки", false);

        LinearLayout az = box(col, AiSettings.TTS_AZURE);
        az.addView(text("Azure AI Speech, бесплатный уровень F0 (portal.azure.com → Speech): ключ и регион ресурса.", 13, DIM));
        field(az, "tts_azure_key", "Ключ", true);
        field(az, "tts_azure_region", "Регион (например westeurope)", false);
        field(az, "tts_azure_voice", "Голос Геральта (ru-RU-DmitryNeural)", false);

        LinearLayout el = box(col, AiSettings.TTS_ELEVENLABS);
        el.addView(text("elevenlabs.io → профиль → API Keys. Голос — id из библиотеки голосов.", 13, DIM));
        field(el, "tts_eleven_key", "Ключ", true);
        field(el, "tts_eleven_voice", "Id голоса", false);
        field(el, "tts_eleven_model", "Модель", false);

        LinearLayout oa = box(col, AiSettings.TTS_OPENAI);
        oa.addView(text("Любой сервис с /v1/audio/speech: OpenAI, свой сервер и т. п.", 13, DIM));
        field(oa, "tts_openai_url", "Адрес", false);
        field(oa, "tts_openai_key", "Ключ", true);
        field(oa, "tts_openai_model", "Модель", false);
        field(oa, "tts_openai_voice", "Голос Геральта", false);

        col.addView(smallButton("Прослушать голос Геральта", v -> testVoice()));
        showTts(cur);

        section(col, "Речь игрока");
        CheckBox stt = new CheckBox(this);
        stt.setText("«Сказать (голосом)» — распознавание речи телефона (иначе — ввод текстом)");
        stt.setTextColor(TEXT);
        stt.setChecked(ai.voiceInput());
        stt.setOnCheckedChangeListener((b, c) -> ai.set("stt_voice", c ? "1" : "0"));
        col.addView(stt);

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
            } catch (Exception e) {
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
            } catch (Exception e) {
                runOnUiThread(() -> result.setText("Не вышло: " + e.getMessage()));
            }
        }).start();
    }

    private void testVoice() {
        saveFields();
        result.setText("Озвучиваю…");
        new Thread(() -> {
            try {
                Tts tts = Tts.create(ai, this);
                if (tts == null) {
                    runOnUiThread(() -> result.setText("Голос выключен — только субтитры."));
                    return;
                }
                Pcm pcm = tts.synth("Ведьмак. Монстров убиваю за плату. Тебе что-то нужно?", Tts.Voice.geralt(""));
                play(pcm);
                runOnUiThread(() -> result.setText("Голос работает (" + pcm.ms() + " мс)."));
            } catch (Exception e) {
                runOnUiThread(() -> result.setText("Голос не вышел: " + e.getMessage()));
            }
        }).start();
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
