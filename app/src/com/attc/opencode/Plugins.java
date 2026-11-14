package com.attc.opencode;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the opencode plugin configuration and cache in sync with npm.
 *
 * The guest home is /root, so the config it reads lives at
 * {@code <rootfs>/root/.config/opencode/opencode.json}. On every boot the
 * config is merged (never overwritten) and, at most once every six hours, the
 * npm registry is asked for the latest version of each bundled plugin. When a
 * newer version exists the stale plugin cache is removed so opencode
 * re-installs it on the next start.
 *
 * Both entry points are best effort: no failure here may break the boot.
 */
public final class Plugins {

    /** Bare npm package names the app guarantees in the guest server config. */
    private static final String[] NPM_PACKAGES = {
            "@prevalentware/opencode-goal-plugin",
            "oh-my-opencode-slim",
            "open-plan-annotator",
            "opencode-swarm"
    };

    /** Guest path of the bundled local plan-manager plugin. */
    static final String PLAN_MANAGER = "/root/.config/opencode/plugins/plan-manager";

    /** Asset folder that holds the plan-manager plugin files. */
    private static final String PLAN_MANAGER_ASSET = "plugins/plan-manager";

    /** Marker written after the bundled local plugin is unpacked. */
    private static final String PLAN_MANAGER_MARKER = ".attc-local";

    /**
     * Every server-side plugin entry: the npm packages plus the local
     * plan-manager directory. OpenCode 2 reads this list from opencode.json.
     */
    private static final String[] PACKAGES = {
            "@prevalentware/opencode-goal-plugin",
            "oh-my-opencode-slim",
            "open-plan-annotator",
            "opencode-swarm",
            PLAN_MANAGER
    };

    /**
     * TUI-side entries. OpenCode 2 reads these from cli.json; both packages
     * ship a dedicated ./tui entrypoint (goal-mode.tui, oh-my-opencode-slim:tui).
     */
    private static final String[] TUI_PACKAGES = {
            "@prevalentware/opencode-goal-plugin",
            "oh-my-opencode-slim"
    };

    static final String SCHEMA = "https://opencode.ai/config.json";
    private static final String REGISTRY = "https://registry.npmjs.org/";
    private static final String STATE_FILE = ".plugins-state";

    private static final long THROTTLE_MS = 6L * 60 * 60 * 1000;
    /** Retry sooner after a failed attempt so a transient outage is not 6 h. */
    private static final long RETRY_MS = 30L * 60 * 1000;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final long LOOKUP_CAP_MS = 25000;

    private Plugins() {
    }

    // ------------------------------------------------------------- config

    /**
     * Unpacks the bundled local plan-manager plugin into the guest config.
     * A marker makes this a one-time write; failures never break the boot.
     */
    public static void installLocal(Context ctx, File rootfs) {
        try {
            File dir = new File(new File(new File(new File(rootfs, "root"), ".config"),
                    "opencode"), "plugins/plan-manager");
            File marker = new File(dir, PLAN_MANAGER_MARKER);
            if (marker.isFile() && new File(dir, "index.js").isFile()) {
                return;
            }
            File vendor = new File(dir, "vendor");
            if ((!dir.exists() && !dir.mkdirs() && !dir.exists())
                    || (!vendor.exists() && !vendor.mkdirs() && !vendor.exists())) {
                Logger.i("plugins: local plan-manager mkdir failed");
                return;
            }
            copyAsset(ctx, PLAN_MANAGER_ASSET + "/index.js", new File(dir, "index.js"));
            copyAsset(ctx, PLAN_MANAGER_ASSET + "/schemas.json", new File(dir, "schemas.json"));
            copyAsset(ctx, PLAN_MANAGER_ASSET + "/vendor/opencode-plan-manager.js",
                    new File(vendor, "opencode-plan-manager.js"));
            copyAsset(ctx, PLAN_MANAGER_ASSET + "/vendor/LICENSE", new File(vendor, "LICENSE"));
            writeText(marker, "1\n");
            Logger.i("plugins: local plan-manager installed");
        } catch (Exception e) {
            Logger.i("plugins: local plan-manager failed " + e);
        }
    }

