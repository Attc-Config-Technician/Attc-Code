package com.attc.opencode;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku bridge.
 *
 * Shizuku lets the app perform privileged shell operations (runtime permission
 * grants, appops) without root. It is not required for the terminal to work:
 * the terminal and opencode run through proot with the app's own uid. When
 * Shizuku is installed and the user grants this app the Shizuku permission, the
 * app additionally self-grants storage and notification access through
 * {@code pm}/{@code appops}.
 */
public final class ShizukuHelper {

    public static final int REQUEST_CODE = 4242;

    public interface Listener {
        void onShizukuStatus(String status, boolean available, boolean granted);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean installed;
    private static Listener listener;

    private ShizukuHelper() {
    }

    /** Registers the Shizuku listeners once and reports the current state. */
    public static void init(Context context, Listener l) {
        listener = l;
        if (installed) {
            notifyStatus();
            return;
        }
        installed = true;
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                Logger.i("shizuku binder received");
                notifyStatus();
                if (granted()) {
                    grantOwnPermissions(context);
                }
            });
            Shizuku.addBinderDeadListener(() -> {
                Logger.i("shizuku binder dead");
                notifyStatus();
            });
            Shizuku.addRequestPermissionResultListener((requestCode, grantResult) -> {
                Logger.i("shizuku permission result code=" + requestCode
                        + " grant=" + grantResult);
                notifyStatus();
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    grantOwnPermissions(context);
                }
            });
        } catch (Throwable t) {
            Logger.i("shizuku listener setup failed: " + t);
        }
        notifyStatus();
    }

    public static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean granted() {
        try {
            return available()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String status() {
        if (!available()) {
            return "Shizuku not running";
        }
        return granted() ? "Shizuku granted" : "Shizuku permission needed";
    }

    /** Asks Shizuku Manager to show the permission dialog for this app. */
    public static void request(Activity activity) {
        if (!available()) {
            Logger.i("shizuku request skipped: service not running");
            return;
        }
        try {
            if (Shizuku.isPreV11() || Shizuku.getVersion() < 11) {
                activity.requestPermissions(
                        new String[]{"moe.shizuku.manager.permission.API_V23"},
                        REQUEST_CODE);
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                Logger.i("shizuku permission permanently denied; open Shizuku Manager");
                notifyStatus();
                return;
            }
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable t) {
            Logger.i("shizuku request failed: " + t);
        }
    }

    /**
     * Grants the app's own runtime permissions through the Shizuku shell.
     * Runs in the background; each command's exit code is logged.
     */
    public static void grantOwnPermissions(Context context) {
        final String pkg = context.getPackageName();
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append(run("pm grant " + pkg + " android.permission.POST_NOTIFICATIONS"));
            sb.append(run("appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow"));
            sb.append(run("appops set " + pkg + " POST_NOTIFICATION allow"));
            sb.append(run("appops set " + pkg + " LEGACY_STORAGE allow"));
            Logger.i("shizuku grants:\n" + sb);
            notifyStatus();
        }, "attc-shizuku-grant").start();
    }

    /** Runs one shell command through Shizuku and returns "cmd -> exit" plus output. */
    public static String run(String command) {
        Process process = null;
        try {
            Method newProcess = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            newProcess.setAccessible(true);
            final Process proc = (Process) newProcess.invoke(null,
                    new String[]{"sh", "-c", command}, null, "/");
            process = proc;

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread errThread = new Thread(() -> drain(proc.getErrorStream(), err));
            errThread.start();
            drain(proc.getInputStream(), out);
            // ShizukuRemoteProcess only supports the blocking waitFor(): its
            // exitValue() throws while the process is alive, which breaks the
            // timed Process.waitFor(long, TimeUnit) implementation.
            int code = proc.waitFor();
            errThread.join(2000);
            String text = (out.toString("UTF-8") + err.toString("UTF-8")).trim();
            return command + " -> " + code + (text.isEmpty() ? "" : " " + text) + "\n";
        } catch (Throwable t) {
            return command + " -> failed: " + t + "\n";
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } catch (Exception ignored) {
        }
    }

    private static void notifyStatus() {
        final String text = status();
        final boolean avail = available();
        final boolean ok = granted();
        MAIN.post(() -> {
            if (listener != null) {
                listener.onShizukuStatus(text, avail, ok);
            }
        });
    }
}
