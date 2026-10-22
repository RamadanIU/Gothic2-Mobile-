package gothic2.mobile.og.ai;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал живого NPC (files/ai/ai-log.txt): ходы модели, время ответа, инструменты, ошибки — без ключей.
 * Входит в «Сохранить журнал в «Загрузки»». Больше 256 КБ — прежний уходит в ai-log.old.txt.
 */
public final class AiLog {
    private static final long MAX = 256 * 1024;
    private static File file;

    private AiLog() {
    }

    public static synchronized void init(File dir) {
        if (!dir.isDirectory()) dir.mkdirs();
        file = new File(dir, "ai-log.txt");
    }

    public static synchronized void i(String msg) {
        if (file == null) return;
        if (file.length() > MAX) {
            File old = new File(file.getParentFile(), "ai-log.old.txt");
            old.delete();
            file.renameTo(old);
        }
        String line = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + " " + msg.replace('\n', ' ') + "\n";
        try (OutputStream os = new FileOutputStream(file, true)) {
            os.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // журнал - не главное
        }
    }

    static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
