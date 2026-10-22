package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Текст ответа модели — в реплики: без рассуждений и ремарок, кусками по предложениям. */
public final class Speech {
    private static final Pattern THINK = Pattern.compile("(?is)<think>.*?(</think>|$)");
    private static final Pattern STARS = Pattern.compile("\\*[^*]{0,200}\\*");
    private static final Pattern BRACKETS = Pattern.compile("\\([^()]{0,200}\\)|\\[[^\\[\\]]{0,200}\\]");
    private static final Pattern SPEAKER = Pattern.compile("^(?iu)\\s*(геральт|geralt)\\s*:\\s*");
    // Вызов инструмента текстом вместо function calling: в речь не попадает
    private static final Pattern CODE = Pattern.compile("(?s)```.*?(```|$)|\\{[^{}]*(\\{[^{}]*\\}[^{}]*)*\\}|<\\|[^|]*\\|>");
    private static final int MAX_CHARS = 600;

    private Speech() {
    }

    /** То, что Геральт скажет вслух: без <think>, *действий*, (ремарок), «Геральт:» и кавычек вокруг. */
    public static String clean(String text) {
        if (text == null) return "";
        String t = THINK.matcher(text).replaceAll(" ");
        t = CODE.matcher(t).replaceAll(" ");
        t = STARS.matcher(t).replaceAll(" ");
        t = BRACKETS.matcher(t).replaceAll(" ");
        t = SPEAKER.matcher(t).replaceAll("");
        t = t.replace('\n', ' ').replaceAll("\\s+", " ").trim();
        while (t.length() >= 2 && isQuote(t.charAt(0)) && isQuote(t.charAt(t.length() - 1))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        if (t.length() > MAX_CHARS) {
            int cut = lastSentenceEnd(t, MAX_CHARS);
            t = t.substring(0, cut > 40 ? cut : MAX_CHARS).trim();
        }
        return t;
    }

    /** Куски для озвучки: по предложениям, короткие склеиваются (до ~200 знаков). */
    public static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        if (text.isEmpty()) return out;
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean end = c == '.' || c == '!' || c == '?' || c == '…';
            if (end && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                sentences.add(text.substring(start, i + 1).trim());
                start = i + 1;
            }
        }
        if (start < text.length() && !text.substring(start).trim().isEmpty()) sentences.add(text.substring(start).trim());
        StringBuilder cur = new StringBuilder();
        for (String s : sentences) {
            if (cur.length() > 0 && cur.length() + s.length() > 200) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(s);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /** Длительность реплики без голоса (для субтитров): как читается. */
    public static int estimateMs(String text) {
        return Math.max(1500, Math.min(9000, text.length() * 60));
    }

    private static boolean isQuote(char c) {
        return c == '"' || c == '«' || c == '»' || c == '“' || c == '”' || c == '\'';
    }

    private static int lastSentenceEnd(String t, int limit) {
        int best = -1;
        for (int i = 0; i < Math.min(limit, t.length()); i++) {
            char c = t.charAt(i);
            if (c == '.' || c == '!' || c == '?') best = i + 1;
        }
        return best;
    }
}
