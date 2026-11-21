package com.attc.opencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * Checks the GitHub release feed, downloads a signed APK, verifies it and
 * hands it to PackageInstaller. Android always shows its own confirmation
 * screen on API 26+; the app never claims a silent install.
 */
public final class Updater {

    public static final String PREF_AUTO = "auto_update";
    public static final String PREF_LAST = "update_last_check";
    public static final String PREF_AVAILABLE = "update_available";
    public static final String PREF_URL = "update_url";
    public static final String PREF_DIGEST = "update_digest";
    public static final String PREF_VERSION = "update_version";
    public static final String ACTION_INSTALL_RESULT = "com.attc.opencode.UPDATE_INSTALL_RESULT";

    private static final String API = "https://api.github.com/repos/Attc-Config-Technician/Attc-Code/releases/latest";
    private static final String CHANNEL = "attc_updates";
    private static final int NOTIFICATION_ID = 22;
    private static final String EXPECTED_CERT =
            "791435ce1087c7841019c82429d1f333dc9c95bb2aa0f24e55594b1d43f72a85";

    public interface Listener {
        void onResult(String message, boolean updateAvailable);
    }

    private Updater() {
    }

    /** Checks at most once an hour; safe to call from every app start. */
    public static void checkQuiet(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
        long last = prefs.getLong(PREF_LAST, 0L);
        if (System.currentTimeMillis() - last < 3600000L) {
            return;
        }
        check(context, null);
    }

