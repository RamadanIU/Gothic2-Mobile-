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
import java.util.regex.Pattern;

/**
 * Память Геральта: сжатая память, важные заметки (remember) и журнал последних событий (до STEPS записей). Своя у каждого
 * сохранения игры: движок перед записью ставит метку (aivar Геральта), память пишется в файл
 * <прохождение>_<метка>.json; при загрузке читается файл той же метки. Загрузил старое сохранение —
 * Геральт не помнит того, что было после него.
 * <p>
 * Сжатие (Gothic II Mobile, 2026-10-29): набралось больше STEPS записей — модели уходит вся память (прежняя сжатая,
 * заметки, слышанное, записи), и она переписывает её заново: поворотные события — по короткой фразе, мелочи — одним-двумя
 * словами (Persona.digest). Сжатое заменяет всё, что ушло модели; последние JOURNAL_KEEP записей остаются как есть, чтобы
 * не оборвать идущий разговор. То же у каждого жителя: его беседы, заметки и слышанное — в его сжатую память.
 */
public final class Memory {
    /** Шагов памяти: столько последних записей журнала видит модель; больше — пора сжимать. */
    public static final int STEPS = 100;
    public static final int JOURNAL_MAX = STEPS;
    /** После сжатия остаются как есть: идущий разговор не обрывается. */
    public static final int JOURNAL_KEEP = 10;
    /** Сжать не удаётся (нет сети, ключа) — журнал не растёт без конца. */
    public static final int JOURNAL_HARD = 3 * STEPS;
    /** Беседы с жителями: до TALK_LINES реплик с каждым, больше — сжатие; не больше TALKS_NPCS жителей. */
    public static final int TALK_LINES = STEPS;
    public static final int TALK_KEEP = 10;
    public static final int TALK_HARD = 3 * STEPS;
    public static final int TALKS_NPCS = 40;
    /** Сжатая память жителей живёт дольше бесед: не больше SUMMARY_NPCS жителей. */
    public static final int SUMMARY_NPCS = 200;
    /** Что житель (или Геральт) видел и слышал рядом: последние HEARD_LINES записей, не больше TALKS_NPCS жителей. */
    public static final int HEARD_LINES = 30;
    /** Сжатая память длиннее — обрезается по строкам (модель не послушалась). */
    public static final int SUMMARY_CHARS = 6000;
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
    public static final int NPC_NOTES = 20;
    /** id жителя → его сжатая память (поворотные события и мелочи). */
    public final Map<String, String> npcSummary = new LinkedHashMap<>();
    /** Растёт при каждой загрузке: сжатие, начатое до загрузки, не трогает новую память. */
    private int gen;

    public Memory(File dir) {
        this.dir = dir;
    }

    public synchronized long story() {
        return story;
    }

