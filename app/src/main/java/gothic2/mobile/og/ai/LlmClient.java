package gothic2.mobile.og.ai;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Языковая модель по адресу, совместимому с OpenAI (/chat/completions): Ollama Cloud, Gemini, Groq,
 * OpenRouter и другие. Инструменты — в формате function calling. Лимит (429) — запасной ключ роли, затем
 * одна пауза и повтор.
 */
public final class LlmClient {
    /** Вызов инструмента из ответа модели. */
    public static final class ToolCall {
        public final String id;
        public final String name;
        public final Map<String, Object> args;
        public final String rawArgs;

        ToolCall(String id, String name, Map<String, Object> args, String rawArgs) {
            this.id = id;
            this.name = name;
            this.args = args;
            this.rawArgs = rawArgs;
        }
    }

    /** Ответ: реплика (может быть пустой) и вызовы инструментов. */
    public static final class Reply {
        public final String text;
        public final List<ToolCall> calls;
        /** Сообщение ассистента как есть — вернуть в историю перед результатами инструментов. */
        public final Map<String, Object> message;

        Reply(String text, List<ToolCall> calls, Map<String, Object> message) {
            this.text = text;
            this.calls = calls;
            this.message = message;
        }
    }

    public static final class LlmException extends IOException {
        public final int status;

        LlmException(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /**
     * Свой User-Agent у всех запросов: Ollama Cloud отвечает 403 (HTML-страница) на заголовок, который Android
     * ставит по умолчанию, — «Dalvik/2.1.0 (Linux; U; Android …)».
     */
    static final String USER_AGENT = "Gothic2Mobile/1.0";

    static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", USER_AGENT);
        return c;
    }

    /** HTTP — отдельно, чтобы подменить в тестах. */
    public interface Http {
        /** Тело ответа; код не 2xx — LlmException. */
        String post(String url, String key, String body, int timeoutMs) throws IOException;
    }

    private final AiSettings.Role role;
    private final Http http;

    public LlmClient(AiSettings.Role role, Http http) {
        this.role = role;
        this.http = http;
    }

    public LlmClient(AiSettings.Role role) {
        this(role, LlmClient::httpPost);
    }

    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens)
        throws IOException {
        return chat(messages, tools, maxTokens, false);
    }

