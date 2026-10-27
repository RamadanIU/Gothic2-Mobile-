package gothic2.mobile.og.ai;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Память Геральта: краткая сводка, важные заметки (remember) и журнал последних событий. Своя у каждого
 * сохранения игры: движок перед записью ставит метку (aivar Геральта), память пишется в файл
 * <прохождение>_<метка>.json; при загрузке читается файл той же метки. Загрузил старое сохранение —
 * Геральт не помнит того, что было после него.
 */
public final class Memory {
    public static final int JOURNAL_MAX = 80;
    public static final int JOURNAL_KEEP = 30;
    /** Беседы с жителями: последние реплики с каждым (житель помнит, о чём говорили), не больше TALKS_NPCS жителей. */
    public static final int TALK_LINES = 20;
    public static final int TALKS_NPCS = 40;
    /** Что житель (или Геральт) видел и слышал рядом: последние HEARD_LINES записей, не больше TALKS_NPCS жителей. */
    public static final int HEARD_LINES = 12;
    /** Ключ Геральта в беседах и «слышал рядом» (id экземпляра в скриптах). */
    public static final String GERALT = "VLK_2000_THORUS";
    private static final int FILES_MAX = 60;

    private final File dir;
    private long story;
    public String summary = "";
    public final List<String> notes = new ArrayList<>();
    public final List<String> journal = new ArrayList<>();
    /** id жителя (экземпляр скрипта) → реплики «Геральт: …» / «Имя: …»; порядок — по последней беседе. */
    public final Map<String, List<String>> talks = new LinkedHashMap<>();
    /** id жителя → что он запомнил сам (remember в его ходе), до NPC_NOTES штук. */
    public final Map<String, List<String>> npcNotes = new LinkedHashMap<>();
    /**
     * id жителя (и Геральта) → что он видел и слышал рядом: реплики чужих разговоров (вслух, до 10 м), кто кого
     * избил, кто очнулся. Прежде NPC не знали, о чём говорили и что случилось у них под носом.
     */
    public final Map<String, List<String>> heard = new LinkedHashMap<>();
    public static final int NPC_NOTES = 10;

    public Memory(File dir) {
        this.dir = dir;
    }

    public synchronized long story() {
        return story;
    }

    /** Загрузка для прохождения и метки сохранения; метки нет или файла нет — пустая память. */
    public synchronized void load(long story, long token) {
        this.story = story;
        summary = "";
        notes.clear();
        journal.clear();
        talks.clear();
        npcNotes.clear();
        heard.clear();
        if (token == 0) return;
        File f = file(story, token);
        if (!f.isFile()) return;
        try {
            Map<String, Object> m = Json.obj(Json.parse(readText(f)));
            summary = Json.str(m, "summary");
            for (Object o : Json.arr(m.get("notes"))) notes.add(String.valueOf(o));
            for (Object o : Json.arr(m.get("journal"))) journal.add(String.valueOf(o));
            for (Map.Entry<String, Object> e : Json.obj(m.get("talks")).entrySet()) {
                List<String> lines = new ArrayList<>();
                for (Object o : Json.arr(e.getValue())) lines.add(String.valueOf(o));
                talks.put(e.getKey(), lines);
            }
            for (Map.Entry<String, Object> e : Json.obj(m.get("npcNotes")).entrySet()) {
                List<String> lines = new ArrayList<>();
                for (Object o : Json.arr(e.getValue())) lines.add(String.valueOf(o));
                npcNotes.put(e.getKey(), lines);
            }
            for (Map.Entry<String, Object> e : Json.obj(m.get("heard")).entrySet()) {
                List<String> lines = new ArrayList<>();
                for (Object o : Json.arr(e.getValue())) lines.add(String.valueOf(o));
                heard.put(e.getKey(), lines);
            }
        } catch (IOException | IllegalArgumentException e) {
            // повреждённый файл - пустая память, игра идёт
        }
    }

