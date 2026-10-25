package gothic2.mobile.og;

import android.app.NativeActivity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.WindowManager;

import gothic2.mobile.og.ai.AiBridge;

/**
 * Экран игры: NativeActivity движка OpenGothic во весь экран, без системных панелей,
 * экран не гаснет. Кнопку «Назад» движок получает как Esc (патч Tempest).
 *
 * Частота дисплея подстраивается под ограничение кадров из настроек графики: при 30 FPS дисплею
 * незачем обновляться 120 раз в секунду (лишний расход и нагрев), а кадры ложатся на каждое
 * второе обновление ровно, без подёргиваний.
 *
 * Игра идёт в своём процессе (:game, манифест): движок после выхода завершает процесс, а экран запуска
 * остаётся — из него игру можно перезапустить с другими настройками или другим вариантом. Кнопка
 * «Приложение» в меню игры открывает экран запуска поверх игры ({@link #openAppMenu}); свёрнутая игра
 * стоит на паузе и продолжается с того же места (патч Tempest: потеря окна — не выход).
 */
public class GameActivity extends NativeActivity {
    private static volatile GameActivity current;

    private int fpsLimit;

    private static native void nativeAttachApp();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Живой NPC: Java-методы движка (AiBridge.native*) находятся, только если библиотеку загрузил и
        // System.loadLibrary - NativeActivity грузит её сама, мимо Java. Тот же файл, второй загрузки нет.
        try {
            System.loadLibrary("Gothic2Notr");
        } catch (UnsatisfiedLinkError ignored) {
            // NativeActivity сообщит об ошибке сама
        }
        super.onCreate(savedInstanceState);
        current = this;
        try {
            nativeAttachApp();
        } catch (UnsatisfiedLinkError ignored) {
            // движок без связи с приложением - не будет только кнопки «Приложение»
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUi();
        fpsLimit = new GraphicsSettings(getSharedPreferences("launcher", MODE_PRIVATE)).fpsLimit();
        preferDisplayMode(fpsLimit);
        AiBridge.start(this);
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        AiBridge.stop();
        super.onDestroy();
    }

    /** Кнопка «Приложение» в меню игры (движок, поток игры): экран запуска поверх игры. */
    static void openAppMenu() {
        GameActivity a = current;
        if (a == null) return;
        a.runOnUiThread(() -> {
            Intent i = new Intent(a, LauncherActivity.class);
            i.putExtra(LauncherActivity.EXTRA_FROM_GAME, true);
            a.startActivity(i);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (!AiBridge.onPermission(requestCode, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        super.surfaceCreated(holder);
        // Android 11+: частота кадров самой поверхности - система выбирает подходящий режим дисплея
        if (fpsLimit > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                holder.getSurface().setFrameRate(fpsLimit, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
            } catch (RuntimeException ignored) {
                // поверхность уже освобождена - не важно
            }
        }
    }

    /**
     * Режим дисплея с наименьшей частотой не ниже 60 Гц, кратной ограничению кадров (30 → 60 Гц,
     * 45 → 90 Гц), при том же разрешении. Без ограничения кадров - как решит система.
     */
    @SuppressWarnings("deprecation")
    private void preferDisplayMode(int fps) {
        if (fps <= 0) return;
        Display display = getWindowManager().getDefaultDisplay();
        Display.Mode current = display.getMode();
        Display.Mode best = null;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() != current.getPhysicalWidth()
                || m.getPhysicalHeight() != current.getPhysicalHeight()) continue;
            float rate = m.getRefreshRate();
            float ratio = rate / fps;
            // не ниже 60 Гц: при 30-герцовом дисплее любой долгий кадр - сразу 15 FPS, и меню вяло
            if (rate + 0.5f < Math.max(fps, 60) || Math.abs(ratio - Math.round(ratio)) > 0.02f) continue;
            if (best == null || rate < best.getRefreshRate()) best = m;
        }
        if (best == null || best.getModeId() == current.getModeId()) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        getWindow().setAttributes(lp);
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }
}
