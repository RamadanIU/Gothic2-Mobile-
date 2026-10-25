package gothic2.mobile.og;

import java.io.File;
import java.util.ArrayDeque;

/** Папка игры игрока: проверка, поиск в памяти телефона, путь из идентификатора папки SAF. */
final class GameFolder {
    private GameFolder() {
    }

    /** Папка установленной игры: Data и _work/Data/Scripts/_compiled (имена без учёта регистра). */
    static boolean isGameDir(File dir) {
        if (!dir.isDirectory()) return false;
        File compiled = child(child(child(child(dir, "_work"), "data"), "scripts"), "_compiled");
        return compiled != null && child(dir, "data") != null;
    }

    /**
     * В копии есть классическая Gothic II без дополнения: в Золотом издании GOG её скрипты лежат отдельно —
     * _work/Data/Scripts/_compiled_GII/Gothic.dat (так её запускает Gothic2c.bat).
     */
    static boolean hasClassic(File dir) {
        File compiled = child(child(child(child(dir, "_work"), "data"), "scripts"), "_compiled_GII");
        return file(compiled, "gothic.dat") != null;
    }

    private static File child(File dir, String name) {
        File f = entry(dir, name);
        return f != null && f.isDirectory() ? f : null;
    }

    private static File file(File dir, String name) {
        File f = entry(dir, name);
        return f != null && f.isFile() ? f : null;
    }

    private static File entry(File dir, String name) {
        if (dir == null) return null;
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) {
            if (n.equalsIgnoreCase(name)) return new File(dir, n);
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

    /** Идентификатор папки SAF («primary:Games/Gothic», «1A2B-3C4D:Gothic», «raw:/…») → путь. */
    static String docIdToPath(String id, String primaryRoot) {
        if (id.startsWith("raw:")) return id.substring(4);
        int colon = id.indexOf(':');
        if (colon < 0) return null;
        String volume = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        String base;
        if ("primary".equalsIgnoreCase(volume)) base = primaryRoot;
        else if (volume.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) base = "/storage/" + volume; // карта памяти
        else return null; // «Загрузки», облака и т. п. — не папка на диске
        return rel.isEmpty() ? base : base + "/" + rel;
    }
}
