package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Random;

/**
 * Guest identity isolation.
 *
 * When enabled the Linux side sees a rotating machine-id, hostname and MAC
 * address instead of values derived from the device. When disabled it sees the
 * real values again.
 *
 * What this can and cannot do:
 *  - it changes what programs inside Ubuntu read from /etc/machine-id,
 *    /etc/hostname and the bound MAC file;
 *  - it does NOT change the public IP address. That requires a VPN or proxy,
 *    which the app cannot provide by itself.
 */
public final class Privacy {

    public static final String PREFS = "settings";
    public static final String KEY = "privacy_shield";

    public static final long ROTATE_MS = 30L * 60 * 1000;

    private static final String DIR = "privacy";

    private Privacy() {
    }

    public static boolean enabled(Context c) {
        return prefs(c).getBoolean(KEY, false);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY, on).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Fake MAC file bound over /sys/class/net/wlan0/address for the guest. */
    public static File macFile(Context c) {
        File dir = new File(c.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, "mac");
    }

    /**
     * Writes the identity the guest should see. Safe to call repeatedly:
     * with the shield on each call produces a fresh identity.
     */
    public static void apply(Context c, File root) {
        if (root == null || !root.exists()) {
            return;
        }
        boolean on = enabled(c);
        String id = on ? randomHex(32) : realId(c);
        String host = on ? "ubuntu-" + randomHex(6) : realHost();
        String mac = on ? randomMac() : realMac();

        write(new File(root, "etc/machine-id"), id + "\n");
        write(new File(root, "var/lib/dbus/machine-id"), id + "\n");
        write(new File(root, "etc/hostname"), host + "\n");
        write(macFile(c), mac + "\n");

        Logger.i("privacy " + (on ? "rotated" : "real")
                + " host=" + host + " mac=" + mac);
    }

    private static void write(File f, String text) {
        File parent = f.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            Logger.i("privacy write failed " + f + ": " + e);
        }
    }

    private static String realId(Context c) {
        String id = null;
        try {
            id = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (id == null || id.isEmpty()) {
            id = Build.FINGERPRINT + ":" + Build.SERIAL;
        }
        return md5(id);
    }

    private static String realHost() {
        String model = Build.MODEL == null ? "android" : Build.MODEL;
        model = model.toLowerCase(Locale.US).replaceAll("[^a-z0-9-]", "");
        return model.isEmpty() ? "android" : model;
    }

    private static String realMac() {
        try (FileInputStream in = new FileInputStream("/sys/class/net/wlan0/address")) {
            byte[] buf = new byte[64];
            int n = in.read(buf);
            if (n > 0) {
                String v = new String(buf, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
                if (v.matches("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")) {
                    return v.toLowerCase(Locale.US);
                }
            }
        } catch (Throwable ignored) {
        }
        return "02:00:00:00:00:01";
    }

    private static String randomHex(int n) {
        Random r = new Random();
        StringBuilder sb = new StringBuilder(n);
        String alphabet = "0123456789abcdef";
        for (int i = 0; i < n; i++) {
            sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    private static String randomMac() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder("02");
        for (int i = 0; i < 5; i++) {
            sb.append(':').append(String.format(Locale.US, "%02x", r.nextInt(256)));
        }
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return randomHex(32);
        }
    }
}