    public static void check(final Context context, final Listener listener) {
        final Context app = context.getApplicationContext();
        Thread thread = new Thread(() -> {
            String message;
            boolean available = false;
            try {
                JSONObject release = new JSONObject(get(API));
                String tag = release.optString("tag_name", "");
                String version = tag.startsWith("v") ? tag.substring(1) : tag;
                String current = currentVersion(app);
                SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
                prefs.edit().putLong(PREF_LAST, System.currentTimeMillis()).apply();
                if (version.isEmpty()) {
                    message = "GitHub sürüm bilgisi bulunamadı.";
                } else if (compare(version, current) <= 0) {
                    prefs.edit().remove(PREF_AVAILABLE).remove(PREF_URL)
                            .remove(PREF_DIGEST).remove(PREF_VERSION).apply();
                    message = "Attc Code güncel: " + current;
                    Logger.i("update: up to date current=" + current + " latest=" + version);
                } else {
                    JSONArray assets = release.optJSONArray("assets");
                    JSONObject apk = null;
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.optJSONObject(i);
                            if (asset == null) {
                                continue;
                            }
                            String name = asset.optString("name", "");
                            if (name.endsWith(".apk") && name.toUpperCase().contains("ATTC")) {
                                apk = asset;
                                break;
                            }
                        }
                    }
                    if (apk == null) {
                        message = "Yeni sürüm var ama APK bulunamadı: " + version;
                    } else {
                        available = true;
                        prefs.edit().putString(PREF_AVAILABLE, version)
                                .putString(PREF_URL, apk.optString("browser_download_url", ""))
                                .putString(PREF_DIGEST, apk.optString("digest", ""))
                                .putString(PREF_VERSION, version).apply();
                        message = "Yeni sürüm hazır: " + version;
                        Logger.i("update: available=" + version + " current=" + current);
                        notifyUpdate(app, version, true);
                        if (prefs.getBoolean(PREF_AUTO, true) && !hasActiveSession()) {
                            downloadAndInstall(app);
                        }
                    }
                }
            } catch (Throwable t) {
                message = "Güncelleme kontrolü başarısız: " + t;
                Logger.i("update: check failed " + t);
            }
            if (listener != null) {
                listener.onResult(message, available);
            }
        }, "update-check");
        thread.setDaemon(true);
        thread.start();
    }

    /** Downloads and verifies the stored update; installs through the system UI. */
    public static void downloadAndInstall(final Context context) {
        final Context app = context.getApplicationContext();
        final SharedPreferences prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE);
        final String url = prefs.getString(PREF_URL, "");
        if (url == null || url.isEmpty()) {
            notifyUpdate(app, prefs.getString(PREF_AVAILABLE, "bilinmeyen"), false);
            return;
        }
        notifyText(app, "Güncelleme indiriliyor", "APK indiriliyor ve doğrulanıyor...");
        Thread thread = new Thread(() -> {
            File apk = new File(app.getCacheDir(), "attc-update.apk");
            try {
                download(url, apk);
                String digest = prefs.getString(PREF_DIGEST, "");
                if (digest != null && digest.startsWith("sha256:")) {
                    String actual = sha256(apk);
                    if (!digest.substring(7).equalsIgnoreCase(actual)) {
                        throw new SecurityException("APK özeti uyuşmuyor");
                    }
                }
                verifyApk(app, apk);
                install(app, apk);
            } catch (Throwable t) {
                Logger.i("update: download/verify failed " + t);
                notifyText(app, "Güncelleme başarısız", String.valueOf(t.getMessage()));
            }
        }, "update-download");
        thread.setDaemon(true);
        thread.start();
    }

    public static void openInstallPermission(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            Logger.i("update: permission page failed " + t);
        }
    }

    public static void notifyInstallResult(Context context, int status, String message) {
        String text = status == PackageInstaller.STATUS_SUCCESS
                ? "Attc Code güncellendi. Uygulamayı açabilirsiniz."
                : "Kurulum tamamlanamadı: " + message;
        notifyText(context, "Attc Code güncelleme", text);
    }

    private static void verifyApk(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo info;
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info == null || info.signingInfo == null) {
                throw new SecurityException("APK imzası okunamadı");
            }
            SigningInfo signing = info.signingInfo;
            signatures = signing.getApkContentsSigners();
        } else {
            info = pm.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.GET_SIGNATURES);
            signatures = info == null ? null : info.signatures;
        }
        if (info == null || !context.getPackageName().equals(info.packageName)) {
            throw new SecurityException("APK paketi uyuşmuyor");
        }
        if (signatures == null || signatures.length == 0) {
            throw new SecurityException("APK imzası yok");
        }
        if (!EXPECTED_CERT.equals(sha256(signatures[0].toByteArray()))) {
            throw new SecurityException("APK imzası Attc Code ile uyuşmuyor");
        }
        PackageInfo current = pm.getPackageInfo(context.getPackageName(), 0);
        if (info.versionCode <= current.versionCode) {
            throw new SecurityException("APK sürümü daha yeni değil");
        }
        Logger.i("update: verified package=" + info.packageName
                + " versionCode=" + info.versionCode);
    }

    private static void install(Context context, File apk) throws Exception {
        PackageManager pm = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls()) {
            notifyText(context, "Kurulum izni gerekli",
                    "Ayarlardan bilinmeyen kaynak iznini verin.");
            openInstallPermission(context);
            return;
        }
        PackageInstaller installer = pm.getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId = installer.createSession(params);
        PackageInstaller.Session session = installer.openSession(sessionId);
        try (InputStream in = new FileInputStream(apk);
             OutputStream out = session.openWrite("base.apk", 0, -1)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            session.fsync(out);
        } finally {
            session.close();
        }
        Intent result = new Intent(context, InstallReceiver.class)
                .setAction(ACTION_INSTALL_RESULT)
                .setPackage(context.getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= 0x02000000;
        }
        PendingIntent pending = PendingIntent.getBroadcast(context, 42, result, flags);
        session.commit(pending.getIntentSender());
        Logger.i("update: install committed");
        notifyText(context, "Attc Code güncelleme", "Android kurulum onayı açılıyor...");
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/octet-stream");
        c.setRequestProperty("User-Agent", "Attc-Code-Updater");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new SecurityException("APK indirilemedi HTTP " + code);
        }
        try (InputStream in = c.getInputStream();
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > 200L * 1024L * 1024L) {
                    throw new SecurityException("APK çok büyük");
                }
                out.write(buffer, 0, read);
            }
        }
        Logger.i("update: downloaded bytes=" + target.length());
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "Attc-Code-Updater");
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        if (in == null) {
            throw new SecurityException("GitHub HTTP " + code);
        }
        try (InputStream stream = in) {
            byte[] buffer = new byte[8192];
            StringBuilder text = new StringBuilder();
            int read;
            while ((read = stream.read(buffer)) > 0) {
                text.append(new String(buffer, 0, read, "UTF-8"));
            }
            return text.toString();
        }
    }

    private static boolean hasActiveSession() {
        for (Session session : Hub.sessions) {
            if (session.isRunning()) {
                return true;
            }
        }
        return false;
    }

    private static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "0";
        }
    }

    private static int compare(String a, String b) {
        String[] left = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] right = b.replaceAll("[^0-9.]", "").split("\\.");
        int count = Math.max(left.length, right.length);
        for (int i = 0; i < count; i++) {
            int x = i < left.length ? number(left[i]) : 0;
            int y = i < right.length ? number(right[i]) : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                md.update(buffer, 0, read);
            }
        }
        return hex(md.digest());
    }

    private static String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static String hex(byte[] data) {
        StringBuilder text = new StringBuilder(data.length * 2);
        for (byte b : data) {
            text.append(String.format("%02x", b & 0xff));
        }
        return text.toString();
    }

    private static void notifyUpdate(Context context, String version, boolean actionable) {
        createChannel(context);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("installupdate", actionable);
        PendingIntent content = PendingIntent.getActivity(context, 31, open, pendingFlags());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setContentTitle("Attc Code " + version + " hazır")
                .setContentText(hasActiveSession()
                        ? "Aktif oturum var; kurulum için uygulamayı açın."
                        : "İndirmek ve kurmak için dokunun.")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true)
                .setContentIntent(content);
        if (actionable) {
            b.addAction(new Notification.Action.Builder(android.R.drawable.stat_sys_download,
                    "İndir ve kur", content).build());
        }
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, b.build());
        }
    }

    private static void notifyText(Context context, String title, String text) {
        createChannel(context);
        Intent open = new Intent(context, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 32, open, pendingFlags());
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL)
                : new Notification.Builder(context);
        b.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setAutoCancel(true).setContentIntent(content);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, b.build());
        }
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Attc Code güncellemeleri", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("GitHub sürüm kontrolü ve kurulum bildirimleri");
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }
}
