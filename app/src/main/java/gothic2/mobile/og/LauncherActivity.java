package gothic2.mobile.og;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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

import gothic2.mobile.og.ai.AiSettings;

/**
 * Экран запуска: разрешение «Файлы», поиск папки игры (System, Data, _work), «Играть» — «Ночь Ворона» или
 * Gothic II без дополнения. Настройки — по разделам, каждый на своей странице: «Графика», «Управление»
 * (настройка кнопок в игре, как играть), «Живые NPC» (AiSettingsActivity), «Папка игры», «Журнал и сведения»;
 * «Назад» телефона возвращает на главную. Путь к игре движок читает из files/gothic_path.txt (патч InstallDetect),
 * вариант игры — из files/game_variant.txt (патч CommandLine).
 *
 * Открывается и из меню игры (кнопка «Приложение»): игра идёт в своём процессе и стоит на паузе,
 * отсюда — «Вернуться в игру» или перезапуск с новыми настройками и другим вариантом.
 */
public class LauncherActivity extends Activity {
    /** Экран запуска открыт из меню игры (GameActivity.openAppMenu). */
    static final String EXTRA_FROM_GAME = "from_game";
    static final String VARIANT_NOTR = "notr";
    static final String VARIANT_CLASSIC = "classic";

    private static final int REQ_PERMISSION = 1;
    private static final int REQ_FOLDER = 2;
    private static final String PREF_PATH = "game_path";
    private static final String PREF_VARIANT = "variant";
    private static final String GAME_PROCESS_SUFFIX = ":game";

    private static final int GOLD = Color.rgb(0xD9, 0xB6, 0x6A);
    private static final int TEXT = Color.rgb(0xE6, 0xDC, 0xC8);
    private static final int DIM = Color.rgb(0xA8, 0x9C, 0x86);
    private static final int BG = Color.rgb(0x12, 0x0E, 0x0A);
    private static final int PANEL = Color.rgb(0x2E, 0x22, 0x14);

    private static final int PAGE_MAIN = 0;
    private static final int PAGE_GRAPHICS = 1;
    private static final int PAGE_CONTROLS = 2;
    private static final int PAGE_GAME = 3;
    private static final int PAGE_INFO = 4;

    private ScrollView scroll;
    private LinearLayout mainPage;
    private int page = PAGE_MAIN;
    private int pad;
    private TextView status;
    private TextView graphicsNav;
    private TextView folderNav;
    private TextView gameFolder;
    private Button resume;
    private Button playNotr;
    private Button playClassic;
    private Button permission;
    private boolean restarting;
    private String gamePath;
    private boolean searching;
    private GraphicsSettings graphics;
    private TextView graphicsSummary;
    private TextView aiNav;
    private final List<Runnable> graphicsRefresh = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Значок приложения нажали, когда игра уже открыта: система может положить второй экран запуска
        // поверх игры - тогда вернуться к ней, а не показывать начальное меню
        Intent start = getIntent();
        if (!isTaskRoot() && Intent.ACTION_MAIN.equals(start.getAction())
            && start.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish();
            return;
        }
        pad = (int) (24 * getResources().getDisplayMetrics().density);

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

        mainPage = column();
        TextView title = text("Gothic II", 30, GOLD);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        mainPage.addView(title);
        mainPage.addView(text("Твоя Gothic II прямо на телефоне: сенсорное управление, автобой, напарники.", 15, DIM));

        status = text("", 16, TEXT);
        status.setPadding(0, pad / 2, 0, pad / 2);
        mainPage.addView(status);

        permission = button("Разрешить доступ к файлам", v -> requestPermissions(
            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
            REQ_PERMISSION));
        mainPage.addView(permission);
        resume = button("Вернуться в игру", v -> resumeGame());
        mainPage.addView(resume);
        playNotr = button("Играть: Ночь Ворона", v -> play(VARIANT_NOTR));
        mainPage.addView(playNotr);
        playClassic = button("", v -> play(VARIANT_CLASSIC));
        mainPage.addView(playClassic);

        section(mainPage, "Настройки");
        graphicsNav = navRow(mainPage, "Графика", () -> showPage(PAGE_GRAPHICS));
        navRow(mainPage, "Управление", () -> showPage(PAGE_CONTROLS))
            .setText("Размер и место кнопок, как играть");
        aiNav = navRow(mainPage, "Живые NPC (ИИ)", () -> startActivity(new Intent(this, AiSettingsActivity.class)));
        folderNav = navRow(mainPage, "Папка игры", () -> showPage(PAGE_GAME));
        navRow(mainPage, "Журнал и сведения", () -> showPage(PAGE_INFO))
            .setText("Журнал для разбора сбоев, лицензии");

