package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Кто что слышит (решение пользователя 2026-10-26). Герой выбрал человека касанием — говорит ему лично, вполголоса:
 * слышит только он. Иначе герой говорит вслух: слышат все люди в 10 м (движок присылает их списком near), а отвечает
 * тот, на кого герой смотрит, или тот, кого он назвал по имени («Мика, ...»). Реплики вслух — и героя, и NPC, и родных
 * диалогов игры — люди рядом запоминают (Memory.hear): прежде NPC не знали, о чём говорили у них под носом.
 */
final class Hearing {
    private Hearing() {
    }

    /**
     * Кого герой назвал по имени: ref человека из near (вслух, до 10 м), чьё имя есть в словах героя; несколько —
     * названный раньше, при равенстве — ближний (near идёт по расстоянию). Пусто — никого. Имена склоняются («Мике»,
     * «Ватрасу», «ополченцу»): слово героя совпадает с именем, если у них общее начало почти во всё имя.
     */
    static String named(String text, List<Object> near) {
        List<String> words = words(text);
        String best = "";
        int bestAt = Integer.MAX_VALUE;
        for (Object o : near) {
            Map<String, Object> n = Json.obj(o);
            String ref = Json.str(n, "ref");
            if (ref.isEmpty()) continue;
            int at = firstMention(words, Json.str(n, "name"));
            if (at >= 0 && at < bestAt) {
                best = ref;
                bestAt = at;
            }
        }
        return best;
    }

    /** Номер первого слова героя, которым он назвал это имя; -1 — не назвал. */
    static int firstMention(List<String> words, String name) {
        int first = -1;
        for (String part : words(name)) {
            if (part.length() < 3 || TITLES.contains(part)) continue;
            for (int i = 0; i < words.size(); i++) {
                if (sameName(words.get(i), part) && (first < 0 || i < first)) {
                    first = i;
                    break;
                }
            }
        }
        return first;
    }

    /** Слова, которые бывают частью имени, но сами по себе никого не называют. */
    private static final List<String> TITLES = java.util.Arrays.asList("лорд", "мастер", "брат", "сестра", "отец",
        "старый", "старик", "молодой", "толстый", "лысый", "дядя");

    /** Слово героя — то же имя в другом падеже: общее начало почти во всё имя, длина почти та же. */
    static boolean sameName(String word, String name) {
        if (word.equals(name)) return true;
        int common = 0;
        int max = Math.min(word.length(), name.length());
        while (common < max && word.charAt(common) == name.charAt(common)) common++;
        return common >= Math.max(3, name.length() - 2) && Math.abs(word.length() - name.length()) <= 3;
    }

    /** Слова строчными, «ё» — «е», без знаков. */
    static List<String> words(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        String t = text.toLowerCase(Locale.ROOT).replace('ё', 'е');
        for (int i = 0; i <= t.length(); i++) {
            char c = i < t.length() ? t.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                w.append(c);
            } else if (w.length() > 0) {
                out.add(w.toString());
                w.setLength(0);
            }
        }
        return out;
    }

    /** Кто ещё слышал (для подсказки модели): «Мика, Ополченец»; пусто — никто. */
    static String names(List<Object> near, String except) {
        StringBuilder sb = new StringBuilder();
        for (Object o : near) {
            Map<String, Object> n = Json.obj(o);
            if (Json.str(n, "ref").equals(except)) continue;
            String name = Json.str(n, "name");
            if (name.isEmpty()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    /**
     * Запись «слышал рядом» для того, кто стоял рядом: «Мика (собеседник — герой): «…»». speaker и to — из события
     * heard движка (ref, name); имя героя — «герой», Геральта — его имя.
     */
    static String overheard(Map<String, Object> speaker, Map<String, Object> to, String text) {
        StringBuilder sb = new StringBuilder(who(speaker, true));
        if (!to.isEmpty()) sb.append(" (собеседник — ").append(who(to, false)).append(')');
        sb.append(": «").append(text.trim()).append('»');
        return sb.toString();
    }

    /** Имя участника разговора: герой, Геральт или житель. */
    static String who(Map<String, Object> p, boolean capital) {
        String ref = Json.str(p, "ref");
        if ("hero".equals(ref)) return capital ? "Герой" : "герой";
        if ("self".equals(ref)) return Persona.NAME;
        String name = Json.str(p, "name");
        return name.isEmpty() ? (capital ? "Кто-то" : "кто-то") : name;
    }
}
