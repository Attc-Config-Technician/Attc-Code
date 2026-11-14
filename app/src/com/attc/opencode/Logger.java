package com.attc.opencode;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Tiny append-only log for setup diagnostics. Writes to the shared folder so a
 * user can attach it to a bug report, and mirrors to logcat.
 */
public final class Logger {

    private static final String TAG = "ATTC";
    private static File file;
    private static final SimpleDateFormat STAMP =
            new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);

    private Logger() {
    }

    public static synchronized void init(Context context) {
        if (file != null) {
            return;
        }
        try {
            File dir = new File(Setup.STORAGE_DIR, "logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            file = new File(dir, "attc.log");
        } catch (Exception e) {
            file = null;
        }
    }

    public static void i(String message) {
        Log.i(TAG, message);
        write("I", message);
    }

    public static void e(String message, Throwable t) {
        Log.e(TAG, message, t);
        write("E", message + (t == null ? "" : " :: " + t));
    }

    private static synchronized void write(String level, String message) {
        if (file == null) {
            return;
        }
        if (file.length() > 512 * 1024) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
        try (FileWriter w = new FileWriter(file, true)) {
            w.write(STAMP.format(new Date()));
            w.write(' ');
            w.write(level);
            w.write(' ');
            w.write(message);
            w.write('\n');
        } catch (Exception ignored) {
        }
    }
}
