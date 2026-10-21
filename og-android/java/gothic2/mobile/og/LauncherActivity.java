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
import java.util.ArrayDeque;

/**
 * Экран запуска пробной сборки: разрешение «Файлы», поиск папки игры (System, Data, _work),
 * выбор вручную, «Играть», сохранение журнала в «Загрузки». Путь к игре движок читает
 * из files/gothic_path.txt (патч InstallDetect).
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (24 * d);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(Color.rgb(0x12, 0x0E, 0x0A));

        TextView title = text("Gothic II · проба", 30, GOLD);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        col.addView(title);
        col.addView(text("Движок OpenGothic прямо на телефоне, без Wine. Сенсорное управление и автобой.", 15, DIM));

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
                + "• Диалог и сумка: стрелки, «Выбрать», «Назад». Кнопка «Назад» телефона — Esc.\n\n"
                + "Сохранения этой пробы хранятся в самом приложении (удаление приложения их удалит) "
                + "и с сохранениями обычной игры не совместимы.",
            14, DIM);
        help.setPadding(0, pad, 0, 0);
        col.addView(help);

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
        if (gamePath != null && isGameDir(new File(gamePath))) {
            play.setEnabled(true);
            status.setText("Папка игры:\n" + gamePath);
            return;
        }
        play.setEnabled(false);
        if (searching) return;
        searching = true;
        status.setText("Ищу папку игры…");
        new Thread(() -> {
            File found = findGameDir(Environment.getExternalStorageDirectory());
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

    private void startGame() {
        if (gamePath == null) return;
        try (OutputStream out = new FileOutputStream(new File(getFilesDir(), "gothic_path.txt"))) {
            out.write(gamePath.getBytes(StandardCharsets.UTF_8));
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
        if (!isGameDir(dir)) {
            // Выбрали папку уровнем выше - поищем игру внутри
            File inner = findGameDir(dir);
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
        File out = new File(downloads, "gothic2-og-log.txt");
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

    /** Папка установленной игры: Data и _work/Data/Scripts/_compiled (имена без учёта регистра). */
    static boolean isGameDir(File dir) {
        if (!dir.isDirectory()) return false;
        File compiled = child(child(child(child(dir, "_work"), "data"), "scripts"), "_compiled");
        return compiled != null && child(dir, "data") != null;
    }

    private static File child(File dir, String name) {
        if (dir == null) return null;
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) {
                File f = new File(dir, n);
                return f.isDirectory() ? f : null;
            }
        }
        return null;
    }

    /** Поиск в ширину, не глубже 4 уровней и не больше 4000 папок; Android/ и скрытые пропускаются. */
    static File findGameDir(File root) {
        ArrayDeque<File> queue = new ArrayDeque<>();
        ArrayDeque<Integer> depth = new ArrayDeque<>();
        queue.add(root);
        depth.add(0);
        int visited = 0;
        while (!queue.isEmpty() && visited < 4000) {
            File dir = queue.poll();
            int dep = depth.poll();
            visited++;
            if (isGameDir(dir)) return dir;
            if (dep >= 4) continue;
            File[] subs = dir.listFiles(File::isDirectory);
            if (subs == null) continue;
            for (File s : subs) {
                String n = s.getName();
                if (n.startsWith(".") || (dep == 0 && n.equals("Android"))) continue;
                queue.add(s);
                depth.add(dep + 1);
            }
        }
        return null;
    }

    /** content://…/tree/primary%3AGames%2FGothic → /storage/emulated/0/Games/Gothic. */
    static String treeToPath(Uri tree) {
        String id;
        try {
            id = DocumentsContract.getTreeDocumentId(tree);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (id.startsWith("raw:")) return id.substring(4);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        String base = "primary".equalsIgnoreCase(volume)
            ? Environment.getExternalStorageDirectory().getPath()
            : "/storage/" + volume;
        return rel.isEmpty() ? base : base + "/" + rel;
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