    /** wordsOnly — ответ только словами: инструменты описаны (история с их вызовами остаётся верной), но вызывать нельзя. */
    public Reply chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools, int maxTokens, boolean wordsOnly)
        throws IOException {
        Map<String, Object> req = Json.of("model", role.model, "messages", messages,
            "max_tokens", maxTokens, "temperature", 0.8);
        if (tools != null && !tools.isEmpty()) {
            req.put("tools", tools);
            if (wordsOnly) req.put("tool_choice", "none");
        }
        String body = Json.write(req);
        String url = endpoint(role.url, "chat/completions");
        String resp;
        try {
            resp = http.post(url, role.key, body, 60000);
        } catch (LlmException e) {
            if (e.status != 429 && e.status < 500) throw e;
            // Лимит или сбой сервиса: запасной ключ, иначе пауза и повтор
            if (e.status == 429 && !role.spareKey.isEmpty()) {
                resp = http.post(url, role.spareKey, body, 60000);
            } else {
                sleep(e.status == 429 ? 6000 : 2000);
                resp = http.post(url, role.key, body, 60000);
            }
        }
        Reply r = parse(resp);
        if (tools == null || tools.isEmpty() || wordsOnly || !r.calls.isEmpty()) return r;
        return withTextCalls(r, toolNames(tools));
    }

    /** Имена инструментов из их описаний (function.name). */
    static Set<String> toolNames(List<Map<String, Object>> tools) {
        Set<String> names = new HashSet<>();
        for (Map<String, Object> t : tools) names.add(Json.str(Json.obj(t.get("function")), "name"));
        return names;
    }

    /**
     * Модель написала вызовы инструментов текстом (<attack target="n5"/>, JSON, follow_hero()) вместо function
     * calling — это настоящие вызовы (TextCalls); сообщение для истории — с ними, как при обычном вызове.
     */
    static Reply withTextCalls(Reply r, Set<String> names) {
        List<ToolCall> calls = TextCalls.extract(r.text, names);
        if (calls.isEmpty()) return r;
        Map<String, Object> back = Json.of("role", "assistant", "content", Speech.clean(r.text));
        List<Object> tcs = new ArrayList<>();
        for (ToolCall c : calls) {
            tcs.add(Json.of("id", c.id, "type", "function", "function", Json.of("name", c.name, "arguments", c.rawArgs)));
        }
        back.put("tool_calls", tcs);
        return new Reply(r.text, calls, back);
    }

    /** Разбор ответа /chat/completions. */
    public static Reply parse(String resp) throws IOException {
        Map<String, Object> root;
        try {
            root = Json.obj(Json.parse(resp));
        } catch (IllegalArgumentException e) {
            throw new IOException("ответ модели не JSON");
        }
        List<Object> choices = Json.arr(root.get("choices"));
        if (choices.isEmpty()) {
            Map<String, Object> err = Json.obj(root.get("error"));
            throw new IOException(err.isEmpty() ? "пустой ответ модели" : Json.str(err, "message"));
        }
        Map<String, Object> msg = Json.obj(Json.obj(choices.get(0)).get("message"));
        String text = Json.str(msg, "content");
        List<ToolCall> calls = new ArrayList<>();
        for (Object o : Json.arr(msg.get("tool_calls"))) {
            Map<String, Object> tc = Json.obj(o);
            Map<String, Object> fn = Json.obj(tc.get("function"));
            Object a = fn.get("arguments");
            String raw = a instanceof String ? (String) a : Json.write(a);
            Map<String, Object> args;
            try {
                args = Json.obj(Json.parse(raw.isEmpty() ? "{}" : raw));
            } catch (IllegalArgumentException e) {
                args = Json.of();
            }
            String id = Json.str(tc, "id");
            if (id.isEmpty()) id = "call_" + calls.size();
            calls.add(new ToolCall(id, Json.str(fn, "name"), args, raw));
        }
        // Ответ ассистента для истории: только то, что понимают все совместимые адреса
        Map<String, Object> back = Json.of("role", "assistant", "content", text);
        if (!calls.isEmpty()) {
            List<Object> tcs = new ArrayList<>();
            for (ToolCall c : calls) {
                tcs.add(Json.of("id", c.id, "type", "function",
                    "function", Json.of("name", c.name, "arguments", c.rawArgs.isEmpty() ? "{}" : c.rawArgs)));
            }
            back.put("tool_calls", tcs);
        }
        return new Reply(text == null ? "" : text, calls, back);
    }

    /** Список моделей адреса (/models) — для выбора в настройках. */
    public static List<String> models(String baseUrl, String key) throws IOException {
        HttpURLConnection c = open(endpoint(baseUrl, "models"));
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new LlmException(code, shortError(code, body));
        List<String> out = new ArrayList<>();
        for (Object o : Json.arr(Json.obj(Json.parse(body)).get("data"))) {
            String id = Json.str(Json.obj(o), "id");
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    static String endpoint(String base, String path) {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        if (b.endsWith("/" + path)) return b;
        return b + "/" + path;
    }

    static String httpPost(String url, String key, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = open(url);
        c.setConnectTimeout(15000);
        c.setReadTimeout(timeoutMs);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(data.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
        int code = c.getResponseCode();
        String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code < 200 || code >= 300) throw new LlmException(code, shortError(code, resp));
        return resp;
    }

    static String shortError(int code, String body) {
        String msg = body == null ? "" : body;
        try {
            Map<String, Object> e = Json.obj(Json.obj(Json.parse(msg)).get("error"));
            if (!e.isEmpty()) msg = Json.str(e, "message");
        } catch (IllegalArgumentException ignored) {
            // не JSON - как есть
        }
        // HTML-страница ошибки (403 сайта, прокси) - только текст
        if (msg.trim().startsWith("<")) msg = msg.replaceAll("(?s)<title>.*?</title>", " ").replaceAll("<[^>]*>", " ");
        msg = msg.replaceAll("\\s+", " ").trim();
        if (msg.length() > 300) msg = msg.substring(0, 300);
        String hint = code == 401 ? " — ключ не подходит" : code == 403 ? " — доступ запрещён" : code == 404 ? " — нет такого адреса или модели" : "";
        return "HTTP " + code + hint + (msg.isEmpty() ? "" : ": " + msg);
    }

    static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > 8 * 1024 * 1024) throw new IOException("слишком большой ответ");
            }
            return bo.toString("UTF-8");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