    private static void copyAsset(Context ctx, String name, File out) throws IOException {
        try (InputStream in = ctx.getAssets().open(name);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    /**
     * Ensures {@code <rootfs>/root/.config/opencode/opencode.json} lists every
     * package. Existing entries, their order and local path entries are kept.
     * When only a JSONC file exists it is edited textually so comments and
     * formatting survive.
     */
    public static void ensureConfig(File rootfs) {
        try {
            File dir = new File(new File(new File(rootfs, "root"), ".config"), "opencode");
            File config = new File(dir, "opencode.json");
            File jsonc = new File(dir, "opencode.jsonc");

            ensureCliConfig(dir);

            if (!config.exists() || config.length() == 0) {
                if (jsonc.exists()) {
                    ensureJsonc(jsonc);
                    return;
                }
                JSONObject fresh = new JSONObject();
                fresh.put("$schema", SCHEMA);
                fresh.put("plugins", new JSONArray());
                ensureMerged(config, fresh);
                return;
            }

            JSONObject json;
            try (InputStream in = new BufferedInputStream(new FileInputStream(config))) {
                json = new JSONObject(readAll(in));
            } catch (org.json.JSONException e) {
                Logger.i("plugins: config parse failed");
                return;
            }
            ensureMerged(config, json);
        } catch (Exception e) {
            Logger.i("plugins: config parse failed");
        }
    }

    /**
     * Merges the TUI plugin entries into {@code cli.json}. OpenCode 2 loads
     * TUI plugins from this file; the server list lives in opencode.json.
     * Existing settings and entries are preserved.
     */
    private static void ensureCliConfig(File dir) {
        File cli = new File(dir, "cli.json");
        try {
            if (!cli.exists() || cli.length() == 0) {
                JSONObject fresh = new JSONObject();
                fresh.put("$schema", "https://opencode.ai/v2/cli.json");
                JSONArray array = new JSONArray();
                for (String pkg : TUI_PACKAGES) {
                    array.put(pkg);
                }
                fresh.put("plugins", array);
                writeConfig(cli, fresh);
                Logger.i("plugins: tui created entries=" + array.length());
                return;
            }

            JSONObject json;
            try (InputStream in = new BufferedInputStream(new FileInputStream(cli))) {
                json = new JSONObject(readAll(in));
            } catch (org.json.JSONException e) {
                Logger.i("plugins: tui parse failed");
                return;
            }
            String key = json.has("plugin") ? "plugin" : "plugins";
            JSONArray array;
            if (json.has(key)) {
                Object value = json.opt(key);
                if (!(value instanceof JSONArray)) {
                    Logger.i("plugins: tui skipped (" + key + " key is not an array)");
                    return;
                }
                array = (JSONArray) value;
            } else {
                array = new JSONArray();
                json.put(key, array);
            }

            List<String> added = new ArrayList<>();
            for (String pkg : TUI_PACKAGES) {
                if (!contains(array, pkg)) {
                    array.put(pkg);
                    added.add(pkg);
                }
            }
            if (!added.isEmpty()) {
                writeConfig(cli, json);
            }
            Logger.i("plugins: tui ready entries=" + array.length()
                    + " added=" + (added.isEmpty() ? "none" : join(added)));
        } catch (Exception e) {
            Logger.i("plugins: tui config failed");
        }
    }

    /**
     * Adds missing packages to an existing JSONC file without reformatting it.
     * A backup is taken once; the edit is validated with a comment-stripping
     * parser before it replaces the file.
     */
    private static void ensureJsonc(File jsonc) {
        try {
            String original = readText(jsonc);
            List<String> missing = new ArrayList<>();
            for (String pkg : PACKAGES) {
                if (!original.contains("\"" + pkg + "\"")) {
                    missing.add(pkg);
                }
            }
            if (missing.isEmpty()) {
                Logger.i("plugins: config ready (jsonc) present="
                        + PACKAGES.length + "/" + PACKAGES.length);
                return;
            }

            String edited = insertJsonc(original, missing);
            File backup = new File(jsonc.getParentFile(), jsonc.getName() + ".attc.bak");
            boolean backedUp = false;
            if (!backup.exists()) {
                copyFile(jsonc, backup);
                backedUp = true;
            }
            if (!validJsonc(edited)) {
                if (backedUp) {
                    copyFile(backup, jsonc);
                }
                Logger.i("plugins: config edit failed");
                return;
            }
            writeText(jsonc, edited);
            Logger.i("plugins: config updated (jsonc) added=" + join(missing));
        } catch (Exception e) {
            Logger.i("plugins: config edit failed");
        }
    }

    /** Inserts the missing names into the first plugin array, or adds one. */
    private static String insertJsonc(String text, List<String> missing) {
        StringBuilder names = new StringBuilder();
        for (String pkg : missing) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append('"').append(pkg).append('"');
        }

        int[] found = findTopLevelPluginArray(text);

        if (found != null) {
            int bracket = found[2];
            int next = nextSignificant(text, bracket + 1);
            boolean empty = next < text.length() && text.charAt(next) == ']';
            String insert = names + (empty ? "" : ", ");
            return text.substring(0, bracket + 1) + insert + text.substring(bracket + 1);
        }

        int close = text.lastIndexOf('}');
        if (close < 0) {
            throw new IllegalArgumentException("no top-level object");
        }
        int open = text.indexOf('{');
        String body = open >= 0 && open < close ? text.substring(open + 1, close).trim() : "";
        String insert = (body.isEmpty() ? "" : ",")
                + "\n  \"plugins\": [" + names + "]\n";
        return text.substring(0, close) + insert + text.substring(close);
    }

    /** Index of the next non-whitespace, non-comment character, or text length. */
    private static int nextSignificant(String text, int i) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length()
                        && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else {
                return i;
            }
        }
        return i;
    }

    /**
     * Finds the first top-level {@code "plugin"} or {@code "plugins"} key whose
     * value is an array. Returns {key start, key length, bracket index}, or null.
     */
    private static int[] findTopLevelPluginArray(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length()
                        && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    i++;
                }
                i++;
                continue;
            }
            if (c == '"') {
                int start = i;
                int end = i + 1;
                boolean escaped = false;
                while (end < text.length()) {
                    char d = text.charAt(end);
                    if (escaped) {
                        escaped = false;
                    } else if (d == '\\') {
                        escaped = true;
                    } else if (d == '"') {
                        break;
                    }
                    end++;
                }
                if (end >= text.length()) {
                    return null;
                }
                String name = text.substring(start + 1, end);
                if (depth == 1 && ("plugin".equals(name) || "plugins".equals(name))) {
                    int k = end + 1;
                    while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
                        k++;
                    }
                    if (k < text.length() && text.charAt(k) == ':') {
                        k++;
                        while (k < text.length() && Character.isWhitespace(text.charAt(k))) {
                            k++;
                        }
                        if (k < text.length() && text.charAt(k) == '[') {
                            return new int[]{start, end + 1 - start, k};
                        }
                    }
                }
                i = end;
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return null;
    }

    /**
     * True when the edited text parses as JSON after removing comments and
     * trailing commas. Used only to guard the file, never to read config.
     */
    private static boolean validJsonc(String text) {
        try {
            String stripped = stripComments(text);
            stripped = stripped.replaceAll(",\\s*([}\\]])", "$1");
            new JSONObject(stripped);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Removes // and /* *&#47; comments while respecting string literals. */
    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
                out.append(c);
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                i += 2;
                while (i < text.length() && text.charAt(i) != '\n') {
                    i++;
                }
                if (i < text.length()) {
                    out.append('\n');
                }
            } else if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < text.length()
                        && !(text.charAt(i) == '*' && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') {
                        out.append('\n');
                    }
                    i++;
                }
                i++;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Merge into the parsed object, write only when something was added. */
    private static void ensureMerged(File config, JSONObject json)
            throws IOException, org.json.JSONException {
        String key = json.has("plugin") ? "plugin" : "plugins";
        JSONArray array;
        if (json.has(key)) {
            Object value = json.opt(key);
            if (!(value instanceof JSONArray)) {
                Logger.i("plugins: config skipped (" + key + " key is not an array)");
                return;
            }
            array = (JSONArray) value;
        } else {
            array = new JSONArray();
            json.put(key, array);
        }

        List<String> added = new ArrayList<>();
        for (String pkg : PACKAGES) {
            if (!contains(array, pkg)) {
                array.put(pkg);
                added.add(pkg);
            }
        }

        if (!added.isEmpty()) {
            writeConfig(config, json);
        }
        Logger.i("plugins: config ready entries=" + array.length()
                + " added=" + (added.isEmpty() ? "none" : join(added)));
    }

    private static boolean contains(JSONArray array, String pkg) {
        for (int i = 0; i < array.length(); i++) {
            Object entry = array.opt(i);
            if (entry instanceof String && pkg.equals(entry)) {
                return true;
            }
            if (entry instanceof JSONObject
                    && pkg.equals(((JSONObject) entry).optString("package", ""))) {
                return true;
            }
        }
        return false;
    }

    private static boolean writeConfig(File config, JSONObject json)
            throws IOException, org.json.JSONException {
        File dir = config.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IOException("could not create " + dir);
        }
        File tmp = new File(dir, config.getName() + ".tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            os.write((json.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        if (tmp.renameTo(config)) {
            return true;
        }
        // Some filesystems refuse to rename over an existing file.
        //noinspection ResultOfMethodCallIgnored
        config.delete();
        if (!tmp.renameTo(config)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("could not replace " + config);
        }
        return true;
    }

    // ------------------------------------------------------------ refresh

    /**
     * Checks npm for newer plugin versions and clears the pinned cache when one
     * is found. Throttled to one check per six hours (30 minutes after a
     * failed attempt) and capped at roughly twenty-five seconds of boot time,
     * sized for a slow registry.
     */
    public static void refresh(final Context ctx, final File rootfs) {
        try {
            File stateFile = new File(ctx.getFilesDir(), STATE_FILE);
            Map<String, String> state = readState(stateFile);
            long checked = parseLong(state.get("checked"));
            long now = System.currentTimeMillis();
            long window = "1".equals(state.get("failed")) ? RETRY_MS : THROTTLE_MS;
            if (checked > 0 && now - checked < window) {
                Logger.i("plugins: refresh skipped (checked recently)");
                return;
            }

            final String[] versions = new String[NPM_PACKAGES.length];
            final Exception[] errors = new Exception[NPM_PACKAGES.length];
            Thread[] threads = new Thread[NPM_PACKAGES.length];
            for (int i = 0; i < NPM_PACKAGES.length; i++) {
                final int index = i;
                threads[i] = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            versions[index] = latestVersion(NPM_PACKAGES[index]);
                        } catch (Exception e) {
                            errors[index] = e;
                        }
                    }
                }, "attc-plugins-" + NPM_PACKAGES[i]);
                threads[i].setDaemon(true);
                threads[i].start();
            }

            long deadline = System.currentTimeMillis() + LOOKUP_CAP_MS;
            for (int i = 0; i < threads.length; i++) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    threads[i].join(remaining);
                }
                if (threads[i].isAlive()) {
                    errors[i] = new IOException("timed out");
                }
            }

            int failures = 0;
            for (int i = 0; i < NPM_PACKAGES.length; i++) {
                if (errors[i] != null || versions[i] == null) {
                    String why = errors[i] == null ? "no version" : message(errors[i]);
                    Logger.i("plugins: refresh lookup failed " + NPM_PACKAGES[i] + ": " + why);
                    failures++;
                }
            }

            if (failures == NPM_PACKAGES.length) {
                writeState(stateFile, now, stateVersions(state, versions), true);
                Logger.i("plugins: refresh failed all lookups");
                return;
            }

            List<String> outdated = new ArrayList<>();
            for (int i = 0; i < NPM_PACKAGES.length; i++) {
                if (versions[i] == null) {
                    continue;
                }
                String stored = state.get("pkg:" + NPM_PACKAGES[i]);
                if (stored == null || compareSemver(versions[i], stored) > 0) {
                    outdated.add(NPM_PACKAGES[i]);
                }
            }

            if (!outdated.isEmpty()) {
                clearCache(rootfs);
                for (int i = 0; i < NPM_PACKAGES.length; i++) {
                    if (!outdated.contains(NPM_PACKAGES[i])) {
                        continue;
                    }
                    String before = state.get("pkg:" + NPM_PACKAGES[i]);
                    Logger.i("plugins: refresh cleared cache pkg=" + NPM_PACKAGES[i] + "="
                            + (before == null ? "0" : before) + "->" + versions[i]);
                }
            } else {
                Logger.i("plugins: refresh up-to-date");
            }

            writeState(stateFile, now, stateVersions(state, versions), false);
        } catch (Exception e) {
            Logger.i("plugins: refresh failed " + message(e));
        }
    }

    private static String latestVersion(String pkg) throws IOException {
        String url = REGISTRY + pkg + "/latest";
        if (!url.startsWith("https://")) {
            throw new IOException("refusing non-https lookup");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(CONNECT_TIMEOUT_MS);
            c.setReadTimeout(READ_TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "ATTC-OPENCODE");
            int code = c.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = new BufferedInputStream(c.getInputStream())) {
                JSONObject json = new JSONObject(readAll(in));
                String version = json.optString("version", "");
                if (version.isEmpty()) {
                    throw new IOException("no version field");
                }
                return version;
            } catch (org.json.JSONException e) {
                throw new IOException("bad registry response", e);
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * node_modules, the bun lock files and package.json pin the plugin versions
     * opencode installed earlier. bin/ is left alone: it holds the toolchain.
     */
    private static void clearCache(File rootfs) {
        File cache = new File(new File(new File(rootfs, "root"), ".cache"), "opencode");
        deleteTree(new File(cache, "node_modules"));
        //noinspection ResultOfMethodCallIgnored
        new File(cache, "bun.lock").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(cache, "bun.lockb").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(cache, "package.json").delete();
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                deleteTree(k);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // -------------------------------------------------------------- state

    private static Map<String, String> readState(File file) {
        Map<String, String> state = new HashMap<>();
        if (!file.exists()) {
            return state;
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            for (String raw : readAll(in).split("\n")) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String key = line.substring(0, eq);
                String value = line.substring(eq + 1);
                if ("pkg".equals(key)) {
                    int split = value.indexOf('=');
                    if (split > 0) {
                        state.put("pkg:" + value.substring(0, split), value.substring(split + 1));
                    }
                } else {
                    state.put(key, value);
                }
            }
        } catch (Exception ignored) {
        }
        return state;
    }

    /** New version for successful lookups, previous stored version otherwise. */
    private static String[] stateVersions(Map<String, String> state, String[] versions) {
        String[] out = new String[NPM_PACKAGES.length];
        for (int i = 0; i < NPM_PACKAGES.length; i++) {
            out[i] = versions[i] != null ? versions[i] : state.get("pkg:" + NPM_PACKAGES[i]);
        }
        return out;
    }

    private static void writeState(File file, long checked, String[] versions, boolean failed) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("checked=").append(checked).append('\n');
            sb.append("failed=").append(failed ? "1" : "0").append('\n');
            for (int i = 0; i < NPM_PACKAGES.length; i++) {
                if (versions[i] == null) {
                    continue;
                }
                sb.append("pkg=").append(NPM_PACKAGES[i]).append('=').append(versions[i]).append('\n');
            }
            try (OutputStream os = new FileOutputStream(file)) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Logger.i("plugins: refresh failed state write " + message(e));
        }
    }

    // --------------------------------------------------------------- utils

    private static String readText(File file) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            return readAll(in);
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(from));
             OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    /** Numeric semver comparison; suffixes after the patch number are ignored. */
    static int compareSemver(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int xi = i < x.length ? leadingInt(x[i]) : 0;
            int yi = i < y.length ? leadingInt(y[i]) : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\d+").matcher(s == null ? "" : s);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group());
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