        gamePath = prefs().getString(PREF_PATH, null);
        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        setContentView(scroll);
        showPage(PAGE_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean aiOn = AiSettings.of(getSharedPreferences("ai", MODE_PRIVATE)).enabled();
        aiNav.setText("Геральт и жители говорят сами: " + (aiOn ? "включено" : "выключено"));
        graphicsNav.setText(graphics.summaryShort());
        String folder = gamePath != null ? gamePath : "не выбрана";
        folderNav.setText(folder);
        if (gameFolder != null) gameFolder.setText("Папка игры:\n" + folder);
        if (restarting) return;
        boolean running = gamePid(this) != 0;
        resume.setVisibility(running ? View.VISIBLE : View.GONE);
        String game = running
            ? "Игра идёт и ждёт на паузе. Новые настройки и другой вариант игры — после перезапуска.\n\n" : "";
        boolean granted = hasPermission();
        permission.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            setPlayable(null);
            status.setText(game + "Нужно разрешение «Файлы»: игра читается прямо из твоей папки.");
            return;
        }
        if (gamePath != null && GameFolder.isGameDir(new File(gamePath))) {
            setPlayable(new File(gamePath));
            status.setText(game.isEmpty() ? "Игра найдена — можно играть." : game.trim());
            return;
        }
        setPlayable(null);
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
                    status.setText("Папка игры не найдена. Открой «Папка игры» и выбери её — нужна "
                        + "распакованная Gothic II: Gold, где есть System, Data и _work.");
                }
            });
        }).start();
    }

    /** Кнопки «Играть»: без папки игры - выключены; без дополнения - только если в копии есть классика. */
    private void setPlayable(File dir) {
        boolean classic = dir != null && GameFolder.hasClassic(dir);
        playNotr.setEnabled(dir != null);
        playClassic.setEnabled(classic);
        playClassic.setText(dir == null || classic ? "Играть: Gothic II без дополнения"
            : "Gothic II без дополнения: нет в этой копии");
        String last = prefs().getString(PREF_VARIANT, VARIANT_NOTR);
        playNotr.setTypeface(null, VARIANT_NOTR.equals(last) ? Typeface.BOLD : Typeface.NORMAL);
        playClassic.setTypeface(null, VARIANT_CLASSIC.equals(last) && classic ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void setGamePath(String path) {
        gamePath = path;
        prefs().edit().putString(PREF_PATH, path).apply();
        refresh();
    }

    // ---------------- страницы ----------------

    /** Главная или раздел настроек: раздел — своя страница с «Назад» вверху. */
    private void showPage(int p) {
        page = p;
        scroll.removeAllViews();
        if (mainPage.getParent() != null) ((LinearLayout) mainPage.getParent()).removeView(mainPage);
        graphicsRefresh.clear();
        graphicsSummary = null;
        gameFolder = null;
        switch (p) {
            case PAGE_GRAPHICS: scroll.addView(graphicsPage()); break;
            case PAGE_CONTROLS: scroll.addView(controlsPage()); break;
            case PAGE_GAME: scroll.addView(gamePage()); break;
            case PAGE_INFO: scroll.addView(infoPage()); break;
            default: scroll.addView(mainPage); break;
        }
        scroll.scrollTo(0, 0);
        if (p == PAGE_MAIN && status != null) refresh();
    }

    @Override
    public void onBackPressed() {
        if (page != PAGE_MAIN) {
            showPage(PAGE_MAIN);
            return;
        }
        super.onBackPressed();
    }

    /** Страница раздела: «‹ Назад», заголовок. */
    private LinearLayout pageOf(String title) {
        LinearLayout col = column();
        Button back = button("‹ Назад", v -> showPage(PAGE_MAIN));
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) back.getLayoutParams();
        lp.width = LinearLayout.LayoutParams.WRAP_CONTENT;
        lp.topMargin = 0;
        col.addView(back);
        TextView t = text(title, 26, GOLD);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setPadding(0, pad / 2, 0, 0);
        col.addView(t);
        return col;
    }

    /** «Графика»: готовые наборы и отдельные параметры; касание кнопки — следующий вариант. */
    private LinearLayout graphicsPage() {
        LinearLayout col = pageOf("Графика");
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

        section(col, "Параметры");
        for (GraphicsSettings.Option o : GraphicsSettings.ALL) addOption(col, o);
        section(col, "Дополнительно");
        col.addView(text("Не входят в наборы.", 13, DIM));
        for (GraphicsSettings.Option o : GraphicsSettings.EXTRA) addOption(col, o);
        col.addView(note("Настройки видео в меню самой игры написаны для старого движка и почти не действуют — "
            + "графика задаётся здесь. Если игра уже идёт, новая графика — после перезапуска игры."));
        refreshGraphics();
        return col;
    }

    private void addOption(LinearLayout col, GraphicsSettings.Option o) {
        if (o.check) {
            col.addView(checkBox(o));
            return;
        }
        Button b = button("", v -> {
            graphics.cycle(o);
            refreshGraphics();
        });
        graphicsRefresh.add(() -> b.setText(graphics.label(o)));
        col.addView(b);
    }

    /** «Управление»: настройка кнопок поверх игры и коротко — как играть. */
    private LinearLayout controlsPage() {
        LinearLayout col = pageOf("Управление");
        section(col, "Кнопки на экране");
        col.addView(note("Размер, место и видимость кнопок настраиваются прямо поверх игры: перетащи кнопку пальцем, "
            + "коснись — выбрать её, «Меньше»/«Больше» — размер выбранной (коснись мимо кнопок — всех сразу), "
            + "«Прозрачнее»/«Ярче» — видимость, «Сбросить», «Готово». То же — кнопка «Кнопки» в главном меню игры."));
        col.addView(button("Настроить кнопки в игре", v -> editControls()));
        col.addView(button("Вернуть кнопки на места по умолчанию", v -> resetControls()));

        section(col, "Лук и арбалет");
        col.addView(note("Прицел — точка и круг: стрела уходит в случайное место круга, и чем выше умение, тем он уже "
            + "(100 % — точка). Попадание решает полёт стрелы: в голову — урон вдвое, в ноги — вдвое меньше."));
        for (GraphicsSettings.Option o : GraphicsSettings.CONTROLS) addOption(col, o);
        col.addView(note("С автонаведением прицел сам встаёт на грудь цели. Без него прицел — середина экрана: "
            + "куда повернёшь камеру, туда и стрела (можно целиться в голову). Меняется и посреди игры."));

        section(col, "Как играть");
        col.addView(note(
            "• Слева — джойстик: появляется под пальцем; чуть сдвинуть — шаг, до края — бег.\n"
                + "• Справа — обзор пальцем. Коснуться человека или зверя — выбрать цель.\n"
                + "• Главная кнопка в углу делает то, что на ней написано: «Говорить», «Взять», «Открыть», "
                + "«Удар» (держать — серия ударов, свайп вбок — боковой удар), «Выстрел», заклинание.\n"
                + "• Вокруг неё: «Оружие» (подержать — колесо оружия и заклинаний), «Прыжок», «Блок».\n"
                + "• Вторая дуга: «Зелье» (коснуться — лечение, подержать — мана; при полном здоровье не пьёт), "
                + "«Автобой», «Собрать», «Отряд». «Сказать» — обратиться к человеку рядом (живые NPC).\n"
                + "• Справа вверху: «Сумка», «Карта», «Путь» (быстрое перемещение), «Журнал», «Герой», "
                + "«Сохранить» (подержать — быстрая загрузка), «Меню».\n"
                + "• Диалог, сумка, меню: касание по строке — выделить, «Выбрать» — подтвердить, «Назад» — закрыть. "
                + "Кнопка «Назад» телефона — Esc.\n"
                + "• Напарники: в разговоре с безымянным жителем, стражником или наёмником — «Пойдём со мной». "
                + "До шести, 10 золотых за уровень.\n"
                + "• «Приложение» в главном меню игры открывает этот экран, игра ждёт на паузе."));
        refreshGraphics();
        return col;
    }

    /** «Папка игры»: где игра, выбор вручную, варианты и сохранения. */
    private LinearLayout gamePage() {
        LinearLayout col = pageOf("Папка игры");
        gameFolder = text("", 15, TEXT);
        gameFolder.setPadding(0, pad / 2, 0, 0);
        col.addView(gameFolder);
        col.addView(button("Выбрать папку игры", v -> startActivityForResult(
            new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)));
        col.addView(note("Нужна распакованная Gothic II: Золотое издание (папки System, Data и _work) во внутренней "
            + "памяти телефона. Игра читается прямо из этой папки.\n\n"
            + "«Ночь Ворона» — Gothic II с дополнением (Яркендар, новые гильдии и задания). Без дополнения — "
            + "классическая Gothic II, как Gothic2c.exe в Золотом издании GOG; у неё свои ячейки сохранений.\n\n"
            + "Сохранения хранятся в самом приложении (удаление приложения их удалит) и с сохранениями "
            + "оригинальной игры на компьютере не совместимы."));
        refresh();
        return col;
    }

    /** «Журнал и сведения»: журнал для разбора сбоев, лицензии. */
    private LinearLayout infoPage() {
        LinearLayout col = pageOf("Журнал и сведения");
        col.addView(note("Журнал — что делала игра и живые NPC в последних двух запусках (старее удаляется сам). "
            + "Если что-то пошло не так — сохрани его сразу после игры и пришли."));
        col.addView(button("Сохранить журнал в «Загрузки»", v -> saveLog()));
        col.addView(button("Очистить журнал", v -> clearLog()));
        section(col, "О приложении");
        col.addView(button("Сведения о лицензиях", v -> startActivity(new Intent(this, LicensesActivity.class))));
        return col;
    }

    /** Галочка настройки; недействующая (регдолл живых без регдолла и т. п.) — неактивна. */
    private CheckBox checkBox(GraphicsSettings.Option o) {
        CheckBox c = new CheckBox(this);
        c.setText(o.title);
        c.setTextColor(GOLD);
        c.setTextSize(15);
        c.setButtonTintList(ColorStateList.valueOf(GOLD));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        c.setLayoutParams(lp);
        graphicsRefresh.add(() -> {
            c.setChecked(graphics.isOn(o));
            boolean enabled = graphics.isAvailable(o);
            c.setEnabled(enabled);
            c.setAlpha(enabled ? 1f : 0.5f);
        });
        c.setOnCheckedChangeListener((v, on) -> {
            if (on == graphics.isOn(o)) return;
            graphics.setOn(o, on);
            refreshGraphics();
            // управление идущая игра перечитывает, вернувшись на экран (GameActivity.onResume)
            if (isControl(o)) writeSettingsQuietly();
        });
        return c;
    }

    private static boolean isControl(GraphicsSettings.Option o) {
        for (GraphicsSettings.Option c : GraphicsSettings.CONTROLS) if (c == o) return true;
        return false;
    }

    /** g2m.ini сразу: идущая игра берёт из него настройки управления (Gothic::reloadControls). */
    private void writeSettingsQuietly() {
        try {
            graphics.write(getFilesDir());
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось сохранить настройку: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshGraphics() {
        if (graphicsSummary != null) graphicsSummary.setText(graphics.summary());
        for (Runnable r : graphicsRefresh) r.run();
    }

    /** «Играть»: игра уже идёт (экран открыт из её меню) - только перезапуском, с подтверждением. */
    private void play(String variant) {
        if (gamePath == null) return;
        if (gamePid(this) == 0) {
            startGame(variant);
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Игра уже идёт")
            .setMessage("Перезапустить игру? Всё, что не сохранено, пропадёт.")
            .setPositiveButton("Перезапустить", (dlg, w) -> restartGame(variant))
            .setNegativeButton("Отмена", null)
            .show();
    }

    /** Завершить процесс игры и, когда его не станет, запустить снова (не дольше 5 с ожидания). */
    private void restartGame(String variant) {
        int pid = gamePid(this);
        if (pid != 0) Process.killProcess(pid);
        restarting = true;
        playNotr.setEnabled(false);
        playClassic.setEnabled(false);
        resume.setVisibility(View.GONE);
        status.setText("Перезапуск игры…");
        new Thread(() -> {
            long until = SystemClock.uptimeMillis() + 5000;
            while (gamePid(this) != 0 && SystemClock.uptimeMillis() < until) SystemClock.sleep(100);
            runOnUiThread(() -> {
                restarting = false;
                if (gamePid(this) != 0) {
                    Toast.makeText(this, "Игра не закрылась — попробуй ещё раз.", Toast.LENGTH_LONG).show();
                    refresh();
                    return;
                }
                startGame(variant);
            });
        }).start();
    }

    private void startGame(String variant) {
        startGame(variant, false);
    }

    /** edit — сразу открыть настройку кнопок (поверх главного меню игры). */
    private void startGame(String variant, boolean edit) {
        if (gamePath == null) return;
        try {
            writeFile("gothic_path.txt", gamePath);
            writeFile("game_variant.txt", variant);
            graphics.write(getFilesDir());
        } catch (IOException e) {
            Toast.makeText(this, "Не удалось записать путь: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        prefs().edit().putString(PREF_VARIANT, variant).apply();
        Intent i = new Intent(this, GameActivity.class);
        if (edit) i.putExtra(GameActivity.EXTRA_EDIT_CONTROLS, true);
        startActivity(i);
        finish();
    }

    /** «Настроить кнопки в игре»: игра идёт — вернуться в неё с настройкой, нет — запустить последний вариант. */
    private void editControls() {
        if (gamePid(this) != 0) {
            Intent i = new Intent(this, GameActivity.class);
            i.putExtra(GameActivity.EXTRA_EDIT_CONTROLS, true);
            startActivity(i);
            finish();
            return;
        }
        if (gamePath == null || !GameFolder.isGameDir(new File(gamePath))) {
            Toast.makeText(this, "Сначала нужна папка игры.", Toast.LENGTH_LONG).show();
            return;
        }
        String last = prefs().getString(PREF_VARIANT, VARIANT_NOTR);
        if (VARIANT_CLASSIC.equals(last) && !GameFolder.hasClassic(new File(gamePath))) last = VARIANT_NOTR;
        startGame(last, true);
    }

    private void resetControls() {
        new File(getFilesDir(), "touch_layout.ini").delete();
        Toast.makeText(this, gamePid(this) != 0
            ? "Кнопки встанут на места после перезапуска игры (или «Сбросить» в настройке кнопок)."
            : "Кнопки — на местах по умолчанию.", Toast.LENGTH_LONG).show();
    }

    /** «Вернуться в игру»: экран игры - singleTask, он выходит вперёд, экран запуска закрывается. */
    private void resumeGame() {
        if (gamePid(this) == 0) {
            refresh();
            return;
        }
        startActivity(new Intent(this, GameActivity.class));
        finish();
    }

    private void writeFile(String name, String text) throws IOException {
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Процесс игры (GameActivity, манифест); 0 - игра не запущена. */
    static int gamePid(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> list = am != null ? am.getRunningAppProcesses() : null;
        if (list == null) return 0;
        String name = c.getPackageName() + GAME_PROCESS_SUFFIX;
        for (ActivityManager.RunningAppProcessInfo p : list) {
            if (name.equals(p.processName)) return p.pid;
        }
        return 0;
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
            for (String name : new String[]{"log.txt", "crash.log", "crash.prev.log", "ai/ai-log.old.txt", "ai/ai-log.txt"}) {
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

    /** Журнал игры пишется заново при каждом запуске; здесь — удалить всё, что накопилось (не во время игры). */
    private void clearLog() {
        if (gamePid(this) != 0) {
            Toast.makeText(this, "Игра идёт — журнал очистится при следующем запуске.", Toast.LENGTH_LONG).show();
            return;
        }
        for (String name : new String[]{"log.txt", "crash.log", "crash.prev.log"}) new File(getFilesDir(), name).delete();
        gothic2.mobile.og.ai.AiLog.clear(new File(getFilesDir(), "ai"));
        Toast.makeText(this, "Журнал очищен.", Toast.LENGTH_SHORT).show();
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


    private LinearLayout column() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(BG);
        return col;
    }

    private void section(LinearLayout col, String title) {
        TextView t = text(title, 20, GOLD);
        t.setTypeface(Typeface.SERIF, Typeface.BOLD);
        t.setPadding(0, pad, 0, pad / 4);
        col.addView(t);
    }

    private TextView note(String s) {
        TextView t = text(s, 14, DIM);
        t.setPadding(0, pad / 3, 0, 0);
        return t;
    }

    /** Строка раздела: название, под ним — коротко, что внутри (возвращается — обновлять), справа «›». */
    private TextView navRow(LinearLayout col, String title, Runnable open) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(PANEL);
        row.setPadding((int) (14 * d), (int) (10 * d), (int) (14 * d), (int) (10 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * d);
        row.setLayoutParams(lp);
        row.setClickable(true);
        row.setOnClickListener(v -> open.run());

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        texts.addView(text(title, 17, GOLD));
        TextView sub = text("", 13, DIM);
        texts.addView(sub);
        row.addView(texts);
        row.addView(text("›", 26, GOLD));
        col.addView(row);
        return sub;
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
        b.setBackgroundColor(PANEL);
        b.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (8 * getResources().getDisplayMetrics().density);
        b.setLayoutParams(lp);
        b.setOnClickListener(l);
        return b;
    }
}
