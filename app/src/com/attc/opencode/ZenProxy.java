package com.attc.opencode;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

/**
 * Anthropic-compatible gateway for Claude Code.
 *
 * Claude Code speaks the Anthropic Messages API. opencode Zen's free models
 * speak OpenAI chat completions. This class runs a small HTTP server on the
 * loopback interface, translates messages/tools/streaming in both directions,
 * and exposes the live free-model catalogue through {@code /v1/models} so
 * Claude Code's own {@code /model} picker can discover models that did not
 * exist when this APK was built.
 */
public final class ZenProxy {

    public static final int PORT = 8797;

    private static final String CATALOG_URL = "https://opencode.ai/zen/v1/models";
    private static final String DEFAULT_BASE = "https://opencode.ai/inference/openai/v1";
    private static final String USER_AGENT = "opencode/latest/2.2.7/cli";
    private static final String PROJECT_ID = "470c8549ef01a2a9d3c85b22965766cefc874134";
    private static final String FALLBACK_SESSION = "ses_e2f6b6b23ffe42s7BR3Zq7hip5";
    private static final long CATALOG_TTL_MS = 10 * 60 * 1000L;

    private static ZenProxy instance;

    private final Context context;
    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile List<Model> catalog = Collections.emptyList();
    private volatile long catalogAt;
    private volatile boolean fetching;
    private volatile String lastError = "";
    private volatile String session = FALLBACK_SESSION;

    private ZenProxy(Context context) {
        this.context = context.getApplicationContext();
    }

    public static synchronized ZenProxy get(Context context) {
        if (instance == null) {
            instance = new ZenProxy(context);
        }
        return instance;
    }

    /** Points the free-tier handshake at a session the real CLI created. */
    void setSession(String id) {
        if (id != null && !id.isEmpty()) {
            session = id;
        }
    }

