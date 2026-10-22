package gothic2.mobile.og;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Экран запуска: разрешение «Файлы», поиск папки игры (System, Data, _work), выбор вручную,
 * «Играть», графика, сохранение журнала в «Загрузки», сведения о лицензиях. Путь к игре движок
 * читает из files/gothic_path.txt (патч InstallDetect).
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PERMISSION = 1;
    private static final int REQ_FOLDER = 2;
    private static final String PREF_PATH = "game_path";

    private static final int GOLD = Color.rgb(0xD9, 0xB6, 0x6A);
    private static final int TEXT = Color.rgb(0xE6, 0xDC, 0xC8);
    private static final int DIM = Color.rgb(0xA8, 0x9C, 0x86);

    private TextView status;
    private Button play;
    private Button permission;
    private String gamePath;
    private boolean searching;
    private GraphicsSettings graphics;
    private TextView graphicsSummary;
    private final List<Runnable> graphicsRefresh = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(Color.rgb(0x12, 0x0E, 0x0A));

        TextView title = text("Gothic II", 30, GOLD);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        col.addView(title);
        col.addView(text("Твоя Gothic II прямо на телефоне: сенсорное управление, автобой, напарники.", 15, DIM));

        status = text("", 16, TEXT);
        status.setPadding(0, pad / 2, 0, pad / 2);
        col.addView(status);

        permission = button("Разрешить доступ к файлам", v -> requestPermissions(
            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
            REQ_PERMISSION));
        col.addView(permission);
        play = button("Играть", v -> startGame());
        col.addView(play);
        col.addView(button("Выбрать папку игры", v -> startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)));
        col.addView(button("Сохранить журнал в «Загрузки»", v -> saveLog()));

        graphics = new GraphicsSettings(prefs());
        // Первый запуск: на слабом телефоне сразу «Экономно» (оценка самого быстрого ядра процессора)
        if (graphics.isUntouched()) {
            Double score = DevicePerformance.scoreOfThisDevice();
            boolean weak = score != null && score < DevicePerformance.MIN_SCORE;
            graphics.applyPreset(weak ? GraphicsSettings.ECONOMY_PRESET : GraphicsSettings.DEFAULT_PRESET);
            if (weak) {
                Toast.makeText(this, "Телефон слабее, чем нужно игре: графика — «Экономно».", Toast.LENGTH_LONG).show();
            }
        }
        addGraphicsSection(col, pad);

        TextView help = text(
            "Управление\n"
                + "• Слева — джойстик: вперёд/назад; вбок — поворот, с оружием — шаг в сторону.\n"
                + "• Справа — обзор пальцем.\n"
                + "• «Удар» — разговор, подобрать, открыть; с мечом держать — серия ударов, "
                + "каждый в своё окно комбо; свайп по «Удару» влево/вправо — боковой удар.\n"
                + "• «Блок» — держать. «Оружие» — меч; подержать — лук.\n"
                + "• «Автобой» — герой сам дерётся с тем, кто на него напал (и с хищником рядом). "
                + "Тронул джойстик или «Удар» — управляешь сам, отпустил — автобой продолжает. "
                + "Здоровья меньше четверти — пьёт зелье лечения.\n"
                + "• Напарник: в разговоре с безымянным жителем, стражником или наёмником — "
                + "«Пойдём со мной — поможешь в бою?». Цена — 10 золотых за уровень, не больше трёх напарников. "
                + "Напарник идёт следом и дерётся с врагами, опыт за убитых им — тебе. "
                + "Ещё раз заговорить — «Подожди меня здесь» или «Дальше я сам».\n"
                + "• Диалог и сумка: стрелки, «Выбрать», «Назад»; у сундука и тела — «Взять всё». "
                + "Кнопка «Назад» телефона — Esc.\n"
                + "• Графика задаётся здесь, на экране запуска: настройки видео в меню самой игры "
                + "написаны для старого движка и почти не действуют.\n\n"
                + "Сохранения хранятся в самом приложении (удаление приложения их удалит) "
                + "и с сохранениями оригинальной игры на компьютере не совместимы.",
            14, DIM);
        help.setPadding(0, pad, 0, 0);
        col.addView(help);
        col.addView(button("Сведения о лицензиях", v -> startActivity(new Intent(this, LicensesActivity.class))));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(0x12, 0x0E, 0x0A));
        scroll.addView(col);
        setContentView(scroll);

        gamePath = prefs().getString(PREF_PATH, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean granted = hasPermission();
        permission.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            play.setEnabled(false);
            status.setText("Нужно разрешение «Файлы»: игра читается прямо из твоей папки.");
            return;
        }
        if (gamePath != null && GameFolder.isGameDir(new File(gamePath))) {
            play.setEnabled(true);
            status.setText("Папка игры:\n" + gamePath);
            return;
        }
        play.setEnabled(false);
        if (searching) return;
        searching = true;
        status.setText("Ищу папку игры…");
        new Thread(() -> {
            File found = GameFolder.findGameDir(Environment.getExternalStorageDirectory());
            runOnUiThread(() -> {
                searching = false;
                if (found != null) {
                    setGamePath(found.getPath());
                } else {
                    status.setText("Папка игры не найдена. Нажми «Выбрать папку игры» — нужна "
                        + "распакованная Gothic II: Gold, где есть System, Data и _work.");
                }
            });
        }).start();
    }

    private void setGamePath(String path) {
        gamePath = path;
        prefs().edit().putString(PREF_PATH, path).apply();
        refresh();
    }

    /** «Графика»: готовые наборы и отдельные параметры; касание кнопки — следующий вариант. */
    private void addGraphicsSection(LinearLayout col, int pad) {
        TextView title = text("Графика", 20, GOLD);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setPadding(0, pad, 0, 0);
        col.addView(title);
        graphicsSummary = text("", 14, DIM);
        col.addView(graphicsSummary);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < GraphicsSettings.PRESET_NAMES.length; i++) {
            final int preset = i;
            Button b = button(GraphicsSettings.PRESET_NAMES[i], v -> {
                graphics.applyPreset(preset);
                refreshGraphics();
            });
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
            lp.width = 0;
            lp.weight = 1;
            if (i > 0) lp.leftMargin = pad / 3;
            presets.addView(b);
        }
        col.addView(presets);

        List<GraphicsSettings.Option> options = new ArrayList<>(Arrays.asList(GraphicsSettings.ALL));
        options.addAll(Arrays.asList(GraphicsSettings.EXTRA));
        for (GraphicsSettings.Option o : options) {
            Button b = button("", v -> {
                graphics.cycle(o);
                refreshGraphics();
            });
            graphicsRefresh.add(() -> b.setText(graphics.label(o)));
            col.addView(b);
        }
        refreshGraphics();
    }

    private void refreshGraphics() {
        graphicsSummary.setText(graphics.summary());
        for (Runnable r : graphicsRefresh) r.run();
    }

    private void startGame() {
        if (gamePath == null) return;
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), "gothic_path.txt"))) {
            out.write(gamePath.getBytes(StandardCharsets.UTF_8));
            graphics.write(getFilesDir());
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось записать путь: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FOLDER || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        String path = treeToPath(data.getData());
        if (path == null) {
            Toast.makeText(this, "Эту папку нельзя открыть как путь — выбери папку во внутренней памяти.",
                Toast.LENGTH_LONG).show();
            return;
        }
        File dir = new File(path);
        if (!GameFolder.isGameDir(dir)) {
            // Выбрали папку уровнем выше - поищем игру внутри
            File inner = GameFolder.findGameDir(dir);
            if (inner == null) {
                Toast.makeText(this, "В папке нет игры (System, Data, _work):\n" + path, Toast.LENGTH_LONG).show();
                return;
            }
            dir = inner;
        }
        setGamePath(dir.getPath());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private void saveLog() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        File out = new File(downloads, "gothic2-log.txt");
        try (OutputStream os = new FileOutputStream(out)) {
            boolean any = false;
            for (String name : new String[]{"log.txt", "crash.log"}) {
                File f = new File(getFilesDir(), name);
                if (!f.isFile()) continue;
                any = true;
                os.write(("==== " + name + " ====\n").getBytes(StandardCharsets.UTF_8));
                try (InputStream in = new FileInputStream(f)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
            }
            if (!any) os.write("Журнала ещё нет: игра не запускалась.\n".getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Журнал сохранён: " + out.getPath(), Toast.LENGTH_LONG).show();
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось сохранить журнал: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("launcher", MODE_PRIVATE);
    }

    /** content://…/tree/primary%3AGames%2FGothic → /storage/emulated/0/Games/Gothic. */
    static String treeToPath(Uri tree) {
        try {
            return GameFolder.docIdToPath(DocumentsContract.getTreeDocumentId(tree),
                Environment.getExternalStorageDirectory().getPath());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }


    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button button(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(GOLD);
        b.setBackgroundColor(Color.rgb(0x2E, 0x22, 0x14));
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }
}
