package com.attc.opencode;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small on-disk history of the CLI sessions the app started, plus a listing of
 * the files those sessions produced. The panel reads this so the app can show
 * a Claude-style session list without parsing the terminal scrollback.
 */
final class SessionStore {

    private static final String FILE = Setup.STORAGE_DIR + "/sessions.json";
    private static final int LIMIT = 60;

    static final class Entry {
        String cli = "opencode";
        long started;
        String id = "";
    }

    private SessionStore() {
    }

    static synchronized void record(String cli) {
        try {
            JSONArray array = read();
            JSONObject item = new JSONObject();
            item.put("cli", cli);
            item.put("started", System.currentTimeMillis());
            item.put("id", "ses_" + Long.toString(System.currentTimeMillis(), 36));
            array.put(item);
            while (array.length() > LIMIT) {
                array.remove(0);
            }
            write(array);
        } catch (Exception e) {
            Logger.i("sessions: record failed " + e);
        }
    }

    static synchronized List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        JSONArray array = read();
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Entry entry = new Entry();
            entry.cli = item.optString("cli", "opencode");
            entry.started = item.optLong("started", 0L);
            entry.id = item.optString("id", "");
            out.add(entry);
        }
        return out;
    }

    /**
     * The CLI that was running when this file was last written: the newest
     * session that started before the file's modification time, or null when
     * the file predates the recorded history.
     */
    static String cliFor(long modified) {
        for (Entry entry : list()) {
            if (entry.started > 0 && entry.started <= modified) {
                return entry.cli;
            }
        }
        return null;
    }

    static String label(String cli) {
        if ("claude".equals(cli)) {
            return "Claude Code";
        }
        if ("agy".equals(cli)) {
            return "Antigravity";
        }
        if ("terminal".equals(cli)) {
            return "Terminal";
        }
        if ("codex".equals(cli)) {
            return "Codex";
        }
        if ("kimi".equals(cli)) {
            return "Kimi";
        }
        return "Attc Code";
    }

    static String when(long millis) {
        if (millis <= 0) {
            return "";
        }
        return new SimpleDateFormat("MMM d, HH:mm", Locale.ENGLISH)
                .format(new Date(millis));
    }

    /** Recently changed files under the guest home, newest first. */
    static List<File> files(File home) {
        List<File> found = new ArrayList<>();
        collect(home, found, 0);
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        if (found.size() > 120) {
            return found.subList(0, 120);
        }
        return found;
    }

    private static void collect(File dir, List<File> out, int depth) {
        if (dir == null || depth > 4) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith(".") || "node_modules".equals(name)
                    || "logs".equals(name) || "mitm".equals(name)) {
                continue;
            }
            if (child.isDirectory()) {
                collect(child, out, depth + 1);
            } else if (child.length() < 4L * 1024 * 1024) {
                out.add(child);
            }
        }
    }

    private static JSONArray read() {
        try {
            File file = new File(FILE);
            if (!file.exists()) {
                return new JSONArray();
            }
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
            in.close();
            return new JSONArray(new String(data, 0, read, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void write(JSONArray array) throws Exception {
        new File(Setup.STORAGE_DIR).mkdirs();
        FileOutputStream out = new FileOutputStream(FILE);
        try {
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