    private String sessionId() {
        return session;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            ServerSocket socket = new ServerSocket(PORT, 48,
                    InetAddress.getByName("127.0.0.1"));
            server = socket;
            running = true;
            Thread accept = new Thread(this::acceptLoop, "zen-proxy-accept");
            accept.setDaemon(true);
            accept.start();
            Logger.i("zenproxy: listening 127.0.0.1:" + PORT);
            refreshCatalogAsync();
        } catch (Exception e) {
            running = false;
            lastError = String.valueOf(e);
            Logger.i("zenproxy: start failed " + e);
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocket socket = server;
        server = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = server.accept();
                Thread thread = new Thread(() -> handle(client), "zen-proxy-client");
                thread.setDaemon(true);
                thread.start();
            } catch (Exception e) {
                if (running) {
                    Logger.i("zenproxy: accept " + e);
                }
            }
        }
    }

    // ------------------------------------------------------------------ HTTP

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(180000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                writeJson(out, 400, errorJson("invalid_request_error", "bad request line"));
                return;
            }
            String method = parts[0];
            String path = parts[1].split("\\?")[0];
            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                            line.substring(colon + 1).trim());
                }
            }
            int length = 0;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            } catch (NumberFormatException ignored) {
            }
            byte[] body = new byte[Math.max(0, Math.min(length, 32 * 1024 * 1024))];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }

            Logger.i("zenproxy: " + method + " " + path + " bytes=" + off);
            if (!"POST".equals(method) && !"GET".equals(method)) {
                writeJson(out, 405, errorJson("invalid_request_error", "method not allowed"));
                return;
            }
            if ("GET".equals(method) && ("/v1/models".equals(path) || "/models".equals(path))) {
                writeJson(out, 200, modelsJson().toString());
                return;
            }
            if ("GET".equals(method) && path.startsWith("/v1/models/")) {
                writeJson(out, 200, oneModelJson(path.substring("/v1/models/".length())).toString());
                return;
            }
            if ("GET".equals(method) && ("/health".equals(path) || "/healthz".equals(path))) {
                JSONObject health = new JSONObject();
                health.put("status", "ok");
                health.put("models", catalog().size());
                health.put("error", lastError);
                writeJson(out, 200, health.toString());
                return;
            }
            if ("POST".equals(method)
                    && ("/v1/messages/count_tokens".equals(path)
                    || "/messages/count_tokens".equals(path))) {
                JSONObject request = new JSONObject(new String(body, 0, off, StandardCharsets.UTF_8));
                JSONObject result = new JSONObject();
                result.put("input_tokens", estimateTokens(request));
                writeJson(out, 200, result.toString());
                return;
            }
            if ("POST".equals(method)
                    && ("/v1/messages".equals(path) || "/messages".equals(path))) {
                JSONObject request = new JSONObject(new String(body, 0, off, StandardCharsets.UTF_8));
                boolean stream = request.optBoolean("stream", true);
                if (stream) {
                    streamMessages(out, request);
                } else {
                    completeMessages(out, request);
                }
                return;
            }
            writeJson(out, 404, errorJson("not_found_error", "unknown path " + path));
        } catch (Exception e) {
            Logger.i("zenproxy: client error " + e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
        }
        if (c == -1 && buffer.size() == 0) {
            return null;
        }
        return buffer.toString("ISO-8859-1");
    }

    private static void writeJson(OutputStream out, int status, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + status + " "
                + (status == 200 ? "OK" : status == 400 ? "Bad Request"
                : status == 404 ? "Not Found" : status == 405 ? "Method Not Allowed"
                : status == 429 ? "Too Many Requests" : "Internal Server Error")
                + "\r\nContent-Type: application/json\r\nContent-Length: " + data.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String errorJson(String type, String message) {
        try {
            JSONObject error = new JSONObject();
            error.put("type", type);
            error.put("message", message == null ? "" : message);
            JSONObject root = new JSONObject();
            root.put("type", "error");
            root.put("error", error);
            return root.toString();
        } catch (Exception e) {
            return "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"error\"}}";
        }
    }

    // -------------------------------------------------------------- catalogue

    private static final class Model {
        final String id;
        final String name;
        final String baseUrl;

        Model(String id, String name, String baseUrl) {
            this.id = id;
            this.name = name;
            this.baseUrl = baseUrl;
        }
    }

    private List<Model> catalog() {
        List<Model> current = catalog;
        if (current.isEmpty()) {
            // Never block the caller (Claude Code's model discovery gives us
            // three seconds): start a fetch and answer with the known list.
            refreshCatalogAsync();
            return fallbackCatalog();
        }
        if (System.currentTimeMillis() - catalogAt >= CATALOG_TTL_MS) {
            refreshCatalogAsync();
        }
        return current;
    }

    private void refreshCatalogAsync() {
        synchronized (this) {
            if (fetching) {
                return;
            }
            fetching = true;
        }
        Thread thread = new Thread(() -> {
            List<Model> fetched = fetchCatalog();
            synchronized (ZenProxy.this) {
                if (!fetched.isEmpty()) {
                    catalog = fetched;
                    catalogAt = System.currentTimeMillis();
                    Logger.i("zenproxy: catalog " + fetched.size() + " free models");
                }
                fetching = false;
            }
        }, "zen-catalog");
        thread.setDaemon(true);
        thread.start();
    }

    private List<Model> fetchCatalog() {
        List<Model> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            URL url = new URL(CATALOG_URL);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status != 200) {
                lastError = "catalog http " + status;
                return result;
            }
            String payload = readAll(connection.getInputStream());
            JSONObject root = new JSONObject(payload);
            JSONArray data = root.optJSONArray("data");
            if (data == null) {
                return result;
            }
            for (int i = 0; i < data.length(); i++) {
                JSONObject model = data.optJSONObject(i);
                if (model == null) {
                    continue;
                }
                if (!isFree(model)) {
                    continue;
                }
                JSONObject capabilities = model.optJSONObject("capabilities");
                JSONObject tools = capabilities == null ? null
                        : capabilities.optJSONObject("tools");
                if (tools != null && !tools.optBoolean("supported", true)) {
                    continue;
                }
                String id = model.optString("id", "");
                if (id.isEmpty()) {
                    continue;
                }
                String name = model.optString("name", id);
                String base = DEFAULT_BASE;
                JSONObject api = model.optJSONObject("api");
                JSONObject chat = api == null ? null : api.optJSONObject("chat-completions");
                if (chat != null) {
                    String candidate = chat.optString("base_url", "");
                    if (candidate.startsWith("https://")) {
                        base = candidate;
                    }
                }
                result.add(new Model(id, name, base));
            }
        } catch (Exception e) {
            lastError = String.valueOf(e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return result;
    }

    private static boolean isFree(JSONObject model) {
        JSONObject cost = model.optJSONObject("cost");
        JSONObject text = cost == null ? null : cost.optJSONObject("text");
        if (text == null) {
            return false;
        }
        return zeroPrice(text.optJSONObject("input"))
                && zeroPrice(text.optJSONObject("output"));
    }

    private static boolean zeroPrice(JSONObject price) {
        if (price == null) {
            return false;
        }
        JSONObject value = price.optJSONObject("price");
        if (value != null) {
            return value.optDouble("price", value.optDouble("amount", 1)) == 0;
        }
        return price.optDouble("price", price.optDouble("amount", 1)) == 0;
    }

    private static List<Model> fallbackCatalog() {
        List<Model> fallback = new ArrayList<>();
        String[] ids = {
                "big-pickle", "mimo-v2.6-flash-free", "deepseek-v4.1-flash-free",
                "glm-5.3-flash-free", "longcat-2.5-free", "iceman-free",
                "inkling-2-small-free", "beam-free", "flint-40b-free",
                "nemotron-3.5-super-vl-free", "nemotron-3.5-lightning-free",
                "minimax-m3.1-flash-free"
        };
        for (String id : ids) {
            fallback.add(new Model(id, id, DEFAULT_BASE));
        }
        return fallback;
    }

    private JSONObject modelsJson() throws Exception {
        JSONArray data = new JSONArray();
        for (Model model : catalog()) {
            JSONObject item = new JSONObject();
            item.put("type", "model");
            item.put("id", publicId(model.id));
            item.put("display_name", model.name);
            item.put("description", "Free model via opencode Zen");
            item.put("created_at", "2026-01-01T00:00:00Z");
            data.put(item);
        }
        JSONObject root = new JSONObject();
        root.put("data", data);
        root.put("has_more", false);
        if (data.length() > 0) {
            root.put("first_id", data.getJSONObject(0).optString("id"));
            root.put("last_id", data.getJSONObject(data.length() - 1).optString("id"));
        }
        return root;
    }

    /**
     * Claude Code only puts gateway models into its /model picker when the id
     * starts with {@code claude-} or {@code anthropic/}, so every Zen model is
     * exposed under a claude- prefix and mapped back before the upstream call.
     */
    private static String publicId(String zenId) {
        return "claude-" + zenId;
    }

    private static String zenId(String requested) {
        String id = requested == null ? "" : requested.trim();
        if (id.endsWith("[1m]")) {
            id = id.substring(0, id.length() - 4);
        }
        if (id.startsWith("anthropic/")) {
            id = id.substring("anthropic/".length());
        }
        if (id.startsWith("claude-")) {
            id = id.substring("claude-".length());
        }
        return id;
    }

    private JSONObject oneModelJson(String rawId) throws Exception {
        String id = java.net.URLDecoder.decode(rawId, "UTF-8");
        for (Model model : catalog()) {
            if (model.id.equals(id) || publicId(model.id).equals(id)) {
                JSONObject item = new JSONObject();
                item.put("type", "model");
                item.put("id", publicId(model.id));
                item.put("display_name", model.name);
                item.put("description", "Free model via opencode Zen");
                item.put("created_at", "2026-01-01T00:00:00Z");
                return item;
            }
        }
        return new JSONObject();
    }

    // ------------------------------------------------------------- requests

    private static final class Upstream {
        String base;
        String model;
        JSONObject request;
        final Map<String, String> aliases = new LinkedHashMap<>();
    }

    private Upstream buildUpstream(JSONObject input) throws Exception {
        List<Model> models = catalog();
        String requested = input.optString("model", "").trim();
        String id = zenId(requested);
        Model chosen = null;
        for (Model model : models) {
            if (model.id.equalsIgnoreCase(id)) {
                chosen = model;
                break;
            }
        }
        if (chosen == null) {
            // Claude Code may send an Anthropic alias. Route those to the
            // strongest currently free model instead of failing the turn.
            chosen = models.isEmpty() ? fallbackCatalog().get(0) : models.get(0);
        }

        JSONObject request = new JSONObject();
        request.put("model", chosen.id);
        // The free tier only streams: non-streaming requests are refused even
        // when every other field matches the accepted shape.
        request.put("stream", true);
        request.put("stream_options", new JSONObject().put("include_usage", true));
        int maxTokens = input.optInt("max_tokens", 8192);
        request.put("max_completion_tokens", Math.max(16, Math.min(maxTokens, 64000)));

        JSONArray messages = new JSONArray();
        String system = systemText(input.opt("system"));
        // Prepend the gate prompt. Without this exact prefix opencode Zen
        // answers 429 FreeUsageLimitError even when the account has quota.
        String gated = ZenGate.system();
        if (!system.isEmpty()) {
            gated = gated + "\n\n" + system;
        }
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", gated);
        messages.put(systemMessage);
        JSONArray inputMessages = input.optJSONArray("messages");
        if (inputMessages != null) {
            for (int i = 0; i < inputMessages.length(); i++) {
                JSONObject message = inputMessages.optJSONObject(i);
                if (message != null) {
                    appendMessage(messages, message);
                }
            }
        }
        request.put("messages", messages);

        // The gate schema set must be present in its original order. Claude
        // Code's own tool schemas are deliberately not forwarded: more than
        // about forty tools makes the free tier answer 429 again. Calls to a
        // gate tool are translated to the matching Claude tool on the way out.
        JSONArray converted = new JSONArray();
        JSONArray gate = ZenGate.tools();
        for (int i = 0; i < gate.length(); i++) {
            converted.put(gate.get(i));
        }
        request.put("tools", converted);
        String choice = toolChoice(input.optJSONObject("tool_choice"));
        if (choice != null) {
            if (choice.startsWith("{")) {
                request.put("tool_choice", new JSONObject(choice));
            } else {
                request.put("tool_choice", choice);
            }
        }

        copyNumber(input, request, "temperature");
        copyNumber(input, request, "top_p");
        JSONArray stop = input.optJSONArray("stop_sequences");
        if (stop != null && stop.length() > 0) {
            request.put("stop", stop);
        }

        Upstream upstream = new Upstream();
        upstream.base = chosen.baseUrl;
        upstream.model = chosen.id;
        upstream.request = request;
        // Claude Code names its tools Bash/Read/Write..., while the gate
        // schema set uses shell/read/write. Translate a gate call into the
        // Claude tool of the same purpose.
        String[][] pairs = {
                {"shell", "Bash"}, {"read", "Read"}, {"write", "Write"},
                {"edit", "Edit"}, {"glob", "Glob"}, {"grep", "Grep"},
                {"webfetch", "WebFetch"}, {"websearch", "WebSearch"},
        };
        for (String[] pair : pairs) {
            upstream.aliases.put(pair[0], pair[1]);
        }
        return upstream;
    }

    private static String mappedName(Upstream upstream, String name) {
        String mapped = upstream.aliases.get(name);
        return mapped == null ? name : mapped;
    }

    private static JSONObject mappedInput(String source, String target, JSONObject input) {
        if (input == null || source.equals(target)) {
            return input == null ? new JSONObject() : input;
        }
        JSONObject mapped = new JSONObject();
        java.util.Iterator<String> keys = input.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = input.opt(key);
            String out = key;
            if (("read".equals(source) || "write".equals(source) || "edit".equals(source))
                    && "path".equals(key)) {
                out = "file_path";
            } else if ("edit".equals(source) && "oldString".equals(key)) {
                out = "old_string";
            } else if ("edit".equals(source) && "newString".equals(key)) {
                out = "new_string";
            } else if ("edit".equals(source) && "replaceAll".equals(key)) {
                out = "replace_all";
            } else if ("grep".equals(source) && "include".equals(key)) {
                out = "glob";
            } else if ("shell".equals(source)
                    && ("workdir".equals(key) || "background".equals(key))) {
                continue;
            }
            try {
                mapped.put(out, value);
            } catch (Exception e) {
                // Skip values org.json refuses to copy.
            }
        }
        return mapped;
    }

    private static String toolChoice(JSONObject choice) throws Exception {
        if (choice == null) {
            return null;
        }
        String type = choice.optString("type", "auto");
        if ("any".equals(type)) {
            return "required";
        }
        if ("tool".equals(type)) {
            String name = gateName(choice.optString("name"));
            if (name == null) {
                return "auto";
            }
            JSONObject function = new JSONObject();
            function.put("name", name);
            JSONObject root = new JSONObject();
            root.put("type", "function");
            root.put("function", function);
            return root.toString();
        }
        return "auto";
    }

    /** Claude tool name -> gate tool name, or null when unmapped. */
    private static String gateName(String name) {
        if (name == null) {
            return null;
        }
        switch (name) {
            case "Bash":
                return "shell";
            case "Read":
                return "read";
            case "Write":
                return "write";
            case "Edit":
                return "edit";
            case "Glob":
                return "glob";
            case "Grep":
                return "grep";
            case "WebFetch":
                return "webfetch";
            case "WebSearch":
                return "websearch";
            default:
                return null;
        }
    }

    private static void copyNumber(JSONObject from, JSONObject to, String key) throws Exception {
        if (from.has(key) && !from.isNull(key)) {
            to.put(key, from.optDouble(key));
        }
    }

    private static String systemText(Object system) {
        if (system == null) {
            return "";
        }
        if (system instanceof String) {
            return (String) system;
        }
        StringBuilder text = new StringBuilder();
        if (system instanceof JSONArray) {
            JSONArray blocks = (JSONArray) system;
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null) {
                    continue;
                }
                if ("text".equals(block.optString("type"))) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(block.optString("text", ""));
                }
            }
        }
        return text.toString();
    }

    private void appendMessage(JSONArray target, JSONObject message) throws Exception {
        String role = message.optString("role", "user");
        Object content = message.opt("content");
        if (content instanceof String) {
            JSONObject item = new JSONObject();
            item.put("role", role);
            item.put("content", content);
            target.put(item);
            return;
        }
        if (!(content instanceof JSONArray)) {
            return;
        }
        JSONArray blocks = (JSONArray) content;
        StringBuilder text = new StringBuilder();
        JSONArray parts = new JSONArray();
        JSONArray toolCalls = new JSONArray();

        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block == null) {
                continue;
            }
            String type = block.optString("type", "");
            if ("text".equals(type)) {
                String value = block.optString("text", "");
                if (value.isEmpty()) {
                    continue;
                }
                text.append(value);
                JSONObject part = new JSONObject();
                part.put("type", "text");
                part.put("text", value);
                parts.put(part);
            } else if ("image".equals(type)) {
                JSONObject source = block.optJSONObject("source");
                if (source != null && "base64".equals(source.optString("type"))) {
                    JSONObject image = new JSONObject();
                    image.put("type", "image_url");
                    JSONObject url = new JSONObject();
                    url.put("url", "data:" + source.optString("media_type", "image/png")
                            + ";base64," + source.optString("data", ""));
                    image.put("image_url", url);
                    parts.put(image);
                }
            } else if ("tool_use".equals(type)) {
                JSONObject function = new JSONObject();
                function.put("name", block.optString("name", "tool"));
                JSONObject arguments = block.optJSONObject("input");
                function.put("arguments", arguments == null ? "{}" : arguments.toString());
                JSONObject call = new JSONObject();
                call.put("id", block.optString("id", "call_" + UUID.randomUUID()));
                call.put("type", "function");
                call.put("function", function);
                toolCalls.put(call);
            } else if ("tool_result".equals(type)) {
                JSONObject tool = new JSONObject();
                tool.put("role", "tool");
                tool.put("tool_call_id", block.optString("tool_use_id", ""));
                tool.put("content", toolResultText(block.opt("content")));
                target.put(tool);
            }
        }

        if ("assistant".equals(role)) {
            if (text.length() == 0 && toolCalls.length() == 0) {
                return;
            }
            JSONObject item = new JSONObject();
            item.put("role", "assistant");
            if (text.length() > 0) {
                item.put("content", text.toString());
            }
            if (toolCalls.length() > 0) {
                item.put("tool_calls", toolCalls);
            }
            target.put(item);
        } else {
            JSONObject item = new JSONObject();
            item.put("role", "user");
            if (parts.length() == 1
                    && "text".equals(parts.getJSONObject(0).optString("type"))) {
                item.put("content", parts.getJSONObject(0).optString("text"));
            } else if (parts.length() > 0) {
                item.put("content", parts);
            } else if (text.length() > 0) {
                item.put("content", text.toString());
            } else {
                item.put("content", "");
            }
            target.put(item);
        }
    }

    private static String toolResultText(Object content) {
        if (content == null) {
            return "";
        }
        if (content instanceof String) {
            return (String) content;
        }
        StringBuilder text = new StringBuilder();
        if (content instanceof JSONArray) {
            JSONArray blocks = (JSONArray) content;
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.optJSONObject(i);
                if (block == null) {
                    continue;
                }
                if ("text".equals(block.optString("type"))) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(block.optString("text", ""));
                } else {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(block.toString());
                }
            }
        }
        return text.toString();
    }

    private static int estimateTokens(JSONObject request) {
        int chars = request.toString().length();
        return Math.max(1, chars / 4);
    }

    // -------------------------------------------------------------- upstream

    private HttpURLConnection openUpstream(Upstream upstream) throws Exception {
        String base = upstream.base;
        // Debug hook: a gateway-base.txt in the storage folder redirects the
        // upstream call (used to inspect the exact request on a local echo).
        try {
            java.io.File override = new java.io.File(Setup.STORAGE_DIR, "gateway-base.txt");
            if (override.exists()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(override), StandardCharsets.UTF_8));
                String line = reader.readLine();
                reader.close();
                if (line != null && !line.trim().isEmpty()) {
                    base = line.trim();
                }
            }
        } catch (Exception ignored) {
        }
        URL url = new URL((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/chat/completions");
        Logger.i("zenproxy: upstream " + url + " session=" + sessionId());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(240000);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", upstream.request.optBoolean("stream")
                ? "text/event-stream" : "application/json");
        connection.setRequestProperty("Authorization", "Bearer public");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("x-opencode-client", "cli");
        // opencode Zen only serves the free tier to a project/session pair it
        // has seen from the real CLI; without these headers every request is
        // answered with 429 FreeUsageLimitError.
        connection.setRequestProperty("x-opencode-project", PROJECT_ID);
        connection.setRequestProperty("x-opencode-session", sessionId());
        connection.setRequestProperty("x-opencode-session-id", sessionId());
        connection.setRequestProperty("x-session-affinity", sessionId());
        connection.setRequestProperty("x-session-id", sessionId());
        byte[] body = upstream.request.toString().getBytes(StandardCharsets.UTF_8);
        try {
            java.io.File dir = new java.io.File(Setup.STORAGE_DIR, "logs");
            dir.mkdirs();
            java.io.FileOutputStream dump =
                    new java.io.FileOutputStream(new java.io.File(dir, "zen-last.json"));
            dump.write(body);
            dump.close();
        } catch (Exception ignored) {
        }
        connection.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = connection.getOutputStream()) {
            out.write(body);
        }
        return connection;
    }

    /**
     * Runs the upstream call inside the guest with Bun: opencode Zen's free
     * tier only accepts Bun's TLS fingerprint, so Java, curl and Python all
     * receive 429 for the very same body and headers.
     */
    private static final String FETCH_SCRIPT =
            "const req = await Bun.file(process.argv[2]).text();\n"
            + "const headers = JSON.parse(await Bun.file(process.argv[3]).text());\n"
            + "const r = await fetch('https://opencode.ai/inference/openai/v1/chat/completions',"
            + "{method:'POST',headers,body:req});\n"
            + "console.log('STATUS ' + r.status);\n"
            + "if (!r.ok) { console.log(await r.text()); process.exit(0); }\n"
            + "const reader = r.body.getReader();\n"
            + "const dec = new TextDecoder();\n"
            + "for (;;) { const v = await reader.read(); if (v.done) break;"
            + " process.stdout.write(dec.decode(v.value, {stream:true})); }\n";

    private static final class GateStream {
        final int status;
        final InputStream stream;
        final Process process;

        GateStream(int status, InputStream stream, Process process) {
            this.status = status;
            this.stream = stream;
            this.process = process;
        }

        void close() {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static void writeFile(java.io.File file, byte[] data) throws IOException {
        java.io.FileOutputStream out = new java.io.FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    private GateStream openGate(Upstream upstream) throws Exception {
        TerminalService service = TerminalService.current();
        if (service == null) {
            throw new IOException("guest not ready");
        }
        java.io.File dir = new java.io.File(Setup.STORAGE_DIR, "gateway");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("gateway folder unavailable");
        }
        writeFile(new java.io.File(dir, "req.json"),
                upstream.request.toString().getBytes(StandardCharsets.UTF_8));
        try {
            java.io.File logs = new java.io.File(Setup.STORAGE_DIR, "logs");
            logs.mkdirs();
            writeFile(new java.io.File(logs, "zen-last.json"),
                    upstream.request.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        JSONObject headers = new JSONObject();
        headers.put("Content-Type", "application/json");
        headers.put("Authorization", "Bearer public");
        headers.put("User-Agent", USER_AGENT);
        headers.put("x-opencode-client", "cli");
        headers.put("x-opencode-project", PROJECT_ID);
        headers.put("x-opencode-session", sessionId());
        headers.put("x-opencode-session-id", sessionId());
        headers.put("x-session-affinity", sessionId());
        headers.put("x-session-id", sessionId());
        writeFile(new java.io.File(dir, "headers.json"),
                headers.toString().getBytes(StandardCharsets.UTF_8));
        byte[] script = FETCH_SCRIPT.getBytes(StandardCharsets.UTF_8);
        java.io.File scriptFile = new java.io.File(dir, "zen-fetch.js");
        if (!scriptFile.exists() || scriptFile.length() != script.length) {
            writeFile(scriptFile, script);
        }
        Process process = service.startGuest(
                "/usr/bin/env", "BUN_BE_BUN=1",
                "/usr/local/lib/opencode/opencode",
                "/root/attc/gateway/zen-fetch.js",
                "/root/attc/gateway/req.json",
                "/root/attc/gateway/headers.json");
        InputStream in = process.getInputStream();
        int status = 502;
        String line = readLine(in);
        if (line != null && line.startsWith("STATUS ")) {
            try {
                status = Integer.parseInt(line.substring(7).trim());
            } catch (Exception ignored) {
            }
        }
        if (status == 200) {
            return new GateStream(status, in, process);
        }
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) > 0) {
            error.write(buffer, 0, read);
        }
        try {
            InputStream errStream = process.getErrorStream();
            while ((read = errStream.read(buffer)) > 0) {
                error.write(buffer, 0, read);
            }
        } catch (Exception ignored) {
        }
        process.destroy();
        Logger.i("zenproxy: gate status " + status + " " + error.toString("UTF-8"));
        return new GateStream(status,
                new java.io.ByteArrayInputStream(error.toByteArray()), null);
    }

    private void completeMessages(OutputStream out, JSONObject input) throws Exception {
        Upstream upstream = buildUpstream(input);
        GateStream gate = null;
        try {
            gate = openGate(upstream);
            int status = gate.status;
            if (status != 200) {
                String error = readAll(gate.stream);
                lastError = "upstream " + status;
                writeJson(out, status == 429 ? 429 : 502,
                        errorJson("api_error", "opencode zen " + status + ": " + error));
                return;
            }
            JSONObject result = readChatResult(gate.stream);
            JSONObject message = result.optJSONArray("choices") == null
                    ? new JSONObject()
                    : result.getJSONArray("choices").getJSONObject(0).optJSONObject("message");
            JSONArray content = new JSONArray();
            if (message != null) {
                String text = message.optString("content", "");
                if (!text.isEmpty()) {
                    JSONObject block = new JSONObject();
                    block.put("type", "text");
                    block.put("text", text);
                    content.put(block);
                }
                JSONArray calls = message.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null) {
                            continue;
                        }
                        JSONObject function = call.optJSONObject("function");
                        String sourceName = function == null ? "tool"
                                : function.optString("name", "tool");
                        String targetName = mappedName(upstream, sourceName);
                        JSONObject arguments = new JSONObject();
                        String raw = function == null ? "{}"
                                : function.optString("arguments", "{}");
                        try {
                            arguments = new JSONObject(raw);
                        } catch (Exception e) {
                            arguments = new JSONObject();
                        }
                        JSONObject block = new JSONObject();
                        block.put("type", "tool_use");
                        block.put("id", call.optString("id", "call_" + UUID.randomUUID()));
                        block.put("name", targetName);
                        block.put("input", mappedInput(sourceName, targetName, arguments));
                        content.put(block);
                    }
                }
            }
            JSONObject response = new JSONObject();
            response.put("id", "msg_" + UUID.randomUUID().toString().replace("-", ""));
            response.put("type", "message");
            response.put("role", "assistant");
            response.put("model", upstream.model);
            response.put("content", content);
            response.put("stop_reason", stopReason(result));
            response.put("stop_sequence", JSONObject.NULL);
            JSONObject usage = new JSONObject();
            usage.put("input_tokens", estimateTokens(input));
            usage.put("output_tokens", Math.max(1, content.toString().length() / 4));
            response.put("usage", usage);
            writeJson(out, 200, response.toString());
        } finally {
            if (gate != null) {
                gate.close();
            }
        }
    }

    /**
     * Folds an OpenAI SSE stream into the non-streaming chat.completion shape
     * so a client that asked for JSON still gets an answer.
     */
    private static JSONObject readChatResult(InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder content = new StringBuilder();
        TreeMap<Integer, JSONObject> calls = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) {
                continue;
            }
            JSONObject chunk;
            try {
                chunk = new JSONObject(data);
            } catch (Exception e) {
                continue;
            }
            JSONArray choices = chunk.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                continue;
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                delta = choices.getJSONObject(0).optJSONObject("message");
            }
            if (delta == null) {
                continue;
            }
            String text = delta.optString("content", "");
            if (!text.isEmpty()) {
                content.append(text);
            }
            JSONArray toolCalls = delta.optJSONArray("tool_calls");
            if (toolCalls != null) {
                for (int i = 0; i < toolCalls.length(); i++) {
                    JSONObject call = toolCalls.optJSONObject(i);
                    if (call == null) {
                        continue;
                    }
                    int index = call.optInt("index", calls.size());
                    JSONObject accumulated = calls.get(index);
                    if (accumulated == null) {
                        accumulated = new JSONObject();
                        accumulated.put("id", call.optString("id",
                                "call_" + UUID.randomUUID().toString().replace("-", "")));
                        accumulated.put("type", "function");
                        JSONObject function = new JSONObject();
                        function.put("name", "");
                        function.put("arguments", "");
                        accumulated.put("function", function);
                        calls.put(index, accumulated);
                    }
                    if (!call.optString("id", "").isEmpty()) {
                        accumulated.put("id", call.optString("id"));
                    }
                    JSONObject function = call.optJSONObject("function");
                    if (function != null) {
                        JSONObject target = accumulated.getJSONObject("function");
                        String name = function.optString("name", "");
                        if (!name.isEmpty()) {
                            target.put("name", name);
                        }
                        String arguments = function.optString("arguments", "");
                        if (!arguments.isEmpty()) {
                            target.put("arguments",
                                    target.optString("arguments", "") + arguments);
                        }
                    }
                }
            }
        }

        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!calls.isEmpty()) {
            JSONArray toolCalls = new JSONArray();
            for (JSONObject call : calls.values()) {
                toolCalls.put(call);
            }
            message.put("tool_calls", toolCalls);
        }
        JSONObject choice = new JSONObject();
        choice.put("message", message);
        choice.put("finish_reason", calls.isEmpty() ? "stop" : "tool_calls");
        JSONArray choices = new JSONArray();
        choices.put(choice);
        JSONObject result = new JSONObject();
        result.put("choices", choices);
        return result;
    }

    private static String stopReason(JSONObject result) {
        JSONArray choices = result.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return "end_turn";
        }
        JSONObject choice = choices.optJSONObject(0);
        String reason = choice == null ? "stop" : choice.optString("finish_reason", "stop");
        if ("tool_calls".equals(reason) || "function_call".equals(reason)) {
            return "tool_use";
        }
        if ("length".equals(reason)) {
            return "max_tokens";
        }
        return "end_turn";
    }

    private static final class ToolState {
        int blockIndex = -1;
        boolean started;
        String id;
        String name;
        final StringBuilder arguments = new StringBuilder();
    }

    private void streamMessages(OutputStream out, JSONObject input) throws Exception {
        Upstream upstream = buildUpstream(input);
        GateStream gate = null;
        try {
            gate = openGate(upstream);
            int status = gate.status;
            if (status != 200) {
                String error = readAll(gate.stream);
                lastError = "upstream " + status;
                writeJson(out, status == 429 ? 429 : 502,
                        errorJson("api_error", "opencode zen " + status + ": " + error));
                return;
            }
            String head = "HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/event-stream\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            String messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
            int inputTokens = estimateTokens(input);
            boolean messageStarted = false;
            int nextBlock = 0;
            int textBlock = -1;
            boolean textOpen = false;
            int outputChars = 0;
            String finish = "stop";
            Map<Integer, ToolState> tools = new LinkedHashMap<>();

            InputStream in = new BufferedInputStream(gate.stream);
            String line;
            while ((line = readLine(in)) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) {
                    continue;
                }
                JSONObject chunk;
                try {
                    chunk = new JSONObject(data);
                } catch (Exception e) {
                    continue;
                }
                if (!messageStarted) {
                    sendEvent(out, "message_start", messageStart(messageId, upstream.model,
                            inputTokens));
                    messageStarted = true;
                }
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    continue;
                }
                JSONObject choice = choices.getJSONObject(0);
                String reason = choice.optString("finish_reason", "");
                if (!reason.isEmpty() && !"null".equals(reason)) {
                    finish = reason;
                }
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) {
                    continue;
                }
                String text = delta.optString("content", "");
                if (!text.isEmpty()) {
                    outputChars += text.length();
                    if (!textOpen) {
                        textBlock = nextBlock++;
                        JSONObject start = new JSONObject();
                        start.put("type", "content_block_start");
                        start.put("index", textBlock);
                        JSONObject block = new JSONObject();
                        block.put("type", "text");
                        block.put("text", "");
                        start.put("content_block", block);
                        sendEvent(out, "content_block_start", start);
                        textOpen = true;
                    }
                    JSONObject event = new JSONObject();
                    event.put("type", "content_block_delta");
                    event.put("index", textBlock);
                    JSONObject d = new JSONObject();
                    d.put("type", "text_delta");
                    d.put("text", text);
                    event.put("delta", d);
                    sendEvent(out, "content_block_delta", event);
                }
                JSONArray calls = delta.optJSONArray("tool_calls");
                if (calls != null) {
                    for (int i = 0; i < calls.length(); i++) {
                        JSONObject call = calls.optJSONObject(i);
                        if (call == null) {
                            continue;
                        }
                        int key = call.optInt("index", i);
                        ToolState state = tools.get(key);
                        if (state == null) {
                            state = new ToolState();
                            tools.put(key, state);
                        }
                        JSONObject function = call.optJSONObject("function");
                        if (call.has("id") && !call.optString("id").isEmpty()) {
                            state.id = call.optString("id");
                        }
                        if (function != null && function.has("name")
                                && !function.optString("name").isEmpty()) {
                            state.name = function.optString("name");
                        }
                        if (function != null && function.has("arguments")) {
                            state.arguments.append(function.optString("arguments", ""));
                        }
                    }
                }
            }

            if (!messageStarted) {
                sendEvent(out, "message_start", messageStart(messageId, upstream.model,
                        inputTokens));
            }
            if (textOpen) {
                sendEvent(out, "content_block_stop", blockStop(textBlock));
                textOpen = false;
            }
            for (ToolState state : tools.values()) {
                String sourceName = state.name == null ? "tool" : state.name;
                String targetName = mappedName(upstream, sourceName);
                JSONObject arguments = new JSONObject();
                String raw = state.arguments.toString();
                if (!raw.isEmpty()) {
                    try {
                        arguments = new JSONObject(raw);
                    } catch (Exception e) {
                        arguments = new JSONObject();
                    }
                }
                JSONObject mapped = mappedInput(sourceName, targetName, arguments);
                state.blockIndex = nextBlock++;
                state.started = true;
                JSONObject start = new JSONObject();
                start.put("type", "content_block_start");
                start.put("index", state.blockIndex);
                JSONObject block = new JSONObject();
                block.put("type", "tool_use");
                block.put("id", state.id == null
                        ? "call_" + UUID.randomUUID() : state.id);
                block.put("name", targetName);
                block.put("input", new JSONObject());
                start.put("content_block", block);
                sendEvent(out, "content_block_start", start);
                if (mapped.length() > 0) {
                    JSONObject event = new JSONObject();
                    event.put("type", "content_block_delta");
                    event.put("index", state.blockIndex);
                    JSONObject d = new JSONObject();
                    d.put("type", "input_json_delta");
                    d.put("partial_json", mapped.toString());
                    event.put("delta", d);
                    sendEvent(out, "content_block_delta", event);
                }
                sendEvent(out, "content_block_stop", blockStop(state.blockIndex));
                outputChars += raw.length();
            }
            if (!tools.isEmpty()) {
                finish = "tool_calls";
            }
            JSONObject delta = new JSONObject();
            delta.put("type", "message_delta");
            JSONObject body = new JSONObject();
            body.put("stop_reason", mapStop(finish));
            body.put("stop_sequence", JSONObject.NULL);
            delta.put("delta", body);
            JSONObject usage = new JSONObject();
            usage.put("output_tokens", Math.max(1, outputChars / 4));
            delta.put("usage", usage);
            sendEvent(out, "message_delta", delta);
            sendEvent(out, "message_stop", messageStop());
            out.flush();
        } catch (Exception e) {
            Logger.i("zenproxy: stream error " + e);
        } finally {
            if (gate != null) {
                gate.close();
            }
        }
    }

    private static JSONObject messageStart(String id, String model, int inputTokens)
            throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "message_start");
        JSONObject message = new JSONObject();
        message.put("id", id);
        message.put("type", "message");
        message.put("role", "assistant");
        message.put("model", model);
        message.put("content", new JSONArray());
        message.put("stop_reason", JSONObject.NULL);
        message.put("stop_sequence", JSONObject.NULL);
        JSONObject usage = new JSONObject();
        usage.put("input_tokens", inputTokens);
        usage.put("output_tokens", 0);
        message.put("usage", usage);
        event.put("message", message);
        return event;
    }

    private static JSONObject blockStop(int index) throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "content_block_stop");
        event.put("index", index);
        return event;
    }

    private static JSONObject messageStop() throws Exception {
        JSONObject event = new JSONObject();
        event.put("type", "message_stop");
        return event;
    }

    private static String mapStop(String reason) {
        if ("tool_calls".equals(reason) || "function_call".equals(reason)) {
            return "tool_use";
        }
        if ("length".equals(reason)) {
            return "max_tokens";
        }
        return "end_turn";
    }

    private static void sendEvent(OutputStream out, String name, JSONObject data)
            throws IOException {
        String payload = "event: " + name + "\ndata: " + data.toString() + "\n\n";
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
