package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Сюжет игры в разговоре с живым жителем: темы его диалогов из скриптов игры (C_INFO), которые доступны герою прямо
 * сейчас, — коротким текстом для модели (снимок движка: topics, choices, quests), и итог сыгранной темы (act_done
 * инструмента story_topic: что сказано в игре, задание в дневнике, вещи, опыт, новые варианты ответа). Тему исполняет
 * движок скриптом игры (AiNpc::storyExec) — последствия настоящие, как в родном диалоге; модель пересказывает её
 * слова своими.
 */
public final class Story {
    private Story() {
    }

    /** Сколько слов жителя из темы показывать модели до того, как тема сыграна (полностью — в итоге). */
    static final int PREVIEW = 360;

    /** Темы, начатый выбор и дневник героя из снимка жителя (поле story); пусто — сюжетных тем с ним сейчас нет. */
    public static String describe(Map<String, Object> snap) {
        if (snap == null) return "";
        Map<String, Object> s = Json.obj(snap.get("story"));
        StringBuilder sb = new StringBuilder();
        List<Object> topics = Json.arr(s.get("topics"));
        if (!topics.isEmpty()) {
            sb.append("Темы разговора — сюжет игры, о чём с тобой можно поговорить прямо сейчас (сыграть — story_topic по id; ")
                .append("слова и последствия настоящие):");
            for (Object o : topics) {
                Map<String, Object> t = Json.obj(o);
                sb.append("\n- ").append(Json.str(t, "id")).append(" — ");
                String hero = Json.str(t, "hero");
                if (Json.bool(t, "imp", false) || hero.isEmpty()) sb.append("ты сам хочешь сказать это герою, как только он заговорит с тобой");
                else sb.append("герой спрашивает или говорит: «").append(hero).append('»');
                if (Json.bool(t, "perm", false)) sb.append(" (можно не раз)");
                String says = cut(Json.str(t, "says"), PREVIEW);
                if (!says.isEmpty()) sb.append(". Ты в ней говоришь: «").append(says).append('»');
                String quest = Json.str(t, "quest");
                if (!quest.isEmpty()) sb.append(". Даёт задание «").append(quest).append('»');
                String done = Json.str(t, "done");
                if (!done.isEmpty()) sb.append(". Задание «").append(done).append("» будет выполнено");
                String log = cut(Json.str(t, "log"), 200);
                if (!log.isEmpty()) sb.append(". В дневник герою: «").append(log).append('»');
                sb.append('.');
            }
        }
        List<Object> choices = Json.arr(s.get("choices"));
        if (!choices.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Начатая тема ждёт ответа героя — варианты (story_topic по id, когда его слова ясно выбирают один):");
            for (Object o : choices) {
                Map<String, Object> c = Json.obj(o);
                sb.append("\n- ").append(Json.str(c, "id")).append(" — «").append(Json.str(c, "text")).append('»');
            }
        }
        List<Object> quests = Json.arr(s.get("quests"));
        if (!quests.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Дневник героя — его задания (это знает герой, а не ты: не пересказывай, только чтобы понимать, о чём он):");
            for (Object o : quests) {
                Map<String, Object> q = Json.obj(o);
                sb.append("\n- «").append(Json.str(q, "name")).append('»');
                String e = cut(Json.str(q, "entry"), 160);
                if (!e.isEmpty()) sb.append(": ").append(e);
            }
        }
        return sb.toString();
    }

