package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Вызовы инструментов, написанные текстом в реплике вместо function calling: часть моделей (и любая модель
 * иногда) пишет <attack target="n5"/>, {"name":"follow_hero","arguments":{}} или follow_hero() прямо в ответе.
 * Такой текст попадал в субтитры, а действие не выполнялось. Здесь — разбор в настоящие вызовы (только известных
 * инструментов); из речи его убирает Speech.clean.
 */
public final class TextCalls {
    private TextCalls() {
    }

    // <name a="1" b='x' c=y/>, <name ...>…</name>
    private static final Pattern TAG = Pattern.compile("<\\s*([a-zA-Z_][a-zA-Z0-9_]*)((?:\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s/>]+))*)\\s*(/?)>");
    private static final Pattern ATTR = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s/>]+))");
    // name(...) - имя инструмента и скобки: follow_hero(), attack(target="n5"), attack({"target":"n5"})
    private static final Pattern CALL = Pattern.compile("\\b([a-z][a-z0-9_]*)\\s*\\(([^()]{0,300})\\)");
    // Объект JSON (вложенность до двух уровней)
    private static final Pattern OBJ = Pattern.compile("\\{[^{}]*(\\{[^{}]*(\\{[^{}]*\\}[^{}]*)*\\}[^{}]*)*\\}");

    /** Вызовы известных инструментов из текста — по порядку появления; повтор одного и того же — один раз. */
    public static List<LlmClient.ToolCall> extract(String text, Set<String> known) {
        List<int[]> spans = new ArrayList<>();
        List<LlmClient.ToolCall> out = new ArrayList<>();
        if (text == null || text.isEmpty() || known.isEmpty()) return out;
        List<Object[]> found = new ArrayList<>(); // {позиция, имя, аргументы}

        Matcher t = TAG.matcher(text);
        while (t.find()) {
            String name = t.group(1);
            if (!known.contains(name)) continue;
            Map<String, Object> args = Json.of();
            Matcher a = ATTR.matcher(t.group(2));
            while (a.find()) {
                String v = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                args.put(a.group(1), value(v));
            }
            // <name>{"target":"n5"}</name> - аргументы внутри
            if (t.group(3).isEmpty()) {
                int close = text.indexOf("</" + name, t.end());
                if (close > 0 && close - t.end() < 400) {
                    Map<String, Object> inner = jsonObj(text.substring(t.end(), close));
                    if (inner != null) args.putAll(inner);
                }
            }
            found.add(new Object[]{t.start(), name, args});
            spans.add(new int[]{t.start(), t.end()});
        }

        Matcher o = OBJ.matcher(text);
        while (o.find()) {
            if (inside(spans, o.start())) continue;
            Map<String, Object> m = jsonObj(o.group());
            if (m == null) continue;
            Map<String, Object> fn = Json.obj(m.get("function"));
            String name = !Json.str(m, "name").isEmpty() ? Json.str(m, "name")
                : !Json.str(m, "tool").isEmpty() ? Json.str(m, "tool") : Json.str(fn, "name");
            if (!known.contains(name)) continue;
            Object a = m.containsKey("arguments") ? m.get("arguments") : m.containsKey("args") ? m.get("args")
                : m.containsKey("parameters") ? m.get("parameters") : fn.get("arguments");
            Map<String, Object> args = a instanceof String ? jsonObj((String) a) : Json.obj(a);
            found.add(new Object[]{o.start(), name, args == null ? Json.of() : args});
            spans.add(new int[]{o.start(), o.end()});
        }

        Matcher c = CALL.matcher(text);
        while (c.find()) {
            String name = c.group(1);
            if (!known.contains(name) || inside(spans, c.start())) continue;
            String inner = c.group(2).trim();
            Map<String, Object> args = inner.startsWith("{") ? jsonObj(inner) : null;
            if (args == null) {
                args = Json.of();
                Matcher a = ATTR.matcher(inner.replace(",", " "));
                while (a.find()) {
                    String v = a.group(3) != null ? a.group(3) : a.group(4) != null ? a.group(4) : a.group(5);
                    args.put(a.group(1), value(v));
                }
            }
            found.add(new Object[]{c.start(), name, args});
        }

        found.sort((x, y) -> Integer.compare((Integer) x[0], (Integer) y[0]));
        List<String> seen = new ArrayList<>();
        for (Object[] f : found) {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) f[2];
            String raw = Json.write(args);
            String sig = f[1] + raw;
            if (seen.contains(sig)) continue;
            seen.add(sig);
            out.add(new LlmClient.ToolCall("text_" + out.size(), (String) f[1], args, raw));
        }
        return out;
    }

    private static boolean inside(List<int[]> spans, int at) {
        for (int[] s : spans) if (at >= s[0] && at < s[1]) return true;
        return false;
    }

    private static Object value(String v) {
        if ("true".equalsIgnoreCase(v)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(v)) return Boolean.FALSE;
        if (v.matches("-?\\d{1,9}")) return Long.parseLong(v);
        return v;
    }

    private static Map<String, Object> jsonObj(String s) {
        String t = s.trim();
        if (!t.startsWith("{")) return null;
        try {
            Object o = Json.parse(t);
            return o instanceof Map ? Json.obj(o) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