    /** Запись под меткой сохранения (вызывается из потока игры перед записью сохранения). */
    public synchronized void save(long story, long token) {
        this.story = story;
        if (token == 0) return;
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        List<Object> n = new ArrayList<>(notes);
        List<Object> j = new ArrayList<>(journal);
        Map<String, Object> t = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : talks.entrySet()) t.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        Map<String, Object> nn = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : npcNotes.entrySet()) nn.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        Map<String, Object> hh = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : heard.entrySet()) hh.put(e.getKey(), new ArrayList<Object>(e.getValue()));
        String text = Json.write(Json.of("story", story, "token", token, "summary", summary, "notes", n, "journal", j,
            "talks", t, "npcNotes", nn, "heard", hh));
        File f = file(story, token);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
        prune();
    }

    public synchronized void add(String entry) {
        journal.add(entry);
    }

    public synchronized void note(String note) {
        String n = note.trim();
        if (n.isEmpty() || notes.contains(n)) return;
        notes.add(n);
        while (notes.size() > 40) notes.remove(0);
    }

    /** Беседа с жителем: прежние реплики (копия). */
    public synchronized List<String> talk(String id) {
        List<String> l = talks.get(id);
        return l == null ? new ArrayList<>() : new ArrayList<>(l);
    }

    /** Реплика в беседу с жителем; старые реплики и давно забытые жители вытесняются. */
    public synchronized void addTalk(String id, String line) {
        if (id.isEmpty()) return;
        List<String> l = talks.remove(id);
        if (l == null) l = new ArrayList<>();
        l.add(line);
        while (l.size() > TALK_LINES) l.remove(0);
        talks.put(id, l);
        while (talks.size() > TALKS_NPCS) talks.remove(talks.keySet().iterator().next());
    }

    /** Заметка жителя (его remember); старые вытесняются, жителей — не больше TALKS_NPCS. */
    public synchronized void npcNote(String id, String note) {
        String n = note.trim();
        if (id.isEmpty() || n.isEmpty()) return;
        List<String> l = npcNotes.remove(id);
        if (l == null) l = new ArrayList<>();
        if (!l.contains(n)) l.add(n);
        while (l.size() > NPC_NOTES) l.remove(0);
        npcNotes.put(id, l);
        while (npcNotes.size() > TALKS_NPCS) npcNotes.remove(npcNotes.keySet().iterator().next());
    }

    /** Что видел и слышал рядом (чужой разговор вслух, драка); старое вытесняется, жителей — не больше TALKS_NPCS. */
    public synchronized void hear(String id, String line) {
        String l = line.trim();
        if (id.isEmpty() || l.isEmpty()) return;
        List<String> h = heard.remove(id);
        if (h == null) h = new ArrayList<>();
        // Одну и ту же реплику могли прислать дважды (повтор строки в диалоге) - не засорять
        if (h.isEmpty() || !h.get(h.size() - 1).equals(l)) h.add(l);
        while (h.size() > HEARD_LINES) h.remove(0);
        heard.put(id, h);
        while (heard.size() > TALKS_NPCS) heard.remove(heard.keySet().iterator().next());
    }

    /** Что видел и слышал рядом (копия). */
    public synchronized List<String> heardBy(String id) {
        List<String> h = heard.get(id);
        return h == null ? new ArrayList<>() : new ArrayList<>(h);
    }

    /** Память жителя для его хода: что запомнил, что видел и слышал рядом и прежние беседы (с героем, Геральтом, другими). */
    public synchronized String npcContext(String id) {
        StringBuilder sb = new StringBuilder();
        List<String> n = npcNotes.get(id);
        if (n != null && !n.isEmpty()) {
            sb.append("Ты запомнил:");
            for (String s : n) sb.append("\n- ").append(s);
            sb.append('\n');
        }
        appendHeard(sb, id);
        List<String> t = talks.get(id);
        if (t != null && !t.isEmpty()) {
            sb.append("Недавние разговоры:");
            for (String s : t) sb.append('\n').append(s);
        }
        return sb.toString().trim();
    }

    private void appendHeard(StringBuilder sb, String id) {
        List<String> h = heard.get(id);
        if (h == null || h.isEmpty()) return;
        sb.append("Что ты видел и слышал рядом (чужие разговоры вслух, драки):");
        for (String s : h) sb.append("\n- ").append(s);
        sb.append('\n');
    }

    /** Нужно ли сжать журнал: старые записи — в сводку. */
    public synchronized boolean needsSummary() {
        return journal.size() > JOURNAL_MAX;
    }

    /** Старые записи для сжатия (все, кроме последних JOURNAL_KEEP). */
    public synchronized List<String> oldEntries() {
        int n = Math.max(0, journal.size() - JOURNAL_KEEP);
        return new ArrayList<>(journal.subList(0, n));
    }

    public synchronized void applySummary(String newSummary, int dropped) {
        summary = newSummary.trim();
        for (int i = 0; i < dropped && !journal.isEmpty(); i++) journal.remove(0);
    }

    /** Память для модели: сводка, заметки, последние записи журнала. */
    public synchronized String context(int lastEntries) {
        StringBuilder sb = new StringBuilder();
        if (!summary.isEmpty()) sb.append("Что ты помнишь: ").append(summary).append('\n');
        if (!notes.isEmpty()) {
            sb.append("Важное (ты запомнил):");
            for (String n : notes) sb.append("\n- ").append(n);
            sb.append('\n');
        }
        appendHeard(sb, GERALT);
        if (!journal.isEmpty()) {
            sb.append("Недавно:");
            int from = Math.max(0, journal.size() - lastEntries);
            for (int i = from; i < journal.size(); i++) sb.append('\n').append(journal.get(i));
        }
        return sb.toString().trim();
    }

    File file(long story, long token) {
        return new File(dir, story + "_" + token + ".json");
    }

    /** Старые файлы памяти (по времени изменения) удаляются: сохранений столько не бывает. */
    private void prune() {
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null || files.length <= FILES_MAX) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - FILES_MAX; i++) files[i].delete();
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(f.length(), 4 * 1024 * 1024)];
            int off = 0;
            int n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            return new String(b, 0, off, StandardCharsets.UTF_8);
        }
    }
}
