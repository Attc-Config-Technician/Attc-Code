package com.attc.opencode;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Shared state for the simulated VPN: address plan, the fake public IP shown
 * to the user and to HTTP clients, and the host allowlist served locally.
 *
 * The simulated IP is drawn from a pool of several hundred documentation
 * addresses and rotates every 30 minutes while the tunnel is up. The real
 * egress address never changes: only the locally served value does.
 */
public final class VpnState {

    public static final String FAKE_IP = "10.7.0.77";
    public static final String DNS_IP = "10.7.0.1";
    public static final String TUN_IP = "10.7.0.2";
    public static final String ROUTE = "10.7.0.0/24";

    public static final String DEFAULT_SIMULATED_IP = "203.0.113.42";

    /** How often the simulated address rotates while the tunnel is active. */
    public static final long ROTATION_INTERVAL_MS = 30L * 60 * 1000;

    private static final String PREFS = "attc";
    private static final String KEY_SIMULATED_IP = "vpn_sim_ip";
    private static final String KEY_SIMULATED_AT = "vpn_sim_at";
    private static final String KEY_ENABLED = "fake_vpn_enabled";

    /**
     * Hosts whose plain-HTTP requests are answered locally. The "www." variants
     * are added programmatically.
     */
    private static final String[] IP_ECHO_HOSTS = {
            // Keep in sync with tools/make_certs.sh (it reads this list).
            "api.ipify.org",
            "api64.ipify.org",
            "ipify.org",
            "icanhazip.com",
            "ipv4.icanhazip.com",
            "ipv6.icanhazip.com",
            "ip-api.com",
            "ipecho.net",
            "ident.me",
            "ipinfo.io",
            "ipwho.is",
            "ipwhois.app",
            "ipwhois.io",
            "ifconfig.me",
            "ifconfig.co",
            "ifconfig.io",
            "checkip.amazonaws.com",
            "checkip.dyndns.org",
            "wtfismyip.com",
            "api.myip.com",
            "myip.com",
            "myip.wtf",
            "ipapi.co",
            "ipapi.com",
            "ipapi.is",
            "ip.me",
            "ip.sb",
            "4.ip.sb",
            "myexternalip.com",
            "ipv4.myexternalip.com",
            "ipchicken.com",
            "ip.seeip.org",
            "seeip.org",
            "whatismyip.akamai.com",
            "ipv4.whatismyip.akamai.com",
            "api.db-ip.com",
            "ip.nf",
            "ip.gs",
            "api.iplocation.net",
            "ip2c.org",
            "ip.anysrc.net",
            "monip.org",
            "ip4.me",
            "ip.cip.cc",
            "cip.cc",
            "myip.ipip.net",
            "ip.check.place",
            "api.whatismyip.com",
            "ipdata.co",
            "ipgeolocation.io",
            "ipstack.com",
            "get.geojs.io",
            "geojs.io",
            "api.bigdatacloud.net",
            "whatismyipaddress.com",
            "showmyip.com",
            "ip2location.com",
            "api.ip2location.io",
            "ipaddress.my",
            "ipfingerprints.com",
            "whatismyip.com"
    };

    private static final Set<String> ALLOWED = buildAllowed();

    /**
     * 3 x 254 documentation addresses (TEST-NET-1/2/3). They are guaranteed
     * never to belong to a real host, so rotating them cannot point at one.
     */
    private static final String[] POOL = buildPool();

    private static final Random RANDOM = new Random();

    private static volatile boolean running;

    private VpnState() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getSimulatedIp(Context c) {
        SharedPreferences p = prefs(c);
        String ip = p.getString(KEY_SIMULATED_IP, DEFAULT_SIMULATED_IP);
        if (ip == null || ip.isEmpty()) {
            ip = DEFAULT_SIMULATED_IP;
        }
        long at = p.getLong(KEY_SIMULATED_AT, 0L);
        if (at == 0L) {
            setSimulatedIp(c, ip);
            return ip;
        }
        if (System.currentTimeMillis() - at >= ROTATION_INTERVAL_MS) {
            String next = pickDifferent(ip);
            setSimulatedIp(c, next);
            return next;
        }
        return ip;
    }

    private static String pickDifferent(String current) {
        String next = current;
        for (int i = 0; i < 8 && next.equals(current); i++) {
            next = POOL[RANDOM.nextInt(POOL.length)];
        }
        return next;
    }

    public static void setSimulatedIp(Context c, String ip) {
        prefs(c).edit()
                .putString(KEY_SIMULATED_IP, ip)
                .putLong(KEY_SIMULATED_AT, System.currentTimeMillis())
                .apply();
    }

    /** Picks a different address from the pool, persists it and returns it. */
    public static String rotate(Context c) {
        String current = getSimulatedIp(c);
        String next = current;
        for (int i = 0; i < 8 && next.equals(current); i++) {
            next = POOL[RANDOM.nextInt(POOL.length)];
        }
        if (next.equals(current)) {
            int index = Math.abs(Collections.binarySearch(
                    java.util.Arrays.asList(POOL), current)) % POOL.length;
            next = POOL[(index + 1) % POOL.length];
        }
        setSimulatedIp(c, next);
        return next;
    }

    public static long simulatedAt(Context c) {
        return prefs(c).getLong(KEY_SIMULATED_AT, 0L);
    }

    public static int poolSize() {
        return POOL.length;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setRunning(boolean on) {
        running = on;
    }

    /** Whether the user wants the tunnel up; used for the best-effort restart. */
    public static boolean isEnabled(Context c) {
        // On by default: hiding the real address from programs inside Ubuntu is
        // the point of the feature, so it must not depend on a first-run toggle.
        return prefs(c).getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean(KEY_ENABLED, on).apply();
    }

    /** True for the IP-echo hosts the local responder answers for. */
    public static boolean isIpEchoHost(String name) {
        if (name == null) {
            return false;
        }
        String host = name.toLowerCase(Locale.US);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return ALLOWED.contains(host);
    }

    private static Set<String> buildAllowed() {
        Set<String> hosts = new HashSet<>();
        for (String host : IP_ECHO_HOSTS) {
            hosts.add(host);
            hosts.add("www." + host);
        }
        return Collections.unmodifiableSet(hosts);
    }

    private static String[] buildPool() {
        List<String> out = new ArrayList<>(762);
        String[] prefixes = {"192.0.2.", "198.51.100.", "203.0.113."};
        for (String prefix : prefixes) {
            for (int i = 1; i <= 254; i++) {
                out.add(prefix + i);
            }
        }
        return out.toArray(new String[0]);
    }
}
