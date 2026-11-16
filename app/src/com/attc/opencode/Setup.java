package com.attc.opencode;

import android.content.Context;
import android.content.Intent;
import android.system.Os;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Idempotent bootstrap:
 *   1. unpack proot + its two shared libraries from the APK assets
 *   2. download the Ubuntu base rootfs once (marker: .rootfs-ok)
 *   3. install the official opencode linux-arm64 binary into the rootfs
 *
 * Every step checks its own marker, so a relaunch never reinstalls anything.
 */
public final class Setup {

    public interface Progress {
        void line(String text);

        void failed(String message);

        void ready();
    }

    public static final String ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.3/release/"
                    + "ubuntu-base-24.04.3-base-arm64.tar.gz";

    /** Shared folder required by the spec; the Linux side sees it at /root/attc. */
    public static final String STORAGE_DIR = "/storage/emulated/0/ATTC OPENCODE";

    private static final String ROOTFS_MARKER = ".rootfs-ok-v2";

    private static final String NPM_LATEST = "https://registry.npmjs.org/opencode-ai/latest";
    private static final String GH_ATOM =
            "https://github.com/anomalyco/opencode/releases.atom";
    private static final String GH_RELEASES =
            "https://api.github.com/repos/anomalyco/opencode/releases?per_page=40";
    private static final String VERSION_MARKER = ".opencode-version";
    private static final String CHECK_MARKER = ".opencode-check";
    private static final String GH_RELEASE =
            "https://github.com/anomalyco/opencode/releases/download/v%s/opencode-linux-arm64.tar.gz";
    private static final String CODEX_URL =
            "https://github.com/openai/codex/releases/latest/download/"
                    + "codex-aarch64-unknown-linux-musl.tar.gz";
    private static final String CODEX_MARKER = ".codex-version";

    private final Context ctx;
    private final File home;
    private final File tools;
    private final File root;
    private final File cache;

    private volatile boolean cancelled;

    public Setup(Context context) {
        this.ctx = context.getApplicationContext();
        this.home = ctx.getFilesDir();
        this.tools = new File(ctx.getApplicationInfo().nativeLibraryDir);
        this.root = new File(home, "ubuntu");
        this.cache = new File(home, "cache");
    }

    public File rootDir() {
        return root;
    }

    /** proot is shipped as libproot.so so it can be exec'd (W^X rule). */
    public File proot() {
        return new File(tools, "libproot.so");
    }

    public File libDir() {
        return tools;
    }

    /** The real opencode binary; the PATH entry is a small wrapper script. */
    public File opencode() {
        return new File(root, "usr/local/lib/opencode/opencode");
    }

    /** The script users and the app call as "opencode". */
    public File wrapper() {
        return new File(root, "usr/local/bin/opencode");
    }

    public void cancel() {
        cancelled = true;
    }

    public void run(Progress p) {
        try {
            ensureDirs();
            p.line("Preparing terminal tools...");
            installTools();
            p.line("Terminal tools ready.");

            if (rootfsReady()) {
                p.line("Ubuntu environment found.");
                verifyEnvironment();
            } else {
                if (root.exists()) {
                    p.line("Repairing the Ubuntu environment...");
                }
                p.line("Downloading Ubuntu 24.04 base (arm64), about 30 MB...");
                File tgz = new File(cache, "ubuntu-base.tar.gz");
                download(ROOTFS_URL, tgz, "Ubuntu base", p);
                checkCancelled();
                p.line("Extracting Ubuntu (this takes a minute)...");
                TarGz.extract(tgz, root);
                //noinspection ResultOfMethodCallIgnored
                tgz.delete();
                writeRootfsConfig();
                marker(ROOTFS_MARKER).createNewFile();
                p.line("Ubuntu environment installed.");
            }

            prepareRuntime();
            ensureWrapper();

            if (opencodeReady()) {
                p.line("opencode " + installedVersion() + " found.");
            } else {
                p.line("Installing the latest opencode...");
                try {
                    installOpencode(p);
                    p.line("opencode " + installedVersion() + " installed.");
                } catch (Exception e) {
                    if (opencode().exists() && opencode().length() > 1024) {
                        Logger.i("opencode install failed, keeping existing binary: " + e);
                        p.line("Update failed, using the installed opencode.");
                    } else {
                        throw e;
                    }
                }
            }

            p.line("");
            p.line("Terminal ready.");
            p.ready();
            autoUpdateInBackground();
        } catch (Exception e) {
            p.failed(describe(e));
        }
    }

