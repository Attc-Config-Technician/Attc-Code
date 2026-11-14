package com.attc.opencode;

import android.app.Application;
import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;

import java.net.Proxy;
import java.net.URL;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.TrustManagerFactory;

/**
 * In-guest IP-echo interceptor.
 *
 * The Android VPN never carries the app's own traffic (VpnService always
 * excludes the app that created it, and the app explicitly disallows its own
 * package), so the Ubuntu guest used by opencode never saw the simulated IP.
 * This class closes that gap without a tunnel:
 *
 * <ul>
 *   <li>a loopback HTTP proxy on {@link #PROXY_PORT}; the PTY exports
 *       {@code HTTP_PROXY}/{@code HTTPS_PROXY} pointing at it,</li>
 *   <li>a loopback HTTP responder on {@link #HTTP_PORT},</li>
 *   <li>a loopback HTTPS responder on {@link #HTTPS_PORT} that terminates TLS
 *       with a certificate signed by the bundled ATTC CA,</li>
 * </ul>
 *
 * Requests to the IP-echo allowlist (and only those) are answered locally with
 * the rotating simulated address, while every other destination is tunnelled
 * through unchanged so the proxy never breaks ordinary traffic.
 *
 * The CA is added only to the guest Ubuntu trust store by {@code Setup}, so it
 * cannot affect Android itself.
 */
public final class IpEcho {

    /** Loopback port the guest points HTTP_PROXY/HTTPS_PROXY at. */
    public static final int PROXY_PORT = 18080;

    /** Loopback origin server for plain HTTP. */
    public static final int HTTP_PORT = 18081;

    /** Loopback TLS origin server for HTTPS. */
    public static final int HTTPS_PORT = 18443;

    /** CA certificate copied into the guest trust store by {@code Setup}. */
    static final String CA_ASSET = "tls/attc-ipecho-ca.pem";

    private static final String P12_ASSET = "tls/attc-ipecho.p12";
    private static final char[] P12_PASS = "attcattc".toCharArray();
    private static final String LOOPBACK = "127.0.0.1";
    private static final int MAX_HEAD = 32 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 15000;

