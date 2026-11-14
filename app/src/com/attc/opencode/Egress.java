package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Chooses which radio carries the responder's outgoing connections.
 *
 * The device can have more than one usable exit at the same time: wifi, mobile
 * data and a VPN app each leave through a different public address. The
 * assistant service meters its free quota per address, so changing the exit is
 * what makes a fresh quota appear.
 *
 * Sockets are bound with {@link Network#bindSocket}. That is the only supported
 * way to pick an exit from inside an app on modern Android: routing is decided
 * per uid and per fwmark, so binding a source address or an interface name is
 * not enough and lands in the VPN table instead.
 */
public final class Egress {

    public static final String MODE_AUTO = "auto";
    public static final String MODE_WIFI = "wifi";
    public static final String MODE_MOBILE = "mobile";
    public static final String MODE_VPN = "vpn";

    private static final String PREFS = "attc_egress";
    private static final String KEY_MODE = "mode";
    private static final String KEY_INDEX = "index";

    private static final int TIMEOUT_MS = 15000;
    private static final String ECHO_HOST = "api.ipify.org";

    private static final Object LOCK = new Object();
    private static ConnectivityManager.NetworkCallback cellularRequest;
    private static long lastCellularAsk;
    private static List<Candidate> cached = new ArrayList<>();
    private static long cachedAt;
    private static String lastPick = "not measured";
    private static String lastError = "";

    /** One usable exit. */
    public static final class Candidate {
        public final Network network;
        public final String kind;
        public final String iface;
        public final String addr;
        public String exitIp = "?";

        Candidate(Network network, String kind, String iface, String addr) {
            this.network = network;
            this.kind = kind;
            this.iface = iface;
            this.addr = addr;
        }

        public String describe() {
            return kind + " (" + iface + " " + addr + ") exit " + exitIp;
        }
    }

    private Egress() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String mode(Context c) {
        String mode = prefs(c).getString(KEY_MODE, MODE_AUTO);
        return mode == null ? MODE_AUTO : mode;
    }

    public static void setMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_MODE, mode).putInt(KEY_INDEX, 0).apply();
        Logger.i("egress: mode=" + mode);
        IpEcho.closeTunnels();
        refresh(c);
    }

    public static String lastPick() {
        return lastPick;
    }

    public static String lastError() {
        return lastError;
    }

    /** True when only the VPN exit works and the radios are blocked. */
    public static boolean rotationBlocked(Context c) {
        synchronized (LOCK) {
            ensure(c);
            boolean vpn = false;
            boolean other = false;
            for (Candidate cand : cached) {
                if (MODE_VPN.equals(cand.kind)) {
                    vpn = true;
                } else if (reachable(cand.exitIp)) {
                    other = true;
                }
            }
            return vpn && !other;
        }
    }

    public static String describe(Context c) {
        String mode = mode(c);
        if (cached.isEmpty()) {
            return mode + ", exits not measured yet";
        }
        StringBuilder sb = new StringBuilder(mode).append(": ");
        for (int i = 0; i < cached.size(); i++) {
            Candidate cand = cached.get(i);
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cand.kind).append(' ').append(cand.exitIp);
        }
        return sb.toString();
    }

    /** Rebuilds the candidate list and measures every exit in the background. */
    public static void refresh(Context c) {
        Thread t = new Thread(() -> {
            try {
                synchronized (LOCK) {
                    cached = build(c, true);
                    cachedAt = System.currentTimeMillis();
                    if (!hasMobile(cached)) {
                        requestCellular(c);
                    }
                    lastPick = pickLocked(c).describe();
                }
            } catch (Exception e) {
                lastError = e.toString();
                Logger.i("egress: refresh failed " + e);
            }
        }, "attc-egress-refresh");
        t.setDaemon(true);
        t.start();
    }

    private static List<Candidate> ensure(Context c) {
        synchronized (LOCK) {
            if (cached.isEmpty()) {
                try {
                    cached = build(c, true);
                    cachedAt = System.currentTimeMillis();
                } catch (Exception e) {
                    lastError = e.toString();
                }
            }
            return cached;
        }
    }

    /**
     * Measures the exits again and moves to the next one, so the next
     * connection leaves from a different public address. Returns a short
     * description for the settings screen.
     */
    public static String rotate(Context c) {
        synchronized (LOCK) {
            try {
                String before = lastPick;
                cached = build(c, true);
                if (!hasMobile(cached)) {
                    // The user's manual "switch to mobile data" is exactly what
                    // is needed here, so ask Shizuku to flip the same setting
                    // and wait for the radio to attach.
                    enableMobileData(c);
                    cellularRequest = null;
                    lastCellularAsk = 0;
                    requestCellular(c);
                    waitForMobile(c, 12000);
                    cached = build(c, true);
                }
                cachedAt = System.currentTimeMillis();
                if (cached.size() > 1) {
                    int index = prefs(c).getInt(KEY_INDEX, 0);
                    String current = cached.get(Math.abs(index) % cached.size()).exitIp;
                    for (int step = 1; step <= cached.size(); step++) {
                        int next = (Math.abs(index) + step) % cached.size();
                        if (!cached.get(next).exitIp.equals(current)) {
                            prefs(c).edit().putInt(KEY_INDEX, next).apply();
                            index = next;
                            break;
                        }
                    }
                }
                Candidate picked = pickLocked(c);
                lastPick = picked == null ? "default route" : picked.describe();
                if (!lastPick.equals(before)) {
                    IpEcho.closeTunnels();
                    Logger.i("egress: rotated -> " + lastPick);
                } else {
                    Logger.i("egress: no different exit available -> " + lastPick
                            + " (a VPN that forbids bypass blocks the radios)");
                }
                return lastPick;
            } catch (Exception e) {
                lastError = e.toString();
                Logger.i("egress: rotate failed " + e);
                return "rotate failed: " + e;
            }
        }
    }

    /**
     * Rotates the public exit AND the guest fingerprint without touching the
     * live PTY. New sockets take the new route; the running CLI session and its
     * already-open sockets are left alone, so no reinstall/reconnect is needed.
     */
    public static String rotateWithFingerprint(Context c, File root) {
        String exit = rotate(c);
        VpnState.rotate(c);
        Privacy.setEnabled(c, true);
        Privacy.apply(c, root);
        IpEcho.closeTunnels();
        return exit;
    }

    private static void enableMobileData(Context c) {
        try {
            if (!ShizukuHelper.granted()) {
                Logger.i("egress: mobile data is off and Shizuku is not granted");
                return;
            }
            String result = ShizukuHelper.run("svc data enable").trim();
            Logger.i("egress: " + result);
        } catch (Throwable t) {
            Logger.i("egress: mobile data enable failed " + t);
        }
    }

    private static void waitForMobile(Context c, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (hasMobile(build(c, false))) {
                return;
            }
            sleep(1500);
        }
    }

    /** The exit a new connection should use right now. */
    public static Candidate pick(Context c) {
        synchronized (LOCK) {
            ensure(c);
            return pickLocked(c);
        }
    }

    private static Candidate pickLocked(Context c) {
        if (cached.isEmpty()) {
            lastPick = "default route";
            return null;
        }
        List<Candidate> usable = new ArrayList<>();
        for (Candidate cand : cached) {
            if (reachable(cand.exitIp)) {
                usable.add(cand);
            }
        }
        if (usable.isEmpty()) {
            // Nothing answered: let the system's default route carry it and
            // surface the real error instead of hiding it behind a bad bind.
            lastPick = "default route";
            return null;
        }
        String mode = mode(c);
        if (!MODE_AUTO.equals(mode)) {
            for (Candidate cand : usable) {
                if (cand.kind.equals(mode)) {
                    lastPick = cand.describe();
                    return cand;
                }
            }
            Logger.i("egress: " + mode + " not available, falling back to auto");
        }
        int index = prefs(c).getInt(KEY_INDEX, 0);
        Candidate cand = usable.get(Math.abs(index) % usable.size());
        lastPick = cand.describe();
        return cand;
    }

    private static boolean reachable(String exitIp) {
        return exitIp != null && !"unreachable".equals(exitIp)
                && !"unreadable".equals(exitIp) && !"?".equals(exitIp);
    }

    /**
     * Opens a socket to host:port on the selected exit. Returns null when no
     * exit could be reached; the caller decides how to report it.
     */
    public static Socket connect(Context c, String host, int port) {
        if (isLoopback(host)) {
            // Local services never belong to a radio; binding them fails.
            return plainConnect(host, port);
        }
        Candidate cand = pick(c);
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = new Socket();
            try {
                if (cand != null) {
                    try {
                        cand.network.bindSocket(socket);
                    } catch (Exception bindFailure) {
                        // A VPN that forbids bypass owns every uid, so the
                        // framework denies binding to the radio underneath it.
                        // Falling back to the default route is correct: the
                        // traffic then leaves through that VPN.
                        Logger.i("egress: " + cand.kind + " is not bindable ("
                                + bindFailure.getMessage() + "), using default route");
                        cand = null;
                    }
                }
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                socket.setSoTimeout(0);
                return socket;
            } catch (Exception e) {
                lastError = e.toString();
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
                if (attempt == 0) {
                    // A radio switch can invalidate the network object between
                    // the pick and the connect; pick again and retry once.
                    sleep(300);
                    cand = pick(c);
                }
            }
        }
        Logger.i("egress: connect failed " + host + ":" + port + " " + lastError);
        return null;
    }

    private static Socket plainConnect(String host, int port) {
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
                socket.setSoTimeout(0);
                return socket;
            } catch (Exception e) {
                lastError = e.toString();
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
                if (attempt == 0) {
                    sleep(250);
                }
            }
        }
        Logger.i("egress: connect failed " + host + ":" + port + " " + lastError);
        return null;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host)
                || "::1".equals(host) || "0.0.0.0".equals(host);
    }

    /**
     * Asks the platform for a cellular data network even when wifi is the
     * default. That is what makes a second exit available without touching the
     * system settings, and it is exactly the wifi/mobile switch the user does
     * by hand today.
     */
    private static void requestCellular(Context c) {
        if (cellularRequest != null || System.currentTimeMillis() - lastCellularAsk < 30_000L) {
            return;
        }
        lastCellularAsk = System.currentTimeMillis();
        final Context app = c.getApplicationContext();
        ConnectivityManager cm = (ConnectivityManager)
                app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return;
        }
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Logger.i("egress: mobile data available as a second exit");
                refresh(app);
            }

            @Override
            public void onLost(Network network) {
                Logger.i("egress: mobile data went away");
            }

            @Override
            public void onUnavailable() {
                cellularRequest = null;
                Logger.i("egress: mobile data unavailable (data off or no data plan)");
            }
        };
        try {
            cm.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(), callback, 20000);
            cellularRequest = callback;
            Logger.i("egress: requesting mobile data as a second exit");
        } catch (Exception e) {
            Logger.i("egress: mobile request failed " + e);
        }
    }

    private static boolean hasMobile(List<Candidate> list) {
        for (Candidate cand : list) {
            if (MODE_MOBILE.equals(cand.kind)) {
                return true;
            }
        }
        return false;
    }

    private static List<Candidate> build(Context c, boolean measure) {
        List<Candidate> out = new ArrayList<>();
        ConnectivityManager cm = (ConnectivityManager)
                c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return out;
        }
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                continue;
            }
            String kind = kindOf(caps);
            if (kind == null) {
                continue;
            }
            LinkProperties lp = cm.getLinkProperties(network);
            String iface = lp == null ? "?" : lp.getInterfaceName();
            String addr = firstV4(lp);
            out.add(new Candidate(network, kind, iface, addr));
        }
        Collections.sort(out, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                return Integer.compare(rank(a.kind), rank(b.kind));
            }
        });
        if (measure) {
            for (Candidate cand : out) {
                cand.exitIp = measure(cand.network);
                Logger.i("egress: " + cand.describe());
            }
        }
        return out;
    }

    private static int rank(String kind) {
        if (MODE_WIFI.equals(kind)) {
            return 0;
        }
        if (MODE_MOBILE.equals(kind)) {
            return 1;
        }
        if (MODE_VPN.equals(kind)) {
            return 2;
        }
        return 3;
    }

    private static String kindOf(NetworkCapabilities caps) {
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return MODE_VPN;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return MODE_WIFI;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return MODE_MOBILE;
        }
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return "ethernet";
        }
        return null;
    }

    private static String firstV4(LinkProperties lp) {
        if (lp == null) {
            return "?";
        }
        for (LinkAddress la : lp.getLinkAddresses()) {
            String text = la.getAddress().getHostAddress();
            if (text != null && text.indexOf(':') < 0) {
                return text;
            }
        }
        return "?";
    }

    /** Asks an echo service which address this exit uses. */
    static String measure(Network network) {
        Socket socket = new Socket();
        try {
            network.bindSocket(socket);
            socket.connect(new InetSocketAddress(ECHO_HOST, 80), TIMEOUT_MS);
            socket.setSoTimeout(TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + ECHO_HOST
                    + "\r\nUser-Agent: attc-egress\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0 && body.size() < 8192) {
                body.write(buf, 0, n);
            }
            String text = body.toString("UTF-8");
            int idx = text.indexOf("\r\n\r\n");
            if (idx >= 0) {
                text = text.substring(idx + 4);
            }
            return ipv4(text);
        } catch (Exception e) {
            return "unreachable";
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String ipv4(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b").matcher(text);
        String found = "unreadable";
        while (m.find()) {
            found = m.group(1);
        }
        return found;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public static String label(String kind) {
        if (MODE_WIFI.equals(kind)) {
            return "WiFi";
        }
        if (MODE_MOBILE.equals(kind)) {
            return "Mobile data";
        }
        if (MODE_VPN.equals(kind)) {
            return "VPN";
        }
        return kind;
    }
}
