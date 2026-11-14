package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Optional upstream exit for the local responder.
 *
 * The responder can only hide the phone's address from programs running inside
 * Ubuntu: when it tunnels a request out, the remote server still sees the real
 * address. Sending that tunnel through an HTTP CONNECT or SOCKS5 proxy (a VPN
 * app with a local inbound, a VPS, Tor's SOCKS port, ...) is the only way to
 * change what the outside world sees.
 *
 * Configuration is either the settings screen or, for scripted setups, the file
 * /storage/emulated/0/ATTC OPENCODE/upstream.txt containing one line such as
 *   socks5://user:pass@10.0.0.2:1080
 *   http://127.0.0.1:8080
 */
public final class Upstream {

    private static final String PREFS = "attc_upstream";
    private static final int TIMEOUT_MS = 25000;
    private static final String CONFIG_PATH = "/storage/emulated/0/ATTC OPENCODE/upstream.txt";

    private static final String KEY_KIND = "kind";
    private static final String KEY_HOST = "host";
    private static final String KEY_PORT = "port";
    private static final String KEY_USER = "user";
    private static final String KEY_PASS = "pass";
    private static final String KEY_SPEC = "spec";

    private static volatile String lastExit = "not checked";
    private static volatile String lastError = "";

    private Upstream() {
    }

    /** Kind is "none", "http" or "socks5". */
    public static final class Config {
        final String kind;
        final String host;
        final int port;
        final String user;
        final String pass;

        Config(String kind, String host, int port, String user, String pass) {
            this.kind = kind;
            this.host = host;
            this.port = port;
            this.user = user;
            this.pass = pass;
        }

        boolean enabled() {
            return !"none".equals(kind) && host != null && !host.isEmpty() && port > 0;
        }

        String describe() {
            if (!enabled()) {
                return "direct (real IP)";
            }
            return kind + " " + host + ":" + port;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String spec(Context c) {
        String spec = prefs(c).getString(KEY_SPEC, "");
        if (spec != null && !spec.trim().isEmpty()) {
            return spec.trim();
        }
        return "";
    }

    public static String describe(Context c) {
        return load(c).describe();
    }

    public static boolean configured(Context c) {
        return load(c).enabled();
    }

    public static String lastExit() {
        return lastExit;
    }

    public static String lastError() {
        return lastError;
    }

    /** Parses and stores a spec; empty clears the upstream. */
    public static void save(Context c, String spec) {
        String text = spec == null ? "" : spec.trim();
        SharedPreferences.Editor e = prefs(c).edit();
        if (text.isEmpty()) {
            e.clear().apply();
            Logger.i("upstream: cleared (direct egress)");
            return;
        }
        Config cfg = parse(text);
        if (cfg == null) {
            Logger.i("upstream: invalid spec " + text);
            return;
        }
        e.putString(KEY_KIND, cfg.kind)
                .putString(KEY_HOST, cfg.host)
                .putInt(KEY_PORT, cfg.port)
                .putString(KEY_USER, cfg.user)
                .putString(KEY_PASS, cfg.pass)
                .putString(KEY_SPEC, text)
                .apply();
        Logger.i("upstream: saved " + cfg.describe());
    }

    private static Config load(Context c) {
        SharedPreferences p = prefs(c);
        String kind = p.getString(KEY_KIND, null);
        String host = p.getString(KEY_HOST, null);
        int port = p.getInt(KEY_PORT, 0);
        if (kind != null && host != null && port > 0) {
            return new Config(kind, host, port, p.getString(KEY_USER, null),
                    p.getString(KEY_PASS, null));
        }
        // Scripted setups can drop a one line file instead of using the UI.
        String fromFile = readConfigFile();
        if (fromFile != null) {
            Config cfg = parse(fromFile);
            if (cfg != null) {
                save(c, fromFile);
                Logger.i("upstream: loaded from " + CONFIG_PATH + " -> " + cfg.describe());
                return cfg;
            }
            Logger.i("upstream: bad line in " + CONFIG_PATH);
        }
        return new Config("none", null, 0, null, null);
    }

    private static String readConfigFile() {
        try {
            File f = new File(CONFIG_PATH);
            if (!f.isFile() || f.length() > 4096) {
                return null;
            }
            try (InputStream in = new FileInputStream(f)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[512];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                String text = out.toString("UTF-8");
                for (String line : text.split("\n")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        return t;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Accepts socks5://[user:pass@]host:port and http://[user:pass@]host:port. */
    static Config parse(String spec) {
        String text = spec.trim();
        String kind = "http";
        int sep = text.indexOf("://");
        if (sep > 0) {
            String scheme = text.substring(0, sep).toLowerCase(Locale.US);
            text = text.substring(sep + 3);
            if (scheme.startsWith("socks5") || scheme.startsWith("socks")) {
                kind = "socks5";
            } else if (scheme.startsWith("http")) {
                kind = "http";
            } else {
                return null;
            }
        }
        String user = null;
        String pass = null;
        int at = text.lastIndexOf('@');
        if (at >= 0) {
            String creds = text.substring(0, at);
            text = text.substring(at + 1);
            int colon = creds.indexOf(':');
            if (colon > 0) {
                user = creds.substring(0, colon);
                pass = creds.substring(colon + 1);
            } else {
                user = creds;
                pass = "";
            }
        }
        int slash = text.indexOf('/');
        if (slash >= 0) {
            text = text.substring(0, slash);
        }
        int colon = text.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String host = text.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(text.substring(colon + 1));
        } catch (Exception e) {
            return null;
        }
        if (host.isEmpty() || port <= 0 || port > 65535) {
            return null;
        }
        return new Config(kind, host, port, user, pass);
    }

    /**
     * Opens a socket to host:port through the configured upstream. Returns null
     * when no upstream is configured (the caller then connects directly) or
     * when the handshake failed. A configured upstream never falls back to a
     * direct connection: failing closed is the whole point.
     */
    public static Socket open(Context c, String host, int port) {
        Config cfg = load(c);
        if (!cfg.enabled()) {
            return null;
        }
        return connect(c, cfg, host, port);
    }

    private static Socket connect(Context c, Config cfg, String host, int port) {
        Socket socket = null;
        try {
            // The hop to the proxy server itself must use the selected exit as
            // well, otherwise the proxy is reached over the real radio and a
            // network switch kills an otherwise healthy tunnel.
            socket = Egress.connect(c, cfg.host, cfg.port);
            if (socket == null) {
                throw new IOException("no route to " + cfg.host + ":" + cfg.port);
            }
            socket.setSoTimeout(TIMEOUT_MS);
            if ("socks5".equals(cfg.kind)) {
                socks5(socket, cfg, host, port);
            } else {
                httpConnect(socket, cfg, host, port);
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (Exception e) {
            lastError = e.toString();
            Logger.i("upstream: " + cfg.describe() + " failed for " + host + ":" + port
                    + " (" + e + ")");
            try {
                socket.close();
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static void httpConnect(Socket socket, Config cfg, String host, int port)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("CONNECT ").append(host).append(':').append(port).append(" HTTP/1.1\r\n");
        head.append("Host: ").append(host).append(':').append(port).append("\r\n");
        if (cfg.user != null) {
            String token = Base64.getEncoder().encodeToString(
                    (cfg.user + ":" + (cfg.pass == null ? "" : cfg.pass))
                            .getBytes(StandardCharsets.UTF_8));
            head.append("Proxy-Authorization: Basic ").append(token).append("\r\n");
        }
        head.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        String status = readLine(socket.getInputStream());
        if (status == null || !status.contains(" 200")) {
            throw new IOException("connect rejected: " + status);
        }
        // Consume the rest of the response head.
        String line;
        int guard = 0;
        while ((line = readLine(socket.getInputStream())) != null
                && !line.isEmpty() && guard++ < 64) {
            // skip headers
        }
    }

    private static void socks5(Socket socket, Config cfg, String host, int port)
            throws IOException {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        boolean auth = cfg.user != null && !cfg.user.isEmpty();
        if (auth) {
            out.write(new byte[]{0x05, 0x02, 0x00, 0x02});
        } else {
            out.write(new byte[]{0x05, 0x01, 0x00});
        }
        out.flush();
        byte[] reply = readFully(in, 2);
        if (reply[0] != 0x05) {
            throw new IOException("bad socks version");
        }
        if (reply[1] == 0x02) {
            if (!auth) {
                throw new IOException("socks auth required");
            }
            byte[] u = cfg.user.getBytes(StandardCharsets.UTF_8);
            byte[] p = (cfg.pass == null ? "" : cfg.pass).getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = 0x01;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg);
            out.flush();
            byte[] authReply = readFully(in, 2);
            if (authReply[1] != 0x00) {
                throw new IOException("socks auth failed");
            }
        } else if (reply[1] != 0x00) {
            throw new IOException("socks method rejected");
        }

        byte[] name = host.getBytes(StandardCharsets.UTF_8);
        if (name.length > 255) {
            throw new IOException("host too long");
        }
        byte[] req = new byte[5 + name.length + 2];
        req[0] = 0x05;
        req[1] = 0x01;
        req[2] = 0x00;
        req[3] = 0x03;
        req[4] = (byte) name.length;
        System.arraycopy(name, 0, req, 5, name.length);
        req[5 + name.length] = (byte) ((port >> 8) & 0xff);
        req[6 + name.length] = (byte) (port & 0xff);
        out.write(req);
        out.flush();

        byte[] head = readFully(in, 4);
        if (head[0] != 0x05 || head[1] != 0x00) {
            throw new IOException("socks connect failed code " + head[1]);
        }
        int atyp = head[3];
        if (atyp == 0x01) {
            readFully(in, 4 + 2);
        } else if (atyp == 0x04) {
            readFully(in, 16 + 2);
        } else if (atyp == 0x03) {
            int len = readFully(in, 1)[0] & 0xff;
            readFully(in, len + 2);
        } else {
            throw new IOException("socks bad atyp");
        }
    }

    /**
     * Returns the address the outside world sees for this device, measured by
     * asking a plain-HTTP echo service. When no upstream is set this is the
     * real address, which is exactly what the settings screen needs to show.
     */
    public static String exitIp(Context c) {
        Socket socket = null;
        try {
            Config cfg = load(c);
            if (cfg.enabled()) {
                socket = connect(c, cfg, "api.ipify.org", 80);
            } else {
                socket = Egress.connect(c, "api.ipify.org", 80);
                if (socket != null) {
                    socket.setSoTimeout(TIMEOUT_MS);
                }
            }
            if (socket == null) {
                lastExit = "unreachable";
                return lastExit;
            }
            OutputStream out = socket.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: api.ipify.org\r\n"
                    + "User-Agent: attc\r\nConnection: close\r\n\r\n")
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
            String ip = ipv4(text);
            if (ip == null) {
                lastExit = "unreadable";
            } else {
                lastExit = ip;
            }
            Logger.i("upstream: exit=" + lastExit + " via " + cfg.describe()
                    + " route=" + Egress.lastPick());
            return lastExit;
        } catch (Exception e) {
            lastError = e.toString();
            lastExit = "error";
            Logger.i("upstream: exit check failed " + e);
            return lastExit;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String ipv4(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b").matcher(text);
        String found = null;
        while (m.find()) {
            found = m.group(1);
        }
        return found;
    }

    private static byte[] readFully(InputStream in, int count) throws IOException {
        byte[] buf = new byte[count];
        int read = 0;
        while (read < count) {
            int n = in.read(buf, read, count - read);
            if (n < 0) {
                throw new IOException("eof");
            }
            read += n;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 512) {
            int c = in.read();
            if (c < 0) {
                return sb.length() == 0 ? null : sb.toString();
            }
            if (c == '\n') {
                int len = sb.length();
                if (len > 0 && sb.charAt(len - 1) == '\r') {
                    sb.setLength(len - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
        }
        return sb.toString();
    }
}