    /**
     * Update hosts reached on the default route: the guest updater is tunnelled
     * through the egress proxy, which may be unavailable while a VPN holds the
     * default route.
     */
    private static final java.util.Set<String> DIRECT_HOSTS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "github.com",
                    "api.github.com",
                    "codeload.github.com",
                    "objects.githubusercontent.com",
                    "raw.githubusercontent.com",
                    "registry.npmjs.org",
                    "opencode.ai",
                    "api.opencode.ai"));

    private static volatile boolean started;
    private static Context app;

    private static final ExecutorService WORKERS =
            Executors.newCachedThreadPool(new ThreadFactory() {
                private int n;

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "attc-ipecho-" + (++n));
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * The device may switch between wifi, mobile data and a VPN app at any
     * moment. Clients inside Ubuntu must not notice: the proxy stays on the
     * same loopback port, names are re-resolved for every new connection and a
     * failed attempt is retried once so an in-flight switch does not surface.
     */
    static {
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0");
        } catch (Exception ignored) {
        }
    }

    private static volatile String lastNetwork = "unknown";
    private static volatile long lastNetworkAt;

    private IpEcho() {
    }

    private static void watchNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return;
            }
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    noteNetwork(describe(cm, network));
                }

                @Override
                public void onLost(Network network) {
                    Logger.i("net: network lost (proxy keeps 127.0.0.1:" + PROXY_PORT + ")");
                    Egress.refresh(app);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                    noteNetwork(describe(cm, network));
                }
            });
            Logger.i("net: watching default network");
        } catch (Exception e) {
            Logger.i("net: watch failed " + e);
        }
    }

    private static String describe(ConnectivityManager cm, Network network) {
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return "vpn";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return "wifi";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return "mobile";
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return "ethernet";
                }
            }
        } catch (Exception ignored) {
        }
        return "other";
    }

    private static void noteNetwork(String kind) {
        if (kind.equals(lastNetwork)) {
            return;
        }
        lastNetwork = kind;
        lastNetworkAt = System.currentTimeMillis();
        // Nothing to rebind: the listener is on loopback. Only cached names
        // have to go, otherwise a switch keeps pointing at the old network.
        try {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
        } catch (Exception ignored) {
        }
        Logger.i("net: default network now " + kind
                + " (listener stays 127.0.0.1:" + PROXY_PORT + ", guest keeps working)");
        Egress.refresh(app);
    }

    public static synchronized void start(Context ctx) {
        if (started) {
            return;
        }
        started = true;
        app = ctx.getApplicationContext();
        Logger.init(app);
        serve("proxy", () -> {
            ServerSocket server = bind(PROXY_PORT);
            for (; ; ) {
                Socket s = server.accept();
                WORKERS.execute(() -> handleProxy(s));
            }
        });
        serve("http", () -> {
            ServerSocket server = bind(HTTP_PORT);
            for (; ; ) {
                Socket s = server.accept();
                WORKERS.execute(() -> handleOrigin(s));
            }
        });
        serve("https", () -> {
            SSLServerSocket server = bindTls(HTTPS_PORT);
            if (server == null) {
                return;
            }
            for (; ; ) {
                Socket s = server.accept();
                WORKERS.execute(() -> handleOrigin(s));
            }
        });
        Thread net = new Thread(IpEcho::watchNetwork, "attc-net-watch");
        net.setDaemon(true);
        net.start();
        Egress.refresh(app);
        Thread exit = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
            }
            Upstream.exitIp(app);
        }, "attc-ipecho-exit");
        exit.setDaemon(true);
        exit.start();
        Thread test = new Thread(IpEcho::selfTest, "attc-ipecho-selftest");
        test.setDaemon(true);
        test.start();
    }

    // ------------------------------------------------------------- self test

    private static volatile String lastCheck = "not run";

    /** Result of the last end-to-end check, for the settings screen. */
    public static String lastCheck() {
        return lastCheck;
    }

    private static volatile String guestCheck = "not run";

    /** Result of the probe that runs inside the Ubuntu rootfs. */
    public static String guestCheck() {
        return guestCheck;
    }

    public static void setGuestResult(String value) {
        guestCheck = value;
    }

    /**
     * Runs one request through the loopback proxy exactly the way the guest
     * does (HTTPS_PROXY + our CA) and compares the answer with the simulated
     * address. This is the proof that opencode's own IP lookups are answered
     * locally instead of reaching the real service.
     */
    public static void selfTest() {
        if (!VpnState.isEnabled(app)) {
            lastCheck = "off (real ip visible)";
            Logger.i("ipecho: selftest " + lastCheck);
            return;
        }
        try {
            Thread.sleep(4000);
            SSLContext context = clientContext();
            if (context == null) {
                lastCheck = "CA load failed";
                return;
            }
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(InetAddress.getByName(LOOPBACK), PROXY_PORT));
            HttpsURLConnection c = (HttpsURLConnection)
                    new URL("https://api.ipify.org").openConnection(proxy);
            c.setSSLSocketFactory(context.getSocketFactory());
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                copy(in, body);
            }
            String seen = body.toString("UTF-8").trim();
            String expected = VpnState.getSimulatedIp(app);
            if (seen.equals(expected)) {
                lastCheck = "ok " + seen;
            } else if (VpnState.isEnabled(app)) {
                lastCheck = "mismatch " + seen;
            } else {
                lastCheck = "off (Fake VPN disabled) " + seen;
            }
            Logger.i("ipecho: selftest " + lastCheck + " expected=" + expected);
        } catch (Exception e) {
            lastCheck = "error " + e;
            Logger.i("ipecho: selftest error " + e);
        }
    }

    /** Trusts only the bundled ATTC CA; used by the self test client. */
    private static SSLContext clientContext() {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            X509Certificate ca;
            try (InputStream in = app.getAssets().open(CA_ASSET)) {
                ca = (X509Certificate) cf.generateCertificate(in);
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            ks.setCertificateEntry("attc-ipecho", ca);
            TrustManagerFactory tmf =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (Exception e) {
            Logger.i("ipecho: client ca failed " + e);
            return null;
        }
    }

    private interface Server {
        void run() throws Exception;
    }

    private static void serve(String name, Server server) {
        Thread t = new Thread(() -> {
            try {
                server.run();
            } catch (Exception e) {
                Logger.i("ipecho: " + name + " stopped " + e);
            }
        }, "attc-ipecho-" + name + "-accept");
        t.setDaemon(true);
        t.start();
    }

    private static ServerSocket bind(int port) throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port));
        Logger.i("ipecho: listening " + LOOPBACK + ":" + port);
        return server;
    }

    private static SSLServerSocket bindTls(int port) throws IOException {
        SSLContext context = tlsContext();
        if (context == null) {
            Logger.i("ipecho: tls context unavailable");
            return null;
        }
        SSLServerSocket server = (SSLServerSocket)
                context.getServerSocketFactory().createServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName(LOOPBACK), port));
        Logger.i("ipecho: listening " + LOOPBACK + ":" + port + " (tls)");
        return server;
    }

    private static SSLContext tlsContext() {
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = app.getAssets().open(P12_ASSET)) {
                ks.load(in, P12_PASS);
            }
            KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, P12_PASS);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        } catch (Exception e) {
            Logger.i("ipecho: tls load failed " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- proxy

    private static void handleProxy(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            byte[] head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int lineEnd = text.indexOf("\r\n");
            String requestLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                return;
            }
            String method = parts[0].toUpperCase(Locale.US);
            String target = parts[1];

            if ("CONNECT".equals(method)) {
                String[] hostPort = splitHostPort(target, 443);
                if (hostPort == null) {
                    writeStatus(out, 400, "Bad Request");
                    return;
                }
                boolean spoof = spoof(hostPort[0]);
                if (spoof) {
                    logOnce("spoof:" + hostPort[0], "ipecho: spoof https host=" + hostPort[0]);
                } else {
                    logOnce("tunnel:" + hostPort[0], "ipecho: tunnel host=" + hostPort[0]);
                }
                Socket upstream = connect(spoof ? LOOPBACK : hostPort[0],
                        spoof ? HTTPS_PORT : Integer.parseInt(hostPort[1]));
                if (upstream == null) {
                    writeStatus(out, 502, "Bad Gateway");
                    return;
                }
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(
                        StandardCharsets.ISO_8859_1));
                out.flush();
                client.setSoTimeout(0);
                pump(client, in, out, upstream);
                return;
            }

            if (!target.startsWith("http://")) {
                writeStatus(out, 400, "Bad Request");
                return;
            }
            String rest = target.substring("http://".length());
            int slash = rest.indexOf('/');
            String authority = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            String[] hostPort = splitHostPort(authority, 80);
            if (hostPort == null) {
                writeStatus(out, 400, "Bad Request");
                return;
            }
            boolean spoof = spoof(hostPort[0]);
            if (spoof) {
                logOnce("spoof:" + hostPort[0], "ipecho: spoof http host=" + hostPort[0]);
            }
            Socket upstream = connect(spoof ? LOOPBACK : hostPort[0],
                    spoof ? HTTP_PORT : Integer.parseInt(hostPort[1]));
            if (upstream == null) {
                writeStatus(out, 502, "Bad Gateway");
                return;
            }
            OutputStream up = upstream.getOutputStream();
            String restHeaders = lineEnd < 0 ? "" : text.substring(lineEnd + 2);
            up.write((requestLine.replace(target, path) + "\r\n" + restHeaders)
                    .getBytes(StandardCharsets.ISO_8859_1));
            up.flush();
            client.setSoTimeout(0);
            pump(client, in, out, upstream);
        } catch (Exception ignored) {
            // A broken proxy connection must never disturb the app.
        }
    }

    /**
     * Copies both directions until either side closes. Any bytes the client
     * sent after the request head are still buffered in {@code in} and are
     * forwarded here, so request bodies are not lost.
     */
    private static void pump(Socket client, InputStream clientIn, OutputStream clientOut,
                             Socket upstream) {
        Thread back = new Thread(() -> {
            try (InputStream in = upstream.getInputStream()) {
                copy(in, clientOut);
            } catch (Exception ignored) {
            } finally {
                // Upstream is done: end the client connection too, otherwise a
                // client that waits for EOF (bash /dev/tcp, HTTP/1.0) hangs.
                closeQuietly(upstream);
                closeQuietly(client);
            }
        }, "attc-ipecho-pump");
        back.setDaemon(true);
        back.start();
        try (OutputStream out = upstream.getOutputStream()) {
            copy(clientIn, out);
        } catch (Exception ignored) {
        } finally {
            LIVE.remove(upstream);
            closeQuietly(upstream);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            out.flush();
        }
    }

    /** Open tunnels, so an exit change can force the client to reconnect. */
    private static final java.util.Set<Socket> LIVE =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Socket, Boolean>());

    /** Closes every live tunnel: the next request picks up the new exit. */
    static void closeTunnels() {
        int closed = 0;
        for (Socket socket : LIVE) {
            closeQuietly(socket);
            closed++;
        }
        LIVE.clear();
        if (closed > 0) {
            Logger.i("ipecho: closed " + closed + " tunnel(s) for the new exit");
        }
    }

    private static Socket connect(String host, int port) {
        String lower = host == null ? "" : host.toLowerCase(Locale.US);
        if (DIRECT_HOSTS.contains(lower)) {
            // The updater must work even when the egress proxy is down.
            try {
                Socket direct = new Socket();
                direct.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                LIVE.add(direct);
                logOnce("direct:" + lower, "ipecho: direct update host=" + host);
                return direct;
            } catch (Exception e) {
                Logger.i("ipecho: direct failed " + host + ":" + port + " " + e);
            }
        }
        if (!LOOPBACK.equals(host) && Upstream.configured(app)) {
            // Never fall back to a direct connection here: a configured
            // upstream that misbehaves must fail closed, not leak the address.
            Socket via = Upstream.open(app, host, port);
            if (via == null) {
                logOnce("upstream-fail:" + host,
                        "ipecho: upstream failed for " + host + " (request dropped)");
            } else {
                LIVE.add(via);
            }
            return via;
        }
        // The egress layer picks the radio (wifi, mobile or the VPN app) and
        // retries once, so a switch between radios does not surface here.
        Socket routed = Egress.connect(app, host, port);
        if (routed == null) {
            Logger.i("ipecho: connect failed " + host + ":" + port + " " + Egress.lastError());
        } else {
            LIVE.add(routed);
        }
        return routed;
    }

    // --------------------------------------------------------------- origin

    private static void handleOrigin(Socket socket) {
        try (Socket client = socket) {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            byte[] head = readHead(in);
            if (head == null) {
                return;
            }
            String text = new String(head, StandardCharsets.ISO_8859_1);
            int lineEnd = text.indexOf("\r\n");
            String requestLine = lineEnd < 0 ? text : text.substring(0, lineEnd);
            String[] parts = requestLine.split(" ");
            String target = parts.length > 1 ? parts[1] : "/";
            String host = header(text, "Host");
            if (host == null) {
                host = "";
            }
            host = host.trim().toLowerCase(Locale.US);
            int colon = host.lastIndexOf(':');
            if (colon > 0) {
                host = host.substring(0, colon);
            }
            out.write(response(host, target));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    private static String header(String head, String name) {
        String prefix = name.toLowerCase(Locale.US) + ":";
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase(Locale.US).startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    // -------------------------------------------------------------- payload

    private static byte[] response(String host, String path) {
        String ip = VpnState.getSimulatedIp(app);
        String body;
        String type = "text/plain; charset=utf-8";
        String lower = path.toLowerCase(Locale.US);

        if (host.equals("ip-api.com")) {
            type = "application/json; charset=utf-8";
            body = "{\"status\":\"success\",\"country\":\"United States\","
                    + "\"countryCode\":\"US\",\"region\":\"CA\",\"regionName\":\"California\","
                    + "\"city\":\"Los Angeles\",\"zip\":\"90001\",\"lat\":34.0522,"
                    + "\"lon\":-118.2437,\"timezone\":\"America/Los_Angeles\","
                    + "\"isp\":\"ATTC Network\",\"org\":\"ATTC Network\","
                    + "\"as\":\"AS64500 ATTC Network\",\"query\":\"" + ip + "\"}";
        } else if (host.equals("ipinfo.io")) {
            type = "application/json; charset=utf-8";
            body = "{\"ip\":\"" + ip + "\",\"hostname\":\"attc.local\","
                    + "\"city\":\"Los Angeles\",\"region\":\"California\",\"country\":\"US\","
                    + "\"loc\":\"34.0522,-118.2437\",\"org\":\"AS64500 ATTC Network\","
                    + "\"postal\":\"90001\",\"timezone\":\"America/Los_Angeles\"}";
        } else if (host.equals("ipwho.is") || host.equals("ipwhois.app")) {
            type = "application/json; charset=utf-8";
            body = "{\"ip\":\"" + ip + "\",\"success\":true,\"type\":\"IPv4\","
                    + "\"continent\":\"North America\",\"country\":\"United States\","
                    + "\"country_code\":\"US\",\"region\":\"California\","
                    + "\"city\":\"Los Angeles\",\"latitude\":34.0522,\"longitude\":-118.2437,"
                    + "\"connection\":{\"asn\":64500,\"org\":\"ATTC Network\","
                    + "\"isp\":\"ATTC Network\",\"domain\":\"attc.local\"},"
                    + "\"timezone\":{\"id\":\"America/Los_Angeles\"}}";
        } else if (host.equals("api.myip.com")) {
            type = "application/json; charset=utf-8";
            body = "{\"ip\":\"" + ip + "\",\"country\":\"United States\",\"cc\":\"US\"}";
        } else if (host.equals("ipapi.co")) {
            type = "application/json; charset=utf-8";
            body = "{\"ip\":\"" + ip + "\",\"city\":\"Los Angeles\","
                    + "\"region\":\"California\",\"country\":\"US\","
                    + "\"country_name\":\"United States\",\"postal\":\"90001\","
                    + "\"latitude\":34.0522,\"longitude\":-118.2437,"
                    + "\"timezone\":\"America/Los_Angeles\","
                    + "\"org\":\"AS64500 ATTC Network\"}";
        } else if (host.equals("wtfismyip.com")) {
            type = "application/json; charset=utf-8";
            body = "{\"YourFuckingIPAddress\":\"" + ip + "\","
                    + "\"YourFuckingLocation\":\"Los Angeles, CA, United States\","
                    + "\"YourFuckingHostname\":\"attc.local\","
                    + "\"YourFuckingISP\":\"ATTC Network\","
                    + "\"YourFuckingTorExit\":false}";
        } else if ((host.equals("api.ipify.org") || host.equals("api64.ipify.org"))
                && lower.contains("json")) {
            type = "application/json; charset=utf-8";
            body = "{\"ip\":\"" + ip + "\"}";
        } else {
            body = ip;
        }

        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Server: ATTC\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[headBytes.length + payload.length];
        System.arraycopy(headBytes, 0, out, 0, headBytes.length);
        System.arraycopy(payload, 0, out, headBytes.length, payload.length);
        return out;
    }

    // ---------------------------------------------------------------- utils

    /**
     * True when the user has the Fake VPN enabled and the host is one of the
     * IP-echo endpoints. The persisted flag is used instead of the transient
     * tunnel state so a failed tunnel restart cannot silently fall back to the
     * real address inside the guest.
     */
    private static boolean spoof(String host) {
        // Follows the user's on/off switch, but never the transient tunnel
        // state: a failed tunnel restart must not silently leak the real IP.
        return VpnState.isEnabled(app) && VpnState.isIpEchoHost(host);
    }

    private static final java.util.Set<String> LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    private static void logOnce(String key, String message) {
        if (LOGGED.add(key)) {
            Logger.i(message);
        }
    }

    private static String[] splitHostPort(String value, int fallbackPort) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String host = value;
        int port = fallbackPort;
        int colon = value.lastIndexOf(':');
        if (colon > 0 && value.indexOf(':') == colon) {
            host = value.substring(0, colon);
            try {
                port = Integer.parseInt(value.substring(colon + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (host.isEmpty() || host.startsWith("[")) {
            return null;
        }
        return new String[]{host, String.valueOf(port)};
    }

    /** Reads the request head. Body bytes stay buffered in {@code in}. */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);
        int prev1 = -1;
        int prev2 = -1;
        int prev3 = -1;
        int b;
        while ((b = in.read()) >= 0) {
            buffer.write(b);
            if (buffer.size() > MAX_HEAD) {
                return null;
            }
            if (b == '\n') {
                if (prev1 == '\r' && prev2 == '\n' && prev3 == '\r') {
                    break;
                }
                if (prev1 == '\n') {
                    break;
                }
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = b;
        }
        if (buffer.size() == 0) {
            return null;
        }
        return buffer.toByteArray();
    }

    private static void writeStatus(OutputStream out, int code, String reason) {
        try {
            out.write(("HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Length: 0\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (Exception ignored) {
        }
    }
}
