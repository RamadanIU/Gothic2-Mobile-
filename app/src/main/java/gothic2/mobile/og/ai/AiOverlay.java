package gothic2.mobile.og.ai;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Панель поверх игры (PopupWindow над экраном движка): микрофон с распознанным текстом, ввод текста,
 * «Геральт думает…» и ошибки связи с моделью. Всё — в главном потоке Android.
 */
final class AiOverlay {
    private static final int GOLD = Color.rgb(0xD9, 0xB6, 0x6A);
    private static final int TEXT = Color.rgb(0xE6, 0xDC, 0xC8);

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private PopupWindow popup;
    private PopupWindow status;
    private SpeechRecognizer recognizer;
    private Runnable hideStatus;

    AiOverlay(Activity activity) {
        this.activity = activity;
    }

    /** Слушать микрофон; итог — в onText (пустая строка — отмена). */
    void listen(Consumer<String> onText) {
        listen(null, onText);
    }

    /** Слушать микрофон для who (имя того, кому говорят; null — Геральт). */
    void listen(String who, Consumer<String> onText) {
        main.post(() -> {
            close();
            if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
                showStatus("Распознавание речи на телефоне недоступно — напиши текстом", 4000);
                type(onText);
                return;
            }
            LinearLayout box = panel();
            TextView title = label("Говори… (" + name(who) + " слушает)", 17, GOLD);
            TextView heard = label("", 16, TEXT);
            box.addView(title);
            box.addView(heard);
            LinearLayout row = row();
            final boolean[] finished = {false};
            Button stop = button("Готово", v -> {
                if (recognizer != null) recognizer.stopListening();
            });
            Button cancel = button("Отмена", v -> {
                finished[0] = true;
                close();
                onText.accept("");
            });
            // Шумно или не хочется говорить вслух - тот же разговор текстом
            Button write = button("Написать", v -> {
                finished[0] = true;
                type(who, onText, () -> listen(who, onText));
            });
            row.addView(stop);
            row.addView(write);
            row.addView(cancel);
            box.addView(row);
            show(box, false);

            recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    title.setText("Распознаю…");
                }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onError(int error) {
                    if (finished[0]) return;
                    finished[0] = true;
                    close();
                    showStatus(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Не расслышал — попробуй ещё раз" : "Микрофон: ошибка " + error, 3000);
                    onText.accept("");
                }

                @Override
                public void onPartialResults(Bundle b) {
                    String t = first(b);
                    if (!t.isEmpty()) heard.setText(t);
                }

                @Override
                public void onResults(Bundle b) {
                    if (finished[0]) return;
                    finished[0] = true;
                    String t = first(b);
                    close();
                    onText.accept(t);
                }
            });
            Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            it.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            it.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            try {
                recognizer.startListening(it);
            } catch (RuntimeException e) {
                finished[0] = true;
                close();
                showStatus("Микрофон недоступен — напиши текстом", 3000);
                type(onText);
            }
        });
    }

    /** Ввод текстом; итог — в onText (пустая строка — отмена). */
    void type(Consumer<String> onText) {
        type(onText, null);
    }

    /** Ввод текстом с кнопкой «Голосом» (toVoice — переключиться на микрофон; null — без кнопки). */
    void type(Consumer<String> onText, Runnable toVoice) {
        type(null, onText, toVoice);
    }

    void type(String who, Consumer<String> onText, Runnable toVoice) {
        main.post(() -> {
            close();
            LinearLayout box = panel();
            box.addView(label(who == null ? "Что сказать Геральту?" : "Что сказать: " + who + "?", 17, GOLD));
            EditText edit = new EditText(activity);
            edit.setTextColor(TEXT);
            edit.setHintTextColor(Color.rgb(0x8A, 0x80, 0x70));
            edit.setHint("Например: «Кто ты такой?»");
            edit.setTextSize(17);
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            edit.setMinWidth(dp(420));
            box.addView(edit);
            final boolean[] sent = {false};
            Runnable send = () -> {
                if (sent[0]) return;
                sent[0] = true;
                String t = edit.getText().toString().trim();
                hideKeyboard(edit);
                close();
                onText.accept(t);
            };
            edit.setOnEditorActionListener((v, id, ev) -> {
                send.run();
                return true;
            });
            LinearLayout row = row();
            row.addView(button("Сказать", v -> send.run()));
            if (toVoice != null) {
                row.addView(button("Голосом", v -> {
                    if (sent[0]) return;
                    sent[0] = true;
                    hideKeyboard(edit);
                    close();
                    toVoice.run();
                }));
            }
            row.addView(button("Отмена", v -> {
                if (sent[0]) return;
                sent[0] = true;
                hideKeyboard(edit);
                close();
                onText.accept("");
            }));
            box.addView(row);
            show(box, true);
            edit.requestFocus();
            main.postDelayed(() -> {
                InputMethodManager im = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (im != null) im.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            }, 150);
        });
    }

    /** Короткая строка вверху экрана: «Геральт думает…», ошибки. ms=0 — пока не уберут. */
    void showStatus(String text, long ms) {
        main.post(() -> {
            if (activity.isFinishing()) return;
            if (status != null) status.dismiss();
            TextView t = label(text, 15, TEXT);
            LinearLayout box = panel();
            box.addView(t);
            status = new PopupWindow(box, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false);
            status.setTouchable(false);
            try {
                status.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(8));
            } catch (RuntimeException e) {
                status = null;
                return;
            }
            if (hideStatus != null) main.removeCallbacks(hideStatus);
            if (ms > 0) {
                hideStatus = this::hideStatusNow;
                main.postDelayed(hideStatus, ms);
            }
        });
    }

    void hideStatus() {
        main.post(this::hideStatusNow);
    }

    private void hideStatusNow() {
        if (status != null) {
            status.dismiss();
            status = null;
        }
    }

    void close() {
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (RuntimeException ignored) {
                // уже освобождён
            }
            recognizer = null;
        }
        if (popup != null) {
            popup.dismiss();
            popup = null;
        }
    }

    private void show(View content, boolean focusable) {
        if (activity.isFinishing()) return;
        popup = new PopupWindow(content, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, focusable);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        try {
            popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(16));
        } catch (RuntimeException e) {
            popup = null;
        }
    }

    private void hideKeyboard(View v) {
        InputMethodManager im = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
        if (im != null) im.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private LinearLayout panel() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        box.setPadding(p, p / 2, p, p / 2);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(0xE6, 0x16, 0x11, 0x0C));
        bg.setStroke(dp(1), Color.rgb(0x8A, 0x6A, 0x3A));
        bg.setCornerRadius(dp(8));
        box.setBackground(bg);
        return box;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(activity);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.END);
        return r;
    }

    private TextView label(String text, int sp, int color) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.SERIF);
        t.setMaxWidth(dp(560));
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(activity);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setOnClickListener(l);
        return b;
    }

    private static String name(String who) {
        return who == null || who.isEmpty() ? "Геральт" : who;
    }

    private int dp(int v) {
        return (int) (v * activity.getResources().getDisplayMetrics().density);
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() || r.get(0) == null ? "" : r.get(0).trim();
    }
}