    /** Итог сыгранной темы для модели: что сказано в игре, что изменилось, что дальше. */
    public static String result(Map<String, Object> done) {
        Map<String, Object> st = Json.obj(done.get("story"));
        if (!Json.bool(done, "ok", false) || st.isEmpty()) {
            String note = Json.str(done, "note");
            return "не вышло: " + (note.isEmpty() ? "тема сейчас недоступна" : note)
                + ". Не обещай этого герою; ответь ему своими словами.";
        }
        StringBuilder sb = new StringBuilder("Тема сыграна — в игре это уже случилось.");
        StringBuilder mine = new StringBuilder();
        StringBuilder hero = new StringBuilder();
        for (Object o : Json.arr(st.get("lines"))) {
            Map<String, Object> l = Json.obj(o);
            String text = Json.str(l, "text").trim();
            if (text.isEmpty()) continue;
            StringBuilder out = Json.bool(l, "hero", false) ? hero : mine;
            if (out.length() > 0) out.append(' ');
            out.append(text);
        }
        if (mine.length() > 0) sb.append("\nТы сказал в игре: «").append(mine).append('»');
        if (hero.length() > 0) sb.append("\nГерой говорил в игре: «").append(hero).append('»');
        String effects = effects(st);
        if (!effects.isEmpty()) sb.append("\nИтог: ").append(effects).append('.');
        if (Json.bool(st, "ended", false)) sb.append("\nПо сценарию на этом ты закончил разговор.");
        List<Object> choices = Json.arr(st.get("choices"));
        if (!choices.isEmpty()) {
            sb.append("\nТеперь герою нужно выбрать ответ:");
            for (Object o : choices) {
                Map<String, Object> c = Json.obj(o);
                sb.append(" ").append(Json.str(c, "id")).append(" «").append(Json.str(c, "text")).append("»;");
            }
            sb.setLength(sb.length() - 1);
            sb.append(". Если его слова уже ясно выбирают — вызови story_topic с id варианта, иначе спроси его.");
        }
        List<Object> next = Json.arr(st.get("next"));
        if (!next.isEmpty()) {
            sb.append("\nТеперь можно поговорить и о: ");
            int n = 0;
            for (Object o : next) {
                Map<String, Object> t = Json.obj(o);
                if (n++ > 0) sb.append("; ");
                sb.append(Json.str(t, "id")).append(" «").append(Json.str(t, "hero")).append('»');
            }
            sb.append('.');
        }
        sb.append("\nСкажи это герою сейчас своими словами, в своём характере, обычно 2–5 предложений: не теряй сути (кого ")
            .append("найти, куда идти, что принести, сколько, что за это будет). Его слова не повторяй, о выдаче вещей и ")
            .append("записи в дневник не говори — герой видит их сам.");
        return sb.toString();
    }

    /** Коротко для памяти беседы: «(по сюжету: задание «…»; герой получил: …)»; пусто — ничего не изменилось. */
    public static String memo(Map<String, Object> done) {
        Map<String, Object> st = Json.obj(done.get("story"));
        if (!Json.bool(done, "ok", false) || st.isEmpty()) return "";
        String title = Json.str(st, "title");
        String effects = effects(st);
        if (title.isEmpty() && effects.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("(по сюжету");
        if (!title.isEmpty()) sb.append(" — «").append(cut(title, 80)).append('»');
        if (!effects.isEmpty()) sb.append(": ").append(cut(effects, 300));
        return sb.append(')').toString();
    }

    /** Что изменилось: дневник, вещи, золото, опыт, умения, гильдия. */
    static String effects(Map<String, Object> st) {
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(st.get("quests"))) {
            Map<String, Object> q = Json.obj(o);
            String name = Json.str(q, "name");
            String what;
            switch (Json.str(q, "status")) {
                case "new": what = "новое задание «" + name + "»"; break;
                case "success": what = "задание «" + name + "» выполнено"; break;
                case "failed": what = "задание «" + name + "» провалено"; break;
                case "obsolete": what = "задание «" + name + "» больше не нужно"; break;
                case "note": what = "заметка «" + name + "»"; break;
                default: what = "запись к заданию «" + name + "»"; break;
            }
            String e = cut(Json.str(q, "entry"), 220);
            out.add("в дневнике героя — " + what + (e.isEmpty() ? "" : ": «" + e + "»"));
        }
        String got = join(Json.arr(st.get("got")));
        if (!got.isEmpty()) out.add("герой получил: " + got);
        String gave = join(Json.arr(st.get("gave")));
        if (!gave.isEmpty()) out.add("герой отдал: " + gave);
        long gold = Json.num(st, "gold", 0);
        if (gold > 0) out.add("герой получил золота: " + gold);
        else if (gold < 0) out.add("герой заплатил золота: " + (-gold));
        long xp = Json.num(st, "xp", 0);
        if (xp > 0) out.add("герой получил опыт: " + xp);
        String attrs = join(Json.arr(st.get("attrs")));
        if (!attrs.isEmpty()) out.add("герой научился: " + attrs);
        String guild = Json.str(st, "guild");
        if (!guild.isEmpty()) out.add("герой теперь в гильдии: " + guild);
        return String.join("; ", out);
    }

    private static String join(List<Object> l) {
        StringBuilder sb = new StringBuilder();
        for (Object o : l) {
            String s = String.valueOf(o).trim();
            if (s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(s);
        }
        return sb.toString();
    }

    static String cut(String s, int max) {
        String t = s.trim();
        if (t.length() <= max) return t;
        int sp = t.lastIndexOf(' ', max);
        return (sp > max / 2 ? t.substring(0, sp) : t.substring(0, max)) + "…";
    }
}