    /** Загрузка для прохождения и метки сохранения; метки нет или файла нет — пустая память. */
    public synchronized void load(long story, long token) {
        this.story = story;
        gen++;
        summary = "";
        notes.clear();
        journal.clear();
        talks.clear();
        npcNotes.clear();
        heard.clear();
        npcSummary.clear();
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
            for (Map.Entry<String, Object> e : Json.obj(m.get("npcSummary")).entrySet()) {
                npcSummary.put(e.getKey(), String.valueOf(e.getValue()));
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
        Map<String, Object> ns = new LinkedHashMap<>(npcSummary);
        String text = Json.write(Json.of("story", story, "token", token, "summary", summary, "notes", n, "journal", j,
            "talks", t, "npcNotes", nn, "heard", hh, "npcSummary", ns));
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
        while (journal.size() > JOURNAL_HARD) journal.remove(0);
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
        while (l.size() > TALK_HARD) l.remove(0);
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

    /** Память жителя для его хода: сжатая память, что запомнил, что видел и слышал рядом и прежние беседы (с героем, Геральтом, другими). */
    public synchronized String npcContext(String id) {
        StringBuilder sb = new StringBuilder();
        appendSummary(sb, npcSummary.get(id));
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
            for (String s : t.subList(Math.max(0, t.size() - TALK_LINES), t.size())) sb.append('\n').append(s);
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

    /**
     * Что пора сжать: Геральт (журнал больше STEPS) или житель (бесед больше TALK_LINES); null — нечего. Вся память
     * сжимаемого — снимком: модель переписывает её целиком, applyDigest заменяет ушедшее сжатым.
     */
    public synchronized Digest digestDue() {
        if (journal.size() > JOURNAL_MAX) {
            return new Digest(gen, GERALT, summary, notes, heardBy(GERALT),
                journal.subList(0, journal.size() - JOURNAL_KEEP));
        }
        for (Map.Entry<String, List<String>> e : talks.entrySet()) {
            List<String> t = e.getValue();
            if (t.size() <= TALK_LINES) continue;
            String id = e.getKey();
            List<String> n = npcNotes.get(id);
            return new Digest(gen, id, npcSummary.getOrDefault(id, ""), n == null ? new ArrayList<>() : n, heardBy(id),
                t.subList(0, t.size() - TALK_KEEP));
        }
        return null;
    }

    /** Сжатая память вместо всего, что ушло модели (записи с начала журнала, заметки, слышанное); false — не применено. */
    public synchronized boolean applyDigest(Digest d, String text) {
        String s = cleanSummary(text);
        // Нет раздела «ГЛАВНОЕ» - модель ответила не памятью (отказ, болтовня): прежняя память дороже
        if (d.gen != gen || !s.toUpperCase(java.util.Locale.ROOT).contains("ГЛАВНОЕ")) return false;
        if (d.geralt()) {
            summary = s;
            dropHead(journal, d.entries);
            notes.removeAll(d.notes);
        } else {
            npcSummary.remove(d.id);
            npcSummary.put(d.id, s);
            while (npcSummary.size() > SUMMARY_NPCS) npcSummary.remove(npcSummary.keySet().iterator().next());
            List<String> t = talks.get(d.id);
            if (t != null) dropHead(t, d.entries);
            List<String> n = npcNotes.get(d.id);
            if (n != null) {
                n.removeAll(d.notes);
                if (n.isEmpty()) npcNotes.remove(d.id);
            }
        }
        List<String> h = heard.get(d.id);
        if (h != null) {
            for (String line : d.heard) h.remove(line);
            if (h.isEmpty()) heard.remove(d.id);
        }
        return true;
    }

    /** Снимает с начала списка сжатые записи; вытесненные за время сжатия (предел списка) пропускаются. */
    private static void dropHead(List<String> list, List<String> done) {
        for (int i = 0; i < done.size() && !list.isEmpty(); i++) {
            if (list.get(0).equals(done.get(i))) list.remove(0);
        }
    }

    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?(</think>|$)");

    /** Ответ модели — в сжатую память: без рассуждений и разметки, пустые строки прочь, не длиннее SUMMARY_CHARS. */
    static String cleanSummary(String text) {
        if (text == null) return "";
        String t = THINK.matcher(text).replaceAll("").replace("```", "").replace("**", "").replace("\r", "");
        StringBuilder sb = new StringBuilder();
        for (String line : t.split("\n")) {
            String l = line.replaceAll("\\s+", " ").trim();
            if (l.isEmpty()) continue;
            if (sb.length() + l.length() + 1 > SUMMARY_CHARS) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString();
    }

    private static void appendSummary(StringBuilder sb, String s) {
        if (s == null || s.isEmpty()) return;
        sb.append("Что ты помнишь (сжатая память: главное и мелочи):\n").append(s).append('\n');
    }

    /** Память для модели: сжатая память, заметки, слышанное, последние записи журнала (до STEPS). */
    public synchronized String context() {
        return context(STEPS);
    }

    public synchronized String context(int lastEntries) {
        StringBuilder sb = new StringBuilder();
        appendSummary(sb, summary);
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

    /** Снимок памяти для сжатия: всё, что модель перепишет заново. */
    public static final class Digest {
        final int gen;
        /** Чья память: GERALT или id жителя. */
        public final String id;
        public final String summary;
        public final List<String> notes;
        public final List<String> heard;
        /** Записи журнала (у жителя — реплики бесед), которые сожмутся. */
        public final List<String> entries;

        Digest(int gen, String id, String summary, List<String> notes, List<String> heard, List<String> entries) {
            this.gen = gen;
            this.id = id;
            this.summary = summary;
            this.notes = new ArrayList<>(notes);
            this.heard = new ArrayList<>(heard);
            this.entries = new ArrayList<>(entries);
        }

        public boolean geralt() {
            return GERALT.equals(id);
        }

        /** Вся память одним текстом — для модели. */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("Прежняя сжатая память:\n").append(summary.isEmpty() ? "нет" : summary);
            if (!notes.isEmpty()) {
                sb.append("\n\nЗаметки (запомнил сам, всё это — главное):");
                for (String n : notes) sb.append("\n- ").append(n);
            }
            if (!heard.isEmpty()) {
                sb.append("\n\nВидел и слышал рядом:");
                for (String h : heard) sb.append("\n- ").append(h);
            }
            sb.append(geralt() ? "\n\nЗаписи событий (по порядку):" : "\n\nРазговоры и дела (по порядку):");
            for (String e : entries) sb.append('\n').append(e);
            return sb.toString();
        }
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
