package com.attc.opencode;

import android.content.Context;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Watches the assistant's own log for the moment its free quota runs out and
 * switches the egress address, so the next request looks like it comes from a
 * new client and the free allowance is available again.
 *
 * The check is deliberately simple: the log line is written by the CLI itself,
 * the tail is re-read only when the file grows, and a cooldown keeps a burst of
 * identical errors from rotating several times in a row.
 */
public final class QuotaWatch {

    private static final long INTERVAL_MS = 5000L;
    private static final long COOLDOWN_MS = 120_000L;
    private static final int TAIL_BYTES = 8192;

    private static final String[] PATTERNS = {
            "free usage limit reached",
            "free usage exceeded",
            "spent quota on every chain provider",
            "insufficient_quota",
            "quota exceeded",
            "credit balance is too low"
    };

    private static volatile boolean started;
    private static long lastReact;

    private QuotaWatch() {
    }

    public static synchronized void start(Context ctx) {
        if (started) {
            return;
        }
        started = true;
        final Context app = ctx.getApplicationContext();
        Thread thread = new Thread(() -> {
            long lastLength = -1;
            while (true) {
                try {
                    File log = new File(new Setup(app).rootDir(),
                            "root/.local/share/opencode/log/opencode.log");
                    if (log.isFile()) {
                        long length = log.length();
                        if (lastLength < 0) {
                            // Ignore history on the first pass: an old error
                            // must not rotate the exit on every app start.
                            lastLength = length;
                        } else if (length != lastLength) {
                            lastLength = length;
                            if (matches(tail(log, length))) {
                                react(app);
                            }
                        }
                    }
                } catch (Exception e) {
                    Logger.i("quota: watch error " + e);
                }
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "attc-quota-watch");
        thread.setDaemon(true);
        thread.start();
        Logger.i("quota: watching for exhausted free allowance");
    }

    private static boolean matches(String text) {
        for (String pattern : PATTERNS) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
    }

    private static String tail(File file, long length) throws Exception {
        long from = Math.max(0, length - TAIL_BYTES);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(from);
            byte[] buf = new byte[(int) (length - from)];
            raf.readFully(buf);
            return new String(buf, "UTF-8").toLowerCase(Locale.US);
        }
    }

    /**
     * Copies the tail of the guest's assistant log to the shared folder so the
     * exact provider error can be inspected without root.
     */
    public static String exportTail(Context ctx) {
        try {
            File src = new File(new Setup(ctx).rootDir(),
                    "root/.local/share/opencode/log/opencode.log");
            if (!src.isFile()) {
                return "no assistant log at " + src;
            }
            long length = src.length();
            long from = Math.max(0, length - 96 * 1024);
            byte[] buf = new byte[(int) (length - from)];
            try (RandomAccessFile raf = new RandomAccessFile(src, "r")) {
                raf.seek(from);
                raf.readFully(buf);
            }
            File out = new File("/storage/emulated/0/ATTC OPENCODE/logs/opencode-tail.log");
            File parent = out.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(buf);
            }
            return "exported " + buf.length + " bytes to " + out;
        } catch (Exception e) {
            return "export failed " + e;
        }
    }

    private static void react(Context app) {
        long now = System.currentTimeMillis();
        if (now - lastReact < COOLDOWN_MS) {
            return;
        }
        lastReact = now;
        Logger.i("quota: free allowance exhausted, requesting a quota reset");
        String exported = exportTail(app);
        Logger.i("quota: " + exported);
        QuotaReset.trigger(app, "free allowance exhausted");
        Logger.i("quota: reset requested");
    }
}
