package gothic2.mobile.og.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Небольшой JSON без зависимостей: org.json из Android в модульных тестах — заглушка. Объект — Map
 * (порядок ключей сохраняется), массив — List, число — Double или Long, а ещё String, Boolean и null.
 */
public final class Json {
    private final String s;
    private int pos;

    private Json(String s) {
        this.s = s;
    }

    /** Разбор строки; при ошибке — IllegalArgumentException. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != p.s.length()) throw p.error("лишние символы");
        return v;
    }

    /** Объект или пустой Map, если это не объект. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : new LinkedHashMap<>();
    }

    /** Массив или пустой List. */
    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object v) {
        return v instanceof List ? (List<Object>) v : new ArrayList<>();
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? "" : v instanceof String ? (String) v : String.valueOf(v);
    }

    public static long num(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v instanceof String) {
            try {
                return Long.parseLong((String) v);
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    public static double dbl(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).longValue() != 0;
        return def;
    }

    /** Запись значения в JSON. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    /** Сборка объекта парами ключ-значение: Json.of("cmd", "say", "id", id). */
    public static Map<String, Object> of(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            quote(sb, (String) v);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("0");
            else if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Number) {
            sb.append(((Number) v).longValue());
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                quote(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else {
            quote(sb, String.valueOf(v));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON: " + what + " в позиции " + pos);
    }

    private void ws() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') break;
            pos++;
        }
    }

    private Object value() {
        if (pos >= s.length()) throw error("конец текста");
        char c = s.charAt(pos);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default: return number();
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, pos)) throw error("ожидалось " + word);
        pos += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        pos++;
        ws();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return m;
        }
        while (true) {
            ws();
            if (pos >= s.length() || s.charAt(pos) != '"') throw error("ожидался ключ");
            String key = string();
            ws();
            if (pos >= s.length() || s.charAt(pos) != ':') throw error("ожидалось ':'");
            pos++;
            ws();
            m.put(key, value());
            ws();
            if (pos >= s.length()) throw error("незакрытый объект");
            char c = s.charAt(pos++);
            if (c == '}') return m;
            if (c != ',') throw error("ожидалось ',' или '}'");
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        pos++;
        ws();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            if (pos >= s.length()) throw error("незакрытый массив");
            char c = s.charAt(pos++);
            if (c == ']') return l;
            if (c != ',') throw error("ожидалось ',' или ']'");
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        pos++;
        while (pos < s.length()) {
            char c = s.charAt(pos++);
            if (c == '"') return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= s.length()) break;
            char e = s.charAt(pos++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (pos + 4 > s.length()) throw error("обрезанный \\u");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("неверный \\u");
                    }
                    pos += 4;
                    break;
                default: throw error("неверная последовательность");
            }
        }
        throw error("незакрытая строка");
    }

    private Object number() {
        int start = pos;
        boolean real = false;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                real = true;
                pos++;
            } else {
                break;
            }
        }
        if (start == pos) throw error("неожиданный символ");
        String t = s.substring(start, pos);
        try {
            if (!real) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            throw error("неверное число");
        }
    }
}