    // ---------------------------------------------------------------- steps

    private void ensureDirs() {
        //noinspection ResultOfMethodCallIgnored
        cache.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        // Best effort: needs MANAGE_EXTERNAL_STORAGE on Android 11+.
        try {
            //noinspection ResultOfMethodCallIgnored
            new File(STORAGE_DIR).mkdirs();
        } catch (Exception ignored) {
        }
    }

    /**
     * proot ships as libproot.so in nativeLibraryDir, together with the two
     * libraries it links against. Nothing is copied at runtime: the app data
     * directory is not executable on modern Android.
     */
    private void installTools() throws IOException {
        File proot = proot();
        if (!proot.exists() || proot.length() < 1024) {
            throw new IOException("proot missing from nativeLibraryDir: " + proot);
        }
        if (!proot.canExecute()) {
            throw new IOException("proot is not executable: " + proot);
        }
        File talloc = new File(tools, "libtalloc.so");
        File shmem = new File(tools, "libandroid-shmem.so");
        if (!talloc.exists() || !shmem.exists()) {
            throw new IOException("proot libraries missing from nativeLibraryDir");
        }
    }

    /** Logs what the extracted rootfs actually looks like. */
    private void verifyEnvironment() {
        String[] paths = {
                "bin/bash", "usr/bin/env", "usr/bin/bash",
                "lib/ld-linux-aarch64.so.1",
                "usr/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1",
                "usr/lib/aarch64-linux-gnu/libc.so.6"
        };
        File loader = new File(tools, "libproot-loader.so");
        Logger.i("loader " + loader + " exists=" + loader.exists()
                + " exec=" + loader.canExecute() + " len=" + loader.length());
        for (String p : paths) {
            File f = new File(root, p);
            String extra = "";
            try {
                android.system.StructStat st = Os.lstat(f.getAbsolutePath());
                extra = String.format(java.util.Locale.US, " mode=%o", st.st_mode);
            } catch (Exception e) {
                extra = " lstat=" + e;
            }
            Logger.i("rootfs " + p + " exists=" + f.exists()
                    + " exec=" + f.canExecute()
                    + " len=" + f.length()
                    + " canon=" + canonical(f) + extra);
        }
    }

