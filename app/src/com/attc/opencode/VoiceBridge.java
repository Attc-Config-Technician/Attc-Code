package com.attc.opencode;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Bridge between an agent running inside the Ubuntu guest and Android itself.
 *
 * The guest cannot inject input or start activities with shell privileges. This
 * watcher picks up small request files written by {@code attc-ctl} inside
 * /root/attc/ctl, runs the command through Shizuku (uid 2000, the same powers
 * as adb shell) and writes the output back. It is deliberately file based: no
 * sockets, no ports, and it works while the screen is off.
 */
public final class VoiceBridge {

    private static final String HELPER = "#!/bin/sh\n"
            + "# attc-ctl: run a shell command on Android through Attc Code (Shizuku).\n"
            + "# Example: attc-ctl \"am start -a android.intent.action.VIEW -d 'https://music.youtube.com'\n"
            + "D=/root/attc/ctl\n"
            + "mkdir -p \"$D\" 2>/dev/null\n"
            + "if [ $# -eq 0 ]; then\n"
            + "  echo \"usage: attc-ctl <android shell command>\"\n"
            + "  echo \"examples:\"\n"
            + "  echo \"  attc-ctl \\\"am start -a android.intent.action.VIEW -d 'https://music.youtube.com/search?q=sezen aksu'\\\"\"\n"
            + "  echo \"  attc-ctl \\\"input keyevent 224\\\"\"\n"
            + "  echo \"  attc-ctl \\\"cmd media_session dispatch play\\\"\"\n"
            + "  exit 2\n"
            + "fi\n"
            + "ID=\"$$.$(date +%s)\"\n"
            + "printf '%s' \"$*\" > \"$D/req.$ID.tmp\" && mv \"$D/req.$ID.tmp\" \"$D/req.$ID\"\n"
            + ": > \"$D/go.$ID\"\n"
            + "i=0\n"
            + "while [ ! -f \"$D/out.$ID\" ]; do\n"
            + "  i=$((i+1))\n"
            + "  if [ $i -ge 600 ]; then\n"
            + "    echo \"attc-ctl: timeout waiting for the app\"\n"
            + "    rm -f \"$D/req.$ID\" \"$D/go.$ID\"\n"
            + "    exit 3\n"
            + "  fi\n"
            + "  sleep 0.1\n"
            + "done\n"
            + "cat \"$D/out.$ID\"\n"
            + "rm -f \"$D/req.$ID\" \"$D/go.$ID\" \"$D/out.$ID\"\n";

    private static volatile boolean started;
    private static Thread thread;

    private VoiceBridge() {
    }

    /** Writes the guest helper script. Safe to call on every boot. */
    static void install(Context context) {
        try {
            Setup setup = new Setup(context);
            File root = setup.rootDir();
            if (!root.exists()) {
                return;
            }
            File home = new File(root, "root");
            File bin = new File(home, ".local/bin");
            if (!bin.exists() && !bin.mkdirs()) {
                return;
            }
            File script = new File(bin, "attc-ctl");
            write(script, HELPER);
            script.setExecutable(true, false);
            Logger.i("voice: attc-ctl installed at " + script);
        } catch (Throwable t) {
            Logger.i("voice: attc-ctl install failed " + t);
        }
    }

    private static void write(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }

    static synchronized void start(Context context) {
        if (started) {
            return;
        }
        started = true;
        final File dir = new File(Setup.STORAGE_DIR, "ctl");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        thread = new Thread(() -> {
            Logger.i("voice: bridge watching " + dir);
            while (started) {
                try {
                    File[] files = dir.listFiles();
                    if (files != null && files.length > 0) {
                        Arrays.sort(files, new Comparator<File>() {
                            @Override
                            public int compare(File a, File b) {
                                return a.getName().compareTo(b.getName());
                            }
                        });
                        for (File go : files) {
                            if (!started) {
                                break;
                            }
                            String name = go.getName();
                            if (!name.startsWith("go.") || !go.isFile()) {
                                continue;
                            }
                            String id = name.substring(3);
                            handle(dir, id);
                        }
                    }
                    Thread.sleep(180L);
                } catch (InterruptedException e) {
                    break;
                } catch (Throwable t) {
                    Logger.i("voice: bridge loop " + t);
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
            Logger.i("voice: bridge stopped");
        }, "attc-voice-bridge");
        thread.setDaemon(true);
        thread.start();
    }

    static synchronized void stop() {
        started = false;
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
        thread = null;
    }

    private static void handle(File dir, String id) {
        File req = new File(dir, "req." + id);
        File go = new File(dir, "go." + id);
        String command = null;
        try {
            command = read(req);
        } catch (Throwable t) {
            Logger.i("voice: ctl read failed " + t);
        }
        if (command == null || command.trim().isEmpty()) {
            writeResult(dir, id, "attc-ctl: empty or unreadable request\n");
            req.delete();
            go.delete();
            return;
        }
        command = command.trim();
        Logger.i("voice: ctl$ " + truncate(command, 400));
        String result;
        if (!ShizukuHelper.granted()) {
            result = "attc-ctl: Shizuku permission is not granted\n";
            Logger.i("voice: ctl blocked, no Shizuku");
        } else {
            try {
                result = ShizukuHelper.run(timeoutWrap(command));
                if (result.length() > 200000) {
                    result = result.substring(0, 200000) + "\n[output truncated]\n";
                }
            } catch (Throwable t) {
                result = "attc-ctl: " + t + "\n";
            }
        }
        Logger.i("voice: ctl result " + truncate(result, 400));
        writeResult(dir, id, result);
        req.delete();
        go.delete();
    }

    private static String read(File file) throws Exception {
        if (!file.exists()) {
            return null;
        }
        byte[] data = new byte[(int) Math.min(file.length(), 64000)];
        FileInputStream in = new FileInputStream(file);
        int read = 0;
        try {
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n <= 0) {
                    break;
                }
                read += n;
            }
        } finally {
            in.close();
        }
        return new String(data, 0, read, StandardCharsets.UTF_8);
    }

    private static void writeResult(File dir, String id, String result) {
        File tmp = new File(dir, "out." + id + ".tmp");
        File out = new File(dir, "out." + id);
        try {
            write(tmp, result == null ? "" : result);
            if (!tmp.renameTo(out)) {
                write(out, result == null ? "" : result);
                tmp.delete();
            }
        } catch (Throwable t) {
            Logger.i("voice: ctl write failed " + t);
            try {
                write(out, "attc-ctl: result write failed\n");
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Toybox ships timeout on modern Android. Wrapping the command keeps one
     * stuck UI dump from blocking the whole bridge.
     */
    private static String timeoutWrap(String command) {
        if (!new File("/system/bin/timeout").exists()) {
            return command;
        }
        return "timeout 40 sh -c '" + command.replace("'", "'\\''") + "'";
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