    private static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (Exception e) {
            return "?";
        }
    }

    private boolean rootfsReady() {
        File marker = marker(ROOTFS_MARKER);
        return marker.exists() && new File(root, "bin/bash").exists();
    }

    /** Public probe used by the UI before asking for download consent. */
    public boolean isRootfsReady() {
        return rootfsReady();
    }

    private boolean opencodeReady() {
        File f = opencode();
        return f.exists() && f.length() > 1024 && marker(VERSION_MARKER).exists()
                && wrapper().exists();
    }

    private void writeRootfsConfig() throws IOException {
        write(new File(root, "etc/resolv.conf"), "nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        write(new File(root, "etc/hostname"), "attc\n");
        write(new File(root, "etc/hosts"), "127.0.0.1 localhost attc\n::1 localhost\n");
        //noinspection ResultOfMethodCallIgnored
        new File(root, "root").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "tmp").mkdirs();
        chmod(new File(root, "tmp"), 0777);
    }

    /**
     * Installs the opencode launcher.
     *
     * opencode 2.x starts a "managed service" on a fixed port (127.0.0.1:49374).
     * That address is shared with the rest of the phone, so a Termux (or other)
     * opencode service already listening there makes the in-app copy fail with
     * "port already in use". Running with --standalone gives the app its own
     * private server on an ephemeral port instead, without touching Termux.
     */
    private void ensureWrapper() throws IOException {
        File wrapper = wrapper();
        if (wrapper.exists() && wrapper.length() < 4096 && contains(wrapper, "ATTC_WRAPPER_V4")) {
            return;
        }
        File old = wrapper;
        File real = opencode();
        if (old.exists() && !old.equals(real)) {
            // Migrate a previously installed plain binary to the real path.
            File parent = real.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            if (real.exists()) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            } else if (!old.renameTo(real)) {
                copyFile(old, real);
                chmod(real, 0755);
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        }
        File parent = wrapper.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        write(wrapper, "#!/bin/sh\n"
                + "# Attc code launcher: run a private server so the shared\n"
                + "# managed-service port (127.0.0.1:49374) cannot collide with\n"
                + "# another app's opencode instance.\n"
                + "# ATTC_WRAPPER_V4\n"
                + "REAL=/usr/local/lib/opencode/opencode\n"
                + "ERR=\"/storage/emulated/0/ATTC OPENCODE/logs/opencode-stderr.log\"\n"
                + "mkdir -p \"/storage/emulated/0/ATTC OPENCODE/logs\" 2>/dev/null\n"
                + "case \"${1-}\" in\n"
                + "  upgrade|update)\n"
                + "    mkdir -p \"/storage/emulated/0/ATTC OPENCODE\" 2>/dev/null\n"
                + "    date > \"/storage/emulated/0/ATTC OPENCODE/update.request\" 2>/dev/null\n"
                + "    echo \"Attc code: update queued, the app will install the latest opencode. Restart the terminal afterwards.\"\n"
                + "    exit 0 ;;\n"
                + "esac\n"
                + "case \"${1-}\" in\n"
                + "  service|uninstall)\n"
                + "    exec \"$REAL\" \"$@\" 2>\"$ERR\" ;;\n"
                + "esac\n"
                + "case \" $* \" in\n"
                + "  *\" --standalone \"*) exec \"$REAL\" \"$@\" 2>\"$ERR\" ;;\n"
                + "esac\n"
                + "exec \"$REAL\" --standalone \"$@\" 2>\"$ERR\"\n");
        chmod(wrapper, 0755);
        Logger.i("opencode wrapper installed");
    }

    private void installOpencode(Progress p) throws IOException {
        installOpencodeVersion(latestOpencodeVersion(p), p);
    }

    /** Downloads and installs one specific release. */
    private void installOpencodeVersion(String version, Progress p) throws IOException {
        String url = String.format(GH_RELEASE, version);
        Logger.i("opencode installing " + version + " from " + url);
        p.line("opencode " + version + " from GitHub releases");

        File tgz = new File(cache, "opencode.tar.gz");
        download(url, tgz, "opencode", p);
        checkCancelled();

        File out = new File(cache, "oc");
        deleteTree(out);
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        TarGz.extract(tgz, out);
        //noinspection ResultOfMethodCallIgnored
        tgz.delete();

        File binary = findBinary(out);
        if (binary == null) {
            throw new IOException("opencode archive did not contain a binary");
        }
        File target = opencode();
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        copyFile(binary, target);
        chmod(target, 0755);
        Branding.apply(target);
        deleteTree(out);
        ensureWrapper();
        write(marker(VERSION_MARKER), version + "\n");
        Logger.i("opencode installed " + version + " (" + target.length() + " bytes)");
        touchCheckMarker();
    }

    /** Manual "update now" from the settings dialog. */
    public void updateNow(Progress p) throws IOException {
        installOpencodeVersion(latestOpencodeVersion(p), p);
    }

    /** Codex CLI binary inside the guest, installed on first use. */
    public File codex() {
        return new File(root, "root/.local/bin/codex");
    }

    public boolean codexReady() {
        File f = codex();
        return f.isFile() && f.length() > 1024 * 1024;
    }

    /**
     * Downloads the official Codex CLI musl build into the guest. The npm
     * package puts its launcher in the node prefix, which is not on the guest
     * PATH, so the standalone release binary is both smaller to wire up and
     * immediately runnable as {@code codex}.
     */
    public void installCodex(Progress p) throws IOException {
        ensureDirs();
        Logger.i("codex installing from " + CODEX_URL);
        p.line("Downloading Codex CLI, about 120 MB...");
        File tgz = new File(cache, "codex.tar.gz");
        download(CODEX_URL, tgz, "Codex", p);
        checkCancelled();

        File out = new File(cache, "codex");
        deleteTree(out);
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        p.line("Extracting Codex CLI...");
        TarGz.extract(tgz, out);
        //noinspection ResultOfMethodCallIgnored
        tgz.delete();

        File binary = findBinary(out);
        if (binary == null) {
            deleteTree(out);
            throw new IOException("Codex archive did not contain a binary");
        }
        File target = codex();
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        copyFile(binary, target);
        chmod(target, 0755);
        deleteTree(out);
        write(marker(CODEX_MARKER), "latest\n");
        Logger.i("codex installed " + target.length() + " bytes");
    }

    private String installedVersion() {
        File f = marker(VERSION_MARKER);
        if (!f.exists()) {
            return "0";
        }
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in).trim();
        } catch (Exception e) {
            return "0";
        }
    }

    /**
     * GitHub's "latest" tag is stale (1.19.x while 2.x releases exist), so scan
     * the release list and pick the highest semver tag. Falls back to npm.
     */
    private String latestOpencodeVersion(Progress p) throws IOException {
        try {
            String v = latestFromAtom();
            if (v != null && !v.isEmpty()) {
                return v;
            }
        } catch (Exception e) {
            Logger.i("atom release lookup failed: " + e);
        }
        try {
            String v = latestFromGitHub();
            if (v != null && !v.isEmpty()) {
                return v;
            }
        } catch (Exception e) {
            Logger.i("GitHub API lookup failed: " + e);
        }
        return latestFromNpm();
    }

    /** releases.atom is small and not rate-limited like the REST API. */
    private String latestFromAtom() throws IOException {
        HttpURLConnection c = open(GH_ATOM);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            String body = readAll(in);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<title>v(\\d+\\.\\d+\\.\\d+)</title>")
                    .matcher(body);
            String best = null;
            while (m.find()) {
                String v = m.group(1);
                if (best == null || compareVersions(v, best) > 0) {
                    best = v;
                }
            }
            if (best == null) {
                throw new IOException("no release in atom feed");
            }
            return best;
        } finally {
            c.disconnect();
        }
    }

    private String latestFromGitHub() throws IOException {
        HttpURLConnection c = open(GH_RELEASES);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            JSONArray arr = new JSONArray(readAll(in));
            String best = null;
            for (int i = 0; i < arr.length(); i++) {
                String tag = arr.getJSONObject(i).optString("tag_name", "");
                if (!tag.matches("v\\d+\\.\\d+\\.\\d+")) {
                    continue;
                }
                String v = tag.substring(1);
                if (best == null || compareVersions(v, best) > 0) {
                    best = v;
                }
            }
            if (best == null) {
                throw new IOException("no semver release found");
            }
            return best;
        } catch (org.json.JSONException e) {
            throw new IOException("could not parse GitHub releases", e);
        } finally {
            c.disconnect();
        }
    }

    private String latestFromNpm() throws IOException {
        HttpURLConnection c = open(NPM_LATEST);
        try (InputStream in = new BufferedInputStream(c.getInputStream())) {
            JSONObject json = new JSONObject(readAll(in));
            String v = json.optString("version", "");
            if (v.isEmpty()) {
                throw new IOException("npm registry returned no version");
            }
            return v;
        } catch (org.json.JSONException e) {
            throw new IOException("could not read the npm registry response", e);
        } finally {
            c.disconnect();
        }
    }

    static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? number(x[i]) : 0;
            int yi = i < y.length ? number(y[i]) : 0;
            if (xi != yi) {
                return xi < yi ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9].*$", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Refreshes opencode quietly in the background; a restart applies it. */
    private void autoUpdateInBackground() {
        if (!opencodeReady() || !updateDue()) {
            return;
        }
        new Thread(() -> {
            try {
                String latest = latestOpencodeVersion(null);
                String current = installedVersion();
                Logger.i("opencode latest=" + latest + " installed=" + current);
                if (compareVersions(latest, current) > 0) {
                    installOpencodeVersion(latest, quiet());
                    Logger.i("opencode updated " + current + " -> " + latest);
                }
                touchCheckMarker();
            } catch (Exception e) {
                Logger.i("opencode update check failed: " + e);
            }
        }, "attc-opencode-update").start();
    }

    private boolean updateDue() {
        File f = marker(CHECK_MARKER);
        return !f.exists() || System.currentTimeMillis() - f.lastModified() > 6L * 3600 * 1000;
    }

    private void touchCheckMarker() {
        try {
            File f = marker(CHECK_MARKER);
            //noinspection ResultOfMethodCallIgnored
            f.createNewFile();
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
        } catch (Exception ignored) {
        }
    }

    private Progress quiet() {
        return new Progress() {
            @Override
            public void line(String text) {
                Logger.i("update: " + text);
            }

            @Override
            public void failed(String message) {
                Logger.i("update failed: " + message);
            }

            @Override
            public void ready() {
            }
        };
    }

    // --------------------------------------------------------------- runtime

    /** DNS, hosts and a CA bundle so apt/curl/opencode TLS work inside Ubuntu. */
    /**
     * Routes ALSA to the built-in null device. opencode's TUI audio engine can
     * only see the proot rootfs, not Android's audio HAL, and without a device
     * it floods the terminal with ALSA errors on every attention sound. The
     * null PCM accepts playback silently; the Android side plays the tones
     * from OSC 7501 and BEL (see Sound.java).
     */
    private void writeAsoundConfig(File etc) {
        String conf = "pcm.!default {\n"
                + "    type null\n"
                + "}\n"
                + "ctl.!default {\n"
                + "    type null\n"
                + "}\n";
        try {
            write(new File(etc, "asound.conf"), conf);
            write(new File(root, "root/.asoundrc"), conf);
        } catch (IOException e) {
            Logger.i("asound.conf: " + e);
        }
    }

    private void prepareRuntime() throws IOException {
        // /tmp must be world-writable with the sticky bit, or package
        // installers running as root fail on files left by another user.
        File tmp = new File(root, "tmp");
        if (tmp.exists()) {
            chmod(tmp, 01777);
        }
        File etc = new File(root, "etc");
        File resolv = new File(etc, "resolv.conf");
        if (!resolv.exists() || resolv.length() == 0) {
            write(resolv, "nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 9.9.9.9\n");
        }
        File hosts = new File(etc, "hosts");
        if (!hosts.exists() || hosts.length() == 0) {
            write(hosts, "127.0.0.1 localhost attc\n::1 localhost\n");
        }
        File ca = new File(etc, "ssl/certs/ca-certificates.crt");
        if (!ca.exists() || ca.length() < 10000) {
            installCaBundle(ca);
        }
        installIpEchoCa(ca);
        writeAsoundConfig(etc);
        enableSpoofOnce();
        releaseVpnSlotOnce();
        Plugins.installLocal(ctx, root);
        Plugins.ensureConfig(root);
        Plugins.refresh(ctx, root);
        Branding.apply(opencode());
    }

    private void installCaBundle(File out) {
        File dir = new File("/system/etc/security/cacerts");
        File[] files = dir.listFiles();
        if (files == null) {
            Logger.i("CA directory not readable: " + dir);
            return;
        }
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (File f : files) {
            if (!f.getName().endsWith(".0")) {
                continue;
            }
            try (InputStream in = new java.io.FileInputStream(f)) {
                sb.append(readAll(in)).append('\n');
                n++;
            } catch (Exception ignored) {
            }
        }
        if (n == 0) {
            Logger.i("no system CA certificates found");
            return;
        }
        try {
            write(out, sb.toString());
            Logger.i("CA bundle installed: " + n + " certs, " + out.length() + " bytes");
        } catch (IOException e) {
            Logger.i("CA bundle write failed: " + e);
        }
    }

    /**
     * Older builds held the system VPN slot for a feature that does not need
     * it, which revoked the user's real VPN. Release it once after upgrading.
     */
    private void releaseVpnSlotOnce() {
        File marker = new File(ctx.getFilesDir(), ".vpn-slot-released-1");
        if (marker.exists()) {
            return;
        }
        try {
            ctx.startService(new Intent(ctx, FakeVpnService.class)
                    .setAction(FakeVpnService.ACTION_STOP));
        } catch (Exception ignored) {
        }
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (Exception ignored) {
        }
        Logger.i("vpn: released system VPN slot (real VPN can stay connected)");
    }

    /**
     * Older builds disabled the switch while the interception was hard-wired.
     * Turn the feature back on once so the toggle reflects a working state.
     */
    private void enableSpoofOnce() {
        File marker = new File(ctx.getFilesDir(), ".spoof-default-3");
        if (marker.exists()) {
            return;
        }
        VpnState.setEnabled(ctx, true);
        try {
            //noinspection ResultOfMethodCallIgnored
            marker.createNewFile();
        } catch (Exception ignored) {
        }
        Logger.i("vpn: fake ip enabled (upgrade)");
    }

    /**
     * Adds the ATTC ip-echo CA to the guest trust store so the local HTTPS
     * responder (IpEcho) can answer plain `curl`/node IP checks. The CA exists
     * only inside this app's rootfs; Android's own store is untouched.
     */
    private void installIpEchoCa(File bundle) {
        try {
            String pem;
            try (InputStream in = ctx.getAssets().open(IpEcho.CA_ASSET)) {
                pem = readAll(in);
            }
            File cert = new File(new File(root, "etc/ssl/certs"), "attc-ipecho.pem");
            String previous = "";
            if (cert.isFile()) {
                try (InputStream in = new java.io.FileInputStream(cert)) {
                    previous = readAll(in);
                }
            }
            if (!pem.equals(previous)) {
                // The bundled CA changed (new build): drop the stale trust
                // store and rebuild it, otherwise the guest keeps trusting the
                // old CA and every intercepted HTTPS request fails.
                write(cert, pem);
                //noinspection ResultOfMethodCallIgnored
                bundle.delete();
                installCaBundle(bundle);
                Logger.i("CA bundle: rebuilt for new ip-echo CA");
            }

            String current = "";
            if (bundle.isFile()) {
                try (InputStream in = new java.io.FileInputStream(bundle)) {
                    current = readAll(in);
                }
            }
            if (!current.contains("ATTC Local IP Echo CA")) {
                String separator = current.isEmpty() || current.endsWith("\n") ? "" : "\n";
                write(bundle, current + separator + pem + "\n");
                Logger.i("CA bundle: ATTC ip-echo CA added");
            }
        } catch (Exception e) {
            Logger.i("CA bundle: ip-echo CA failed " + e);
        }
    }

    private File findBinary(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                File nested = findBinary(f);
                if (nested != null) {
                    return nested;
                }
            } else if (f.length() > 1024 * 1024) {
                return f;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- downloads

    private void download(String url, File out, String label, Progress p) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        HttpURLConnection c = open(url);
        try {
            long total = c.getContentLengthLong();
            long read = 0;
            long nextReport = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) {
                    checkCancelled();
                    os.write(buf, 0, n);
                    read += n;
                    if (read >= nextReport) {
                        p.line("  " + label + " " + mb(read)
                                + (total > 0 ? " / " + mb(total) : ""));
                        nextReport = read + 4L * 1024 * 1024;
                    }
                }
            }
            if (total > 0 && read < total) {
                throw new IOException(label + " download was truncated");
            }
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(current).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(40000);
            c.setRequestProperty("User-Agent", "ATTC-OpenCode/0.1");
            int code = c.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String location = c.getHeaderField("Location");
                c.disconnect();
                if (location == null) {
                    throw new IOException("redirect without Location from " + current);
                }
                current = location;
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("HTTP " + code + " for " + current);
            }
            return c;
        }
        throw new IOException("too many redirects for " + url);
    }

    private static String mb(long bytes) {
        return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    // ----------------------------------------------------------------- utils

    private void checkCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("cancelled");
        }
    }

    private File marker(String name) {
        return new File(home, name);
    }

    /** True when the file holds the given text within its first 8 KB. */
    private static boolean contains(File f, String needle) {
        if (!f.isFile()) {
            return false;
        }
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(f))) {
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            return n > 0 && new String(buf, 0, n,
                    java.nio.charset.StandardCharsets.UTF_8).contains(needle);
        } catch (Exception e) {
            return false;
        }
    }

    private static void write(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        try (InputStream in = new BufferedInputStream(new java.io.FileInputStream(from));
             OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
        }
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getAbsolutePath(), mode);
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            f.setExecutable((mode & 0111) != 0, false);
        }
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

    private static String readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) {
            m = e.getClass().getSimpleName();
        }
        return m;
    }
}
